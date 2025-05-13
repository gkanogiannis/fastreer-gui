package com.gkanogiannis.fastreer.gui;

import java.awt.BorderLayout;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

public class FastreeRGUI extends JFrame {

    private final File settingsFile = new File(System.getProperty("user.home"), ".fastreer-gui/settings.properties");

    private JComboBox<String> modeCombo;
    private JTextArea inputArea;
    private List<File> selectedFiles = new ArrayList<>();
    private File outputFile;
    private File backendJarPath = new File("lib", "BioInfoJavaUtils.jar"); // default

    public FastreeRGUI() {
        loadSettings();

        setTitle("FastreeR GUI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(600, 350);
        setLayout(new BorderLayout());

        // Menu bar
        JMenuBar menuBar = new JMenuBar();
        setJMenuBar(menuBar);

        JMenu fileMenu = new JMenu("File");
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitItem);
        menuBar.add(fileMenu);

        JMenu settingsMenu = new JMenu("Settings");
        JMenuItem setJarItem = new JMenuItem("Set backend JAR...");
        setJarItem.addActionListener(e -> selectBackendJar());
        settingsMenu.add(setJarItem);
        menuBar.add(settingsMenu);

        JMenu operationsMenu = new JMenu("Operations");
        JMenuItem clearInputItem = new JMenuItem("Clear Input Files");
        clearInputItem.addActionListener(e -> clearInputFiles());
        operationsMenu.add(clearInputItem);
        menuBar.add(operationsMenu);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem checkUpdateItem = new JMenuItem("Check for Update");
        checkUpdateItem.addActionListener(e -> checkForUpdate());
        helpMenu.add(checkUpdateItem);
        menuBar.add(helpMenu);

        String[] modes = {"VCF2DIST", "FASTA2DIST", "DIST2TREE"};
        modeCombo = new JComboBox<>(modes);
        JButton selectInputButton = new JButton("Select Input File(s)");
        JButton runButton = new JButton("Run FastreeR");
        inputArea = new JTextArea(6, 40);
        inputArea.setEditable(false);

        JPanel topPanel = new JPanel();
        topPanel.add(new JLabel("Mode:"));
        topPanel.add(modeCombo);
        topPanel.add(selectInputButton);
        topPanel.add(runButton);

        add(topPanel, BorderLayout.NORTH);
        add(new JScrollPane(inputArea), BorderLayout.CENTER);

        selectInputButton.addActionListener(e -> selectInputFiles());
        runButton.addActionListener(e -> runFastreeR());

        setVisible(true);
    }

    private void loadSettings() {
        Properties props = new Properties();
        if (settingsFile.exists()) {
            try (FileInputStream fis = new FileInputStream(settingsFile)) {
                props.load(fis);
                String jarPath = props.getProperty("backendJarPath");
                if (jarPath != null && !jarPath.isBlank()) {
                    File candidate = new File(jarPath);
                    if (candidate.exists()) {
                        backendJarPath = candidate;
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to load settings: " + e.getMessage());
            }
        } else {
            // Create folder and default file
            try {
                settingsFile.getParentFile().mkdirs();
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("settings.properties")) {
                if (in == null) {
                    throw new FileNotFoundException("Resource settings.properties not found in JAR.");
                }

                try (OutputStream out = new FileOutputStream(settingsFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }
            }

            System.out.println("Copied default settings.properties to: " + settingsFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("Failed to create default settings: " + e.getMessage());
            }
        }
    }

    private void saveSettings() {
        Properties props = new Properties();
        try (FileOutputStream fos = new FileOutputStream(settingsFile)) {
            props.store(fos, "FastreeR GUI Settings");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to save settings.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }    

    private void selectBackendJar() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select fastreeR.jar backend file");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            backendJarPath = chooser.getSelectedFile();
            saveSettings();
            JOptionPane.showMessageDialog(this, "Backend JAR set to:\n" + backendJarPath.getAbsolutePath());
        }
    }

    private void checkForUpdate() {
    String apiUrl = "https://api.github.com/repos/gkanogiannis/fastreer-gui/releases/latest";
    File appDir = getAppDirectory();

    // Load current settings
    Properties props = new Properties();
    String currentVersion = "0.0.0";
    try (FileInputStream fis = new FileInputStream(settingsFile)) {
        props.load(fis);
        currentVersion = props.getProperty("fastreerGuiVersion", "0.0.0");
    } catch (IOException e) {
        JOptionPane.showMessageDialog(this, "Could not load settings to check version.");
        return;
    }

    try {
        // Fetch latest release metadata
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("User-Agent", "FastreeR-Updater");
        if (conn.getResponseCode() != 200) {
            throw new IOException("GitHub API error: " + conn.getResponseCode());
        }

        String json = new BufferedReader(new InputStreamReader(conn.getInputStream()))
            .lines().collect(Collectors.joining("\n"));

        // Simple regex-based tag extraction
        String latestVersion = json.replaceAll("(?s).*\"tag_name\"\\s*:\\s*\"v?(.*?)\".*", "$1");

        if (compareVersions(latestVersion, currentVersion) <= 0) {
            JOptionPane.showMessageDialog(this, "You already have the latest version (" + currentVersion + ").");
            return;
        }

        String localFilename = "fastreer-gui-latest-jar-with-dependencies.jar";
        String downloadFilename = "fastreer-gui-"+currentVersion+"-jar-with-dependencies.jar";
        // Extract download URL for the jar
        Pattern p = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"(.*?)\"");
        Matcher m = p.matcher(json);
        String downloadUrl = null;
        while (m.find()) {
            String url = m.group(1);
            if (url.endsWith(downloadFilename)) {
                downloadUrl = url;
                break;
            }
        }

        if (downloadUrl == null) {
            throw new IOException("Could not find download link for: " + downloadFilename);
        }

        int confirm = JOptionPane.showConfirmDialog(
            this,
            "A new version (" + latestVersion + ") is available.\nDownload and install it now?",
            "Update Available",
            JOptionPane.YES_NO_OPTION
        );
        if (confirm != JOptionPane.YES_OPTION) return;

        // Download to installation folder
        File targetJar = new File(appDir, localFilename);
        try (InputStream in = new URL(downloadUrl).openStream();
             OutputStream out = new FileOutputStream(targetJar)) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
        }

        // Update settings
        props.setProperty("fastreerGuiVersion", latestVersion);
        try (FileOutputStream fos = new FileOutputStream(settingsFile)) {
            props.store(fos, "Updated after successful GUI update.");
        }

        JOptionPane.showMessageDialog(this,
            "Update complete.\nPlease restart the application to use the new version.",
            "Update Installed",
            JOptionPane.INFORMATION_MESSAGE);

        } catch (IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this,
                "Update failed:\n" + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
            int a = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int b = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }    

    private File getAppDirectory() {
        try {
            String path = FastreeRGUI.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            File jarFile = new File(path);
            return jarFile.getParentFile();
        } catch (Exception e) {
            e.printStackTrace();
            return new File("."); // fallback
        }
    }    

    private void clearInputFiles() {
        selectedFiles.clear();
        inputArea.setText("");
    }

    private void selectInputFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            selectedFiles.clear();
            File[] files = chooser.getSelectedFiles();
            for (File f : files)
                selectedFiles.add(f);
            updateInputDisplay();
        }
    }

    private void updateInputDisplay() {
        StringBuilder sb = new StringBuilder();
        for (File f : selectedFiles) {
            sb.append(f.getAbsolutePath()).append("\n");
        }
        inputArea.setText(sb.toString());
    }

    private void runFastreeR() {
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No input files selected.");
            return;
        }

        JFileChooser saveChooser = new JFileChooser();
        saveChooser.setDialogTitle("Select output file");
        int result = saveChooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION)
            return;
        outputFile = saveChooser.getSelectedFile();

        String mode = (String) modeCombo.getSelectedItem();
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(backendJarPath.getAbsolutePath());
        cmd.add(mode);
        for (File f : selectedFiles) {
            cmd.add("-i");
            cmd.add(f.getAbsolutePath());
        }
        cmd.add("-o");
        cmd.add(outputFile.getAbsolutePath());

        try {
            System.out.println("Reconstructed command string:");
            System.out.println(String.join(" ", cmd));
            System.out.println("Each argument token:");
            for (int i = 0; i < cmd.size(); i++) {
                System.out.printf("[%d] %s%n", i, cmd.get(i));
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                JOptionPane.showMessageDialog(this, "FastreeR finished successfully!");
            } else {
                JOptionPane.showMessageDialog(this, "FastreeR failed. Exit code: " + exitCode);
            }
        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(FastreeRGUI::new);
    }
}
