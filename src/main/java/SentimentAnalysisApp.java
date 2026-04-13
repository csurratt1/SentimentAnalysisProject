import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
    private static final Color COLOR_BG = new Color(0x0A0A0A);
    private static final Color COLOR_SURFACE = new Color(0x141414);
    private static final Color COLOR_SURFACE_ALT = new Color(0x1A1A1A);
    private static final Color COLOR_BORDER = new Color(0x2A2A2A);
    private static final Color COLOR_TEXT = new Color(0xE0E0E0);
    private static final Color COLOR_TEXT_MUTED = new Color(0xA0A0A0);
    private static final Color COLOR_TEXT_SUBTLE = new Color(0x808080);
    private static final Color COLOR_ACCENT = new Color(0x2563EB);
    private static final Color COLOR_ACCENT_HOVER = new Color(0x3B82F6);
    private static final Font FONT_BODY = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font FONT_HEADING = new Font("Segoe UI", Font.BOLD, 28);
    private static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 14);

    private final Path projectRoot;
    private final Path inputDir;
    private final Path outputDir;

    private final DefaultListModel<Path> inputModel = new DefaultListModel<>();
    private final DefaultListModel<Path> outputModel = new DefaultListModel<>();

    private JFrame appFrame;
    private JList<Path> inputList;
    private JList<Path> outputList;
    private JTextArea logArea;
    private JButton refreshButton;
    private JButton addInputButton;
    private JButton removeInputButton;
    private JButton removeOutputButton;
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
        appFrame.setLayout(new BorderLayout());
        appFrame.getContentPane().setBackground(COLOR_BG);

        appFrame.add(buildTaskBarPanel(), BorderLayout.WEST);
        appFrame.add(buildMainContentPanel(), BorderLayout.CENTER);

        refreshLists();
        appendLog("App ready.");
        appendLog("Project root: " + projectRoot);
        appendLog("Input dir: " + inputDir);
        appendLog("Output dir: " + outputDir);

        appFrame.setMinimumSize(new Dimension(1100, 720));
        appFrame.setSize(1360, 840);
        appFrame.setLocationRelativeTo(null);
        appFrame.setVisible(true);
    }

    private JPanel buildMainContentPanel() {
        JPanel panel = new JPanel(new BorderLayout(14, 14));
        panel.setBackground(COLOR_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        panel.add(buildHeaderPanel(), BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            buildFilePanel(),
            buildStatusPanel()
        );
        splitPane.setBorder(null);
        splitPane.setResizeWeight(0.54);
        splitPane.setBackground(COLOR_BG);
        splitPane.setContinuousLayout(true);
        panel.add(splitPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildHeaderPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(COLOR_BG);

        JLabel title = new JLabel("Sentiment Analysis Workspace");
        title.setFont(FONT_HEADING);
        title.setForeground(Color.WHITE);

        JLabel subtitle = new JLabel("Run transcript scoring, review outputs, and track persistence activity.");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        subtitle.setForeground(COLOR_TEXT_SUBTLE);

        panel.add(title);
        panel.add(Box.createVerticalStrut(4));
        panel.add(subtitle);

        return panel;
    }

    private JPanel buildStatusPanel() {
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBackground(COLOR_BG);
        panel.add(buildDbSummaryPanel(), BorderLayout.NORTH);

        logArea = new JTextArea(12, 80);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(FONT_BODY);
        logArea.setBackground(COLOR_SURFACE_ALT);
        logArea.setForeground(COLOR_TEXT);
        logArea.setCaretColor(COLOR_TEXT);
        logArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        panel.add(wrapWithLabel("Activity Log", createStyledScrollPane(logArea)), BorderLayout.CENTER);
        return panel;
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

        styleList(inputList, ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        styleList(outputList, ListSelectionModel.SINGLE_SELECTION);
        inputList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectionSummary();
            }
        });

        inputList.setCellRenderer(new FileNameRenderer());
        outputList.setCellRenderer(new FileNameRenderer());

        JPanel panel = new JPanel(new GridLayout(1, 2, 12, 12));
        panel.setBackground(COLOR_BG);

        panel.add(wrapWithLabel("Input Files", createStyledScrollPane(inputList)));
        panel.add(wrapWithLabel("Output Files", createStyledScrollPane(outputList)));

        return panel;
    }

    private JPanel buildTaskBarPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(COLOR_SURFACE);
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, COLOR_BORDER));
        panel.setPreferredSize(new Dimension(300, 0));

        JLabel logo = new JLabel("Senate Hearing Analyzer");
        logo.setForeground(Color.WHITE);
        logo.setFont(new Font("Segoe UI", Font.BOLD, 18));
        logo.setBorder(BorderFactory.createEmptyBorder(24, 20, 20, 20));
        logo.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(logo);

        JPanel fileTaskGroup = createTaskGroupPanel();
        fileTaskGroup.add(createTaskGroupLabel("File Tasks"));
        fileTaskGroup.add(Box.createVerticalStrut(10));

        refreshButton = createTaskButton("Refresh Lists", false, this::refreshLists);
        addInputButton = createTaskButton("Add Input File", false, this::addInputFile);
        removeInputButton = createTaskButton("Remove Input File", false,
            () -> removeSelectedFile(inputList, "input"));
        removeOutputButton = createTaskButton("Remove Output File", false,
            () -> removeSelectedFile(outputList, "output"));
        selectAllInputsButton = createTaskButton("Select All Inputs", false, this::selectAllInputs);
        clearSelectionButton = createTaskButton("Clear Selection", false, () -> {
            inputList.clearSelection();
            updateSelectionSummary();
        });

        fileTaskGroup.add(refreshButton);
        fileTaskGroup.add(Box.createVerticalStrut(8));
        fileTaskGroup.add(addInputButton);
        fileTaskGroup.add(Box.createVerticalStrut(8));
        fileTaskGroup.add(removeInputButton);
        fileTaskGroup.add(Box.createVerticalStrut(8));
        fileTaskGroup.add(removeOutputButton);
        fileTaskGroup.add(Box.createVerticalStrut(8));
        fileTaskGroup.add(selectAllInputsButton);
        fileTaskGroup.add(Box.createVerticalStrut(8));
        fileTaskGroup.add(clearSelectionButton);

        JPanel runOptionsGroup = createTaskGroupPanel();
        runOptionsGroup.add(createTaskGroupLabel("Run Options"));
        runOptionsGroup.add(Box.createVerticalStrut(10));

        batchWorkersSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 4, 1));
        styleSpinner(batchWorkersSpinner);

        JLabel workersLabel = new JLabel("Batch Workers (1-4)");
        workersLabel.setForeground(COLOR_TEXT_MUTED);
        workersLabel.setFont(FONT_BODY);
        workersLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        previewBeforeCommitCheckBox = createTaskCheckBox("Preview Before Save (Single)", true);
        dbPersistCheckBox = createTaskCheckBox("Persist To Database", true);

        runOptionsGroup.add(workersLabel);
        runOptionsGroup.add(Box.createVerticalStrut(4));
        runOptionsGroup.add(batchWorkersSpinner);
        runOptionsGroup.add(Box.createVerticalStrut(10));
        runOptionsGroup.add(previewBeforeCommitCheckBox);
        runOptionsGroup.add(Box.createVerticalStrut(6));
        runOptionsGroup.add(dbPersistCheckBox);

        JPanel runTaskGroup = createTaskGroupPanel();
        runTaskGroup.add(createTaskGroupLabel("Analysis Tasks"));
        runTaskGroup.add(Box.createVerticalStrut(10));

        runSingleButton = createTaskButton("Run Selected (Single)", true, this::runSingleAnalysis);
        runBatchButton = createTaskButton("Run Selected (Batch)", true, this::runBatchAnalysis);

        runTaskGroup.add(runSingleButton);
        runTaskGroup.add(Box.createVerticalStrut(8));
        runTaskGroup.add(runBatchButton);

        panel.add(fileTaskGroup);
        panel.add(Box.createVerticalStrut(10));
        panel.add(runOptionsGroup);
        panel.add(Box.createVerticalGlue());
        panel.add(runTaskGroup);
        panel.add(Box.createVerticalStrut(20));

        return panel;
    }

    private JPanel createTaskGroupPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
        return panel;
    }

    private JLabel createTaskGroupLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(COLOR_TEXT_MUTED);
        label.setFont(new Font("Segoe UI", Font.BOLD, 12));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JButton createTaskButton(String text, boolean accent, Runnable action) {
        JButton button = new JButton(text);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(FONT_BODY);

        Color baseColor = accent ? COLOR_ACCENT : COLOR_SURFACE_ALT;
        Color hoverColor = accent ? COLOR_ACCENT_HOVER : new Color(0x1F1F1F);
        button.setBackground(baseColor);
        button.setForeground(Color.WHITE);
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(accent ? COLOR_ACCENT : COLOR_BORDER),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));
        button.addActionListener(e -> action.run());
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(hoverColor);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(baseColor);
                }
            }
        });

        return button;
    }

    private JCheckBox createTaskCheckBox(String text, boolean selected) {
        JCheckBox checkBox = new JCheckBox(text, selected);
        checkBox.setOpaque(false);
        checkBox.setForeground(COLOR_TEXT_MUTED);
        checkBox.setFont(FONT_BODY);
        checkBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        return checkBox;
    }

    private void styleSpinner(JSpinner spinner) {
        spinner.setAlignmentX(Component.LEFT_ALIGNMENT);
        spinner.setMaximumSize(new Dimension(100, 32));
        spinner.setFont(FONT_BODY);
        spinner.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));

        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            JTextField field = ((JSpinner.DefaultEditor) editor).getTextField();
            field.setBackground(COLOR_SURFACE_ALT);
            field.setForeground(COLOR_TEXT);
            field.setCaretColor(COLOR_TEXT);
            field.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        }
    }

    private void styleList(JList<Path> list, int selectionMode) {
        list.setSelectionMode(selectionMode);
        list.setBackground(COLOR_SURFACE_ALT);
        list.setForeground(COLOR_TEXT);
        list.setSelectionBackground(COLOR_ACCENT);
        list.setSelectionForeground(Color.WHITE);
        list.setFont(FONT_BODY);
        list.setFixedCellHeight(28);
        list.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    }

    private JScrollPane createStyledScrollPane(JComponent child) {
        JScrollPane scrollPane = new JScrollPane(child);
        scrollPane.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        scrollPane.getViewport().setBackground(COLOR_SURFACE_ALT);
        scrollPane.setBackground(COLOR_SURFACE_ALT);
        return scrollPane;
    }

    private JPanel wrapWithLabel(String label, JComponent child) {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(COLOR_SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(COLOR_BORDER),
            BorderFactory.createEmptyBorder(12, 12, 12, 12)
        ));

        JLabel titleLabel = new JLabel(label);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(FONT_TITLE);

        panel.add(titleLabel, BorderLayout.NORTH);
        panel.add(child, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildDbSummaryPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(COLOR_BG);

        JLabel heading = new JLabel("Last DB Run Summary");
        heading.setForeground(Color.WHITE);
        heading.setFont(FONT_TITLE);
        panel.add(heading, BorderLayout.NORTH);

        JPanel statsGrid = new JPanel(new GridLayout(1, 7, 8, 8));
        statsGrid.setBackground(COLOR_BG);

        selectionSummaryValue = createSummaryValueLabel("0 selected");
        dbStatusValue = createSummaryValueLabel("N/A");
        dbHearingValue = createSummaryValueLabel("-");
        dbRunValue = createSummaryValueLabel("-");
        dbTurnsValue = createSummaryValueLabel("-");
        dbScoresValue = createSummaryValueLabel("-");
        dbReplaceValue = createSummaryValueLabel("-");

        statsGrid.add(createSummaryCard("Selected Inputs", selectionSummaryValue));
        statsGrid.add(createSummaryCard("Status", dbStatusValue));
        statsGrid.add(createSummaryCard("Hearing ID", dbHearingValue));
        statsGrid.add(createSummaryCard("Run ID", dbRunValue));
        statsGrid.add(createSummaryCard("Turns Inserted", dbTurnsValue));
        statsGrid.add(createSummaryCard("Scores Inserted", dbScoresValue));
        statsGrid.add(createSummaryCard("Replaced Prior Run", dbReplaceValue));

        panel.add(statsGrid, BorderLayout.CENTER);
        return panel;
    }

    private JLabel createSummaryValueLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(new Font("Segoe UI", Font.BOLD, 16));
        return label;
    }

    private JPanel createSummaryCard(String title, JLabel valueLabel) {
        JPanel card = new JPanel(new BorderLayout(2, 6));
        card.setBackground(COLOR_SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(COLOR_BORDER),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(COLOR_TEXT_MUTED);
        titleLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));

        card.add(titleLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
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
        refreshButton.setEnabled(enabled);
        addInputButton.setEnabled(enabled);
        removeInputButton.setEnabled(enabled);
        removeOutputButton.setEnabled(enabled);
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
            label.setFont(FONT_BODY);
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            if (isSelected) {
                label.setBackground(COLOR_ACCENT);
                label.setForeground(Color.WHITE);
            } else {
                label.setBackground(COLOR_SURFACE_ALT);
                label.setForeground(COLOR_TEXT);
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
