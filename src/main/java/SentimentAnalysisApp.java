import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Simple desktop GUI for running the sentiment pipeline without terminal commands.
 */
public class SentimentAnalysisApp {

    private final Path projectRoot;
    private final Path inputDir;
    private final Path outputDir;

    private final DefaultListModel<Path> inputModel = new DefaultListModel<>();
    private final DefaultListModel<Path> outputModel = new DefaultListModel<>();

    private JFrame appFrame;
    private JList<Path> inputList;
    private JList<Path> outputList;
    private JTextArea logArea;
    private JButton runButton;
    private JCheckBox dbPersistCheckBox;
    private JLabel dbStatusValue;
    private JLabel dbHearingValue;
    private JLabel dbRunValue;
    private JLabel dbTurnsValue;
    private JLabel dbScoresValue;
    private JLabel dbReplaceValue;

    public SentimentAnalysisApp() {
        this.projectRoot = resolveProjectRoot();
        this.inputDir = projectRoot.resolve("input");
        this.outputDir = projectRoot.resolve("output");
    }

    public static void launch() {
        SwingUtilities.invokeLater(() -> new SentimentAnalysisApp().showUI());
    }

    private void showUI() {
        appFrame = new JFrame("Senate Hearing Sentiment Analysis");
        appFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        appFrame.setLayout(new BorderLayout(10, 10));

        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.add(buildFilePanel(), BorderLayout.CENTER);
        topPanel.add(buildActionPanel(), BorderLayout.EAST);

        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        bottomPanel.add(buildDbSummaryPanel(), BorderLayout.NORTH);

        logArea = new JTextArea(12, 80);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        bottomPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        appFrame.add(topPanel, BorderLayout.CENTER);
        appFrame.add(bottomPanel, BorderLayout.SOUTH);

        refreshLists();
        appendLog("App ready.");
        appendLog("Project root: " + projectRoot);
        appendLog("Input dir: " + inputDir);
        appendLog("Output dir: " + outputDir);

        appFrame.pack();
        appFrame.setLocationRelativeTo(null);
        appFrame.setVisible(true);
    }

    private Path resolveProjectRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Set<Path> candidates = new LinkedHashSet<>();

        Path cursor = cwd;
        for (int i = 0; i < 6 && cursor != null; i++) {
            candidates.add(cursor);
            candidates.add(cursor.resolve("SentimentAnalysisProject"));
            cursor = cursor.getParent();
        }

        for (Path candidate : candidates) {
            if (isProjectRoot(candidate)) {
                return candidate;
            }
        }

        return cwd;
    }

    private boolean isProjectRoot(Path candidate) {
        return Files.exists(candidate.resolve("pom.xml"))
            && Files.isDirectory(candidate.resolve("src"))
            && Files.isDirectory(candidate.resolve("input"))
            && Files.isDirectory(candidate.resolve("output"));
    }

    private JPanel buildFilePanel() {
        inputList = new JList<>(inputModel);
        outputList = new JList<>(outputModel);

        inputList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        outputList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        inputList.setCellRenderer(new FileNameRenderer());
        outputList.setCellRenderer(new FileNameRenderer());

        JPanel panel = new JPanel(new GridLayout(1, 2, 10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        panel.add(wrapWithLabel("Input Files", new JScrollPane(inputList)));
        panel.add(wrapWithLabel("Output Files", new JScrollPane(outputList)));

        return panel;
    }

    private JPanel buildActionPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 10));

        JButton refreshButton = new JButton("Refresh");
        JButton addInputButton = new JButton("Add Input File");
        JButton removeInputButton = new JButton("Remove Input File");
        JButton removeOutputButton = new JButton("Remove Output File");
        runButton = new JButton("Run Analysis");
        dbPersistCheckBox = new JCheckBox("Persist To Database", true);

        refreshButton.addActionListener(e -> refreshLists());
        addInputButton.addActionListener(e -> addInputFile());
        removeInputButton.addActionListener(e -> removeSelectedFile(inputList, "input"));
        removeOutputButton.addActionListener(e -> removeSelectedFile(outputList, "output"));
        runButton.addActionListener(e -> runSelectedAnalysis());

        panel.add(refreshButton);
        panel.add(Box.createVerticalStrut(8));
        panel.add(addInputButton);
        panel.add(Box.createVerticalStrut(8));
        panel.add(removeInputButton);
        panel.add(Box.createVerticalStrut(8));
        panel.add(removeOutputButton);
        panel.add(Box.createVerticalStrut(20));
        panel.add(dbPersistCheckBox);
        panel.add(Box.createVerticalStrut(8));
        panel.add(runButton);
        panel.add(Box.createVerticalGlue());

        return panel;
    }

    private JPanel wrapWithLabel(String label, JComponent child) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.add(new JLabel(label), BorderLayout.NORTH);
        panel.add(child, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildDbSummaryPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 6, 8, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Last DB Run Summary"));

        panel.add(new JLabel("Status:"));
        panel.add(new JLabel("Hearing ID:"));
        panel.add(new JLabel("Run ID:"));
        panel.add(new JLabel("Turns Inserted:"));
        panel.add(new JLabel("Scores Inserted:"));
        panel.add(new JLabel("Replaced Prior Run:"));

        dbStatusValue = new JLabel("N/A");
        dbHearingValue = new JLabel("-");
        dbRunValue = new JLabel("-");
        dbTurnsValue = new JLabel("-");
        dbScoresValue = new JLabel("-");
        dbReplaceValue = new JLabel("-");

        panel.add(dbStatusValue);
        panel.add(dbHearingValue);
        panel.add(dbRunValue);
        panel.add(dbTurnsValue);
        panel.add(dbScoresValue);
        panel.add(dbReplaceValue);

        return panel;
    }

    private void refreshLists() {
        ensureDirectories();
        loadFiles(inputDir, inputModel);
        loadFiles(outputDir, outputModel);
        appendLog("Refreshed file lists.");
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(inputDir);
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            appendLog("Failed to ensure input/output directories: " + e.getMessage());
        }
    }

    private void loadFiles(Path dir, DefaultListModel<Path> model) {
        model.clear();
        try {
            List<Path> files = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                      .sorted(Comparator.comparing(Path::getFileName))
                      .forEach(files::add);
            }
            for (Path path : files) {
                model.addElement(path);
            }
        } catch (IOException e) {
            appendLog("Failed to list files in " + dir + ": " + e.getMessage());
        }
    }

    private void addInputFile() {
        FileDialog dialog = new FileDialog(appFrame, "Select Input Text File", FileDialog.LOAD);
        dialog.setDirectory(inputDir.toString());
        dialog.setFile("*.txt");
        dialog.setVisible(true);

        if (dialog.getFile() == null) {
            return;
        }

        Path source = Paths.get(dialog.getDirectory(), dialog.getFile());
        Path target = inputDir.resolve(source.getFileName().toString());

        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            appendLog("Added input file: " + target.getFileName());
            refreshLists();
        } catch (IOException e) {
            appendLog("Failed to copy file: " + e.getMessage());
        }
    }

    private void removeSelectedFile(JList<Path> list, String folderLabel) {
        Path selected = list.getSelectedValue();
        if (selected == null) {
            appendLog("No file selected in " + folderLabel + " list.");
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
            null,
            "Delete " + selected.getFileName() + "?",
            "Confirm Delete",
            JOptionPane.YES_NO_OPTION
        );
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            Files.deleteIfExists(selected);
            appendLog("Deleted file: " + selected.getFileName());
            refreshLists();
        } catch (IOException e) {
            appendLog("Failed to delete file: " + e.getMessage());
        }
    }

    private void runSelectedAnalysis() {
        Path selectedInput = inputList.getSelectedValue();
        if (selectedInput == null) {
            appendLog("Select an input file before running analysis.");
            return;
        }

        runButton.setEnabled(false);
        appendLog("Running analysis for: " + selectedInput.getFileName());
        boolean persistDb = dbPersistCheckBox.isSelected();
        appendLog("DB persistence: " + (persistDb ? "ON" : "OFF"));

        SwingWorker<TurnScorer.RunResult, Void> worker = new SwingWorker<>() {
            @Override
            protected TurnScorer.RunResult doInBackground() throws Exception {
                return TurnScorer.runPipeline(
                    selectedInput.toString(),
                    outputDir.toString(),
                    false,
                    persistDb
                );
            }

            @Override
            protected void done() {
                try {
                    TurnScorer.RunResult result = get();
                    appendLog("Run complete.");
                    appendLog("Total turns: " + result.getTotalTurns()
                        + " | Scored: " + result.getScoredTurns()
                        + " | Pairs: " + result.getInteractionPairs());
                    if (result.getTextFile() != null) {
                        appendLog("Text report: " + result.getTextFile().getFileName());
                    }
                    if (result.getJsonFile() != null) {
                        appendLog("JSON report: " + result.getJsonFile().getFileName());
                    }
                    appendLog("DB: " + result.getDbMessage());
                    updateDbSummary(result);
                    refreshLists();
                } catch (Exception ex) {
                    appendLog("Run failed: " + ex.getMessage());
                    clearDbSummary("Run failed");
                } finally {
                    runButton.setEnabled(true);
                }
            }
        };

        worker.execute();
    }

    private void appendLog(String message) {
        if (logArea == null) return;
        logArea.append(message + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void updateDbSummary(TurnScorer.RunResult result) {
        if (!result.isDbPersisted() || result.getDbSummary() == null) {
            clearDbSummary(result.getDbMessage());
            return;
        }

        ScoringPersistence.PersistenceResult summary = result.getDbSummary();
        dbStatusValue.setText("Persisted");
        dbHearingValue.setText(String.valueOf(summary.getHearingId()));
        dbRunValue.setText(String.valueOf(summary.getScoringRunId()));
        dbTurnsValue.setText(String.valueOf(summary.getTurnsInserted()));
        dbScoresValue.setText(String.valueOf(summary.getTurnScoresInserted()));
        dbReplaceValue.setText(summary.isReplacedExistingRun() ? "Yes" : "No");
    }

    private void clearDbSummary(String status) {
        dbStatusValue.setText(status != null ? status : "N/A");
        dbHearingValue.setText("-");
        dbRunValue.setText("-");
        dbTurnsValue.setText("-");
        dbScoresValue.setText("-");
        dbReplaceValue.setText("-");
    }

    private static class FileNameRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list,
                                                      Object value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                list, value, index, isSelected, cellHasFocus
            );
            if (value instanceof Path) {
                label.setText(((Path) value).getFileName().toString());
            }
            return label;
        }
    }
}
