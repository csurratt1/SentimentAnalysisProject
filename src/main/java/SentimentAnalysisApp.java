import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Simple desktop GUI for running the sentiment pipeline without terminal commands.
 */
public class SentimentAnalysisApp {

    private static final int MAX_SAFE_BATCH_WORKERS = 2;

    private final Path projectRoot;
    private final Path inputDir;
    private final Path outputDir;

    private final DefaultListModel<Path> inputModel = new DefaultListModel<>();
    private final DefaultListModel<Path> outputModel = new DefaultListModel<>();

    private JFrame appFrame;
    private JList<Path> inputList;
    private JList<Path> outputList;
    private JTextArea logArea;
    private JButton runSingleButton;
    private JButton runBatchButton;
    private JButton selectAllInputsButton;
    private JButton clearSelectionButton;
    private JSpinner batchWorkersSpinner;
    private JLabel selectionSummaryValue;
    private JCheckBox dbPersistCheckBox;
    private JCheckBox previewBeforeCommitCheckBox;
    private JLabel dbStatusValue;
    private JLabel dbHearingValue;
    private JLabel dbRunValue;
    private JLabel dbTurnsValue;
    private JLabel dbScoresValue;
    private JLabel dbReplaceValue;
    private TurnScorer.RunResult lastBatchResult;

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

        inputList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        outputList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        inputList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectionSummary();
            }
        });

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
        runSingleButton = new JButton("Run Selected (Single)");
        runBatchButton = new JButton("Run Selected (Batch)");
        selectAllInputsButton = new JButton("Select All Inputs");
        clearSelectionButton = new JButton("Clear Selection");
        batchWorkersSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 4, 1));
        dbPersistCheckBox = new JCheckBox("Persist To Database", true);
        previewBeforeCommitCheckBox = new JCheckBox("Preview Before Save (Single)", true);

        refreshButton.addActionListener(e -> refreshLists());
        addInputButton.addActionListener(e -> addInputFile());
        removeInputButton.addActionListener(e -> removeSelectedFile(inputList, "input"));
        removeOutputButton.addActionListener(e -> removeSelectedFile(outputList, "output"));
        selectAllInputsButton.addActionListener(e -> selectAllInputs());
        clearSelectionButton.addActionListener(e -> {
            inputList.clearSelection();
            updateSelectionSummary();
        });
        runSingleButton.addActionListener(e -> runSingleAnalysis());
        runBatchButton.addActionListener(e -> runBatchAnalysis());

        panel.add(refreshButton);
        panel.add(Box.createVerticalStrut(8));
        panel.add(addInputButton);
        panel.add(Box.createVerticalStrut(8));
        panel.add(removeInputButton);
        panel.add(Box.createVerticalStrut(8));
        panel.add(removeOutputButton);
        panel.add(Box.createVerticalStrut(12));
        panel.add(selectAllInputsButton);
        panel.add(Box.createVerticalStrut(8));
        panel.add(clearSelectionButton);
        panel.add(Box.createVerticalStrut(14));
        panel.add(new JLabel("Batch Workers (1-4):"));
        panel.add(Box.createVerticalStrut(4));
        panel.add(batchWorkersSpinner);
        panel.add(Box.createVerticalStrut(12));
        panel.add(previewBeforeCommitCheckBox);
        panel.add(Box.createVerticalStrut(8));
        panel.add(dbPersistCheckBox);
        panel.add(Box.createVerticalStrut(8));
        panel.add(runSingleButton);
        panel.add(Box.createVerticalStrut(8));
        panel.add(runBatchButton);
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
        JPanel panel = new JPanel(new GridLayout(3, 6, 8, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Last DB Run Summary"));

        panel.add(new JLabel("Selected Input Files:"));
        panel.add(new JLabel(""));
        panel.add(new JLabel(""));
        panel.add(new JLabel(""));
        panel.add(new JLabel(""));
        panel.add(new JLabel(""));

        selectionSummaryValue = new JLabel("0 selected");
        panel.add(selectionSummaryValue);
        panel.add(new JLabel(""));
        panel.add(new JLabel(""));
        panel.add(new JLabel(""));
        panel.add(new JLabel(""));
        panel.add(new JLabel(""));

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
        updateSelectionSummary();
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
        FileDialog dialog = new FileDialog(appFrame, "Select Input Transcript (.txt or .docx)", FileDialog.LOAD);
        dialog.setDirectory(inputDir.toString());
        dialog.setFile("*.txt;*.docx");
        dialog.setVisible(true);

        if (dialog.getFile() == null) {
            return;
        }

        Path source = Paths.get(dialog.getDirectory(), dialog.getFile());
        Path target = inputDir.resolve(source.getFileName().toString());

        String lower = source.getFileName().toString().toLowerCase();
        if (!(lower.endsWith(".txt") || lower.endsWith(".docx"))) {
            appendLog("Unsupported file type: " + source.getFileName() + " (use .txt or .docx)");
            return;
        }

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

    private void selectAllInputs() {
        if (inputModel.isEmpty()) {
            appendLog("No input files available to select.");
            return;
        }
        inputList.setSelectionInterval(0, inputModel.size() - 1);
        updateSelectionSummary();
    }

    private void updateSelectionSummary() {
        if (selectionSummaryValue == null || inputList == null) return;
        int count = inputList.getSelectedValuesList().size();
        selectionSummaryValue.setText(count + " selected");
    }

    private void setRunControlsEnabled(boolean enabled) {
        runSingleButton.setEnabled(enabled);
        runBatchButton.setEnabled(enabled);
        dbPersistCheckBox.setEnabled(enabled);
        previewBeforeCommitCheckBox.setEnabled(enabled);
        batchWorkersSpinner.setEnabled(enabled);
        addInputFileControlsEnabled(enabled);
    }

    private void addInputFileControlsEnabled(boolean enabled) {
        selectAllInputsButton.setEnabled(enabled);
        clearSelectionButton.setEnabled(enabled);
    }

    private void runSingleAnalysis() {
        List<Path> selected = new ArrayList<>(new LinkedHashSet<>(inputList.getSelectedValuesList()));
        if (selected.size() != 1) {
            appendLog("Single run requires exactly one selected input file.");
            return;
        }

        Path selectedInput = selected.get(0);
        setRunControlsEnabled(false);
        appendLog("Analyzing: " + selectedInput.getFileName());
        boolean persistDb = dbPersistCheckBox.isSelected();
        boolean previewBeforeCommit = previewBeforeCommitCheckBox.isSelected();
        appendLog("DB persistence: " + (persistDb ? "ON" : "OFF"));
        appendLog("Preview before save: " + (previewBeforeCommit ? "ON" : "OFF"));

        SwingWorker<TurnScorer.AnalysisBundle, Void> worker = new SwingWorker<>() {
            @Override
            protected TurnScorer.AnalysisBundle doInBackground() throws Exception {
                return TurnScorer.analyzeOnly(
                    selectedInput.toString(),
                    false,
                    false
                );
            }

            @Override
            protected void done() {
                try {
                    TurnScorer.AnalysisBundle analysis = get();
                    appendLog("Analysis complete. Turns scored: " + analysis.getScored().size());

                    if (previewBeforeCommit) {
                        String textPreview = TurnScorer.buildTextPreview(analysis, false);
                        String jsonPreview = TurnScorer.buildJsonPreview(analysis);
                        boolean confirmed = showPreviewAndConfirm(textPreview, jsonPreview);
                        if (!confirmed) {
                            appendLog("Run discarded after preview. No output files or DB changes were made.");
                            clearDbSummary("Discarded after preview");
                            setRunControlsEnabled(true);
                            return;
                        }
                    }

                    appendLog("Committing output and database changes...");
                    commitAnalysisResult(analysis, persistDb);
                } catch (Exception ex) {
                    appendLog("Run failed: " + ex.getMessage());
                    clearDbSummary("Run failed");
                    setRunControlsEnabled(true);
                }
            }
        };

        worker.execute();
    }

    private void commitAnalysisResult(TurnScorer.AnalysisBundle analysis, boolean persistDb) {
        SwingWorker<TurnScorer.RunResult, Void> commitWorker = new SwingWorker<>() {
            @Override
            protected TurnScorer.RunResult doInBackground() throws Exception {
                return TurnScorer.finalizeRun(
                    analysis,
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
                    appendLog("Commit failed: " + ex.getMessage());
                    clearDbSummary("Commit failed");
                } finally {
                    setRunControlsEnabled(true);
                }
            }
        };

        commitWorker.execute();
    }

    private boolean showPreviewAndConfirm(String textPreview, String jsonPreview) {
        JTabbedPane tabs = new JTabbedPane();

        JTextArea textArea = new JTextArea(textPreview, 28, 100);
        textArea.setEditable(false);
        textArea.setCaretPosition(0);

        JTextArea jsonArea = new JTextArea(jsonPreview, 28, 100);
        jsonArea.setEditable(false);
        jsonArea.setCaretPosition(0);

        tabs.addTab("Text Report Preview", new JScrollPane(textArea));
        tabs.addTab("JSON Preview", new JScrollPane(jsonArea));

        int decision = JOptionPane.showConfirmDialog(
            appFrame,
            tabs,
            "Preview Results - Confirm Save",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        );
        return decision == JOptionPane.OK_OPTION;
    }

    private void runBatchAnalysis() {
        List<Path> selected = new ArrayList<>(new LinkedHashSet<>(inputList.getSelectedValuesList()));
        if (selected.isEmpty()) {
            appendLog("Select one or more input files for batch run.");
            return;
        }

        lastBatchResult = null;

        final int requestedWorkers = Math.min((Integer) batchWorkersSpinner.getValue(), selected.size());
        final int workers = Math.min(requestedWorkers, MAX_SAFE_BATCH_WORKERS);
        final boolean persistDb = dbPersistCheckBox.isSelected();

        if (workers < requestedWorkers) {
            appendLog("Batch workers limited to " + workers
                + " for CoreNLP stability (requested " + requestedWorkers + ").");
        }

        int confirm = JOptionPane.showConfirmDialog(
            appFrame,
            buildBatchConfirmationMessage(selected, requestedWorkers, workers, persistDb),
            "Confirm Batch Analysis",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.INFORMATION_MESSAGE
        );
        if (confirm != JOptionPane.OK_OPTION) {
            appendLog("Batch run cancelled by user.");
            return;
        }

        setRunControlsEnabled(false);
        clearDbSummary("Batch running");
        appendLog("Starting batch run for " + selected.size() + " files with " + workers + " worker(s).");
        appendLog("DB persistence: " + (persistDb ? "ON" : "OFF"));

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                ExecutorService executor = Executors.newFixedThreadPool(workers);
                CompletionService<RunOutcome> completion = new ExecutorCompletionService<>(executor);

                try {
                    for (Path input : selected) {
                        completion.submit(() -> {
                            try {
                                TurnScorer.RunResult result = TurnScorer.runPipeline(
                                    input.toString(),
                                    outputDir.toString(),
                                    false,
                                    persistDb
                                );
                                return RunOutcome.success(input, result);
                            } catch (Exception ex) {
                                return RunOutcome.failure(input, ex);
                            }
                        });
                    }

                    for (int i = 0; i < selected.size(); i++) {
                        Future<RunOutcome> future = completion.take();
                        RunOutcome outcome = future.get();
                        if (outcome.error != null) {
                            publish("Batch failed for " + outcome.input.getFileName() + ": " + outcome.error.getMessage());
                        } else {
                            lastBatchResult = outcome.result;
                            publish("Batch complete for " + outcome.input.getFileName()
                                + " | scored=" + outcome.result.getScoredTurns()
                                + " | DB=" + outcome.result.getDbMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    publish("Batch run interrupted.");
                } catch (ExecutionException e) {
                    publish("Batch run error: " + e.getMessage());
                } finally {
                    executor.shutdownNow();
                }

                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    appendLog(msg);
                }
            }

            @Override
            protected void done() {
                setRunControlsEnabled(true);
                refreshLists();
                if (lastBatchResult != null) {
                    updateDbSummary(lastBatchResult);
                } else {
                    clearDbSummary("Batch finished");
                }
                appendLog("Batch run finished.");
            }
        };

        worker.execute();
    }

    private String buildBatchConfirmationMessage(List<Path> selected,
                                                 int requestedWorkers,
                                                 int effectiveWorkers,
                                                 boolean persistDb) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are about to run batch analysis.\n\n");
        sb.append("Files selected: ").append(selected.size()).append("\n");
        sb.append("Workers requested: ").append(requestedWorkers).append("\n");
        sb.append("Workers used: ").append(effectiveWorkers).append("\n");
        sb.append("DB persistence: ").append(persistDb ? "ON" : "OFF").append("\n\n");
        sb.append("First files:\n");

        int previewCount = Math.min(5, selected.size());
        for (int i = 0; i < previewCount; i++) {
            sb.append(" - ").append(selected.get(i).getFileName()).append("\n");
        }
        if (selected.size() > previewCount) {
            sb.append(" - ... and ").append(selected.size() - previewCount).append(" more\n");
        }

        sb.append("\nProceed?");
        return sb.toString();
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

    private static class RunOutcome {
        private final Path input;
        private final TurnScorer.RunResult result;
        private final Exception error;

        private RunOutcome(Path input, TurnScorer.RunResult result, Exception error) {
            this.input = input;
            this.result = result;
            this.error = error;
        }

        static RunOutcome success(Path input, TurnScorer.RunResult result) {
            return new RunOutcome(input, result, null);
        }

        static RunOutcome failure(Path input, Exception error) {
            return new RunOutcome(input, null, error);
        }
    }
}
