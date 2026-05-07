@echo off
title PURE JAVA EXE BUILDER
color 0A

:: ====================================================
:: JAVA PATH
:: ====================================================

set JAVA_HOME=C:\Program Files\Java\jdk-24

set JAVAC="%JAVA_HOME%\bin\javac.exe"
set JAR="%JAVA_HOME%\bin\jar.exe"
set JPACKAGE="%JAVA_HOME%\bin\jpackage.exe"

:: ====================================================
:: APP SETTINGS
:: ====================================================

set APPNAME=FileSearchPreviewApp_Advanced
set MAINCLASS=FileSearchPreviewApp_Advanced

:: ====================================================
:: CLEAN OLD BUILD
:: ====================================================

echo Cleaning old build...

if exist build rmdir /s /q build
if exist dist rmdir /s /q dist

mkdir build
mkdir dist

:: ====================================================
:: COMPILE JAVA
:: ====================================================

echo.
echo Compiling Java source...

%JAVAC% -encoding UTF-8 -d build %APPNAME%.java

if %errorlevel% neq 0 (
    echo.
    echo ======================================
    echo COMPILATION FAILED
    echo ======================================
    pause
    exit /b
)

:: ====================================================
:: CREATE MANIFEST
:: ====================================================

echo.
echo Creating manifest...

(
echo Main-Class: %MAINCLASS%
echo.
) > build\manifest.txt

:: ====================================================
:: CREATE JAR
:: ====================================================

echo.
echo Creating JAR...

%JAR% cfm dist\%APPNAME%.jar build\manifest.txt -C build .

if %errorlevel% neq 0 (
    echo.
    echo ======================================
    echo JAR CREATION FAILED
    echo ======================================
    pause
    exit /b
)

:: ====================================================
:: BUILD EXE
:: ====================================================

echo.
echo Creating EXE installer...

%JPACKAGE% ^
--input dist ^
--name %APPNAME% ^
--main-jar %APPNAME%.jar ^
--main-class %MAINCLASS% ^
--type exe ^
--dest dist ^
--win-console ^
--java-options "-Xmx2G" ^
--java-options "-Dfile.encoding=UTF-8"

if %errorlevel% neq 0 (
    echo.
    echo ======================================
    echo EXE BUILD FAILED
    echo ======================================
    pause
    exit /b
)

:: ====================================================
:: SUCCESS
:: ====================================================

echo.
echo ======================================
echo BUILD SUCCESSFUL
echo ======================================
echo.

echo EXE INSTALLER CREATED:
echo.
echo dist\%APPNAME%-1.0.exe
echo.

pause