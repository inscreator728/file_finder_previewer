import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableModel;

/**
 * Merged application: folder explorer with thumbnails + file search with
 * result table. A common preview panel shows images, text, folder contents,
 * and automatically extracts text from any file (including CDR, PSD) using
 * Apache Tika (auto‑downloaded).
 */
public class FileSearchPreviewApp extends JFrame {

    // ---------- Search tab fields ----------
    private JTextField queryField, rootField;
    private JTable resultTable;
    private DefaultTableModel tableModel;
    private JButton searchButton, stopButton;
    private AtomicBoolean stopFlag = new AtomicBoolean(false);
    private SwingWorker<Void, Object[]> searchWorker;
    private BlockingQueue<Object[]> resultQueue = new LinkedBlockingQueue<>();

    // ---------- Explorer tab fields ----------
    private JLabel pathLabel;
    private JPanel thumbnailContainer;
    private JButton chooseFolderButton, openExplorerButton;
    private File currentFolder;
    private ThumbnailPanel selectedThumbnail;
    private ExecutorService thumbnailLoader = Executors.newFixedThreadPool(4);
    private static final int THUMB_SIZE = 100;

    // ---------- Shared preview fields ----------
    private JPanel previewPanel;
    private CardLayout previewCardLayout;
    private JLabel previewStatusLabel, previewImageLabel;
    private JTextArea previewTextArea;

    // ---------- Tika / content extraction ----------
    private URLClassLoader tikaClassLoader;
    private SwingWorker<String, Void> currentPreviewWorker;

    // ---------- Constants ----------
    private static final String[] COLUMNS = {"Name", "Path", "Type", "Size"};
    private static final String TIKA_VERSION = "2.9.0";
    private static final String TIKA_JAR = "tika-app-" + TIKA_VERSION + ".jar";
    private static final String TIKA_MAVEN_URL =
            "https://repo1.maven.org/maven2/org/apache/tika/tika-app/" +
            TIKA_VERSION + "/" + TIKA_JAR;
    private static final Path LIB_DIR = Paths.get("lib");

    // ---------- Constructor ----------
    public FileSearchPreviewApp() {
        super("File Search & Preview Explorer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 750);
        setMinimumSize(new Dimension(900, 550));
        initUI();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopFlag.set(true);
            }
        });
        // Timer to flush the result queue to the table on the EDT
        new javax.swing.Timer(100, e -> processResultQueue()).start();
    }

    // ===================== UI SETUP =====================
    private void initUI() {
        // --- Top bar: global status ---
        JLabel globalStatus = new JLabel("Ready");
        globalStatus.setBorder(BorderFactory.createLoweredBevelBorder());
        add(globalStatus, BorderLayout.SOUTH);

        // --- Left side: tabbed pane (Search / Explorer) ---
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Search", buildSearchTab());
        tabbedPane.addTab("Explorer", buildExplorerTab());

        // --- Right side: preview panel ---
        JComponent previewPane = buildPreviewPane();

        // --- Split pane holding tabs on the left and preview on the right ---
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                tabbedPane, previewPane);
        splitPane.setResizeWeight(0.55);
        add(splitPane, BorderLayout.CENTER);
    }

    // ===================== SEARCH TAB =====================
    private JPanel buildSearchTab() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Top controls
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Search:"));
        queryField = new JTextField(25);
        queryField.addActionListener(e -> startSearch());
        top.add(queryField);

        top.add(new JLabel("Root:"));
        rootField = new JTextField(35);
        top.add(rootField);

        JButton browseBtn = new JButton("Browse...");
        browseBtn.addActionListener(e -> browseSearchRoot());
        top.add(browseBtn);

        searchButton = new JButton("Search");
        searchButton.addActionListener(e -> startSearch());
        top.add(searchButton);

        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stopSearch());
        top.add(stopButton);
        panel.add(top, BorderLayout.NORTH);

        // Results table
        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        resultTable = new JTable(tableModel);
        resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultTable.getTableHeader().setReorderingAllowed(false);
        resultTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onSearchResultSelected();
        });
        setupTablePopupMenu();
        JScrollPane tableScroll = new JScrollPane(resultTable);
        panel.add(tableScroll, BorderLayout.CENTER);

        return panel;
    }

    private void setupTablePopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem openItem = new JMenuItem("Open with default application");
        openItem.addActionListener(e -> openFileFromRow());
        JMenuItem openFolderItem = new JMenuItem("Open containing folder");
        openFolderItem.addActionListener(e -> openContainingFolderFromRow());
        JMenuItem copyPathItem = new JMenuItem("Copy full path to clipboard");
        copyPathItem.addActionListener(e -> copyPathFromRow());
        popup.add(openItem);
        popup.add(openFolderItem);
        popup.add(copyPathItem);

        resultTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { showPopup(e); }
            @Override
            public void mouseReleased(MouseEvent e) { showPopup(e); }
            private void showPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = resultTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && row < resultTable.getRowCount()) {
                        resultTable.setRowSelectionInterval(row, row);
                        popup.show(resultTable, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    // ===================== EXPLORER TAB =====================
    private JPanel buildExplorerTab() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Top: folder chooser and path display
        JPanel top = new JPanel(new BorderLayout(5, 0));
        chooseFolderButton = new JButton("Open Folder...");
        chooseFolderButton.addActionListener(e -> chooseFolder());
        pathLabel = new JLabel("No folder selected");
        pathLabel.setBorder(BorderFactory.createLoweredBevelBorder());
        top.add(chooseFolderButton, BorderLayout.WEST);
        top.add(pathLabel, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);

        // Center: scrollable thumbnail grid
        thumbnailContainer = new JPanel();
        thumbnailContainer.setLayout(new BoxLayout(thumbnailContainer, BoxLayout.Y_AXIS));
        JScrollPane thumbScroll = new JScrollPane(thumbnailContainer,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        thumbScroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(thumbScroll, BorderLayout.CENTER);

        // Bottom: Open button for the selected file
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        openExplorerButton = new JButton("Open");
        openExplorerButton.setEnabled(false);
        openExplorerButton.setFont(openExplorerButton.getFont().deriveFont(Font.BOLD));
        openExplorerButton.addActionListener(e -> openSelectedExplorerFile());
        bottom.add(openExplorerButton);
        panel.add(bottom, BorderLayout.SOUTH);

        // Click on empty space deselects
        thumbnailContainer.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getSource() == thumbnailContainer && e.getClickCount() == 1) {
                    deselectExplorerThumbnail();
                }
            }
        });

        return panel;
    }

    // ===================== SHARED PREVIEW PANE =====================
    private JComponent buildPreviewPane() {
        previewPanel = new JPanel(previewCardLayout = new CardLayout());
        previewStatusLabel = new JLabel("Select a file to preview", SwingConstants.CENTER);
        previewStatusLabel.setForeground(Color.GRAY);

        previewImageLabel = new JLabel("", SwingConstants.CENTER);
        JScrollPane imageScroll = new JScrollPane(previewImageLabel);

        previewTextArea = new JTextArea();
        previewTextArea.setEditable(false);
        previewTextArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane textScroll = new JScrollPane(previewTextArea);

        previewPanel.add(previewStatusLabel, "status");
        previewPanel.add(imageScroll, "image");
        previewPanel.add(textScroll, "text");

        previewCardLayout.show(previewPanel, "status");
        return previewPanel;
    }

    // ===================== SEARCH LOGIC =====================
    private void startSearch() {
        stopFlag.set(false);
        searchButton.setEnabled(false);
        stopButton.setEnabled(true);
        tableModel.setRowCount(0);
        resultQueue.clear();

        String query = queryField.getText().trim();
        if (query.isEmpty()) {
            searchDone();
            return;
        }

        List<String> roots = new ArrayList<>();
        String raw = rootField.getText().trim();
        if (raw.isEmpty()) {
            for (File r : File.listRoots()) roots.add(r.getAbsolutePath());
        } else {
            for (String part : raw.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) roots.add(trimmed);
            }
        }
        if (roots.isEmpty()) {
            searchDone();
            return;
        }

        final String lowerQuery = query.toLowerCase();
        searchWorker = new SwingWorker<Void, Object[]>() {
            @Override
            protected Void doInBackground() {
                for (String root : roots) {
                    if (stopFlag.get()) break;
                    searchDirectory(new File(root), lowerQuery);
                }
                try { resultQueue.put(new Object[]{null}); } catch (InterruptedException ignored) {}
                return null;
            }
            @Override
            protected void done() {
                SwingUtilities.invokeLater(() -> searchDone());
            }
        };
        searchWorker.execute();
    }

    private void searchDirectory(File dir, String query) {
        if (stopFlag.get()) return;
        try {
            File[] children = dir.listFiles();
            if (children == null) return;
            for (File f : children) {
                if (stopFlag.get()) return;
                if (f.getName().toLowerCase().contains(query)) {
                    publishResult(f.getName(), f.getParent(), f.isDirectory() ? "Folder" : "File",
                            f.isDirectory() ? "" : humanReadableSize(f.length()));
                }
                if (f.isDirectory()) searchDirectory(f, query);
            }
        } catch (SecurityException ignored) {}
    }

    private void publishResult(String name, String path, String type, String size) {
        try { resultQueue.put(new Object[]{name, path, type, size}); } catch (InterruptedException ignored) {}
    }

    private void processResultQueue() {
        Object[] item;
        while ((item = resultQueue.poll()) != null) {
            if (item[0] == null) {
                searchDone();
            } else {
                tableModel.addRow(item);
            }
        }
    }

    private void searchDone() {
        searchButton.setEnabled(true);
        stopButton.setEnabled(false);
        ((JLabel) getContentPane().getComponent(0)).setText(
                "Search finished. " + tableModel.getRowCount() + " items found.");
    }

    private void stopSearch() {
        stopFlag.set(true);
        if (searchWorker != null && !searchWorker.isDone()) searchWorker.cancel(true);
    }

    private void browseSearchRoot() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String dir = chooser.getSelectedFile().getAbsolutePath();
            String current = rootField.getText().trim();
            if (current.isEmpty()) rootField.setText(dir);
            else rootField.setText(current + "; " + dir);
        }
    }

    private File fileFromResultRow(int row) {
        String name = (String) tableModel.getValueAt(row, 0);
        String path = (String) tableModel.getValueAt(row, 1);
        return new File(path, name);
    }

    private void onSearchResultSelected() {
        int row = resultTable.getSelectedRow();
        if (row == -1) return;
        showPreview(fileFromResultRow(row));
    }

    private void openFileFromRow() {
        int row = resultTable.getSelectedRow();
        if (row >= 0) openFile(fileFromResultRow(row));
    }

    private void openContainingFolderFromRow() {
        int row = resultTable.getSelectedRow();
        if (row >= 0) {
            File f = fileFromResultRow(row);
            openContainingFolder(f);
        }
    }

    private void copyPathFromRow() {
        int row = resultTable.getSelectedRow();
        if (row >= 0) {
            File f = fileFromResultRow(row);
            copyToClipboard(f.getAbsolutePath());
        }
    }

    // ===================== EXPLORER LOGIC =====================
    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            currentFolder = chooser.getSelectedFile();
            pathLabel.setText(currentFolder.getAbsolutePath());
            refreshFileList();
        }
    }

    private void refreshFileList() {
        thumbnailContainer.removeAll();
        selectedThumbnail = null;
        openExplorerButton.setEnabled(false);
        clearPreview();
        if (currentFolder == null || !currentFolder.isDirectory()) {
            thumbnailContainer.revalidate();
            thumbnailContainer.repaint();
            return;
        }
        File[] allFiles = currentFolder.listFiles();
        if (allFiles == null || allFiles.length == 0) {
            JLabel empty = new JLabel("No files found", SwingConstants.CENTER);
            empty.setAlignmentX(Component.CENTER_ALIGNMENT);
            thumbnailContainer.add(Box.createVerticalGlue());
            thumbnailContainer.add(empty);
            thumbnailContainer.add(Box.createVerticalGlue());
        } else {
            Map<String, List<File>> grouped = new LinkedHashMap<>();
            for (File f : allFiles) {
                if (f.isFile()) {
                    String ext = getExtensionLower(f);
                    grouped.computeIfAbsent(ext, k -> new ArrayList<>()).add(f);
                }
            }
            List<String> exts = new ArrayList<>(grouped.keySet());
            exts.sort((a, b) -> {
                int i1 = PRIORITY_EXTENSIONS.indexOf(a);
                int i2 = PRIORITY_EXTENSIONS.indexOf(b);
                if (i1 == -1 && i2 == -1) return a.compareTo(b);
                if (i1 == -1) return 1;
                if (i2 == -1) return -1;
                return Integer.compare(i1, i2);
            });

            for (String ext : exts) {
                List<File> files = grouped.get(ext);
                JLabel header = new JLabel(ext.isEmpty() ? "Other" : ext.toUpperCase());
                header.setFont(header.getFont().deriveFont(Font.BOLD, 14f));
                header.setBorder(BorderFactory.createEmptyBorder(10, 10, 2, 10));
                header.setAlignmentX(Component.LEFT_ALIGNMENT);
                thumbnailContainer.add(header);

                JPanel groupPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));
                groupPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                for (File f : files) {
                    ThumbnailPanel tp = new ThumbnailPanel(f);
                    groupPanel.add(tp);
                    thumbnailLoader.submit(new ThumbnailLoadTask(tp));
                }
                thumbnailContainer.add(groupPanel);
            }
        }
        thumbnailContainer.revalidate();
        thumbnailContainer.repaint();
    }

    private void deselectExplorerThumbnail() {
        if (selectedThumbnail != null) {
            selectedThumbnail.setSelected(false);
            selectedThumbnail = null;
            openExplorerButton.setEnabled(false);
            clearPreview();
        }
    }

    private void openSelectedExplorerFile() {
        if (selectedThumbnail != null && selectedThumbnail.file.exists()) {
            openFile(selectedThumbnail.file);
        }
    }

    // ===================== PREVIEW LOGIC =====================
    /**
     * Shows the best possible preview for a file:
     * - folder contents
     * - image (if ImageIO can read it)
     * - text (if detected as text)
     * - extracts text using Tika for any other file (including CDR, PSD, PDF, etc.)
     * - binary hex dump fallback if Tika unavailable or fails
     */
    private void showPreview(File file) {
        if (currentPreviewWorker != null && !currentPreviewWorker.isDone()) {
            currentPreviewWorker.cancel(true);
        }
        if (file.isDirectory()) {
            showFolderPreview(file);
            return;
        }
        // 1) try image preview for common formats and PSD (if ImageIO supports it, e.g. with TwelveMonkeys plugin)
        if (tryImagePreview(file)) return;
        // 2) try reading as text if extension hints are strong
        if (isTextFile(file)) {
            showTextPreview(readFileAsString(file, 500_000));
            return;
        }
        // 3) use Tika to extract textual content for everything else (CDR, PSD, PDF, DOCX, ...)
        if (tikaClassLoader != null) {
            previewStatusLabel.setText("Extracting text...");
            previewCardLayout.show(previewPanel, "status");
            currentPreviewWorker = new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() {
                    try {
                        return extractTextWithTika(file);
                    } catch (Exception e) {
                        return null;
                    }
                }
                @Override
                protected void done() {
                    if (isCancelled()) return;
                    try {
                        String text = get();
                        if (text != null && !text.trim().isEmpty()) {
                            showTextPreview(text);
                        } else {
                            // Tika gave nothing – fallback to binary hex
                            showBinaryPreview(file);
                        }
                    } catch (Exception e) {
                        showBinaryPreview(file);
                    }
                }
            };
            currentPreviewWorker.execute();
        } else {
            // Tika not available, show binary hex
            showBinaryPreview(file);
        }
    }

    private void showFolderPreview(File folder) {
        StringBuilder sb = new StringBuilder("Folder: " + folder.getAbsolutePath() + "\n\nContents:\n");
        try {
            File[] entries = folder.listFiles();
            if (entries != null) {
                Arrays.sort(entries, Comparator.comparing(File::isDirectory).reversed()
                        .thenComparing(File::getName));
                for (File f : entries) {
                    sb.append(f.getName()).append(f.isDirectory() ? "/" : "")
                      .append("  [").append(f.isDirectory() ? "DIR" : "FILE").append("]  ")
                      .append(f.isDirectory() ? "" : humanReadableSize(f.length())).append("\n");
                }
            }
        } catch (SecurityException e) {
            sb.append("Permission denied.");
        }
        previewTextArea.setText(sb.toString());
        previewTextArea.setCaretPosition(0);
        previewCardLayout.show(previewPanel, "text");
    }

    private boolean tryImagePreview(File file) {
        try {
            // ImageIO.read can read many formats, and with external plugins even PSD, CDR? CDR not directly.
            BufferedImage img = ImageIO.read(file);
            if (img != null) {
                int maxW = previewPanel.getWidth() - 20;
                int maxH = previewPanel.getHeight() - 20;
                if (maxW < 100) maxW = 400;
                if (maxH < 100) maxH = 400;
                BufferedImage fit = scaleImage(img, maxW, maxH);
                previewImageLabel.setIcon(new ImageIcon(fit));
                previewCardLayout.show(previewPanel, "image");
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void showBinaryPreview(File file) {
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            if (data.length > 10240) {
                byte[] truncated = new byte[10240];
                System.arraycopy(data, 0, truncated, 0, 10240);
                data = truncated;
            }
            previewTextArea.setText("Binary file. Hex preview:\n\n" + hexDump(data));
            previewTextArea.setCaretPosition(0);
            previewCardLayout.show(previewPanel, "text");
        } catch (IOException e) {
            previewTextArea.setText("Cannot read file: " + e.getMessage());
            previewCardLayout.show(previewPanel, "text");
        }
    }

    private void showTextPreview(String text) {
        if (text == null || text.isEmpty()) {
            previewStatusLabel.setText("No text content.");
            previewCardLayout.show(previewPanel, "status");
            return;
        }
        previewTextArea.setText(text.length() > 500_000 ? text.substring(0, 500_000) + "\n... (truncated)" : text);
        previewTextArea.setCaretPosition(0);
        previewCardLayout.show(previewPanel, "text");
    }

    private void clearPreview() {
        if (currentPreviewWorker != null && !currentPreviewWorker.isDone()) {
            currentPreviewWorker.cancel(true);
            currentPreviewWorker = null;
        }
        previewCardLayout.show(previewPanel, "status");
        previewStatusLabel.setText("Select a file to preview");
    }

    // ===================== UTILITY METHODS =====================
    private void openFile(File file) {
        try { Desktop.getDesktop().open(file); }
        catch (IOException e) { JOptionPane.showMessageDialog(this, "Cannot open file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE); }
    }

    private void openContainingFolder(File file) {
        try {
            Desktop.getDesktop().open(file.isDirectory() ? file : file.getParentFile());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Cannot open folder: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(text), null);
    }

    private String humanReadableSize(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double size = bytes;
        for (String unit : units) {
            if (size < 1024.0) {
                if (unit.equals("B")) return (long) size + " B";
                return String.format("%.1f %s", size, unit);
            }
            size /= 1024.0;
        }
        return String.format("%.1f PB", size);
    }

    private String hexDump(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 16) {
            int end = Math.min(i + 16, data.length);
            sb.append(String.format("%08x  ", i));
            StringBuilder hex = new StringBuilder(), txt = new StringBuilder();
            for (int j = i; j < end; j++) {
                int b = data[j] & 0xFF;
                hex.append(String.format("%02x ", b));
                txt.append(b >= 32 && b < 127 ? (char) b : '.');
            }
            while (hex.length() < 48) hex.append("   ");
            sb.append(hex).append("  ").append(txt).append("\n");
        }
        return sb.toString();
    }

    private String readFileAsString(File file, int maxLen) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            char[] buf = new char[8192];
            int read;
            while ((read = br.read(buf)) != -1 && sb.length() < maxLen) {
                sb.append(buf, 0, Math.min(read, maxLen - sb.length()));
            }
            if (sb.length() >= maxLen) sb.append("\n... (truncated)");
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
        return sb.toString();
    }

    private boolean isTextFile(File file) {
        String ext = getExtensionLower(file);
        if (ext.matches("txt|log|csv|xml|json|html|css|js|java|py|cpp|c|h|sh|bat|cmd|ps1|ini|yaml|yml|md|rst|tex"))
            return true;
        // Additional check: read first bytes and see if they look like text
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[512];
            int read = fis.read(buf);
            if (read > 0) {
                for (int i = 0; i < read; i++) if (buf[i] == 0) return false;
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String getExtensionLower(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return (dot == -1) ? "" : name.substring(dot + 1).toLowerCase();
    }

    private BufferedImage scaleImage(BufferedImage src, int maxW, int maxH) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxW && h <= maxH) return src;
        double ratio = Math.min((double) maxW / w, (double) maxH / h);
        int nw = (int) (w * ratio), nh = (int) (h * ratio);
        Image scaled = src.getScaledInstance(nw, nh, Image.SCALE_SMOOTH);
        BufferedImage result = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = result.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(scaled, 0, 0, null);
        g2.dispose();
        return result;
    }

    // ===================== TIKA DEPENDENCY =====================
    /**
     * Downloads Apache Tika if not already present and prepares the custom classloader.
     * Must be called after the frame is visible.
     */
    public void ensureDependencies() {
        try {
            Files.createDirectories(LIB_DIR);
            Path jarPath = LIB_DIR.resolve(TIKA_JAR);
            if (!Files.exists(jarPath)) showDownloadDialog(jarPath);
            tikaClassLoader = new URLClassLoader(new URL[]{jarPath.toUri().toURL()}, getClass().getClassLoader());
        } catch (Exception e) {
            System.err.println("Could not set up Tika. Fallback to basic preview.");
            e.printStackTrace();
            tikaClassLoader = null;
        }
    }

    private void showDownloadDialog(Path jarPath) {
        JDialog dialog = new JDialog(this, "Downloading required library", true);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setString("Downloading Apache Tika...");
        bar.setStringPainted(true);
        dialog.add(bar);
        dialog.setSize(400, 80);
        dialog.setLocationRelativeTo(this);
        new Thread(() -> {
            try {
                downloadFile(TIKA_MAVEN_URL, jarPath);
                SwingUtilities.invokeLater(dialog::dispose);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    dialog.dispose();
                    JOptionPane.showMessageDialog(FileSearchPreviewApp.this,
                            "Download failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
        dialog.setVisible(true);
    }

    private void downloadFile(String url, Path dest) throws IOException {
        try {
            URL website = new URI(url).toURL();
            try (ReadableByteChannel rbc = Channels.newChannel(website.openStream());
                 FileOutputStream fos = new FileOutputStream(dest.toFile())) {
                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URL: " + url, e);
        }
    }

    private String extractTextWithTika(File file) throws Exception {
        ClassLoader cl = tikaClassLoader;
        Class<?> parserCls = cl.loadClass("org.apache.tika.parser.AutoDetectParser");
        Object parser = parserCls.getDeclaredConstructor().newInstance();
        Class<?> handlerCls = cl.loadClass("org.apache.tika.sax.BodyContentHandler");
        Object handler = handlerCls.getDeclaredConstructor().newInstance();
        Class<?> metadataCls = cl.loadClass("org.apache.tika.metadata.Metadata");
        Object metadata = metadataCls.getDeclaredConstructor().newInstance();
        Class<?> contextCls = cl.loadClass("org.apache.tika.parser.ParseContext");
        Object context = contextCls.getDeclaredConstructor().newInstance();
        try (InputStream stream = new FileInputStream(file)) {
            Method parse = parserCls.getMethod("parse", InputStream.class,
                    Class.forName("org.xml.sax.ContentHandler"), metadataCls, contextCls);
            parse.invoke(parser, stream, handler, metadata, context);
        }
        return handler.toString();
    }

    // ===================== INNER CLASSES =====================
    private static final List<String> PRIORITY_EXTENSIONS = Arrays.asList(
            "cdr", "psd", "ai", "eps", "svg", "png", "jpg", "jpeg", "tiff", "tif",
            "bmp", "gif", "ico", "exe", "msi", "jar"
    );

    private class ThumbnailPanel extends JPanel {
        final File file;
        final JLabel iconLabel, nameLabel;
        boolean selected;
        static final Border SEL_BORDER = BorderFactory.createLineBorder(Color.BLUE, 3);
        static final Border UNSEL_BORDER = BorderFactory.createEmptyBorder(3, 3, 3, 3);

        ThumbnailPanel(File file) {
            this.file = file;
            setLayout(new BorderLayout(5, 5));
            setBorder(UNSEL_BORDER);
            setMaximumSize(new Dimension(THUMB_SIZE + 20, THUMB_SIZE + 40));

            iconLabel = new JLabel(getDefaultIcon(), SwingConstants.CENTER);
            iconLabel.setPreferredSize(new Dimension(THUMB_SIZE, THUMB_SIZE));
            String name = file.getName();
            if (name.length() > 15) name = name.substring(0, 12) + "...";
            nameLabel = new JLabel(name, SwingConstants.CENTER);
            nameLabel.setFont(nameLabel.getFont().deriveFont(10f));
            add(iconLabel, BorderLayout.CENTER);
            add(nameLabel, BorderLayout.SOUTH);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (!selected) {
                        if (selectedThumbnail != null) selectedThumbnail.setSelected(false);
                        setSelected(true);
                        selectedThumbnail = ThumbnailPanel.this;
                        openExplorerButton.setEnabled(true);
                        showPreview(ThumbnailPanel.this.file);
                    }
                }
            });
            // Context menu on right-click
            JPopupMenu menu = new JPopupMenu();
            JMenuItem open = new JMenuItem("Open");
            open.addActionListener(ev -> openFile(file));
            JMenuItem openFolder = new JMenuItem("Open containing folder");
            openFolder.addActionListener(ev -> openContainingFolder(file));
            JMenuItem copy = new JMenuItem("Copy full path");
            copy.addActionListener(ev -> copyToClipboard(file.getAbsolutePath()));
            menu.add(open); menu.add(openFolder); menu.add(copy);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) menu.show(ThumbnailPanel.this, e.getX(), e.getY()); }
                @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) menu.show(ThumbnailPanel.this, e.getX(), e.getY()); }
            });
        }

        void setSelected(boolean sel) {
            selected = sel;
            setBorder(sel ? SEL_BORDER : UNSEL_BORDER);
        }

        void setThumbnail(ImageIcon icon) {
            if (icon != null) iconLabel.setIcon(icon);
        }

        private ImageIcon getDefaultIcon() {
            String ext = getExtensionLower(file);
            if (ext.isEmpty()) ext = "?";
            return createTextIcon(ext.toUpperCase(), new Color(100, 100, 100), THUMB_SIZE, THUMB_SIZE);
        }
    }

    private class ThumbnailLoadTask implements Runnable {
        private final ThumbnailPanel panel;
        ThumbnailLoadTask(ThumbnailPanel p) { this.panel = p; }
        @Override
        public void run() {
            try {
                BufferedImage img = ImageIO.read(panel.file);
                if (img != null) {
                    BufferedImage scaled = scaleImage(img, THUMB_SIZE, THUMB_SIZE);
                    if (scaled != null) SwingUtilities.invokeLater(() -> panel.setThumbnail(new ImageIcon(scaled)));
                }
            } catch (Exception ignored) {}
        }
    }

    private static ImageIcon createTextIcon(String text, Color bg, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(bg);
        g2.fillRoundRect(5, 5, w - 10, h - 10, 10, 10);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 16));
        FontMetrics fm = g2.getFontMetrics();
        int x = (w - fm.stringWidth(text)) / 2;
        int y = (h - fm.getAscent()) / 2 + fm.getAscent() - 2;
        g2.drawString(text, x, y);
        g2.dispose();
        return new ImageIcon(img);
    }

    // ===================== CLEANUP & MAIN =====================
    @Override
    public void dispose() {
        if (currentPreviewWorker != null) currentPreviewWorker.cancel(true);
        thumbnailLoader.shutdownNow();
        if (tikaClassLoader != null) {
            try { tikaClassLoader.close(); } catch (IOException ignored) {}
        }
        super.dispose();
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            FileSearchPreviewApp app = new FileSearchPreviewApp();
            app.setLocationRelativeTo(null);
            app.setVisible(true);
            app.ensureDependencies();   // download Tika if needed
        });
    }
}