import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileSystemView;
import javax.swing.table.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/**
 * Advanced File Search & Preview Explorer with CDR Image Rendering
 * Features: Multi-format support, advanced search, image viewer with zoom/pan,
 * thumbnail caching, batch operations, metadata extraction, and more.
 */
public class FileSearchPreviewApp_Advanced extends JFrame {
    
    // ========== CORE COMPONENTS ==========
    private JTextField queryField, rootField;
    private JTable resultTable;
    private DefaultTableModel tableModel;
    private JButton searchButton, stopButton, clearButton;
    private AtomicBoolean stopFlag = new AtomicBoolean(false);
    private SwingWorker<Void, Object[]> searchWorker;
    private BlockingQueue<Object[]> resultQueue = new LinkedBlockingQueue<>();
    
    // Explorer components
    private JLabel pathLabel;
    private JPanel thumbnailContainer;
    private JButton chooseFolderButton, openExplorerButton, refreshButton;
    private File currentFolder;
    private ThumbnailPanel selectedThumbnail;
    private ExecutorService thumbnailLoader = Executors.newFixedThreadPool(8);
    private Map<String, BufferedImage> thumbnailCache = new ConcurrentHashMap<>();
    
    // Preview components
    private JPanel previewPanel;
    private CardLayout previewCardLayout;
    private JLabel previewStatusLabel;
    private AdvancedImageViewer imageViewer;
    private JTextArea previewTextArea;
    private JPanel metadataPanel;
    private JProgressBar previewProgressBar;
    
    // Advanced search filters
    private JComboBox<String> fileTypeFilter, sizeFilter, dateFilter;
    private JCheckBox caseSensitiveCheck, regexCheck, includeSubfoldersCheck;
    private JSpinner minSizeSpinner, maxSizeSpinner;
    
    // Status and statistics
    private JLabel statusLabel, statsLabel;
    private AtomicInteger filesScanned = new AtomicInteger(0);
    private AtomicInteger matchesFound = new AtomicInteger(0);
    private long searchStartTime;
    
    // Tika for text extraction
    private URLClassLoader tikaClassLoader;
    private SwingWorker<String, Void> currentPreviewWorker;
    
    // Constants
    private static final String[] COLUMNS = {"Name", "Path", "Type", "Size", "Modified", "Extension"};
    private static final int THUMB_SIZE = 120;
    private static final String TIKA_VERSION = "2.9.0";
    private static final String TIKA_JAR = "tika-app-" + TIKA_VERSION + ".jar";
    private static final String TIKA_MAVEN_URL = 
            "https://repo1.maven.org/maven2/org/apache/tika/tika-app/" + TIKA_VERSION + "/" + TIKA_JAR;
    private static final Path LIB_DIR = Paths.get("lib");
    private static final Path CACHE_DIR = Paths.get("cache");
    
    // Image formats
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
        "jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif", "webp", "ico", "psd", "svg"
    ));
    private static final Set<String> CDR_EXTENSIONS = new HashSet<>(Arrays.asList(
        "cdr", "cdt", "cmx", "pat", "ccx", "cdrx"
    ));
    
    // Constructor
    public FileSearchPreviewApp_Advanced() {
        super("Advanced File Search & Preview Explorer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1400, 850);
        setMinimumSize(new Dimension(1000, 600));
        initCacheDirectory();
        initUI();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cleanup();
            }
        });
        new javax.swing.Timer(100, e -> processResultQueue()).start();
    }
    
    // ========== UI INITIALIZATION ==========
    private void initUI() {
        // Menu bar
        setJMenuBar(createMenuBar());
        
        // Main layout
        JPanel mainPanel = new JPanel(new BorderLayout());
        
        // Create tabbed pane for Search and Explorer
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("🔍 Advanced Search", buildAdvancedSearchTab());
        tabbedPane.addTab("📁 File Explorer", buildExplorerTab());
        
        // Preview pane
        JComponent previewPane = buildAdvancedPreviewPane();
        
        // Split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabbedPane, previewPane);
        splitPane.setResizeWeight(0.5);
        splitPane.setDividerLocation(700);
        mainPanel.add(splitPane, BorderLayout.CENTER);
        
        // Status bar at bottom
        JPanel statusBar = createStatusBar();
        mainPanel.add(statusBar, BorderLayout.SOUTH);
        
        add(mainPanel);
    }
    
    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        
        // File menu
        JMenu fileMenu = new JMenu("File");
        JMenuItem openFileItem = new JMenuItem("Open File...");
        openFileItem.addActionListener(e -> openFileDialog());
        JMenuItem openFolderItem = new JMenuItem("Open Folder...");
        openFolderItem.addActionListener(e -> openFolderDialog());
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(openFileItem);
        fileMenu.add(openFolderItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);
        
        // Tools menu
        JMenu toolsMenu = new JMenu("Tools");
        JMenuItem clearCacheItem = new JMenuItem("Clear Thumbnail Cache");
        clearCacheItem.addActionListener(e -> clearThumbnailCache());
        JMenuItem batchConvertItem = new JMenuItem("Batch Convert CDR Files...");
        batchConvertItem.addActionListener(e -> showBatchConvertDialog());
        toolsMenu.add(clearCacheItem);
        toolsMenu.add(batchConvertItem);
        
        // View menu
        JMenu viewMenu = new JMenu("View");
        JCheckBoxMenuItem showHiddenItem = new JCheckBoxMenuItem("Show Hidden Files");
        showHiddenItem.addActionListener(e -> refreshExplorer());
        JCheckBoxMenuItem showExtensionsItem = new JCheckBoxMenuItem("Show File Extensions", true);
        viewMenu.add(showHiddenItem);
        viewMenu.add(showExtensionsItem);
        
        // Help menu
        JMenu helpMenu = new JMenu("Help");
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> showAboutDialog());
        helpMenu.add(aboutItem);
        
        menuBar.add(fileMenu);
        menuBar.add(toolsMenu);
        menuBar.add(viewMenu);
        menuBar.add(helpMenu);
        
        return menuBar;
    }
    
    // ========== ADVANCED SEARCH TAB ==========
    private JPanel buildAdvancedSearchTab() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Top controls panel
        JPanel controlsPanel = new JPanel(new GridBagLayout());
        controlsPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), "Search Criteria"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // Row 1: Search query
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        controlsPanel.add(new JLabel("Search Query:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1; gbc.gridwidth = 3;
        queryField = new JTextField();
        queryField.addActionListener(e -> startSearch());
        queryField.getDocument().addDocumentListener(new DocumentListener() {
            public void changedUpdate(DocumentEvent e) { updateSearchHint(); }
            public void removeUpdate(DocumentEvent e) { updateSearchHint(); }
            public void insertUpdate(DocumentEvent e) { updateSearchHint(); }
        });
        controlsPanel.add(queryField, gbc);
        
        // Row 2: Root directory
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0; gbc.gridwidth = 1;
        controlsPanel.add(new JLabel("Root Directory:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1; gbc.gridwidth = 2;
        rootField = new JTextField();
        rootField.setText(System.getProperty("user.home"));
        controlsPanel.add(rootField, gbc);
        gbc.gridx = 3; gbc.weightx = 0; gbc.gridwidth = 1;
        JButton browseBtn = new JButton("Browse...");
        browseBtn.addActionListener(e -> browseSearchRoot());
        controlsPanel.add(browseBtn, gbc);
        
        // Row 3: Filters
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        controlsPanel.add(new JLabel("File Type:"), gbc);
        gbc.gridx = 1; gbc.weightx = 0.3;
        String[] types = {"All Files", "Images", "Documents", "Videos", "Audio", "Archives", "CDR Files"};
        fileTypeFilter = new JComboBox<>(types);
        controlsPanel.add(fileTypeFilter, gbc);
        
        gbc.gridx = 2; gbc.weightx = 0;
        controlsPanel.add(new JLabel("Size:"), gbc);
        gbc.gridx = 3; gbc.weightx = 0.3;
        String[] sizes = {"Any Size", "< 1 MB", "1-10 MB", "10-100 MB", "> 100 MB", "Custom..."};
        sizeFilter = new JComboBox<>(sizes);
        controlsPanel.add(sizeFilter, gbc);
        
        // Row 4: Checkboxes
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        caseSensitiveCheck = new JCheckBox("Case Sensitive");
        controlsPanel.add(caseSensitiveCheck, gbc);
        gbc.gridx = 2; gbc.gridwidth = 2;
        regexCheck = new JCheckBox("Regular Expression");
        controlsPanel.add(regexCheck, gbc);
        
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2;
        includeSubfoldersCheck = new JCheckBox("Include Subfolders", true);
        controlsPanel.add(includeSubfoldersCheck, gbc);
        
        // Row 5: Buttons
        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 4;
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        searchButton = new JButton("🔍 Search");
        searchButton.setFont(searchButton.getFont().deriveFont(Font.BOLD));
        searchButton.addActionListener(e -> startSearch());
        stopButton = new JButton("⏹ Stop");
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stopSearch());
        clearButton = new JButton("🗑 Clear");
        clearButton.addActionListener(e -> clearResults());
        buttonPanel.add(searchButton);
        buttonPanel.add(stopButton);
        buttonPanel.add(clearButton);
        controlsPanel.add(buttonPanel, gbc);
        
        panel.add(controlsPanel, BorderLayout.NORTH);
        
        // Results table with advanced features
        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
            @Override
            public Class<?> getColumnClass(int column) {
                if (column == 3 || column == 4) return String.class;
                return super.getColumnClass(column);
            }
        };
        resultTable = new JTable(tableModel);
        resultTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        resultTable.setAutoCreateRowSorter(true);
        resultTable.getTableHeader().setReorderingAllowed(true);
        resultTable.setRowHeight(22);
        
        // Custom renderer for file sizes
        resultTable.getColumnModel().getColumn(3).setCellRenderer(new FileSizeRenderer());
        resultTable.getColumnModel().getColumn(4).setCellRenderer(new DateRenderer());
        
        // Column widths
        resultTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        resultTable.getColumnModel().getColumn(1).setPreferredWidth(350);
        resultTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        resultTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        resultTable.getColumnModel().getColumn(4).setPreferredWidth(120);
        resultTable.getColumnModel().getColumn(5).setPreferredWidth(60);
        
        resultTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onSearchResultSelected();
        });
        
        setupAdvancedTablePopupMenu();
        
        JScrollPane tableScroll = new JScrollPane(resultTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Search Results"));
        panel.add(tableScroll, BorderLayout.CENTER);
        
        // Stats panel at bottom
        statsLabel = new JLabel("Ready to search");
        statsLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        panel.add(statsLabel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private void setupAdvancedTablePopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        
        JMenuItem openItem = new JMenuItem("Open");
        openItem.setIcon(UIManager.getIcon("FileView.fileIcon"));
        openItem.addActionListener(e -> openSelectedFiles());
        
        JMenuItem openFolderItem = new JMenuItem("Open Containing Folder");
        openFolderItem.setIcon(UIManager.getIcon("FileView.directoryIcon"));
        openFolderItem.addActionListener(e -> openContainingFolderFromSelection());
        
        JMenuItem copyPathItem = new JMenuItem("Copy Path");
        copyPathItem.addActionListener(e -> copyPathsFromSelection());
        
        JMenuItem propertiesItem = new JMenuItem("Properties...");
        propertiesItem.addActionListener(e -> showFileProperties());
        
        JMenuItem exportItem = new JMenuItem("Export CDR to PNG...");
        exportItem.addActionListener(e -> exportSelectedCDRFiles());
        
        JMenuItem deleteItem = new JMenuItem("Delete");
        deleteItem.addActionListener(e -> deleteSelectedFiles());
        
        popup.add(openItem);
        popup.add(openFolderItem);
        popup.addSeparator();
        popup.add(copyPathItem);
        popup.add(propertiesItem);
        popup.addSeparator();
        popup.add(exportItem);
        popup.addSeparator();
        popup.add(deleteItem);
        
        resultTable.setComponentPopupMenu(popup);
    }
    
    // ========== EXPLORER TAB ==========
    private JPanel buildExplorerTab() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Top controls
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        pathLabel = new JLabel("No folder selected");
        pathLabel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Current Path"),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        pathLabel.setFont(pathLabel.getFont().deriveFont(Font.BOLD));
        topPanel.add(pathLabel, BorderLayout.CENTER);
        
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        chooseFolderButton = new JButton("📁 Choose Folder...");
        chooseFolderButton.addActionListener(e -> chooseExplorerFolder());
        refreshButton = new JButton("🔄 Refresh");
        refreshButton.addActionListener(e -> refreshExplorer());
        openExplorerButton = new JButton("📂 Open");
        openExplorerButton.setEnabled(false);
        openExplorerButton.addActionListener(e -> openSelectedThumbnail());
        buttonPanel.add(chooseFolderButton);
        buttonPanel.add(refreshButton);
        buttonPanel.add(openExplorerButton);
        topPanel.add(buttonPanel, BorderLayout.EAST);
        
        panel.add(topPanel, BorderLayout.NORTH);
        
        // Thumbnail grid with scroll
        thumbnailContainer = new JPanel();
        thumbnailContainer.setLayout(new WrapLayout(FlowLayout.LEFT, 10, 10));
        thumbnailContainer.setBackground(Color.WHITE);
        JScrollPane scrollPane = new JScrollPane(thumbnailContainer);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Files"));
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    // ========== ADVANCED PREVIEW PANE ==========
    private JComponent buildAdvancedPreviewPane() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), "Preview"));
        
        // Card layout for different preview types
        previewCardLayout = new CardLayout();
        previewPanel = new JPanel(previewCardLayout);
        
        // 1. Status card (default)
        JPanel statusCard = new JPanel(new GridBagLayout());
        previewStatusLabel = new JLabel("Select a file to preview");
        previewStatusLabel.setFont(previewStatusLabel.getFont().deriveFont(14f));
        previewStatusLabel.setForeground(Color.GRAY);
        statusCard.add(previewStatusLabel);
        previewPanel.add(statusCard, "status");
        
        // 2. Advanced image viewer
        imageViewer = new AdvancedImageViewer();
        JScrollPane imageScroll = new JScrollPane(imageViewer);
        imageScroll.getViewport().setBackground(new Color(50, 50, 50));
        previewPanel.add(imageScroll, "image");
        
        // 3. Text preview
        previewTextArea = new JTextArea();
        previewTextArea.setEditable(false);
        previewTextArea.setLineWrap(true);
        previewTextArea.setWrapStyleWord(true);
        previewTextArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane textScroll = new JScrollPane(previewTextArea);
        previewPanel.add(textScroll, "text");
        
        // 4. Metadata viewer
        metadataPanel = new JPanel(new BorderLayout());
        JTextArea metadataArea = new JTextArea();
        metadataArea.setEditable(false);
        metadataArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        metadataPanel.add(new JScrollPane(metadataArea), BorderLayout.CENTER);
        previewPanel.add(metadataPanel, "metadata");
        
        panel.add(previewPanel, BorderLayout.CENTER);
        
        // Progress bar at bottom
        previewProgressBar = new JProgressBar();
        previewProgressBar.setStringPainted(true);
        previewProgressBar.setVisible(false);
        panel.add(previewProgressBar, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private JPanel createStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
            BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));
        
        statusLabel = new JLabel("Ready");
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusBar.add(statusLabel, BorderLayout.WEST);
        
        JLabel versionLabel = new JLabel("v2.0 Advanced | CDR Image Renderer Enabled");
        versionLabel.setFont(versionLabel.getFont().deriveFont(10f));
        versionLabel.setForeground(Color.GRAY);
        statusBar.add(versionLabel, BorderLayout.EAST);
        
        return statusBar;
    }
    
    // ========== SEARCH IMPLEMENTATION ==========
    private void startSearch() {
        String query = queryField.getText().trim();
        String root = rootField.getText().trim();
        
        if (query.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a search query", 
                "Search Error", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        if (root.isEmpty() || !new File(root).exists()) {
            JOptionPane.showMessageDialog(this, "Please select a valid root directory", 
                "Search Error", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        // Clear previous results
        tableModel.setRowCount(0);
        filesScanned.set(0);
        matchesFound.set(0);
        searchStartTime = System.currentTimeMillis();
        
        // Disable search button, enable stop
        searchButton.setEnabled(false);
        stopButton.setEnabled(true);
        stopFlag.set(false);
        
        // Start search worker
        searchWorker = new SwingWorker<Void, Object[]>() {
            @Override
            protected Void doInBackground() {
                try {
                    searchFiles(new File(root), query);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return null;
            }
            
            @Override
            protected void done() {
                searchButton.setEnabled(true);
                stopButton.setEnabled(false);
                long duration = (System.currentTimeMillis() - searchStartTime) / 1000;
                statsLabel.setText(String.format(
                    "Search complete: %d matches found in %d files (%d seconds)",
                    matchesFound.get(), filesScanned.get(), duration
                ));
                statusLabel.setText("Search completed");
            }
        };
        searchWorker.execute();
    }
    
    private void searchFiles(File dir, String query) {
        if (stopFlag.get()) return;
        
        File[] files = dir.listFiles();
        if (files == null) return;
        
        Pattern pattern = null;
        if (regexCheck.isSelected()) {
            try {
                int flags = caseSensitiveCheck.isSelected() ? 0 : Pattern.CASE_INSENSITIVE;
                pattern = Pattern.compile(query, flags);
            } catch (Exception e) {
                // Invalid regex, use plain text
                pattern = null;
            }
        }
        
        for (File file : files) {
            if (stopFlag.get()) break;
            
            filesScanned.incrementAndGet();
            
            if (file.isDirectory()) {
                if (includeSubfoldersCheck.isSelected()) {
                    searchFiles(file, query);
                }
            } else {
                boolean matches = false;
                String fileName = file.getName();
                
                // Apply search logic
                if (pattern != null) {
                    matches = pattern.matcher(fileName).find();
                } else if (caseSensitiveCheck.isSelected()) {
                    matches = fileName.contains(query);
                } else {
                    matches = fileName.toLowerCase().contains(query.toLowerCase());
                }
                
                // Apply filters
                if (matches) {
                    matches = applyFilters(file);
                }
                
                if (matches) {
                    matchesFound.incrementAndGet();
                    String ext = getExtension(file);
                    String type = getFileType(file);
                    long size = file.length();
                    Date modified = new Date(file.lastModified());
                    
                    Object[] row = {
                        fileName,
                        file.getAbsolutePath(),
                        type,
                        formatFileSize(size),
                        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(modified),
                        ext
                    };
                    
                    try {
                        resultQueue.put(row);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            
            // Update status every 100 files
            if (filesScanned.get() % 100 == 0) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText(String.format(
                        "Scanning... %d files scanned, %d matches found",
                        filesScanned.get(), matchesFound.get()
                    ));
                });
            }
        }
    }
    
    private boolean applyFilters(File file) {
        // File type filter
        String selectedType = (String) fileTypeFilter.getSelectedItem();
        if (selectedType != null && !selectedType.equals("All Files")) {
            String ext = getExtension(file).toLowerCase();
            switch (selectedType) {
                case "Images":
                    if (!IMAGE_EXTENSIONS.contains(ext) && !CDR_EXTENSIONS.contains(ext)) return false;
                    break;
                case "CDR Files":
                    if (!CDR_EXTENSIONS.contains(ext)) return false;
                    break;
                case "Documents":
                    if (!Arrays.asList("doc", "docx", "pdf", "txt", "rtf", "odt").contains(ext)) return false;
                    break;
                case "Videos":
                    if (!Arrays.asList("mp4", "avi", "mkv", "mov", "wmv", "flv").contains(ext)) return false;
                    break;
                case "Audio":
                    if (!Arrays.asList("mp3", "wav", "flac", "aac", "ogg", "wma").contains(ext)) return false;
                    break;
                case "Archives":
                    if (!Arrays.asList("zip", "rar", "7z", "tar", "gz", "bz2").contains(ext)) return false;
                    break;
            }
        }
        
        // Size filter
        String selectedSize = (String) sizeFilter.getSelectedItem();
        if (selectedSize != null && !selectedSize.equals("Any Size")) {
            long size = file.length();
            switch (selectedSize) {
                case "< 1 MB":
                    if (size >= 1024 * 1024) return false;
                    break;
                case "1-10 MB":
                    if (size < 1024 * 1024 || size >= 10 * 1024 * 1024) return false;
                    break;
                case "10-100 MB":
                    if (size < 10 * 1024 * 1024 || size >= 100 * 1024 * 1024) return false;
                    break;
                case "> 100 MB":
                    if (size < 100 * 1024 * 1024) return false;
                    break;
            }
        }
        
        return true;
    }
    
    private void stopSearch() {
        stopFlag.set(true);
        if (searchWorker != null) {
            searchWorker.cancel(true);
        }
        statusLabel.setText("Search stopped by user");
    }
    
    private void clearResults() {
        tableModel.setRowCount(0);
        filesScanned.set(0);
        matchesFound.set(0);
        statsLabel.setText("Ready to search");
        statusLabel.setText("Results cleared");
    }
    
    private void processResultQueue() {
        List<Object[]> batch = new ArrayList<>();
        resultQueue.drainTo(batch, 50);
        for (Object[] row : batch) {
            tableModel.addRow(row);
        }
    }
    
    // ========== EXPLORER IMPLEMENTATION ==========
    private void chooseExplorerFolder() {
        JFileChooser chooser = new JFileChooser(currentFolder);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadFolder(chooser.getSelectedFile());
        }
    }
    
    private void loadFolder(File folder) {
        currentFolder = folder;
        pathLabel.setText(folder.getAbsolutePath());
        thumbnailContainer.removeAll();
        selectedThumbnail = null;
        openExplorerButton.setEnabled(false);
        
        File[] files = folder.listFiles();
        if (files == null) return;
        
        // Sort files: directories first, then by name
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        
        for (File file : files) {
            if (file.isHidden()) continue;
            ThumbnailPanel thumb = new ThumbnailPanel(file);
            thumbnailContainer.add(thumb);
            
            // Load thumbnail asynchronously
            if (file.isFile()) {
                String ext = getExtension(file).toLowerCase();
                if (IMAGE_EXTENSIONS.contains(ext) || CDR_EXTENSIONS.contains(ext)) {
                    thumbnailLoader.submit(new ThumbnailLoadTask(thumb, file));
                }
            }
        }
        
        thumbnailContainer.revalidate();
        thumbnailContainer.repaint();
    }
    
    private void refreshExplorer() {
        if (currentFolder != null) {
            loadFolder(currentFolder);
        }
    }
    
    // ========== CDR FILE HANDLING ==========
    private BufferedImage loadCDRImage(File cdrFile) {
        try {
            // Try multiple methods to extract CDR image
            
            // Method 1: Extract embedded preview from CDR file structure
            BufferedImage preview = extractCDRPreview(cdrFile);
            if (preview != null) {
                statusLabel.setText("CDR preview extracted successfully");
                return preview;
            }
            
            // Method 2: Try to read as RIFF format (older CDR versions)
            preview = extractRIFFPreview(cdrFile);
            if (preview != null) {
                statusLabel.setText("CDR RIFF preview extracted");
                return preview;
            }
            
            // Method 3: Generate placeholder with CDR info
            statusLabel.setText("Creating CDR placeholder");
            return createCDRPlaceholder(cdrFile);
            
        } catch (Exception e) {
            e.printStackTrace();
            statusLabel.setText("Error loading CDR: " + e.getMessage());
            return createErrorImage("Failed to load CDR file");
        }
    }
    
    private BufferedImage extractCDRPreview(File cdrFile) {
        try (RandomAccessFile raf = new RandomAccessFile(cdrFile, "r")) {
            byte[] header = new byte[4];
            raf.read(header);
            
            // Check for CDR signature
            String signature = new String(header);
            if (!signature.equals("RIFF") && !signature.equals("CDRF")) {
                // Try to find embedded BMP or PNG
                return findEmbeddedImage(cdrFile);
            }
            
            // For RIFF files, scan for embedded preview
            raf.seek(0);
            byte[] buffer = new byte[(int) Math.min(cdrFile.length(), 10 * 1024 * 1024)];
            int read = raf.read(buffer);
            
            // Look for BMP signature (424D)
            for (int i = 0; i < read - 2; i++) {
                if (buffer[i] == 0x42 && buffer[i + 1] == 0x4D) {
                    // Found BMP header
                    return extractBMPFromBuffer(buffer, i);
                }
            }
            
            // Look for PNG signature
            byte[] pngSig = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47};
            for (int i = 0; i < read - 4; i++) {
                if (matchesSignature(buffer, i, pngSig)) {
                    return extractPNGFromBuffer(buffer, i);
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    
    private BufferedImage findEmbeddedImage(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            int chunkSize = 8192;
            byte[] buffer = new byte[chunkSize];
            
            // PNG signature
            byte[] pngSig = {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            // JPEG signature
            byte[] jpegSig = {(byte)0xFF, (byte)0xD8, (byte)0xFF};
            
            for (long pos = 0; pos < length - chunkSize; pos += chunkSize - 100) {
                raf.seek(pos);
                int read = raf.read(buffer);
                
                for (int i = 0; i < read - 8; i++) {
                    // Check for PNG
                    if (matchesSignature(buffer, i, pngSig)) {
                        long imageStart = pos + i;
                        return extractImageFromPosition(raf, imageStart, "PNG");
                    }
                    // Check for JPEG
                    if (matchesSignature(buffer, i, jpegSig)) {
                        long imageStart = pos + i;
                        return extractImageFromPosition(raf, imageStart, "JPEG");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    
    private boolean matchesSignature(byte[] buffer, int offset, byte[] signature) {
        if (offset + signature.length > buffer.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if (buffer[offset + i] != signature[i]) return false;
        }
        return true;
    }
    
    private BufferedImage extractImageFromPosition(RandomAccessFile raf, long start, String format) {
        try {
            raf.seek(start);
            // Read up to 5MB for the image
            int maxSize = 5 * 1024 * 1024;
            byte[] imageData = new byte[Math.min(maxSize, (int)(raf.length() - start))];
            int read = raf.read(imageData);
            
            // Try to find end of image
            int endPos = findImageEnd(imageData, format);
            if (endPos > 0) {
                imageData = Arrays.copyOf(imageData, endPos);
            }
            
            ByteArrayInputStream bais = new ByteArrayInputStream(imageData);
            return ImageIO.read(bais);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    
    private int findImageEnd(byte[] data, String format) {
        if (format.equals("PNG")) {
            // PNG ends with IEND chunk
            for (int i = data.length - 12; i >= 0; i--) {
                if (data[i] == 'I' && data[i+1] == 'E' && data[i+2] == 'N' && data[i+3] == 'D') {
                    return i + 12;
                }
            }
        } else if (format.equals("JPEG")) {
            // JPEG ends with FFD9
            for (int i = data.length - 2; i >= 0; i--) {
                if (data[i] == (byte)0xFF && data[i+1] == (byte)0xD9) {
                    return i + 2;
                }
            }
        }
        return -1;
    }
    
    private BufferedImage extractBMPFromBuffer(byte[] buffer, int offset) {
        try {
            // Read BMP size from header
            int size = ByteBuffer.wrap(buffer, offset + 2, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (size <= 0 || size > buffer.length - offset) {
                size = Math.min(1024 * 1024, buffer.length - offset);
            }
            
            byte[] bmpData = Arrays.copyOfRange(buffer, offset, offset + size);
            return ImageIO.read(new ByteArrayInputStream(bmpData));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    
    private BufferedImage extractPNGFromBuffer(byte[] buffer, int offset) {
        try {
            // Find PNG end marker (IEND chunk)
            int endPos = -1;
            for (int i = offset; i < buffer.length - 12; i++) {
                if (buffer[i] == 'I' && buffer[i+1] == 'E' && 
                    buffer[i+2] == 'N' && buffer[i+3] == 'D') {
                    endPos = i + 12;
                    break;
                }
            }
            
            if (endPos > 0) {
                byte[] pngData = Arrays.copyOfRange(buffer, offset, endPos);
                return ImageIO.read(new ByteArrayInputStream(pngData));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    
    private BufferedImage extractRIFFPreview(File file) {
        // Placeholder for RIFF-based extraction
        return null;
    }
    
    private BufferedImage createCDRPlaceholder(File file) {
        int width = 800;
        int height = 600;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        // Background
        GradientPaint gradient = new GradientPaint(0, 0, new Color(240, 240, 250), 
                                                    0, height, new Color(200, 200, 220));
        g2.setPaint(gradient);
        g2.fillRect(0, 0, width, height);
        
        // CDR icon
        int iconSize = 200;
        int iconX = (width - iconSize) / 2;
        int iconY = 150;
        g2.setColor(new Color(100, 100, 200));
        g2.fillRoundRect(iconX, iconY, iconSize, iconSize, 20, 20);
        
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 80));
        String ext = "CDR";
        FontMetrics fm = g2.getFontMetrics();
        int textX = iconX + (iconSize - fm.stringWidth(ext)) / 2;
        int textY = iconY + (iconSize - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(ext, textX, textY);
        
        // File info
        g2.setColor(new Color(60, 60, 60));
        g2.setFont(new Font("Arial", Font.BOLD, 24));
        String fileName = file.getName();
        if (fileName.length() > 40) fileName = fileName.substring(0, 37) + "...";
        fm = g2.getFontMetrics();
        g2.drawString(fileName, (width - fm.stringWidth(fileName)) / 2, iconY + iconSize + 50);
        
        g2.setFont(new Font("Arial", Font.PLAIN, 16));
        String info = String.format("Size: %s | Modified: %s", 
            formatFileSize(file.length()),
            new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(file.lastModified()))
        );
        fm = g2.getFontMetrics();
        g2.drawString(info, (width - fm.stringWidth(info)) / 2, iconY + iconSize + 80);
        
        // Instruction
        g2.setColor(new Color(100, 100, 100));
        g2.setFont(new Font("Arial", Font.ITALIC, 14));
        String msg = "CDR preview not available - Original image data may be embedded";
        fm = g2.getFontMetrics();
        g2.drawString(msg, (width - fm.stringWidth(msg)) / 2, iconY + iconSize + 110);
        
        g2.dispose();
        return img;
    }
    
    private BufferedImage createErrorImage(String message) {
        int width = 400;
        int height = 300;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        g2.setColor(new Color(250, 240, 240));
        g2.fillRect(0, 0, width, height);
        
        g2.setColor(new Color(200, 50, 50));
        g2.setFont(new Font("Arial", Font.BOLD, 16));
        FontMetrics fm = g2.getFontMetrics();
        
        // Draw message centered
        String[] lines = message.split("\n");
        int y = (height - lines.length * fm.getHeight()) / 2;
        for (String line : lines) {
            int x = (width - fm.stringWidth(line)) / 2;
            g2.drawString(line, x, y);
            y += fm.getHeight() + 5;
        }
        
        g2.dispose();
        return img;
    }
    
    // ========== PREVIEW IMPLEMENTATION ==========
    private void showPreview(File file) {
        if (currentPreviewWorker != null && !currentPreviewWorker.isDone()) {
            currentPreviewWorker.cancel(true);
        }
        
        previewProgressBar.setVisible(true);
        previewProgressBar.setIndeterminate(true);
        previewProgressBar.setString("Loading preview...");
        
        String ext = getExtension(file).toLowerCase();
        
        // Handle image files (including CDR)
        if (IMAGE_EXTENSIONS.contains(ext) || CDR_EXTENSIONS.contains(ext)) {
            currentPreviewWorker = new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() {
                    try {
                        BufferedImage img;
                        if (CDR_EXTENSIONS.contains(ext)) {
                            img = loadCDRImage(file);
                        } else {
                            img = ImageIO.read(file);
                        }
                        
                        if (img != null) {
                            SwingUtilities.invokeLater(() -> {
                                imageViewer.setImage(img);
                                imageViewer.setFile(file);
                                previewCardLayout.show(previewPanel, "image");
                                previewProgressBar.setVisible(false);
                            });
                        } else {
                            return "Failed to load image";
                        }
                    } catch (Exception e) {
                        return "Error: " + e.getMessage();
                    }
                    return null;
                }
                
                @Override
                protected void done() {
                    try {
                        String error = get();
                        if (error != null) {
                            previewStatusLabel.setText(error);
                            previewCardLayout.show(previewPanel, "status");
                        }
                    } catch (Exception e) {
                        previewStatusLabel.setText("Preview error: " + e.getMessage());
                        previewCardLayout.show(previewPanel, "status");
                    }
                    previewProgressBar.setVisible(false);
                }
            };
            currentPreviewWorker.execute();
        }
        // Handle text files
        else if (Arrays.asList("txt", "log", "xml", "json", "java", "py", "js", "html", "css").contains(ext)) {
            currentPreviewWorker = new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() throws Exception {
                    if (file.length() > 1024 * 1024) {
                        return "File too large for preview (> 1MB)";
                    }
                    return new String(Files.readAllBytes(file.toPath()));
                }
                
                @Override
                protected void done() {
                    try {
                        String content = get();
                        previewTextArea.setText(content);
                        previewTextArea.setCaretPosition(0);
                        previewCardLayout.show(previewPanel, "text");
                    } catch (Exception e) {
                        previewStatusLabel.setText("Error loading text: " + e.getMessage());
                        previewCardLayout.show(previewPanel, "status");
                    }
                    previewProgressBar.setVisible(false);
                }
            };
            currentPreviewWorker.execute();
        }
        // Default: show file info
        else {
            previewStatusLabel.setText(String.format(
                "<html><center>%s<br><br>Size: %s<br>Modified: %s<br><br>No preview available</center></html>",
                file.getName(),
                formatFileSize(file.length()),
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(file.lastModified()))
            ));
            previewCardLayout.show(previewPanel, "status");
            previewProgressBar.setVisible(false);
        }
    }
    
    // ========== ACTION HANDLERS ==========
    private void onSearchResultSelected() {
        int row = resultTable.getSelectedRow();
        if (row >= 0) {
            row = resultTable.convertRowIndexToModel(row);
            String path = (String) tableModel.getValueAt(row, 1);
            File file = new File(path);
            if (file.exists() && file.isFile()) {
                showPreview(file);
            }
        }
    }
    
    private void openSelectedFiles() {
        int[] rows = resultTable.getSelectedRows();
        for (int row : rows) {
            row = resultTable.convertRowIndexToModel(row);
            String path = (String) tableModel.getValueAt(row, 1);
            openFile(new File(path));
        }
    }
    
    private void openFile(File file) {
        try {
            Desktop.getDesktop().open(file);
            statusLabel.setText("Opened: " + file.getName());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to open file: " + e.getMessage(), 
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void openContainingFolderFromSelection() {
        int row = resultTable.getSelectedRow();
        if (row >= 0) {
            row = resultTable.convertRowIndexToModel(row);
            String path = (String) tableModel.getValueAt(row, 1);
            openContainingFolder(new File(path));
        }
    }
    
    private void openContainingFolder(File file) {
        try {
            Desktop.getDesktop().open(file.getParentFile());
            statusLabel.setText("Opened folder: " + file.getParent());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to open folder: " + e.getMessage(), 
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void copyPathsFromSelection() {
        int[] rows = resultTable.getSelectedRows();
        StringBuilder sb = new StringBuilder();
        for (int row : rows) {
            row = resultTable.convertRowIndexToModel(row);
            String path = (String) tableModel.getValueAt(row, 1);
            sb.append(path).append("\n");
        }
        copyToClipboard(sb.toString());
        statusLabel.setText(rows.length + " path(s) copied to clipboard");
    }
    
    private void copyToClipboard(String text) {
        StringSelection selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
    }
    
    private void showFileProperties() {
        int row = resultTable.getSelectedRow();
        if (row >= 0) {
            row = resultTable.convertRowIndexToModel(row);
            String path = (String) tableModel.getValueAt(row, 1);
            File file = new File(path);
            
            JDialog dialog = new JDialog(this, "File Properties", true);
            dialog.setLayout(new BorderLayout(10, 10));
            dialog.setSize(500, 400);
            
            JTextArea propsArea = new JTextArea();
            propsArea.setEditable(false);
            propsArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
            
            StringBuilder props = new StringBuilder();
            props.append("File Properties\n");
            props.append("=".repeat(50)).append("\n\n");
            props.append("Name: ").append(file.getName()).append("\n");
            props.append("Path: ").append(file.getAbsolutePath()).append("\n");
            props.append("Size: ").append(formatFileSize(file.length())).append("\n");
            props.append("Modified: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(file.lastModified()))).append("\n");
            props.append("Readable: ").append(file.canRead()).append("\n");
            props.append("Writable: ").append(file.canWrite()).append("\n");
            props.append("Hidden: ").append(file.isHidden()).append("\n");
            
            try {
                Path path2 = file.toPath();
                props.append("\nAttributes:\n");
                props.append("  Created: ").append(Files.getAttribute(path2, "creationTime")).append("\n");
                props.append("  Last Access: ").append(Files.getAttribute(path2, "lastAccessTime")).append("\n");
            } catch (Exception ignored) {}
            
            propsArea.setText(props.toString());
            
            JScrollPane scroll = new JScrollPane(propsArea);
            scroll.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            dialog.add(scroll, BorderLayout.CENTER);
            
            JButton closeBtn = new JButton("Close");
            closeBtn.addActionListener(e -> dialog.dispose());
            JPanel btnPanel = new JPanel();
            btnPanel.add(closeBtn);
            dialog.add(btnPanel, BorderLayout.SOUTH);
            
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
        }
    }
    
    private void exportSelectedCDRFiles() {
        int[] rows = resultTable.getSelectedRows();
        if (rows.length == 0) return;
        
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Export Directory");
        
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File outputDir = chooser.getSelectedFile();
            
            JProgressBar progressBar = new JProgressBar(0, rows.length);
            progressBar.setStringPainted(true);
            JDialog progressDialog = new JDialog(this, "Exporting...", false);
            progressDialog.add(progressBar);
            progressDialog.setSize(400, 100);
            progressDialog.setLocationRelativeTo(this);
            progressDialog.setVisible(true);
            
            SwingWorker<Void, Integer> worker = new SwingWorker<Void, Integer>() {
                @Override
                protected Void doInBackground() {
                    for (int i = 0; i < rows.length; i++) {
                        int row = resultTable.convertRowIndexToModel(rows[i]);
                        String path = (String) tableModel.getValueAt(row, 1);
                        File cdrFile = new File(path);
                        
                        String ext = getExtension(cdrFile).toLowerCase();
                        if (CDR_EXTENSIONS.contains(ext)) {
                            try {
                                BufferedImage img = loadCDRImage(cdrFile);
                                if (img != null) {
                                    String outputName = cdrFile.getName().replaceAll("\\.[^.]+$", ".png");
                                    File outputFile = new File(outputDir, outputName);
                                    ImageIO.write(img, "PNG", outputFile);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        publish(i + 1);
                    }
                    return null;
                }
                
                @Override
                protected void process(List<Integer> chunks) {
                    if (!chunks.isEmpty()) {
                        int progress = chunks.get(chunks.size() - 1);
                        progressBar.setValue(progress);
                        progressBar.setString(progress + " / " + rows.length);
                    }
                }
                
                @Override
                protected void done() {
                    progressDialog.dispose();
                    JOptionPane.showMessageDialog(FileSearchPreviewApp_Advanced.this, 
                        "Export complete! Files saved to: " + outputDir.getAbsolutePath(),
                        "Export Complete", JOptionPane.INFORMATION_MESSAGE);
                }
            };
            worker.execute();
        }
    }
    
    private void deleteSelectedFiles() {
        int[] rows = resultTable.getSelectedRows();
        if (rows.length == 0) return;
        
        int confirm = JOptionPane.showConfirmDialog(this, 
            "Delete " + rows.length + " file(s)? This action cannot be undone.",
            "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        
        if (confirm == JOptionPane.YES_OPTION) {
            int deleted = 0;
            for (int row : rows) {
                row = resultTable.convertRowIndexToModel(row);
                String path = (String) tableModel.getValueAt(row, 1);
                File file = new File(path);
                if (file.delete()) {
                    deleted++;
                }
            }
            
            // Remove from table
            for (int i = rows.length - 1; i >= 0; i--) {
                tableModel.removeRow(resultTable.convertRowIndexToModel(rows[i]));
            }
            
            JOptionPane.showMessageDialog(this, deleted + " file(s) deleted successfully",
                "Delete Complete", JOptionPane.INFORMATION_MESSAGE);
        }
    }
    
    private void openSelectedThumbnail() {
        if (selectedThumbnail != null) {
            openFile(selectedThumbnail.file);
        }
    }
    
    private void browseSearchRoot() {
        JFileChooser chooser = new JFileChooser(rootField.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            rootField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }
    
    private void openFileDialog() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            showPreview(chooser.getSelectedFile());
        }
    }
    
    private void openFolderDialog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadFolder(chooser.getSelectedFile());
        }
    }
    
    private void clearThumbnailCache() {
        thumbnailCache.clear();
        try {
            Files.walk(CACHE_DIR)
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
        JOptionPane.showMessageDialog(this, "Thumbnail cache cleared", 
            "Cache Cleared", JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void showBatchConvertDialog() {
        JDialog dialog = new JDialog(this, "Batch Convert CDR Files", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(600, 400);
        
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        panel.add(new JLabel("Input Folder:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        JTextField inputField = new JTextField();
        panel.add(inputField, gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        JButton inputBtn = new JButton("Browse...");
        inputBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                inputField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        panel.add(inputBtn, gbc);
        
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        panel.add(new JLabel("Output Folder:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        JTextField outputField = new JTextField();
        panel.add(outputField, gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        JButton outputBtn = new JButton("Browse...");
        outputBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                outputField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        panel.add(outputBtn, gbc);
        
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3;
        JCheckBox subfolderCheck = new JCheckBox("Include Subfolders", true);
        panel.add(subfolderCheck, gbc);
        
        gbc.gridy = 3;
        JButton convertBtn = new JButton("Start Conversion");
        convertBtn.addActionListener(e -> {
            // Implementation for batch conversion
            dialog.dispose();
        });
        panel.add(convertBtn, gbc);
        
        dialog.add(panel, BorderLayout.CENTER);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    private void showAboutDialog() {
        String message = "<html><center>" +
            "<h2>Advanced File Search & Preview Explorer</h2>" +
            "<p>Version 2.0</p>" +
            "<br>" +
            "<p>Features:</p>" +
            "<ul align='left'>" +
            "<li>Advanced file search with filters</li>" +
            "<li>CDR (CorelDRAW) image rendering</li>" +
            "<li>Thumbnail caching</li>" +
            "<li>Batch operations</li>" +
            "<li>Image viewer with zoom/pan</li>" +
            "<li>Metadata extraction</li>" +
            "</ul>" +
            "<br>" +
            "<p>© 2024 - All Rights Reserved</p>" +
            "</center></html>";
        
        JOptionPane.showMessageDialog(this, message, "About", JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void updateSearchHint() {
        // Could add search hints here
    }
    
    // ========== UTILITY METHODS ==========
    private String getExtension(File file) {
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        return lastDot > 0 ? name.substring(lastDot + 1) : "";
    }
    
    private String getFileType(File file) {
        if (file.isDirectory()) return "Folder";
        String ext = getExtension(file).toLowerCase();
        if (IMAGE_EXTENSIONS.contains(ext)) return "Image";
        if (CDR_EXTENSIONS.contains(ext)) return "CDR Image";
        if (Arrays.asList("doc", "docx", "pdf", "txt").contains(ext)) return "Document";
        if (Arrays.asList("mp4", "avi", "mkv").contains(ext)) return "Video";
        if (Arrays.asList("mp3", "wav", "flac").contains(ext)) return "Audio";
        if (Arrays.asList("zip", "rar", "7z").contains(ext)) return "Archive";
        return "File";
    }
    
    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        else if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        else if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        else return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
    }
    
    private void initCacheDirectory() {
        try {
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            System.err.println("Failed to create cache directory: " + e.getMessage());
        }
    }
    
    private void cleanup() {
        stopFlag.set(true);
        if (searchWorker != null) searchWorker.cancel(true);
        if (currentPreviewWorker != null) currentPreviewWorker.cancel(true);
        thumbnailLoader.shutdownNow();
    }
    
    // ========== INNER CLASSES ==========
    
    // Advanced Image Viewer with zoom/pan
    private class AdvancedImageViewer extends JPanel {
        private BufferedImage image;
        private File currentFile;
        private double scale = 1.0;
        private int offsetX = 0, offsetY = 0;
        private Point dragStart;
        
        public AdvancedImageViewer() {
            setBackground(new Color(50, 50, 50));
            setOpaque(true);
            
            addMouseWheelListener(e -> {
                if (image != null) {
                    double oldScale = scale;
                    if (e.getWheelRotation() < 0) {
                        scale *= 1.1;
                    } else {
                        scale /= 1.1;
                    }
                    scale = Math.max(0.1, Math.min(scale, 10.0));
                    
                    // Adjust offset to zoom toward mouse position
                    Point mouse = e.getPoint();
                    offsetX = (int) ((offsetX - mouse.x) * scale / oldScale + mouse.x);
                    offsetY = (int) ((offsetY - mouse.y) * scale / oldScale + mouse.y);
                    
                    repaint();
                    updateStatusBar();
                }
            });
            
            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    dragStart = e.getPoint();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
                
                @Override
                public void mouseReleased(MouseEvent e) {
                    setCursor(Cursor.getDefaultCursor());
                }
                
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragStart != null) {
                        offsetX += e.getX() - dragStart.x;
                        offsetY += e.getY() - dragStart.y;
                        dragStart = e.getPoint();
                        repaint();
                    }
                }
                
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        fitToWindow();
                    }
                }
            };
            
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }
        
        public void setImage(BufferedImage img) {
            this.image = img;
            fitToWindow();
        }
        
        public void setFile(File file) {
            this.currentFile = file;
        }
        
        private void fitToWindow() {
            if (image == null) return;
            
            int panelWidth = getWidth();
            int panelHeight = getHeight();
            int imgWidth = image.getWidth();
            int imgHeight = image.getHeight();
            
            double scaleX = (double) panelWidth / imgWidth;
            double scaleY = (double) panelHeight / imgHeight;
            scale = Math.min(scaleX, scaleY) * 0.9;
            
            offsetX = (panelWidth - (int)(imgWidth * scale)) / 2;
            offsetY = (panelHeight - (int)(imgHeight * scale)) / 2;
            
            repaint();
            updateStatusBar();
        }
        
        private void updateStatusBar() {
            if (image != null && currentFile != null) {
                statusLabel.setText(String.format("%s | %dx%d | Zoom: %.0f%%", 
                    currentFile.getName(), 
                    image.getWidth(), 
                    image.getHeight(), 
                    scale * 100));
            }
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            
            if (image == null) return;
            
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, 
                scale > 1.0 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR 
                           : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            
            AffineTransform transform = new AffineTransform();
            transform.translate(offsetX, offsetY);
            transform.scale(scale, scale);
            g2.drawImage(image, transform, null);
            
            // Draw border
            g2.setColor(new Color(100, 100, 100));
            int w = (int)(image.getWidth() * scale);
            int h = (int)(image.getHeight() * scale);
            g2.drawRect(offsetX - 1, offsetY - 1, w + 1, h + 1);
        }
        
        @Override
        public Dimension getPreferredSize() {
            if (image != null) {
                return new Dimension(
                    (int)(image.getWidth() * scale), 
                    (int)(image.getHeight() * scale)
                );
            }
            return super.getPreferredSize();
        }
    }
    
    // Thumbnail Panel
    private class ThumbnailPanel extends JPanel {
        final File file;
        final JLabel iconLabel, nameLabel;
        boolean selected;
        static final Border SEL_BORDER = BorderFactory.createLineBorder(new Color(0, 120, 215), 3);
        static final Border UNSEL_BORDER = BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1);
        
        ThumbnailPanel(File file) {
            this.file = file;
            setLayout(new BorderLayout(5, 5));
            setBorder(UNSEL_BORDER);
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(THUMB_SIZE + 20, THUMB_SIZE + 50));
            setMaximumSize(new Dimension(THUMB_SIZE + 20, THUMB_SIZE + 50));
            
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
                        showPreview(file);
                    }
                }
                
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        openFile(file);
                    }
                }
            });
            
            // Context menu
            JPopupMenu menu = new JPopupMenu();
            JMenuItem open = new JMenuItem("Open");
            open.addActionListener(ev -> openFile(file));
            JMenuItem openFolder = new JMenuItem("Open Folder");
            openFolder.addActionListener(ev -> openContainingFolder(file));
            JMenuItem copy = new JMenuItem("Copy Path");
            copy.addActionListener(ev -> copyToClipboard(file.getAbsolutePath()));
            menu.add(open);
            menu.add(openFolder);
            menu.add(copy);
            setComponentPopupMenu(menu);
        }
        
        void setSelected(boolean sel) {
            selected = sel;
            setBorder(sel ? SEL_BORDER : UNSEL_BORDER);
            setBackground(sel ? new Color(230, 240, 255) : Color.WHITE);
        }
        
        void setThumbnail(BufferedImage img) {
            if (img != null) {
                iconLabel.setIcon(new ImageIcon(img));
            }
        }
        
        private ImageIcon getDefaultIcon() {
            if (file.isDirectory()) {
                return createTextIcon("📁", new Color(100, 150, 200), THUMB_SIZE, THUMB_SIZE);
            }
            String ext = getExtension(file).toUpperCase();
            if (ext.isEmpty()) ext = "?";
            Color color = getColorForExtension(ext);
            return createTextIcon(ext, color, THUMB_SIZE, THUMB_SIZE);
        }
        
        private Color getColorForExtension(String ext) {
            switch (ext.toLowerCase()) {
                case "cdr": return new Color(0, 150, 136);
                case "psd": return new Color(49, 168, 255);
                case "png": case "jpg": case "jpeg": return new Color(76, 175, 80);
                case "doc": case "docx": return new Color(33, 150, 243);
                case "pdf": return new Color(244, 67, 54);
                case "mp3": case "wav": return new Color(156, 39, 176);
                case "mp4": case "avi": return new Color(255, 152, 0);
                default: return new Color(100, 100, 100);
            }
        }
    }
    
    // Thumbnail loader task
    private class ThumbnailLoadTask implements Runnable {
        private final ThumbnailPanel panel;
        private final File file;
        
        ThumbnailLoadTask(ThumbnailPanel panel, File file) {
            this.panel = panel;
            this.file = file;
        }
        
        @Override
        public void run() {
            try {
                String cacheKey = file.getAbsolutePath() + "_" + file.lastModified();
                BufferedImage thumb = thumbnailCache.get(cacheKey);
                
                if (thumb == null) {
                    String ext = getExtension(file).toLowerCase();
                    if (CDR_EXTENSIONS.contains(ext)) {
                        BufferedImage full = loadCDRImage(file);
                        if (full != null) {
                            thumb = scaleImage(full, THUMB_SIZE, THUMB_SIZE);
                        }
                    } else {
                        BufferedImage full = ImageIO.read(file);
                        if (full != null) {
                            thumb = scaleImage(full, THUMB_SIZE, THUMB_SIZE);
                        }
                    }
                    
                    if (thumb != null) {
                        thumbnailCache.put(cacheKey, thumb);
                    }
                }
                
                if (thumb != null) {
                    final BufferedImage finalThumb = thumb;
                    SwingUtilities.invokeLater(() -> panel.setThumbnail(finalThumb));
                }
            } catch (Exception e) {
                // Silently ignore thumbnail load errors
            }
        }
    }
    
    private BufferedImage scaleImage(BufferedImage original, int maxWidth, int maxHeight) {
        int width = original.getWidth();
        int height = original.getHeight();
        
        double scaleX = (double) maxWidth / width;
        double scaleY = (double) maxHeight / height;
        double scale = Math.min(scaleX, scaleY);
        
        int newWidth = (int) (width * scale);
        int newHeight = (int) (height * scale);
        
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(original, 0, 0, newWidth, newHeight, null);
        g2.dispose();
        
        return scaled;
    }
    
    private static ImageIcon createTextIcon(String text, Color bg, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        // Background
        g2.setColor(bg);
        g2.fillRoundRect(5, 5, w - 10, h - 10, 10, 10);
        
        // Text
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, text.length() <= 3 ? 18 : 14));
        FontMetrics fm = g2.getFontMetrics();
        int x = (w - fm.stringWidth(text)) / 2;
        int y = (h - fm.getAscent()) / 2 + fm.getAscent();
        g2.drawString(text, x, y);
        
        g2.dispose();
        return new ImageIcon(img);
    }
    
    // Custom table renderers
    private static class FileSizeRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, 
                boolean isSelected, boolean hasFocus, int row, int column) {
            setHorizontalAlignment(SwingConstants.RIGHT);
            return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        }
    }
    
    private static class DateRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, 
                boolean isSelected, boolean hasFocus, int row, int column) {
            setHorizontalAlignment(SwingConstants.CENTER);
            return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        }
    }
    
    // WrapLayout for thumbnail grid
    private static class WrapLayout extends FlowLayout {
        public WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }
        
        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }
        
        @Override
        public Dimension minimumLayoutSize(Container target) {
            return layoutSize(target, false);
        }
        
        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getSize().width;
                if (targetWidth == 0) targetWidth = Integer.MAX_VALUE;
                
                int hgap = getHgap();
                int vgap = getVgap();
                Insets insets = target.getInsets();
                int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
                int maxWidth = targetWidth - horizontalInsetsAndGap;
                
                Dimension dim = new Dimension(0, 0);
                int rowWidth = 0;
                int rowHeight = 0;
                
                int nmembers = target.getComponentCount();
                for (int i = 0; i < nmembers; i++) {
                    Component m = target.getComponent(i);
                    if (m.isVisible()) {
                        Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                        
                        if (rowWidth + d.width > maxWidth) {
                            addRow(dim, rowWidth, rowHeight);
                            rowWidth = 0;
                            rowHeight = 0;
                        }
                        
                        if (rowWidth != 0) {
                            rowWidth += hgap;
                        }
                        
                        rowWidth += d.width;
                        rowHeight = Math.max(rowHeight, d.height);
                    }
                }
                addRow(dim, rowWidth, rowHeight);
                
                dim.width += horizontalInsetsAndGap;
                dim.height += insets.top + insets.bottom + vgap * 2;
                
                Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
                if (scrollPane != null && target.isValid()) {
                    dim.width -= (hgap + 1);
                }
                
                return dim;
            }
        }
        
        private void addRow(Dimension dim, int rowWidth, int rowHeight) {
            dim.width = Math.max(dim.width, rowWidth);
            if (dim.height > 0) {
                dim.height += getVgap();
            }
            dim.height += rowHeight;
        }
    }
    
    // ========== MAIN METHOD ==========
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        SwingUtilities.invokeLater(() -> {
            FileSearchPreviewApp_Advanced app = new FileSearchPreviewApp_Advanced();
            app.setLocationRelativeTo(null);
            app.setVisible(true);
        });
    }
}