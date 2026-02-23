import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.*;
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations;

import javax.json.*;
import javax.json.stream.JsonGenerator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * TurnScorer.java
 *
 * Full pipeline: parse speaker turns → resolve targets → score each turn
 * with CoreNLP sentiment → aggregate by (senator, nominee) pairs → print
 * Nominee Scorecard and Senator Voting Profile reports.
 *
 * <h3>Pipeline stages:</h3>
 * <ol>
 *   <li>Read transcript text from file</li>
 *   <li>Parse into {@link SpeakerTurn} objects via {@link SpeakerTurnParser}</li>
 *   <li>Resolve targets via {@link TargetResolver}</li>
 *   <li>Build CoreNLP pipeline (tokenize, ssplit, parse, sentiment)</li>
 *   <li>Score each substantive non-self turn through CoreNLP</li>
 *   <li>Aggregate scores by (senator, nominee) interaction pairs</li>
 *   <li>Print reports</li>
 * </ol>
 *
 * <p>Run standalone: {@code java -Xmx4g TurnScorer hearing.txt}</p>
 */
public class TurnScorer {

    // ── Sentiment labels and mapping ─────────────────────────────────────

    private static final String[] LABELS = {
        "Very Negative", "Negative", "Neutral", "Positive", "Very Positive"
    };

    /** Maps CoreNLP's 0-4 class index to [-2, +2] integer score. */
    private static int toScore(int classIndex) {
        return classIndex - 2;
    }

    // ── Known senator titles (for filtering approval-relevant turns) ─────

    private static final Set<String> SENATOR_TITLES = Set.of(
        "Chairman", "The Chairman", "Senator"
    );

    // ── Data structures for aggregation ──────────────────────────────────

    /**
     * Holds aggregated scores for one (senator, nominee) interaction pair.
     */
    static class InteractionScore {
        String senatorLabel;
        String nomineeLabel;
        int    turns;
        int    sentences;
        double totalWeighted;   // sum of weighted scores across turns
        double totalRaw;        // sum of raw avg scores across turns

        InteractionScore(String senator, String nominee) {
            this.senatorLabel = senator;
            this.nomineeLabel = nominee;
        }

        void addTurn(ScoredTurn st) {
            turns++;
            sentences += st.getSentenceCount();
            totalWeighted += st.getWeightedScore();
            totalRaw      += st.getAvgScore();
        }

        double avgWeighted() { return turns > 0 ? totalWeighted / turns : 0.0; }
        double avgRaw()      { return turns > 0 ? totalRaw / turns : 0.0; }
    }

    // ── Core scoring method ──────────────────────────────────────────────

    /**
     * Scores a single turn's text through CoreNLP.
     *
     * @param resolved the resolved target for this turn
     * @param pipeline the CoreNLP pipeline (reused across turns)
     * @return a ScoredTurn with sentence-level results, or null if no
     *         substantive text
     */
    public static ScoredTurn scoreTurn(ResolvedTarget resolved,
                                       StanfordCoreNLP pipeline) {
        SpeakerTurn turn = resolved.getTurn();

        // Skip non-substantive turns
        if (!turn.hasSubstantiveText()) return null;

        // Strip bracketed annotations before scoring
        String text = turn.getText().replaceAll("\\[.*?\\]", " ").trim();
        if (text.isEmpty()) return null;

        // Annotate through CoreNLP
        Annotation annotation = new Annotation(text);
        pipeline.annotate(annotation);

        List<CoreMap> sentences =
            annotation.get(CoreAnnotations.SentencesAnnotation.class);

        if (sentences == null || sentences.isEmpty()) return null;

        int totalScore = 0;
        for (CoreMap sentence : sentences) {
            Tree tree = sentence.get(
                SentimentCoreAnnotations.SentimentAnnotatedTree.class);
            int classIdx = RNNCoreAnnotations.getPredictedClass(tree);
            totalScore += toScore(classIdx);
        }

        return new ScoredTurn(resolved, sentences.size(), totalScore);
    }

    // ── Report generation ────────────────────────────────────────────────

    /**
     * Aggregates scored turns by (senator, nominee) pairs.
     */
    static Map<String, InteractionScore> aggregate(List<ScoredTurn> scored) {
        // Key: "senatorLabel|nomineeLabel"
        Map<String, InteractionScore> map = new LinkedHashMap<>();

        for (ScoredTurn st : scored) {
            if (st.isSelfTurn()) continue;       // nominee's own speech
            if (!st.hasSpecificTarget()) continue; // ambiguous target

            String senator = st.getSpeakerLabel();
            String nominee = st.getTarget().getNominee().getDisplayName();
            String key = senator + "|" + nominee;

            map.computeIfAbsent(key, k -> new InteractionScore(senator, nominee))
               .addTurn(st);
        }

        return map;
    }

    /**
     * Prints the Nominee Scorecard: for each nominee, which senators
     * questioned them and what the approval sentiment was.
     */
    static void printNomineeScorecard(PrintStream out,
                                      Map<String, InteractionScore> interactions,
                                      List<ScoredTurn> scored) {
        out.println("=".repeat(70));
        out.println("  NOMINEE SCORECARD");
        out.println("=".repeat(70));
        out.println();

        // Group interactions by nominee
        Map<String, List<InteractionScore>> byNominee = new LinkedHashMap<>();
        for (InteractionScore is : interactions.values()) {
            byNominee.computeIfAbsent(is.nomineeLabel, k -> new ArrayList<>())
                     .add(is);
        }

        for (Map.Entry<String, List<InteractionScore>> entry : byNominee.entrySet()) {
            String nominee = entry.getKey();
            List<InteractionScore> senators = entry.getValue();

            // Compute overall nominee approval
            int totalTurns = 0;
            int totalSentences = 0;
            double totalWeighted = 0;
            for (InteractionScore is : senators) {
                totalTurns     += is.turns;
                totalSentences += is.sentences;
                totalWeighted  += is.totalWeighted;
            }
            double overallAvg = totalTurns > 0 ? totalWeighted / totalTurns : 0.0;

            out.printf("  %s%n", nominee);
            out.println("  " + "-".repeat(66));
            out.printf("  Overall Approval Score: %+.2f  (%d sentences from %d senator turns)%n",
                overallAvg, totalSentences, totalTurns);
            out.println();

            // Sort senators by weighted score (most negative first)
            senators.sort(Comparator.comparingDouble(InteractionScore::avgWeighted));

            for (InteractionScore is : senators) {
                out.printf("    %-28s %+.2f  (%d turns, %d sentences)%n",
                    is.senatorLabel, is.avgWeighted(), is.turns, is.sentences);
            }
            out.println();
        }
    }

    /**
     * Prints the Senator Voting Profile: for each senator, their scores
     * toward each nominee they questioned.
     */
    static void printSenatorProfile(PrintStream out,
                                    Map<String, InteractionScore> interactions) {
        out.println("=".repeat(70));
        out.println("  SENATOR VOTING PROFILE");
        out.println("=".repeat(70));
        out.println();

        // Group interactions by senator
        Map<String, List<InteractionScore>> bySenator = new LinkedHashMap<>();
        for (InteractionScore is : interactions.values()) {
            bySenator.computeIfAbsent(is.senatorLabel, k -> new ArrayList<>())
                     .add(is);
        }

        for (Map.Entry<String, List<InteractionScore>> entry : bySenator.entrySet()) {
            String senator = entry.getKey();
            List<InteractionScore> nominees = entry.getValue();

            out.printf("  %s%n", senator);
            out.println("  " + "-".repeat(66));

            // Sort nominees by weighted score (most negative first)
            nominees.sort(Comparator.comparingDouble(InteractionScore::avgWeighted));

            for (InteractionScore is : nominees) {
                out.printf("    → %-24s %+.2f  (%d turns, %d sentences)%n",
                    is.nomineeLabel, is.avgWeighted(), is.turns, is.sentences);
            }
            out.println();
        }
    }

    /**
     * Prints a summary of the scoring run.
     */
    static void printSummary(PrintStream out,
                             List<ResolvedTarget> resolved,
                             List<ScoredTurn> scored,
                             Map<String, InteractionScore> interactions,
                             long elapsedMs) {
        out.println("=".repeat(70));
        out.println("  SCORING SUMMARY");
        out.println("=".repeat(70));
        out.println();

        int selfTurns = 0;
        int skippedTurns = 0;
        int scoredSenatorTurns = 0;
        int totalSentences = 0;
        double totalConfidence = 0;
        int confCount = 0;

        for (ScoredTurn st : scored) {
            totalSentences += st.getSentenceCount();
            if (st.isSelfTurn()) {
                selfTurns++;
            } else {
                scoredSenatorTurns++;
            }
            totalConfidence += st.getTarget().getConfidence();
            confCount++;
        }

        skippedTurns = resolved.size() - scored.size();

        out.printf("  Total turns in transcript:    %d%n", resolved.size());
        out.printf("  Turns scored by CoreNLP:      %d%n", scored.size());
        out.printf("    - Senator turns scored:     %d%n", scoredSenatorTurns);
        out.printf("    - Nominee self-turns:       %d%n", selfTurns);
        out.printf("    - Skipped (non-substantive):%d%n", skippedTurns);
        out.printf("  CoreNLP sentences processed:  %d%n", totalSentences);
        out.printf("  Unique senator→nominee pairs: %d%n", interactions.size());
        out.printf("  Avg resolution confidence:    %.2f%n",
            confCount > 0 ? totalConfidence / confCount : 0.0);
        out.printf("  Total scoring time:           %.1f seconds%n",
            elapsedMs / 1000.0);
        out.println();
    }

    /**
     * Prints per-turn detail (optional verbose output for debugging).
     */
    static void printTurnDetail(PrintStream out,
                                List<ScoredTurn> scored, boolean verbose) {
        if (!verbose) return;

        out.println("=".repeat(70));
        out.println("  PER-TURN DETAIL");
        out.println("=".repeat(70));
        out.println();

        for (ScoredTurn st : scored) {
            String arrow = st.isSelfTurn() ? "(SELF)" :
                st.hasSpecificTarget() ?
                    "→ " + st.getTarget().getNominee().getDisplayName() : "(panel)";

            out.printf("  Turn %3d  %-24s  avg=%+.2f  w=%+.2f  [%2d sent]  %s  (%s)%n",
                st.getTarget().getTurn().getTurnNumber(),
                st.getSpeakerLabel(),
                st.getAvgScore(),
                st.getWeightedScore(),
                st.getSentenceCount(),
                arrow,
                st.getTarget().getMethod());
        }
        out.println();
    }

    // ── Structured output ─────────────────────────────────────────────

    /**
     * Writes the complete scoring results as structured JSON using
     * jakarta.json (shipped with CoreNLP). This is the SQL-ready output.
     */
    static void writeJsonOutput(Path jsonPath, String inputFile,
                                List<ResolvedTarget> resolved,
                                List<ScoredTurn> scored,
                                Map<String, InteractionScore> interactions,
                                long elapsedMs) throws IOException {

        // ── Build turn-level array ──
        JsonArrayBuilder turnsArr = Json.createArrayBuilder();
        for (ScoredTurn st : scored) {
            ResolvedTarget rt = st.getTarget();
            SpeakerTurn turn = rt.getTurn();
            String targetName = rt.isSelfTurn() ? "SELF" :
                rt.hasSpecificTarget() ? rt.getNominee().getDisplayName() :
                    "UNKNOWN";

            turnsArr.add(Json.createObjectBuilder()
                .add("turnNumber",        turn.getTurnNumber())
                .add("speaker",           turn.getSpeakerLabel())
                .add("target",            targetName)
                .add("resolutionMethod",  rt.getMethod().toString())
                .add("confidence",        round4(rt.getConfidence()))
                .add("sentenceCount",     st.getSentenceCount())
                .add("totalScore",        st.getTotalScore())
                .add("avgScore",          round4(st.getAvgScore()))
                .add("weightedScore",     round4(st.getWeightedScore()))
            );
        }

        // ── Build interaction-level array ──
        JsonArrayBuilder interArr = Json.createArrayBuilder();
        for (InteractionScore is : interactions.values()) {
            interArr.add(Json.createObjectBuilder()
                .add("senator",          is.senatorLabel)
                .add("nominee",          is.nomineeLabel)
                .add("turns",            is.turns)
                .add("sentences",        is.sentences)
                .add("avgWeightedScore", round4(is.avgWeighted()))
                .add("avgRawScore",      round4(is.avgRaw()))
                .add("totalWeighted",    round4(is.totalWeighted))
            );
        }

        // ── Build nominee scorecard array ──
        JsonArrayBuilder nomineesArr = Json.createArrayBuilder();
        Map<String, List<InteractionScore>> byNominee = new LinkedHashMap<>();
        for (InteractionScore is : interactions.values()) {
            byNominee.computeIfAbsent(is.nomineeLabel, k -> new ArrayList<>())
                     .add(is);
        }
        for (Map.Entry<String, List<InteractionScore>> entry : byNominee.entrySet()) {
            String nominee = entry.getKey();
            List<InteractionScore> senators = entry.getValue();
            int totalTurns = 0, totalSentences = 0;
            double totalWeighted = 0;
            for (InteractionScore is : senators) {
                totalTurns += is.turns;
                totalSentences += is.sentences;
                totalWeighted += is.totalWeighted;
            }

            JsonArrayBuilder senArr = Json.createArrayBuilder();
            senators.sort(Comparator.comparingDouble(InteractionScore::avgWeighted));
            for (InteractionScore is : senators) {
                senArr.add(Json.createObjectBuilder()
                    .add("senator",  is.senatorLabel)
                    .add("score",    round4(is.avgWeighted()))
                    .add("turns",    is.turns)
                    .add("sentences", is.sentences)
                );
            }

            nomineesArr.add(Json.createObjectBuilder()
                .add("nominee",         nominee)
                .add("overallApproval", round4(totalTurns > 0
                    ? totalWeighted / totalTurns : 0.0))
                .add("totalTurns",      totalTurns)
                .add("totalSentences",  totalSentences)
                .add("senators",        senArr)
            );
        }

        // ── Compute summary stats ──
        int selfTurns = 0, senatorTurns = 0, totalSentences = 0;
        double totalConf = 0;
        for (ScoredTurn st : scored) {
            totalSentences += st.getSentenceCount();
            totalConf += st.getTarget().getConfidence();
            if (st.isSelfTurn()) selfTurns++;
            else senatorTurns++;
        }

        // ── Assemble root object ──
        JsonObject root = Json.createObjectBuilder()
            .add("metadata", Json.createObjectBuilder()
                .add("hearingFile",        Paths.get(inputFile).getFileName().toString())
                .add("scoredAt",           LocalDateTime.now().format(
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .add("parserModel",        "englishSR.beam.ser.gz")
                .add("totalTurns",         resolved.size())
                .add("scoredTurns",        scored.size())
                .add("senatorTurns",       senatorTurns)
                .add("selfTurns",          selfTurns)
                .add("skippedTurns",       resolved.size() - scored.size())
                .add("sentencesProcessed", totalSentences)
                .add("uniquePairs",        interactions.size())
                .add("avgConfidence",      round4(scored.isEmpty() ? 0.0
                    : totalConf / scored.size()))
                .add("scoringTimeSeconds", round4(elapsedMs / 1000.0))
            )
            .add("turns",        turnsArr)
            .add("interactions", interArr)
            .add("nominees",     nomineesArr)
            .build();

        // ── Write with pretty-printing ──
        Map<String, Object> writerConfig = new HashMap<>();
        writerConfig.put(JsonGenerator.PRETTY_PRINTING, true);
        JsonWriterFactory factory = Json.createWriterFactory(writerConfig);

        try (OutputStream os = Files.newOutputStream(jsonPath);
             JsonWriter writer = factory.createWriter(os)) {
            writer.writeObject(root);
        }
    }

    /** Round to 4 decimal places for JSON output. */
    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * Writes the human-readable text report to a file.
     * Re-uses the same print methods that render to console.
     */
    static void writeTextReport(Path txtPath, boolean verbose,
                                List<ResolvedTarget> resolved,
                                List<ScoredTurn> scored,
                                Map<String, InteractionScore> interactions,
                                long elapsedMs) throws IOException {
        try (PrintStream out = new PrintStream(
                Files.newOutputStream(txtPath), true,
                StandardCharsets.UTF_8.name())) {
            out.println("=".repeat(70));
            out.println("  Senate Hearing Sentiment Analysis — Full Pipeline");
            out.println("=".repeat(70));
            out.println();
            printTurnDetail(out, scored, verbose);
            printNomineeScorecard(out, interactions, scored);
            printSenatorProfile(out, interactions);
            printSummary(out, resolved, scored, interactions, elapsedMs);
        }
    }

    // ── Main: standalone runner ──────────────────────────────────────────

    public static void main(String[] args) throws IOException {

        // 1. Parse arguments
        boolean verbose = false;
        String filePath = null;
        String outputDir = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-v") || args[i].equals("--verbose")) {
                verbose = true;
            } else if (args[i].equals("-o") && i + 1 < args.length) {
                outputDir = args[++i];
            } else {
                filePath = args[i];
            }
        }

        // 2. Read input text
        String text;
        if (filePath != null) {
            text = new String(Files.readAllBytes(Paths.get(filePath)),
                              StandardCharsets.UTF_8);
        } else {
            System.err.println("Usage: java TurnScorer [-v] [-o outputDir] <transcript.txt>");
            System.err.println("  -v            Print per-turn detail");
            System.err.println("  -o <dir>      Write JSON + TXT reports to <dir>");
            System.exit(1);
            return;
        }

        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("  Senate Hearing Sentiment Analysis — Full Pipeline");
        System.out.println("=".repeat(70));
        System.out.println();

        // 3. Parse speaker turns
        System.out.println("[1/4] Parsing speaker turns...");
        String[] rawLines = text.split("\\r?\\n");
        List<String> lines = Arrays.asList(rawLines);
        List<SpeakerTurn> turns = SpeakerTurnParser.parse(lines);
        System.out.printf("       %d turns from %d lines%n", turns.size(), lines.size());

        // 4. Resolve targets
        System.out.println("[2/4] Resolving targets...");
        List<ResolvedTarget> resolved = TargetResolver.resolve(turns, lines);

        int selfCount = 0, specificCount = 0;
        for (ResolvedTarget rt : resolved) {
            if (rt.isSelfTurn()) selfCount++;
            else if (rt.hasSpecificTarget()) specificCount++;
        }
        System.out.printf("       %d self-turns, %d targeted, %d other%n",
            selfCount, specificCount, resolved.size() - selfCount - specificCount);

        // 5. Build CoreNLP pipeline (this is the slow step)
        System.out.println("[3/4] Loading CoreNLP models...");
        long pipelineStart = System.currentTimeMillis();

        Properties props = new Properties();
        // SR parser needs POS tags as input features
        props.setProperty("annotators", "tokenize,ssplit,pos,parse,sentiment");
        // Use Shift-Reduce constituency parser (O(n) linear time)
        // instead of default PCFG (O(n³)). Requires english extra models JAR.
        props.setProperty("parse.model",
            "edu/stanford/nlp/models/srparser/englishSR.beam.ser.gz");
        StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

        long pipelineMs = System.currentTimeMillis() - pipelineStart;
        System.out.printf("       Pipeline ready (%.1f seconds)%n", pipelineMs / 1000.0);

        // 6. Score each turn
        System.out.println("[4/4] Scoring turns through CoreNLP...");
        long scoreStart = System.currentTimeMillis();

        List<ScoredTurn> scored = new ArrayList<>();
        int processedCount = 0;

        for (ResolvedTarget rt : resolved) {
            ScoredTurn st = scoreTurn(rt, pipeline);
            if (st != null) {
                scored.add(st);
            }
            processedCount++;

            // Progress indicator every 25 turns
            if (processedCount % 25 == 0 || processedCount == resolved.size()) {
                System.out.printf("       %d / %d turns scored...%n",
                    processedCount, resolved.size());
                System.out.flush();
            }
        }

        long scoreMs = System.currentTimeMillis() - scoreStart;
        long totalMs = System.currentTimeMillis() - pipelineStart;
        System.out.printf("       Scoring done (%.1f seconds)%n", scoreMs / 1000.0);
        System.out.println();

        // 7. Aggregate
        Map<String, InteractionScore> interactions = aggregate(scored);

        // 8. Print to console
        printTurnDetail(System.out, scored, verbose);
        printNomineeScorecard(System.out, interactions, scored);
        printSenatorProfile(System.out, interactions);
        printSummary(System.out, resolved, scored, interactions, totalMs);

        // 9. Write output files if -o was specified
        if (outputDir != null) {
            Path outDir = Paths.get(outputDir);
            Files.createDirectories(outDir);

            String timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));

            Path jsonFile    = outDir.resolve("score_" + timestamp + ".json");
            Path txtFile     = outDir.resolve("score_" + timestamp + ".txt");
            Path jsonLatest  = outDir.resolve("score_latest.json");
            Path txtLatest   = outDir.resolve("score_latest.txt");

            writeJsonOutput(jsonFile, filePath, resolved, scored,
                            interactions, totalMs);
            writeTextReport(txtFile, verbose, resolved, scored,
                            interactions, totalMs);

            // Copy to _latest for easy access
            Files.copy(jsonFile, jsonLatest, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(txtFile,  txtLatest,  StandardCopyOption.REPLACE_EXISTING);

            System.out.println("=".repeat(70));
            System.out.println("  OUTPUT FILES");
            System.out.println("=".repeat(70));
            System.out.printf("  JSON: %s%n", jsonFile);
            System.out.printf("  TXT:  %s%n", txtFile);
            System.out.printf("  (also copied to score_latest.json / .txt)%n");
            System.out.println();
        }
    }
}
