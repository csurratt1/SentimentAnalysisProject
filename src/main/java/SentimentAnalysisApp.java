import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * SentimentAnalysisApp — desktop GUI for the Senate Hearing Sentiment Analysis pipeline.
 *
 * Layout: navigation sidebar (left) + CardLayout content area (right).
 * Pages: Dashboard, Run Analysis, Results.
 */
public class SentimentAnalysisApp {

    // ── Worker cap ───────────────────────────────────────────────────────
    private static final int MAX_SAFE_BATCH_WORKERS = 2;

    // ── Color palette ────────────────────────────────────────────────────
    private static final Color COLOR_BG           = new Color(0x0A0A0A);
    private static final Color COLOR_SURFACE      = new Color(0x141414);
    private static final Color COLOR_SURFACE_ALT  = new Color(0x1A1A1A);
    private static final Color COLOR_BORDER       = new Color(0x2A2A2A);
    private static final Color COLOR_TEXT         = new Color(0xE0E0E0);
    private static final Color COLOR_TEXT_MUTED   = new Color(0xA0A0A0);
    private static final Color COLOR_TEXT_SUBTLE  = new Color(0x606060);
    private static final Color COLOR_ACCENT       = new Color(0x2563EB);
    private static final Color COLOR_ACCENT_HOVER = new Color(0x3B82F6);
    private static final Color COLOR_SUCCESS      = new Color(0x22C55E);

    // ── Fonts ────────────────────────────────────────────────────────────
    private static final Font FONT_BODY    = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font FONT_HEADING = new Font("Segoe UI", Font.BOLD, 26);
    private static final Font FONT_TITLE   = new Font("Segoe UI", Font.BOLD, 14);
    private static final Font FONT_SMALL   = new Font("Segoe UI", Font.PLAIN, 11);

    // ── Page names ───────────────────────────────────────────────────────
    private static final String PAGE_HOME    = "Dashboard";
    private static final String PAGE_RUN     = "Run Analysis";
    private static final String PAGE_RESULTS = "Results";

    // ── Project paths ────────────────────────────────────────────────────
    private final Path projectRoot;
    private final Path inputDir;
    private final Path outputDir;

    // ── File models ──────────────────────────────────────────────────────
    private final DefaultListModel<Path> inputModel  = new DefaultListModel<>();
    private final DefaultListModel<Path> outputModel = new DefaultListModel<>();

    // ── Core UI references ───────────────────────────────────────────────
    private JFrame  appFrame;
    private JPanel  contentCards;
    private String  activeNav = PAGE_HOME;
    private final Map<String, JButton> navButtons = new LinkedHashMap<>();

    // ── Run Analysis page controls ────────────────────────────────────────
    private JList<Path>  inputList;
    private JTextArea    logArea;
    private JSpinner     batchWorkersSpinner;
    private JCheckBox    dbPersistCheckBox;
    private JCheckBox    previewBeforeCommitCheckBox;
    private JButton      runSingleButton;
    private JButton      runBatchButton;
    private JButton      addInputButton;
    private JButton      removeInputButton;
    private JButton      selectAllInputsButton;
    private JButton      clearSelectionButton;
    private JLabel       selectionCountLabel;

    // ── Results page controls ─────────────────────────────────────────────
    private JList<Path>  outputList;
    private JButton      removeOutputButton;
    private JTextArea    resultViewerArea;
    private JLabel       resultViewerTitle;

    // ── Dashboard DB summary labels ───────────────────────────────────────
    private JLabel dbStatusValue;
    private JLabel dbHearingValue;
    private JLabel dbRunValue;
    private JLabel dbTurnsValue;
    private JLabel dbScoresValue;
    private JLabel dbReplaceValue;

    // ── Batch state ───────────────────────────────────────────────────────
    private TurnScorer.RunResult lastBatchResult;

    // ── Constructor ───────────────────────────────────────────────────────

    public SentimentAnalysisApp() {
        this.projectRoot = resolveProjectRoot();
        this.inputDir    = projectRoot.resolve("input");
        this.outputDir   = projectRoot.resolve("output");
    }

    public static void launch() {
        SwingUtilities.invokeLater(() -> new SentimentAnalysisApp().showUI());
    }

    // ── Entry point ───────────────────────────────────────────────────────

    private void showUI() {
        applyDarkLafOverrides();

        appFrame = new JFrame("Senate Hearing Sentiment Analysis");
        appFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        appFrame.setLayout(new BorderLayout());
        appFrame.getContentPane().setBackground(COLOR_BG);

        appFrame.add(buildNavSidebar(), BorderLayout.WEST);
        appFrame.add(buildContentArea(), BorderLayout.CENTER);

        refreshLists();
        appendLog("Ready. Project root: " + projectRoot);
        showNavPage(PAGE_HOME);

        appFrame.setMinimumSize(new Dimension(1100, 720));
        appFrame.setSize(1360, 840);
        appFrame.setLocationRelativeTo(null);
        appFrame.setVisible(true);
    }

    private void applyDarkLafOverrides() {
        UIManager.put("OptionPane.background",         COLOR_SURFACE);
        UIManager.put("OptionPane.messageForeground",  COLOR_TEXT);
        UIManager.put("Panel.background",              COLOR_SURFACE);
        UIManager.put("Button.focus",                  new Color(0, 0, 0, 0));
        UIManager.put("ScrollPane.border",             BorderFactory.createEmptyBorder());
        UIManager.put("TabbedPane.background",         COLOR_SURFACE);
        UIManager.put("TabbedPane.foreground",         COLOR_TEXT);
    }

    // ── Navigation sidebar ────────────────────────────────────────────────

    private JPanel buildNavSidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(COLOR_SURFACE);
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, COLOR_BORDER));
        sidebar.setPreferredSize(new Dimension(220, 0));

        // Logo
        JLabel logo = new JLabel("Senate Hearing");
        logo.setForeground(Color.WHITE);
        logo.setFont(new Font("Segoe UI", Font.BOLD, 16));
        logo.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel logoSub = new JLabel("Sentiment Analyzer");
        logoSub.setForeground(COLOR_TEXT_MUTED);
        logoSub.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        logoSub.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel logoPanel = new JPanel();
        logoPanel.setLayout(new BoxLayout(logoPanel, BoxLayout.Y_AXIS));
        logoPanel.setOpaque(false);
        logoPanel.setBorder(BorderFactory.createEmptyBorder(24, 20, 20, 20));
        logoPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        logoPanel.add(logo);
        logoPanel.add(Box.createVerticalStrut(2));
        logoPanel.add(logoSub);

        sidebar.add(logoPanel);
        sidebar.add(buildSidebarDivider());
        sidebar.add(Box.createVerticalStrut(10));

        // Nav items
        for (String page : new String[]{PAGE_HOME, PAGE_RUN, PAGE_RESULTS}) {
            JButton btn = createNavButton(page);
            navButtons.put(page, btn);
            sidebar.add(btn);
            sidebar.add(Box.createVerticalStrut(2));
        }

        sidebar.add(Box.createVerticalGlue());
        sidebar.add(buildSidebarDivider());

        // Version footer
        JLabel version = new JLabel("CoreNLP 4.5.10 · Java 17");
        version.setForeground(COLOR_TEXT_SUBTLE);
        version.setFont(FONT_SMALL);
        version.setBorder(BorderFactory.createEmptyBorder(10, 20, 16, 20));
        version.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(version);

        return sidebar;
    }

    private JPanel buildSidebarDivider() {
        JPanel d = new JPanel();
        d.setBackground(COLOR_BORDER);
        d.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        d.setAlignmentX(Component.LEFT_ALIGNMENT);
        return d;
    }

    private JButton createNavButton(String page) {
        JButton btn = new JButton(page);
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        btn.setHorizontalAlignment(SwingConstants.LEFT);
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setFont(FONT_BODY);
        btn.setBackground(COLOR_SURFACE);
        btn.setForeground(COLOR_TEXT_MUTED);
        btn.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        btn.addActionListener(e -> showNavPage(page));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                if (!page.equals(activeNav)) btn.setBackground(new Color(0x1F1F1F));
            }
            @Override public void mouseExited(MouseEvent e) {
                if (!page.equals(activeNav)) btn.setBackground(COLOR_SURFACE);
            }
        });
        return btn;
    }

    private void showNavPage(String name) {
        activeNav = name;
        ((CardLayout) contentCards.getLayout()).show(contentCards, name);
        navButtons.forEach((n, btn) -> {
            boolean active = n.equals(name);
            btn.setBackground(active ? COLOR_ACCENT : COLOR_SURFACE);
            btn.setForeground(active ? Color.WHITE : COLOR_TEXT_MUTED);
        });
    }

    // ── Content area (CardLayout) ─────────────────────────────────────────

    private JPanel buildContentArea() {
        contentCards = new JPanel(new CardLayout());
        contentCards.setBackground(COLOR_BG);
        contentCards.add(buildHomePage(),        PAGE_HOME);
        contentCards.add(buildRunAnalysisPage(), PAGE_RUN);
        contentCards.add(buildResultsPage(),     PAGE_RESULTS);
        return contentCards;
    }

    // ── Dashboard page ────────────────────────────────────────────────────

    private JPanel buildHomePage() {
        JPanel page = new JPanel(new BorderLayout(0, 20));
        page.setBackground(COLOR_BG);
        page.setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28));

        page.add(buildPageHeader("Dashboard",
            "Last run summary and quick access to analysis."), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, 16));
        center.setBackground(COLOR_BG);
        center.add(buildDbSummarySection(), BorderLayout.NORTH);
        center.add(buildRecentOutputsSection(), BorderLayout.CENTER);
        page.add(center, BorderLayout.CENTER);

        // Bottom quick-action
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bottom.setBackground(COLOR_BG);
        JButton goRun = createAccentButton("Go to Run Analysis →", () -> showNavPage(PAGE_RUN));
        bottom.add(goRun);
        page.add(bottom, BorderLayout.SOUTH);

        return page;
    }

    private JPanel buildDbSummarySection() {
        JPanel section = new JPanel(new BorderLayout(0, 10));
        section.setBackground(COLOR_BG);

        JLabel heading = new JLabel("Last Run Summary");
        heading.setForeground(COLOR_TEXT_MUTED);
        heading.setFont(new Font("Segoe UI", Font.BOLD, 12));
        section.add(heading, BorderLayout.NORTH);

        JPanel cards = new JPanel(new GridLayout(1, 6, 10, 0));
        cards.setBackground(COLOR_BG);

        dbStatusValue  = createSummaryValueLabel("—");
        dbHearingValue = createSummaryValueLabel("—");
        dbRunValue     = createSummaryValueLabel("—");
        dbTurnsValue   = createSummaryValueLabel("—");
        dbScoresValue  = createSummaryValueLabel("—");
        dbReplaceValue = createSummaryValueLabel("—");

        cards.add(createSummaryCard("Status",          dbStatusValue));
        cards.add(createSummaryCard("Hearing ID",      dbHearingValue));
        cards.add(createSummaryCard("Run ID",          dbRunValue));
        cards.add(createSummaryCard("Turns Inserted",  dbTurnsValue));
        cards.add(createSummaryCard("Scores Inserted", dbScoresValue));
        cards.add(createSummaryCard("Replaced Prior",  dbReplaceValue));

        section.add(cards, BorderLayout.CENTER);
        return section;
    }

    private JPanel buildRecentOutputsSection() {
        JPanel section = new JPanel(new BorderLayout(0, 10));
        section.setBackground(COLOR_BG);

        JLabel heading = new JLabel("Recent Output Files");
        heading.setForeground(COLOR_TEXT_MUTED);
        heading.setFont(new Font("Segoe UI", Font.BOLD, 12));
        section.add(heading, BorderLayout.NORTH);

        JPanel listContainer = new JPanel();
        listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
        listContainer.setBackground(COLOR_SURFACE);
        listContainer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(COLOR_BORDER),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));

        // Populated lazily in refreshRecentOutputs()
        listContainer.setName("recentOutputsContainer");
        section.add(listContainer, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        footer.setBackground(COLOR_BG);
        JButton viewAll = createGhostButton("View all in Results →", () -> showNavPage(PAGE_RESULTS));
        footer.add(viewAll);
        section.add(footer, BorderLayout.SOUTH);

        return section;
    }

    // ── Run Analysis page ─────────────────────────────────────────────────

    private JPanel buildRunAnalysisPage() {
        JPanel page = new JPanel(new BorderLayout(0, 16));
        page.setBackground(COLOR_BG);
        page.setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28));

        page.add(buildPageHeader("Run Analysis",
            "Select transcript files and run the sentiment pipeline."), BorderLayout.NORTH);

        // Top row: file list (left) + options (right)
        JPanel topRow = new JPanel(new GridLayout(1, 2, 16, 0));
        topRow.setBackground(COLOR_BG);
        topRow.setPreferredSize(new Dimension(0, 310));
        topRow.add(buildInputFilesPanel());
        topRow.add(buildRunOptionsPanel());
        page.add(topRow, BorderLayout.CENTER);

        // Bottom: log
        JPanel bottom = new JPanel(new BorderLayout(0, 12));
        bottom.setBackground(COLOR_BG);
        bottom.add(buildRunButtonBar(), BorderLayout.NORTH);
        bottom.add(buildLogPanel(), BorderLayout.CENTER);
        page.add(bottom, BorderLayout.SOUTH);

        return page;
    }

    private JPanel buildInputFilesPanel() {
        inputList = new JList<>(inputModel);
        styleList(inputList, ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        inputList.setCellRenderer(new FileNameRenderer());
        inputList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateSelectionCount();
        });

        // File action buttons
        addInputButton       = createSmallButton("Add File",   this::addInputFile);
        removeInputButton    = createSmallButton("Remove",     () -> removeSelectedFile(inputList, "input"));
        selectAllInputsButton= createSmallButton("Select All", this::selectAllInputs);
        clearSelectionButton = createSmallButton("Clear",      () -> { inputList.clearSelection(); updateSelectionCount(); });

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttonRow.setOpaque(false);
        buttonRow.add(addInputButton);
        buttonRow.add(removeInputButton);
        buttonRow.add(selectAllInputsButton);
        buttonRow.add(clearSelectionButton);

        selectionCountLabel = new JLabel("0 selected");
        selectionCountLabel.setForeground(COLOR_TEXT_SUBTLE);
        selectionCountLabel.setFont(FONT_SMALL);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        footer.add(buttonRow, BorderLayout.WEST);
        footer.add(selectionCountLabel, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(COLOR_SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(COLOR_BORDER),
            BorderFactory.createEmptyBorder(12, 12, 12, 12)
        ));

        JLabel title = new JLabel("Input Files");
        title.setForeground(Color.WHITE);
        title.setFont(FONT_TITLE);

        panel.add(title, BorderLayout.NORTH);
        panel.add(createStyledScrollPane(inputList), BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildRunOptionsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBackground(COLOR_SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(COLOR_BORDER),
            BorderFactory.createEmptyBorder(12, 16, 12, 16)
        ));

        JLabel title = new JLabel("Run Options");
        title.setForeground(Color.WHITE);
        title.setFont(FONT_TITLE);
        panel.add(title, BorderLayout.NORTH);

        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.setOpaque(false);

        dbPersistCheckBox          = createOptionCheckBox("Persist results to database", true);
        previewBeforeCommitCheckBox= createOptionCheckBox("Preview results before saving (single run)", true);

        JLabel workersLabel = createOptionLabel("Batch workers (1–4):");
        batchWorkersSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 4, 1));
        styleSpinner(batchWorkersSpinner);
        batchWorkersSpinner.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel workerNote = createOptionLabel("Max 2 workers recommended for CoreNLP stability.");
        workerNote.setForeground(COLOR_TEXT_SUBTLE);
        workerNote.setFont(FONT_SMALL);

        options.add(dbPersistCheckBox);
        options.add(Box.createVerticalStrut(10));
        options.add(previewBeforeCommitCheckBox);
        options.add(Box.createVerticalStrut(16));
        options.add(workersLabel);
        options.add(Box.createVerticalStrut(4));
        options.add(batchWorkersSpinner);
        options.add(Box.createVerticalStrut(4));
        options.add(workerNote);

        panel.add(options, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildRunButtonBar() {
        runSingleButton = createAccentButton("Run Selected (Single)", this::runSingleAnalysis);
        runBatchButton  = createAccentButton("Run Selected (Batch)",  this::runBatchAnalysis);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        bar.setBackground(COLOR_BG);
        bar.add(runSingleButton);
        bar.add(runBatchButton);
        return bar;
    }

    private JPanel buildLogPanel() {
        logArea = new JTextArea(10, 80);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setBackground(COLOR_SURFACE_ALT);
        logArea.setForeground(COLOR_TEXT);
        logArea.setCaretColor(COLOR_TEXT);
        logArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(COLOR_BG);

        JLabel title = new JLabel("Activity Log");
        title.setForeground(COLOR_TEXT_MUTED);
        title.setFont(new Font("Segoe UI", Font.BOLD, 12));
        panel.add(title, BorderLayout.NORTH);
        panel.add(createStyledScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    // ── Results page ──────────────────────────────────────────────────────

    private JPanel buildResultsPage() {
        JPanel page = new JPanel(new BorderLayout(0, 16));
        page.setBackground(COLOR_BG);
        page.setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28));

        page.add(buildPageHeader("Results",
            "Browse and review scoring output files."), BorderLayout.NORTH);

        // Two-column: file list (left 280px) + viewer (right)
        JPanel content = new JPanel(new BorderLayout(16, 0));
        content.setBackground(COLOR_BG);
        content.add(buildOutputListPanel(), BorderLayout.WEST);
        content.add(buildFileViewerPanel(), BorderLayout.CENTER);
        page.add(content, BorderLayout.CENTER);

        return page;
    }

    private JPanel buildOutputListPanel() {
        outputList = new JList<>(outputModel);
        styleList(outputList, ListSelectionModel.SINGLE_SELECTION);
        outputList.setCellRenderer(new FileNameRenderer());
        outputList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadSelectedOutputFile();
        });

        removeOutputButton = createSmallButton("Remove", () -> removeSelectedFile(outputList, "output"));
        JButton refreshBtn = createSmallButton("Refresh", this::refreshLists);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttonRow.setOpaque(false);
        buttonRow.add(removeOutputButton);
        buttonRow.add(refreshBtn);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        footer.add(buttonRow, BorderLayout.WEST);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(COLOR_SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(COLOR_BORDER),
            BorderFactory.createEmptyBorder(12, 12, 12, 12)
        ));
        panel.setPreferredSize(new Dimension(280, 0));

        JLabel title = new JLabel("Output Files");
        title.setForeground(Color.WHITE);
        title.setFont(FONT_TITLE);

        panel.add(title, BorderLayout.NORTH);
        panel.add(createStyledScrollPane(outputList), BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildFileViewerPanel() {
        resultViewerTitle = new JLabel("Select a file to view");
        resultViewerTitle.setForeground(COLOR_TEXT_MUTED);
        resultViewerTitle.setFont(FONT_TITLE);

        resultViewerArea = new JTextArea();
        resultViewerArea.setEditable(false);
        resultViewerArea.setLineWrap(false);
        resultViewerArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        resultViewerArea.setBackground(COLOR_SURFACE_ALT);
        resultViewerArea.setForeground(COLOR_TEXT);
        resultViewerArea.setCaretColor(COLOR_TEXT);
        resultViewerArea.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(COLOR_SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(COLOR_BORDER),
            BorderFactory.createEmptyBorder(12, 12, 12, 12)
        ));
        panel.add(resultViewerTitle, BorderLayout.NORTH);
        panel.add(createStyledScrollPane(resultViewerArea), BorderLayout.CENTER);
        return panel;
    }

    private void loadSelectedOutputFile() {
        Path selected = outputList.getSelectedValue();
        if (selected == null) return;
        resultViewerTitle.setText(selected.getFileName().toString());
        try {
            String content = Files.readString(selected, StandardCharsets.UTF_8);
            resultViewerArea.setText(content);
            resultViewerArea.setCaretPosition(0);
        } catch (IOException e) {
            resultViewerArea.setText("Could not read file: " + e.getMessage());
        }
    }

    // ── Shared UI helpers ─────────────────────────────────────────────────

    private JPanel buildPageHeader(String title, String subtitle) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FONT_HEADING);
        titleLabel.setForeground(Color.WHITE);

        JLabel subtitleLabel = new JLabel(subtitle);
        subtitleLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        subtitleLabel.setForeground(COLOR_TEXT_SUBTLE);

        panel.add(titleLabel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(subtitleLabel);
        return panel;
    }

    private JPanel createSummaryCard(String title, JLabel valueLabel) {
        JPanel card = new JPanel(new BorderLayout(2, 6));
        card.setBackground(COLOR_SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(COLOR_BORDER),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(COLOR_TEXT_MUTED);
        titleLabel.setFont(FONT_SMALL);

        card.add(titleLabel,  BorderLayout.NORTH);
        card.add(valueLabel,  BorderLayout.CENTER);
        return card;
    }

    private JLabel createSummaryValueLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(new Font("Segoe UI", Font.BOLD, 18));
        return label;
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
        JScrollPane scroll = new JScrollPane(child);
        scroll.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        scroll.getViewport().setBackground(
            child instanceof JTextArea ? COLOR_SURFACE_ALT : COLOR_SURFACE_ALT
        );
        scroll.setBackground(COLOR_SURFACE_ALT);
        return scroll;
    }

    private JButton createAccentButton(String text, Runnable action) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_BODY);
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setBackground(COLOR_ACCENT);
        btn.setForeground(Color.WHITE);
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(COLOR_ACCENT),
            BorderFactory.createEmptyBorder(9, 16, 9, 16)
        ));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(e -> action.run());
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                if (btn.isEnabled()) btn.setBackground(COLOR_ACCENT_HOVER);
            }
            @Override public void mouseExited(MouseEvent e) {
                if (btn.isEnabled()) btn.setBackground(COLOR_ACCENT);
            }
        });
        return btn;
    }

    private JButton createSmallButton(String text, Runnable action) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_SMALL);
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setBackground(COLOR_SURFACE_ALT);
        btn.setForeground(COLOR_TEXT_MUTED);
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(COLOR_BORDER),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(e -> action.run());
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                if (btn.isEnabled()) { btn.setBackground(new Color(0x252525)); btn.setForeground(Color.WHITE); }
            }
            @Override public void mouseExited(MouseEvent e) {
                if (btn.isEnabled()) { btn.setBackground(COLOR_SURFACE_ALT); btn.setForeground(COLOR_TEXT_MUTED); }
            }
        });
        return btn;
    }

    private JButton createGhostButton(String text, Runnable action) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_SMALL);
        btn.setFocusPainted(false);
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setForeground(COLOR_ACCENT);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(e -> action.run());
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { btn.setForeground(COLOR_ACCENT_HOVER); }
            @Override public void mouseExited(MouseEvent e)  { btn.setForeground(COLOR_ACCENT); }
        });
        return btn;
    }

    private JCheckBox createOptionCheckBox(String text, boolean selected) {
        JCheckBox cb = new JCheckBox(text, selected);
        cb.setOpaque(false);
        cb.setForeground(COLOR_TEXT_MUTED);
        cb.setFont(FONT_BODY);
        cb.setAlignmentX(Component.LEFT_ALIGNMENT);
        cb.setFocusPainted(false);
        return cb;
    }

    private JLabel createOptionLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(COLOR_TEXT_MUTED);
        lbl.setFont(FONT_BODY);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lbl;
    }

    private void styleSpinner(JSpinner spinner) {
        spinner.setFont(FONT_BODY);
        spinner.setMaximumSize(new Dimension(80, 30));
        spinner.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor de) {
            de.getTextField().setBackground(COLOR_SURFACE_ALT);
            de.getTextField().setForeground(COLOR_TEXT);
            de.getTextField().setCaretColor(COLOR_TEXT);
            de.getTextField().setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        }
    }

    // ── File operations ───────────────────────────────────────────────────

    private void refreshLists() {
        ensureDirectories();
        loadFiles(inputDir,  inputModel,  false, ".txt", ".docx");
        loadFiles(outputDir, outputModel, true,  ".txt", ".json");
        updateSelectionCount();
        refreshRecentOutputs();
        appendLog("File lists refreshed.");
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(inputDir);
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            appendLog("Failed to create directories: " + e.getMessage());
        }
    }

    private void loadFiles(Path dir, DefaultListModel<Path> model,
                           boolean newestFirst, String... exts) {
        model.clear();
        try (var stream = Files.list(dir)) {
            Comparator<Path> sort = newestFirst
                ? (a, b) -> {
                    try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); }
                    catch (IOException ex) { return a.getFileName().compareTo(b.getFileName()); }
                  }
                : Comparator.comparing(Path::getFileName);
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      if (exts.length == 0) return true;
                      String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                      for (String ext : exts) if (n.endsWith(ext)) return true;
                      return false;
                  })
                  .sorted(sort)
                  .forEach(model::addElement);
        } catch (IOException e) {
            appendLog("Failed to list " + dir.getFileName() + ": " + e.getMessage());
        }
    }

    private void refreshRecentOutputs() {
        // Find the "recentOutputsContainer" panel on the Dashboard page and repopulate it
        JPanel container = findNamedPanel(contentCards, "recentOutputsContainer");
        if (container == null) return;
        container.removeAll();

        List<Path> files = new ArrayList<>();
        for (int i = 0; i < outputModel.size(); i++) files.add(outputModel.get(i));
        // Show last 6 by modification time
        files.sort((a, b) -> {
            try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); }
            catch (IOException ex) { return 0; }
        });

        if (files.isEmpty()) {
            JLabel empty = new JLabel("No output files yet. Run an analysis to get started.");
            empty.setForeground(COLOR_TEXT_SUBTLE);
            empty.setFont(FONT_SMALL);
            container.add(empty);
        } else {
            int count = Math.min(6, files.size());
            for (int i = 0; i < count; i++) {
                Path f = files.get(i);
                JLabel row = new JLabel(f.getFileName().toString());
                row.setForeground(i == 0 ? COLOR_TEXT : COLOR_TEXT_MUTED);
                row.setFont(FONT_BODY);
                row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
                final Path fp = f;
                row.addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) {
                        showNavPage(PAGE_RESULTS);
                        outputList.setSelectedValue(fp, true);
                    }
                    @Override public void mouseEntered(MouseEvent e) { row.setForeground(COLOR_ACCENT_HOVER); }
                    @Override public void mouseExited(MouseEvent e)  {
                        row.setForeground(fp.equals(files.get(0)) ? COLOR_TEXT : COLOR_TEXT_MUTED);
                    }
                });
                container.add(row);
                if (i < count - 1) container.add(buildThinDivider());
            }
        }
        container.revalidate();
        container.repaint();
    }

    private JPanel buildThinDivider() {
        JPanel d = new JPanel();
        d.setBackground(COLOR_BORDER);
        d.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        d.setAlignmentX(Component.LEFT_ALIGNMENT);
        return d;
    }

    private JPanel findNamedPanel(Container root, String name) {
        for (Component c : root.getComponents()) {
            if (c instanceof JPanel p) {
                if (name.equals(p.getName())) return p;
                JPanel found = findNamedPanel(p, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void addInputFile() {
        FileDialog dialog = new FileDialog(appFrame, "Select Input Transcript (.txt or .docx)", FileDialog.LOAD);
        dialog.setDirectory(inputDir.toString());
        dialog.setVisible(true);
        if (dialog.getFile() == null) return;

        Path source = Paths.get(dialog.getDirectory(), dialog.getFile());
        String lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".txt") || lower.endsWith(".docx"))) {
            appendLog("Unsupported file type: " + source.getFileName() + " (use .txt or .docx)");
            return;
        }

        Path target = inputDir.resolve(source.getFileName().toString());
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            appendLog("Added input file: " + target.getFileName());
            refreshLists();
        } catch (IOException e) {
            appendLog("Failed to copy file: " + e.getMessage());
        }
    }

    private void removeSelectedFile(JList<Path> list, String folder) {
        Path selected = list.getSelectedValue();
        if (selected == null) { appendLog("No file selected in " + folder + " list."); return; }

        int confirm = JOptionPane.showConfirmDialog(appFrame,
            "Delete " + selected.getFileName() + "?", "Confirm Delete",
            JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        try {
            Files.deleteIfExists(selected);
            appendLog("Deleted: " + selected.getFileName());
            refreshLists();
        } catch (IOException e) {
            appendLog("Failed to delete: " + e.getMessage());
        }
    }

    private void selectAllInputs() {
        if (inputModel.isEmpty()) { appendLog("No input files to select."); return; }
        inputList.setSelectionInterval(0, inputModel.size() - 1);
        updateSelectionCount();
    }

    private void updateSelectionCount() {
        if (selectionCountLabel == null || inputList == null) return;
        int n = inputList.getSelectedValuesList().size();
        selectionCountLabel.setText(n + " selected");
    }

    // ── Run control enabling ──────────────────────────────────────────────

    private void setRunControlsEnabled(boolean enabled) {
        runSingleButton.setEnabled(enabled);
        runBatchButton.setEnabled(enabled);
        dbPersistCheckBox.setEnabled(enabled);
        previewBeforeCommitCheckBox.setEnabled(enabled);
        batchWorkersSpinner.setEnabled(enabled);
        addInputButton.setEnabled(enabled);
        removeInputButton.setEnabled(enabled);
        selectAllInputsButton.setEnabled(enabled);
        clearSelectionButton.setEnabled(enabled);
        if (removeOutputButton != null) removeOutputButton.setEnabled(enabled);
    }

    // ── Run logic (single) ────────────────────────────────────────────────

    private void runSingleAnalysis() {
        List<Path> selected = new ArrayList<>(new LinkedHashSet<>(inputList.getSelectedValuesList()));
        if (selected.size() != 1) {
            appendLog("Single run requires exactly one selected input file.");
            return;
        }

        Path selectedInput = selected.get(0);
        setRunControlsEnabled(false);
        boolean persistDb          = dbPersistCheckBox.isSelected();
        boolean previewBeforeCommit= previewBeforeCommitCheckBox.isSelected();
        appendLog("Analyzing: " + selectedInput.getFileName());
        appendLog("DB: " + (persistDb ? "ON" : "OFF") + "  Preview: " + (previewBeforeCommit ? "ON" : "OFF"));

        SwingWorker<TurnScorer.AnalysisBundle, Void> worker = new SwingWorker<>() {
            @Override
            protected TurnScorer.AnalysisBundle doInBackground() throws Exception {
                return TurnScorer.analyzeOnly(selectedInput.toString(), false, false);
            }

            @Override
            protected void done() {
                try {
                    TurnScorer.AnalysisBundle analysis = get();
                    appendLog("Analysis complete. Turns scored: " + analysis.getScored().size());

                    if (previewBeforeCommit) {
                        String textPreview = TurnScorer.buildTextPreview(analysis, false);
                        String jsonPreview = TurnScorer.buildJsonPreview(analysis);
                        if (!showPreviewAndConfirm(textPreview, jsonPreview)) {
                            appendLog("Run discarded after preview.");
                            clearDbSummary("Discarded");
                            setRunControlsEnabled(true);
                            return;
                        }
                    }
                    appendLog("Committing output...");
                    commitAnalysisResult(analysis, persistDb);
                } catch (Exception ex) {
                    appendLog("Run failed: " + ex.getMessage());
                    clearDbSummary("Failed");
                    setRunControlsEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private void commitAnalysisResult(TurnScorer.AnalysisBundle analysis, boolean persistDb) {
        SwingWorker<TurnScorer.RunResult, Void> worker = new SwingWorker<>() {
            @Override
            protected TurnScorer.RunResult doInBackground() throws Exception {
                return TurnScorer.finalizeRun(analysis, outputDir.toString(), false, persistDb);
            }

            @Override
            protected void done() {
                try {
                    TurnScorer.RunResult result = get();
                    appendLog("Complete. Turns: " + result.getTotalTurns()
                        + " | Scored: " + result.getScoredTurns()
                        + " | Pairs: " + result.getInteractionPairs());
                    if (result.getTextFile() != null) appendLog("Report: " + result.getTextFile().getFileName());
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
        worker.execute();
    }

    private boolean showPreviewAndConfirm(String textPreview, String jsonPreview) {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(COLOR_SURFACE);
        tabs.setForeground(COLOR_TEXT);

        JTextArea textArea = new JTextArea(textPreview, 28, 100);
        textArea.setEditable(false);
        textArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        textArea.setCaretPosition(0);

        JTextArea jsonArea = new JTextArea(jsonPreview, 28, 100);
        jsonArea.setEditable(false);
        jsonArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        jsonArea.setCaretPosition(0);

        tabs.addTab("Text Report", new JScrollPane(textArea));
        tabs.addTab("JSON",        new JScrollPane(jsonArea));

        return JOptionPane.showConfirmDialog(appFrame, tabs,
            "Preview — Confirm Save", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
    }

    // ── Run logic (batch) ─────────────────────────────────────────────────

    private void runBatchAnalysis() {
        List<Path> selected = new ArrayList<>(new LinkedHashSet<>(inputList.getSelectedValuesList()));
        if (selected.isEmpty()) { appendLog("Select one or more files for batch run."); return; }

        lastBatchResult = null;
        int requestedWorkers = Math.min((Integer) batchWorkersSpinner.getValue(), selected.size());
        int workers          = Math.min(requestedWorkers, MAX_SAFE_BATCH_WORKERS);
        boolean persistDb    = dbPersistCheckBox.isSelected();

        if (workers < requestedWorkers)
            appendLog("Batch workers capped at " + workers + " (requested " + requestedWorkers + ").");

        int confirm = JOptionPane.showConfirmDialog(appFrame,
            buildBatchConfirmMessage(selected, requestedWorkers, workers, persistDb),
            "Confirm Batch Run", JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) { appendLog("Batch run cancelled."); return; }

        setRunControlsEnabled(false);
        clearDbSummary("Batch running…");
        appendLog("Batch started: " + selected.size() + " file(s), " + workers + " worker(s).");

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                ExecutorService exec = Executors.newFixedThreadPool(workers);
                CompletionService<RunOutcome> cs = new ExecutorCompletionService<>(exec);
                try {
                    for (Path input : selected) {
                        cs.submit(() -> {
                            try {
                                return RunOutcome.success(input,
                                    TurnScorer.runPipeline(input.toString(), outputDir.toString(), false, persistDb));
                            } catch (Exception ex) {
                                return RunOutcome.failure(input, ex);
                            }
                        });
                    }
                    for (int i = 0; i < selected.size(); i++) {
                        RunOutcome outcome = cs.take().get();
                        if (outcome.error != null) {
                            publish("Failed: " + outcome.input.getFileName() + " — " + outcome.error.getMessage());
                        } else {
                            lastBatchResult = outcome.result;
                            publish("Done: " + outcome.input.getFileName()
                                + " | scored=" + outcome.result.getScoredTurns()
                                + " | DB=" + outcome.result.getDbMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    publish("Batch interrupted.");
                } catch (ExecutionException e) {
                    publish("Batch error: " + e.getMessage());
                } finally {
                    exec.shutdownNow();
                }
                return null;
            }

            @Override protected void process(List<String> chunks) { chunks.forEach(SentimentAnalysisApp.this::appendLog); }

            @Override
            protected void done() {
                setRunControlsEnabled(true);
                refreshLists();
                if (lastBatchResult != null) updateDbSummary(lastBatchResult);
                else clearDbSummary("Batch finished");
                appendLog("Batch run finished.");
            }
        };
        worker.execute();
    }

    private String buildBatchConfirmMessage(List<Path> selected, int req, int eff, boolean db) {
        StringBuilder sb = new StringBuilder("Batch analysis:\n\n");
        sb.append("Files: ").append(selected.size()).append("\n");
        sb.append("Workers: ").append(eff).append(eff < req ? " (capped from " + req + ")" : "").append("\n");
        sb.append("DB: ").append(db ? "ON" : "OFF").append("\n\n");
        int preview = Math.min(5, selected.size());
        for (int i = 0; i < preview; i++) sb.append("  ").append(selected.get(i).getFileName()).append("\n");
        if (selected.size() > preview) sb.append("  … and ").append(selected.size() - preview).append(" more\n");
        sb.append("\nProceed?");
        return sb.toString();
    }

    // ── Log + DB summary ──────────────────────────────────────────────────

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
        ScoringPersistence.PersistenceResult s = result.getDbSummary();
        dbStatusValue.setText("Persisted");
        dbStatusValue.setForeground(COLOR_SUCCESS);
        dbHearingValue.setText(String.valueOf(s.getHearingId()));
        dbRunValue.setText(String.valueOf(s.getScoringRunId()));
        dbTurnsValue.setText(String.valueOf(s.getTurnsInserted()));
        dbScoresValue.setText(String.valueOf(s.getTurnScoresInserted()));
        dbReplaceValue.setText(s.isReplacedExistingRun() ? "Yes" : "No");
    }

    private void clearDbSummary(String status) {
        if (dbStatusValue == null) return;
        dbStatusValue.setText(status != null ? status : "—");
        dbStatusValue.setForeground(Color.WHITE);
        dbHearingValue.setText("—");
        dbRunValue.setText("—");
        dbTurnsValue.setText("—");
        dbScoresValue.setText("—");
        dbReplaceValue.setText("—");
    }

    // ── Project root resolution ───────────────────────────────────────────

    private Path resolveProjectRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Set<Path> candidates = new LinkedHashSet<>();
        Path cursor = cwd;
        for (int i = 0; i < 6 && cursor != null; i++) {
            candidates.add(cursor);
            candidates.add(cursor.resolve("SentimentAnalysisProject"));
            cursor = cursor.getParent();
        }
        for (Path c : candidates) {
            if (isProjectRoot(c)) return c;
        }
        return cwd;
    }

    private boolean isProjectRoot(Path candidate) {
        return Files.exists(candidate.resolve("pom.xml"))
            && Files.isDirectory(candidate.resolve("src"))
            && Files.isDirectory(candidate.resolve("input"))
            && Files.isDirectory(candidate.resolve("output"));
    }

    // ── Inner classes ─────────────────────────────────────────────────────

    private static class FileNameRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                list, value, index, isSelected, cellHasFocus);
            if (value instanceof Path p) label.setText(p.getFileName().toString());
            label.setFont(FONT_BODY);
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            label.setBackground(isSelected ? COLOR_ACCENT       : COLOR_SURFACE_ALT);
            label.setForeground(isSelected ? Color.WHITE        : COLOR_TEXT);
            return label;
        }
    }

    private static class RunOutcome {
        final Path input;
        final TurnScorer.RunResult result;
        final Exception error;

        private RunOutcome(Path input, TurnScorer.RunResult result, Exception error) {
            this.input  = input;
            this.result = result;
            this.error  = error;
        }

        static RunOutcome success(Path input, TurnScorer.RunResult r) { return new RunOutcome(input, r, null); }
        static RunOutcome failure(Path input, Exception e)            { return new RunOutcome(input, null, e); }
    }
}
