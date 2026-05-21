import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.*;
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations;

import javax.json.*;
import javax.json.stream.JsonGenerator;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.ejml.simple.SimpleMatrix;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(TurnScorer.class);

    /**
     * Result object returned by non-CLI pipeline execution.
     */
    public static class RunResult {
        private final Path jsonFile;
        private final Path textFile;
        private final Path verificationFile;
        private final int totalTurns;
        private final int scoredTurns;
        private final int interactionPairs;
        private final boolean dbPersisted;
        private final String dbMessage;
        private final ScoringPersistence.PersistenceResult dbSummary;

        public RunResult(Path jsonFile,
                         Path textFile,
                         Path verificationFile,
                         int totalTurns,
                         int scoredTurns,
                         int interactionPairs,
                         boolean dbPersisted,
                         String dbMessage,
                         ScoringPersistence.PersistenceResult dbSummary) {
            this.jsonFile = jsonFile;
            this.textFile = textFile;
            this.verificationFile = verificationFile;
            this.totalTurns = totalTurns;
            this.scoredTurns = scoredTurns;
            this.interactionPairs = interactionPairs;
            this.dbPersisted = dbPersisted;
            this.dbMessage = dbMessage;
            this.dbSummary = dbSummary;
        }

        public Path getJsonFile() { return jsonFile; }
        public Path getTextFile() { return textFile; }
        public Path getVerificationFile() { return verificationFile; }
        public int getTotalTurns() { return totalTurns; }
        public int getScoredTurns() { return scoredTurns; }
        public int getInteractionPairs() { return interactionPairs; }
        public boolean isDbPersisted() { return dbPersisted; }
        public String getDbMessage() { return dbMessage; }
        public ScoringPersistence.PersistenceResult getDbSummary() { return dbSummary; }
    }

    /**
     * In-memory analysis output used for preview-before-save workflows.
     */
    public static class AnalysisBundle {
        private final String inputFile;
        private final List<String> lines;
        private final List<ResolvedTarget> resolved;
        private final List<ScoredTurn> scored;
        private final Map<String, InteractionScore> interactions;
        private final SpeakerAliasResolver.AliasMap aliases;
        private final long elapsedMs;

        public AnalysisBundle(String inputFile,
                              List<String> lines,
                              List<ResolvedTarget> resolved,
                              List<ScoredTurn> scored,
                              Map<String, InteractionScore> interactions,
                              SpeakerAliasResolver.AliasMap aliases,
                              long elapsedMs) {
            this.inputFile = inputFile;
            this.lines = lines;
            this.resolved = resolved;
            this.scored = scored;
            this.interactions = interactions;
            this.aliases = aliases;
            this.elapsedMs = elapsedMs;
        }

        public String getInputFile() { return inputFile; }
        public List<String> getLines() { return lines; }
        public List<ResolvedTarget> getResolved() { return resolved; }
        public List<ScoredTurn> getScored() { return scored; }
        public Map<String, InteractionScore> getInteractions() { return interactions; }
        public SpeakerAliasResolver.AliasMap getAliases() { return aliases; }
        public long getElapsedMs() { return elapsedMs; }
    }

    // ── Sentiment labels and mapping ─────────────────────────────────────

    private static final String[] LABELS = {
        "Very Negative", "Negative", "Neutral", "Positive", "Very Positive"
    };

    // CoreNLP sentiment class index runs 0–4; subtracting this offset maps it to [-2, +2].
    private static final int SENTIMENT_SCORE_OFFSET = 2;

    // Resolution-confidence thresholds for the reliability tier reported per interaction.
    private static final double HIGH_CONF_THRESHOLD = 0.85;
    private static final double MED_CONF_THRESHOLD  = 0.65;

    /** Maps CoreNLP's 0-4 class index to [-2, +2] integer score. */
    private static int toScore(int classIndex) {
        return classIndex - SENTIMENT_SCORE_OFFSET;
    }

    // ── Known senator titles (for filtering approval-relevant turns) ─────

    private static final Set<String> SENATOR_TITLES = Set.of(
        "Chairman", "The Chairman", "Senator", "Ranking Member", "Vice Chairman"
    );

    // ── Cached CoreNLP pipeline (built once, reused across all runs) ──────

    private static volatile StanfordCoreNLP scoringPipeline;
    private static final Object PIPELINE_LOCK = new Object();

    private static StanfordCoreNLP getScoringPipeline() {
        if (scoringPipeline == null) {
            synchronized (PIPELINE_LOCK) {
                if (scoringPipeline == null) {
                    Properties props = new Properties();
                    props.setProperty("annotators", "tokenize,ssplit,pos,parse,sentiment");
                    props.setProperty("parse.model",
                        "edu/stanford/nlp/models/srparser/englishSR.beam.ser.gz");
                    scoringPipeline = new StanfordCoreNLP(props);
                }
            }
        }
        return scoringPipeline;
    }

    // ── Data structures for aggregation ──────────────────────────────────

    /**
     * Holds aggregated scores for one (senator, nominee) interaction pair.
     * Tracks resolution confidence, NLP sentiment confidence, resolution
     * method breakdown, and original speaker labels (before alias merging)
     * so that all of this can be surfaced in reports and JSON output.
     */
    static class InteractionScore {
        String senatorLabel;     // canonical label after alias merging
        String nomineeLabel;
        int    turns;
        int    sentences;
        double totalWeighted;
        double totalRaw;
        double totalResolutionConf;
        double totalSentimentConf;
        final Map<String, Integer> methodCounts      = new LinkedHashMap<>();
        final Map<String, Integer> aliasLabelCounts  = new LinkedHashMap<>();

        InteractionScore(String senator, String nominee) {
            this.senatorLabel = senator;
            this.nomineeLabel = nominee;
        }

        void addTurn(ScoredTurn st) {
            turns++;
            sentences           += st.getSentenceCount();
            totalWeighted       += st.getWeightedScore();
            totalRaw            += st.getAvgScore();
            totalResolutionConf += st.getTarget().getConfidence();
            totalSentimentConf  += st.getAvgSentimentConfidence();
            methodCounts.merge(st.getTarget().getMethod().toString(), 1, Integer::sum);
            // Track original (pre-alias) label so aliases can be reported
            aliasLabelCounts.merge(st.getSpeakerLabel(), 1, Integer::sum);
        }

        double avgWeighted()       { return turns > 0 ? totalWeighted / turns : 0.0; }
        double avgRaw()            { return turns > 0 ? totalRaw / turns : 0.0; }
        double avgResolutionConf() { return turns > 0 ? totalResolutionConf / turns : 0.0; }
        double avgSentimentConf()  { return turns > 0 ? totalSentimentConf / turns : 0.0; }

        String reliabilityTier() {
            double r = avgResolutionConf();
            return r >= HIGH_CONF_THRESHOLD ? "HIGH" : r >= MED_CONF_THRESHOLD ? "MED" : "LOW";
        }

        /** Returns alias labels (non-canonical) that contributed turns to this interaction. */
        List<String> mergedAliasLabels() {
            List<String> aliases = new ArrayList<>();
            for (String label : aliasLabelCounts.keySet()) {
                if (!label.equals(senatorLabel)) aliases.add(label);
            }
            return aliases;
        }
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

        List<SentenceScore> sentenceScores = new ArrayList<>();
        int totalScore = 0;
        double totalSentimentConfidence = 0.0;
        for (CoreMap sentence : sentences) {
            Tree tree = sentence.get(
                SentimentCoreAnnotations.SentimentAnnotatedTree.class);
            int classIdx = RNNCoreAnnotations.getPredictedClass(tree);
            int sentScore = toScore(classIdx);
            totalScore += sentScore;

            SimpleMatrix predictions = RNNCoreAnnotations.getPredictions(tree);
            double sentenceConfidence = 0.0;
            if (predictions != null) {
                for (int idx = 0; idx < predictions.getNumElements(); idx++) {
                    sentenceConfidence = Math.max(sentenceConfidence, predictions.get(idx));
                }
            }
            totalSentimentConfidence += sentenceConfidence;

            String sentText = sentence.get(CoreAnnotations.TextAnnotation.class);
            if (sentText != null) sentText = sentText.replaceAll("\\s+", " ").trim();
            else sentText = "";
            sentenceScores.add(new SentenceScore(sentText, classIdx, sentScore, sentenceConfidence));
        }

        double avgSentimentConfidence = sentences.isEmpty()
            ? 0.0
            : totalSentimentConfidence / sentences.size();

        return new ScoredTurn(resolved, sentences.size(), totalScore, avgSentimentConfidence, sentenceScores);
    }

    // ── Report generation ────────────────────────────────────────────────

    /**
     * Aggregates scored turns by (senator, nominee) pairs.
     * Applies the alias map so that "Ranking Member Sessions" and
     * "Senator Sessions" are counted as a single senator entry.
     */
    static Map<String, InteractionScore> aggregate(List<ScoredTurn> scored,
                                                   SpeakerAliasResolver.AliasMap aliases) {
        Map<String, InteractionScore> map = new LinkedHashMap<>();

        for (ScoredTurn st : scored) {
            if (st.isSelfTurn()) continue;
            if (!st.hasSpecificTarget()) continue;

            String rawLabel = st.getSpeakerLabel();
            String senator  = aliases != null ? aliases.canonicalize(rawLabel) : rawLabel;
            String nominee  = st.getTarget().getNominee().getDisplayName();
            String key      = senator + "|" + nominee;

            map.computeIfAbsent(key, k -> new InteractionScore(senator, nominee))
               .addTurn(st);
        }

        return map;
    }

    /** Maps a weighted/avg score to a human-readable interpretation band. */
    static String interpretScore(double score) {
        if (score <= -1.00) return "Strongly opposing";
        if (score <= -0.25) return "Mildly opposing";
        if (score <   0.25) return "Neutral or mixed";
        if (score <   1.00) return "Mildly supportive";
        return "Strongly supportive";
    }

    /**
     * Prints the Nominee Scorecard with per-senator reliability tiers.
     */
    static void printNomineeScorecard(PrintStream out,
                                      Map<String, InteractionScore> interactions,
                                      List<ScoredTurn> scored) {
        out.println("=".repeat(70));
        out.println("  NOMINEE SCORECARD");
        out.println("=".repeat(70));
        out.println();

        Map<String, List<InteractionScore>> byNominee = new LinkedHashMap<>();
        for (InteractionScore is : interactions.values()) {
            byNominee.computeIfAbsent(is.nomineeLabel, k -> new ArrayList<>()).add(is);
        }

        for (Map.Entry<String, List<InteractionScore>> entry : byNominee.entrySet()) {
            List<InteractionScore> senators = entry.getValue();

            int    totalTurns = 0, totalSentences = 0;
            double totalWeighted = 0;
            for (InteractionScore is : senators) {
                totalTurns     += is.turns;
                totalSentences += is.sentences;
                totalWeighted  += is.totalWeighted;
            }
            double overallAvg = totalTurns > 0 ? totalWeighted / totalTurns : 0.0;

            out.printf("  %s%n", entry.getKey());
            out.println("  " + "-".repeat(66));
            out.printf("  Overall Approval Score: %+.2f  [%s]  (%d sentences from %d senator turns)%n",
                overallAvg, interpretScore(overallAvg), totalSentences, totalTurns);
            out.println();

            senators.sort(Comparator.comparingDouble(InteractionScore::avgWeighted));
            for (InteractionScore is : senators) {
                out.printf("    %-28s %+.2f  [%-20s]  ● %-4s  (%d turns)%n",
                    is.senatorLabel, is.avgWeighted(),
                    interpretScore(is.avgWeighted()),
                    is.reliabilityTier(), is.turns);
            }
            out.println();
        }
    }

    /**
     * Prints the Senator Voting Profile with inline confidence breakdown,
     * resolution method distribution, and alias merge notices.
     */
    static void printSenatorProfile(PrintStream out,
                                    Map<String, InteractionScore> interactions) {
        out.println("=".repeat(70));
        out.println("  SENATOR VOTING PROFILE");
        out.println("=".repeat(70));
        out.println();

        Map<String, List<InteractionScore>> bySenator = new LinkedHashMap<>();
        for (InteractionScore is : interactions.values()) {
            bySenator.computeIfAbsent(is.senatorLabel, k -> new ArrayList<>()).add(is);
        }

        for (Map.Entry<String, List<InteractionScore>> entry : bySenator.entrySet()) {
            String senator = entry.getKey();
            List<InteractionScore> nominees = entry.getValue();

            // Collect alias info across all this senator's interactions
            Map<String, Integer> allAliases = new LinkedHashMap<>();
            for (InteractionScore is : nominees) {
                for (String alias : is.mergedAliasLabels()) {
                    allAliases.merge(alias,
                        is.aliasLabelCounts.getOrDefault(alias, 0), Integer::sum);
                }
            }

            if (allAliases.isEmpty()) {
                out.printf("  %s%n", senator);
            } else {
                StringBuilder aliasNote = new StringBuilder();
                allAliases.forEach((alias, cnt) ->
                    aliasNote.append(alias).append(" (").append(cnt).append(" turns), "));
                String note = aliasNote.toString().replaceAll(", $", "");
                out.printf("  %s  [merged: %s]%n", senator, note);
            }
            out.println("  " + "-".repeat(66));

            nominees.sort(Comparator.comparingDouble(InteractionScore::avgWeighted));
            for (InteractionScore is : nominees) {
                out.printf("    → %-22s  %+.2f  [%-20s]  ● %s%n",
                    is.nomineeLabel, is.avgWeighted(),
                    interpretScore(is.avgWeighted()),
                    is.reliabilityTier());
                out.printf("      Confidence: res=%.2f  nlp=%.2f  |  %d turns · %d sentences%n",
                    is.avgResolutionConf(), is.avgSentimentConf(),
                    is.turns, is.sentences);
                // Method breakdown
                if (!is.methodCounts.isEmpty()) {
                    StringBuilder methods = new StringBuilder("      Methods: ");
                    is.methodCounts.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .forEach(e -> methods
                            .append(e.getValue()).append("×")
                            .append(friendlyMethod(e.getKey())).append("  "));
                    out.println(methods.toString().stripTrailing());
                }
            }
            out.println();
        }
    }

    private static String friendlyMethod(String method) {
        return switch (method) {
            case "DIRECT_ADDRESS"  -> "direct";
            case "RESPONSE_PAIR"   -> "response";
            case "PRIOR_CONTEXT"   -> "context";
            case "SECTION_DEFAULT" -> "section";
            case "SELF"            -> "self";
            default                -> method.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * Prints a compact alias merge report when aliases were found.
     */
    static void printAliasReport(PrintStream out,
                                 SpeakerAliasResolver.AliasMap aliases,
                                 List<SpeakerTurn> turns) {
        out.println("=".repeat(70));
        out.println("  SPEAKER ALIAS MERGES");
        out.println("=".repeat(70));
        out.println();
        out.println("  The following senator labels were merged into a single speaker.");
        out.println("  All turns are counted under the canonical label.");
        out.println();
        for (String canonical : aliases.getAllCanonicals()) {
            List<String> aliasLabels = aliases.getAliases(canonical);
            if (aliasLabels.isEmpty()) continue;
            int canonicalCount = (int) turns.stream()
                .filter(t -> t.getSpeakerLabel().equals(canonical)).count();
            out.printf("  %-30s  %d turns (canonical)%n", canonical, canonicalCount);
            for (String alias : aliasLabels) {
                int cnt = aliases.getAliasTurnCount(alias);
                out.printf("    └─ %-28s  %d turns absorbed%n", alias, cnt);
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
        double totalSentimentConfidence = 0;
        int confCount = 0;

        for (ScoredTurn st : scored) {
            totalSentences += st.getSentenceCount();
            if (st.isSelfTurn()) {
                selfTurns++;
            } else {
                scoredSenatorTurns++;
            }
            totalConfidence += st.getTarget().getConfidence();
            totalSentimentConfidence += st.getAvgSentimentConfidence();
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
        out.printf("  Avg sentiment confidence:     %.2f%n",
            confCount > 0 ? totalSentimentConfidence / confCount : 0.0);
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

        JsonObject root = buildJsonObject(inputFile, resolved, scored, interactions, elapsedMs);

        // ── Write with pretty-printing ──
        Map<String, Object> writerConfig = new HashMap<>();
        writerConfig.put(JsonGenerator.PRETTY_PRINTING, true);
        JsonWriterFactory factory = Json.createWriterFactory(writerConfig);

        try (OutputStream os = Files.newOutputStream(jsonPath);
             JsonWriter writer = factory.createWriter(os)) {
            writer.writeObject(root);
        }
    }

    static String buildJsonPreview(AnalysisBundle analysis) {
        JsonObject root = buildJsonObject(
            analysis.getInputFile(),
            analysis.getResolved(),
            analysis.getScored(),
            analysis.getInteractions(),
            analysis.getElapsedMs()
        );

        Map<String, Object> writerConfig = new HashMap<>();
        writerConfig.put(JsonGenerator.PRETTY_PRINTING, true);
        JsonWriterFactory factory = Json.createWriterFactory(writerConfig);

        StringWriter sw = new StringWriter();
        try (JsonWriter writer = factory.createWriter(sw)) {
            writer.writeObject(root);
        }
        return sw.toString();
    }

    static String buildTextPreview(AnalysisBundle analysis, boolean verbose) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(baos, true, "UTF-8")) {
            out.println("=".repeat(70));
            out.println("  Senate Hearing Sentiment Analysis — Full Pipeline");
            out.println("=".repeat(70));
            out.println();
            printScoreKey(out);
            printTurnDetail(out, analysis.getScored(), verbose);
            printNomineeScorecard(out, analysis.getInteractions(), analysis.getScored());
            printSenatorProfile(out, analysis.getInteractions());
            printSummary(out, analysis.getResolved(), analysis.getScored(), analysis.getInteractions(), analysis.getElapsedMs());
            if (analysis.getAliases() != null && analysis.getAliases().hasAnyAliases()) {
                List<SpeakerTurn> turns = new ArrayList<>();
                for (ResolvedTarget rt : analysis.getResolved()) turns.add(rt.getTurn());
                printAliasReport(out, analysis.getAliases(), turns);
            }
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    // ── Per-sentence verification report ─────────────────────────────────

    /**
     * Abbreviates verbose senator titles for the verification report header line.
     * Keeps the file readable when turns are densely packed.
     */
    private static String abbreviateSpeakerLabel(String label) {
        if (label == null) return "";
        return label
            .replaceFirst("^The Chairman\\b", "Chair.")
            .replaceFirst("^Chairman\\b",     "Chair.")
            .replaceFirst("^Senator\\b",      "Sen.");
    }

    /**
     * Formats one sentence line: score bracket, confidence, and text.
     * Example:  [-1 Negative     ] conf=0.62  I have grave concerns…
     */
    private static String formatSentenceLine(SentenceScore ss) {
        String scoreStr = ss.getScore() > 0
            ? "+" + ss.getScore()
            : (ss.getScore() == 0 ? " 0" : Integer.toString(ss.getScore()));
        return String.format("  [%s %-13s] conf=%.2f  %s",
            scoreStr, ss.getLabel(), ss.getConfidence(), ss.getText());
    }

    /**
     * Prints the per-sentence verification report to the given stream.
     * Grouped by scored turn, with a summary header showing score-band distribution.
     */
    static void printSentenceVerification(PrintStream out, List<ScoredTurn> scored) {
        out.println("=".repeat(70));
        out.println("  PER-SENTENCE SCORING DETAIL  (manual verification reference)");
        out.println("=".repeat(70));
        out.println("Score key:  -2 Very Negative  -1 Negative   0 Neutral  +1 Positive  +2 Very Positive");
        out.println();

        // Compute distribution across all turns
        int totalSentences = 0;
        int turnsWithData = 0;
        int[] bandCounts = new int[5]; // index 0 = score -2, index 4 = score +2
        for (ScoredTurn st : scored) {
            if (st.getSentenceScores().isEmpty()) continue;
            turnsWithData++;
            for (SentenceScore ss : st.getSentenceScores()) {
                totalSentences++;
                int band = ss.getScore() + 2; // map [-2,+2] → [0,4]
                if (band >= 0 && band < 5) bandCounts[band]++;
            }
        }
        out.printf("Turns with sentence data:  %d%n", turnsWithData);
        out.printf("Sentences scored:          %d%n", totalSentences);
        out.printf("Score distribution:   -2: %3d  -1: %3d   0: %3d  +1: %3d  +2: %3d%n",
            bandCounts[0], bandCounts[1], bandCounts[2], bandCounts[3], bandCounts[4]);
        out.println();

        for (ScoredTurn st : scored) {
            if (st.getSentenceScores().isEmpty()) continue;
            ResolvedTarget rt = st.getTarget();
            SpeakerTurn turn  = rt.getTurn();

            String tgtStr = rt.isSelfTurn() ? "SELF"
                : rt.hasSpecificTarget()
                    ? rt.getNominee().getDisplayName() + " (" + friendlyMethod(rt.getMethod().toString()) + ")"
                    : rt.getTargetLabel() + " (" + friendlyMethod(rt.getMethod().toString()) + ")";

            out.println("-".repeat(70));
            out.printf("Turn %d | SPK %s | TGT %s%n",
                turn.getTurnNumber(),
                abbreviateSpeakerLabel(turn.getSpeakerLabel()),
                tgtStr);

            for (SentenceScore ss : st.getSentenceScores()) {
                out.println(formatSentenceLine(ss));
            }
            out.println();
        }
    }

    static void writeSentenceVerificationReport(Path path, List<ScoredTurn> scored)
            throws IOException {
        try (PrintStream out = new PrintStream(
                Files.newOutputStream(path), true, "UTF-8")) {
            printSentenceVerification(out, scored);
        }
    }

    static String buildVerificationPreview(AnalysisBundle analysis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(baos, true, "UTF-8")) {
            printSentenceVerification(out, analysis.getScored());
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static void printScoreKey(PrintStream out) {
        out.println("SCORE KEY");
        out.println("-".repeat(70));
        out.println("CoreNLP class -> project score mapping:");
        out.println("  0 Very Negative -> -2");
        out.println("  1 Negative      -> -1");
        out.println("  2 Neutral       ->  0");
        out.println("  3 Positive      -> +1");
        out.println("  4 Very Positive -> +2");
        out.println();
        out.println("Interpretation (avg/weighted scores):");
        out.println("  <= -1.00         Strongly opposing");
        out.println("  -1.00..-0.25     Mildly opposing");
        out.println("  -0.25..+0.25     Neutral or mixed");
        out.println("  +0.25..+1.00     Mildly supportive");
        out.println("  >= +1.00         Strongly supportive");
        out.println();
    }

    private static JsonObject buildJsonObject(String inputFile,
                                              List<ResolvedTarget> resolved,
                                              List<ScoredTurn> scored,
                                              Map<String, InteractionScore> interactions,
                                              long elapsedMs) {

        // ── Build turn-level array ──
        JsonArrayBuilder turnsArr = Json.createArrayBuilder();
        for (ScoredTurn st : scored) {
            ResolvedTarget rt = st.getTarget();
            SpeakerTurn turn = rt.getTurn();
            String targetName = rt.isSelfTurn() ? "SELF" :
                rt.hasSpecificTarget() ? rt.getNominee().getDisplayName() :
                    rt.getTargetLabel();

            turnsArr.add(Json.createObjectBuilder()
                .add("turnNumber",        turn.getTurnNumber())
                .add("speaker",           turn.getSpeakerLabel())
                .add("target",            targetName)
                .add("resolutionMethod",  rt.getMethod().toString())
                .add("confidence",        round4(rt.getConfidence()))
                .add("sentimentConfidence", round4(st.getAvgSentimentConfidence()))
                .add("sentenceCount",     st.getSentenceCount())
                .add("totalScore",        st.getTotalScore())
                .add("avgScore",          round4(st.getAvgScore()))
                .add("weightedScore",     round4(st.getWeightedScore()))
            );
        }

        // ── Build interaction-level array ──
        JsonArrayBuilder interArr = Json.createArrayBuilder();
        for (InteractionScore is : interactions.values()) {
            JsonArrayBuilder aliasArr = Json.createArrayBuilder();
            is.mergedAliasLabels().forEach(aliasArr::add);

            JsonObjectBuilder methodBreakdown = Json.createObjectBuilder();
            is.methodCounts.forEach(methodBreakdown::add);

            interArr.add(Json.createObjectBuilder()
                .add("senator",                  is.senatorLabel)
                .add("nominee",                  is.nomineeLabel)
                .add("turns",                    is.turns)
                .add("sentences",                is.sentences)
                .add("avgWeightedScore",         round4(is.avgWeighted()))
                .add("avgRawScore",              round4(is.avgRaw()))
                .add("totalWeighted",            round4(is.totalWeighted))
                .add("sentimentInterpretation",  interpretScore(is.avgWeighted()))
                .add("avgResolutionConfidence",  round4(is.avgResolutionConf()))
                .add("avgSentimentConfidence",   round4(is.avgSentimentConf()))
                .add("reliabilityTier",          is.reliabilityTier())
                .add("aliasesMerged",            aliasArr)
                .add("resolutionMethodBreakdown", methodBreakdown)
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
                JsonArrayBuilder aliasArr = Json.createArrayBuilder();
                is.mergedAliasLabels().forEach(aliasArr::add);
                senArr.add(Json.createObjectBuilder()
                    .add("senator",               is.senatorLabel)
                    .add("score",                 round4(is.avgWeighted()))
                    .add("sentimentInterpretation", interpretScore(is.avgWeighted()))
                    .add("reliabilityTier",       is.reliabilityTier())
                    .add("avgResolutionConfidence", round4(is.avgResolutionConf()))
                    .add("avgSentimentConfidence",  round4(is.avgSentimentConf()))
                    .add("turns",                 is.turns)
                    .add("sentences",             is.sentences)
                    .add("aliasesMerged",         aliasArr)
                );
            }

            double overallApproval = totalTurns > 0 ? totalWeighted / totalTurns : 0.0;
            nomineesArr.add(Json.createObjectBuilder()
                .add("nominee",                 nominee)
                .add("overallApproval",         round4(overallApproval))
                .add("sentimentInterpretation", interpretScore(overallApproval))
                .add("totalTurns",              totalTurns)
                .add("totalSentences",          totalSentences)
                .add("senators",                senArr)
            );
        }

        // ── Compute summary stats ──
        int selfTurns = 0, senatorTurns = 0, totalSentences = 0;
        double totalConf = 0;
        double totalSentimentConf = 0;
        for (ScoredTurn st : scored) {
            totalSentences += st.getSentenceCount();
            totalConf += st.getTarget().getConfidence();
            totalSentimentConf += st.getAvgSentimentConfidence();
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
                .add("scoreKey", Json.createObjectBuilder()
                    .add("classToScore", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder().add("class", 0).add("label", "Very Negative").add("score", -2))
                        .add(Json.createObjectBuilder().add("class", 1).add("label", "Negative").add("score", -1))
                        .add(Json.createObjectBuilder().add("class", 2).add("label", "Neutral").add("score", 0))
                        .add(Json.createObjectBuilder().add("class", 3).add("label", "Positive").add("score", 1))
                        .add(Json.createObjectBuilder().add("class", 4).add("label", "Very Positive").add("score", 2))
                    )
                    .add("interpretationBands", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder().add("min", -2.0).add("max", -1.0).add("meaning", "Strongly opposing"))
                        .add(Json.createObjectBuilder().add("min", -1.0).add("max", -0.25).add("meaning", "Mildly opposing"))
                        .add(Json.createObjectBuilder().add("min", -0.25).add("max", 0.25).add("meaning", "Neutral or mixed"))
                        .add(Json.createObjectBuilder().add("min", 0.25).add("max", 1.0).add("meaning", "Mildly supportive"))
                        .add(Json.createObjectBuilder().add("min", 1.0).add("max", 2.0).add("meaning", "Strongly supportive"))
                    )
                )
                .add("totalTurns",         resolved.size())
                .add("scoredTurns",        scored.size())
                .add("senatorTurns",       senatorTurns)
                .add("selfTurns",          selfTurns)
                .add("skippedTurns",       resolved.size() - scored.size())
                .add("sentencesProcessed", totalSentences)
                .add("uniquePairs",        interactions.size())
                .add("avgConfidence",      round4(scored.isEmpty() ? 0.0
                    : totalConf / scored.size()))
                .add("avgSentimentConfidence", round4(scored.isEmpty() ? 0.0
                    : totalSentimentConf / scored.size()))
                .add("scoringTimeSeconds", round4(elapsedMs / 1000.0))
            )
            .add("turns",        turnsArr)
            .add("interactions", interArr)
            .add("nominees",     nomineesArr)
            .build();

        return root;
    }

    /** Round to 4 decimal places for JSON output. */
    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * Build a stable, file-system-safe label from the input transcript name.
     * Example: hearing_text.txt -> hearing_text
     */
    private static String buildInputLabel(String inputPath) {
        String fileName = Paths.get(inputPath).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String sanitized = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "input" : sanitized;
    }

    /**
     * Writes the human-readable text report to a file.
     * Re-uses the same print methods that render to console.
     */
    static void writeTextReport(Path txtPath, boolean verbose,
                                List<ResolvedTarget> resolved,
                                List<ScoredTurn> scored,
                                Map<String, InteractionScore> interactions,
                                SpeakerAliasResolver.AliasMap aliases,
                                long elapsedMs) throws IOException {
        try (PrintStream out = new PrintStream(
                Files.newOutputStream(txtPath), true, "UTF-8")) {
            out.println("=".repeat(70));
            out.println("  Senate Hearing Sentiment Analysis — Full Pipeline");
            out.println("=".repeat(70));
            out.println();
            printScoreKey(out);
            printTurnDetail(out, scored, verbose);
            printNomineeScorecard(out, interactions, scored);
            printSenatorProfile(out, interactions);
            printSummary(out, resolved, scored, interactions, elapsedMs);
            if (aliases != null && aliases.hasAnyAliases()) {
                List<SpeakerTurn> turns = new ArrayList<>();
                for (ResolvedTarget rt : resolved) turns.add(rt.getTurn());
                printAliasReport(out, aliases, turns);
            }
        }
    }

    // ── Main: standalone runner ──────────────────────────────────────────

    public static AnalysisBundle analyzeOnly(String filePath,
                                             boolean verbose,
                                             boolean printConsole) throws IOException {

        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("Input transcript file is required.");
        }

        // 1. Read input text (supports .txt and .docx)
        String text = loadTranscriptText(filePath);

        if (printConsole) {
            System.out.println();
            System.out.println("=".repeat(70));
            System.out.println("  Senate Hearing Sentiment Analysis — Full Pipeline");
            System.out.println("=".repeat(70));
            System.out.println();
        }

        // 3. Parse speaker turns
        if (printConsole) {
            System.out.println("[1/4] Parsing speaker turns...");
        }
        String[] rawLines = text.split("\\r?\\n");
        List<String> lines = Arrays.asList(rawLines);
        List<SpeakerTurn> turns = SpeakerTurnParser.parse(lines);
        if (turns.isEmpty()) {
            throw new IOException(
                "No speaker turns were parsed from input file: "
                    + Paths.get(filePath).getFileName()
                    + ". Check the transcript format and ensure it contains speaker-labeled text."
            );
        }
        if (printConsole) {
            System.out.printf("       %d turns from %d lines%n", turns.size(), lines.size());
        }

        // 4. Resolve targets
        if (printConsole) {
            System.out.println("[2/4] Resolving targets...");
        }
        List<ResolvedTarget> resolved = TargetResolver.resolve(turns, lines);

        int selfCount = 0, specificCount = 0;
        for (ResolvedTarget rt : resolved) {
            if (rt.isSelfTurn()) selfCount++;
            else if (rt.hasSpecificTarget()) specificCount++;
        }
        if (printConsole) {
            System.out.printf("       %d self-turns, %d targeted, %d other%n",
                selfCount, specificCount, resolved.size() - selfCount - specificCount);
            if (specificCount == 0) {
                System.out.println("[WARN] No specific nominee targets were resolved.");
                System.out.println("       Reports may contain little/no senator->nominee interaction data.");
                System.out.println("       Use a transcript that includes NOMINATIONS headers or full hearing context.");
            }
        }

        // 5. Get (or build on first run) the cached CoreNLP pipeline
        boolean pipelineCached = scoringPipeline != null;
        if (printConsole) {
            System.out.println(pipelineCached
                ? "[3/4] CoreNLP pipeline ready (cached)."
                : "[3/4] Loading CoreNLP models (first run — this takes ~60s)...");
        }
        long pipelineStart = System.currentTimeMillis();
        StanfordCoreNLP pipeline = getScoringPipeline();
        if (printConsole && !pipelineCached) {
            System.out.printf("       Pipeline ready (%.1f seconds)%n",
                (System.currentTimeMillis() - pipelineStart) / 1000.0);
        }

        // 6. Score each turn
        if (printConsole) {
            System.out.println("[4/4] Scoring turns through CoreNLP...");
        }
        long scoreStart = System.currentTimeMillis();

        AtomicInteger processedCount = new AtomicInteger(0);
        int total = resolved.size();

        List<ScoredTurn> scored = resolved.parallelStream()
            .map(rt -> {
                ScoredTurn st = scoreTurn(rt, pipeline);
                int n = processedCount.incrementAndGet();
                if (printConsole && (n % 25 == 0 || n == total)) {
                    System.out.printf("       %d / %d turns scored...%n", n, total);
                    System.out.flush();
                }
                return st;
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        long scoreMs = System.currentTimeMillis() - scoreStart;
        long totalMs = System.currentTimeMillis() - pipelineStart;

        if (scored.isEmpty()) {
            throw new IOException(
                "No substantive turns were scored from input file: "
                    + Paths.get(filePath).getFileName()
                    + ". Check transcript formatting and speaker labels before committing output."
            );
        }

        if (printConsole) {
            System.out.printf("       Scoring done (%.1f seconds)%n", scoreMs / 1000.0);
            System.out.println();
        }

        // 7. Build alias map and aggregate
        SpeakerAliasResolver.AliasMap aliases = SpeakerAliasResolver.resolve(turns);
        Map<String, InteractionScore> interactions = aggregate(scored, aliases);

        // 8. Print to console
        if (printConsole) {
            printTurnDetail(System.out, scored, verbose);
            printNomineeScorecard(System.out, interactions, scored);
            printSenatorProfile(System.out, interactions);
            printSummary(System.out, resolved, scored, interactions, totalMs);
            if (aliases.hasAnyAliases()) {
                printAliasReport(System.out, aliases, turns);
            }
        }

        return new AnalysisBundle(filePath, lines, resolved, scored, interactions, aliases, totalMs);
    }

    private static String loadTranscriptText(String filePath) throws IOException {
        Path inputPath = Paths.get(filePath);
        String fileName = inputPath.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".txt")) {
            String text = Files.readString(inputPath, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                throw new IOException("Input text file is empty: " + fileName);
            }
            return text;
        }

        if (lower.endsWith(".docx")) {
            try (InputStream is = Files.newInputStream(inputPath);
                 XWPFDocument document = new XWPFDocument(is);
                 XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                String text = extractor.getText();
                if (text == null || text.isBlank()) {
                    throw new IOException("Input DOCX file has no extractable text: " + fileName);
                }
                return text;
            }
        }

        throw new IOException(
            "Unsupported input format for "
                + fileName
                + ". Supported formats are .txt and .docx"
        );
    }

    public static RunResult finalizeRun(AnalysisBundle analysis,
                                        String outputDir,
                                        boolean verbose,
                                        boolean persistDb) throws IOException {
        if (analysis == null) {
            throw new IllegalArgumentException("Analysis bundle is required.");
        }

        List<ResolvedTarget> resolved = analysis.getResolved();
        List<ScoredTurn> scored = analysis.getScored();
        Map<String, InteractionScore> interactions = analysis.getInteractions();
        long totalMs = analysis.getElapsedMs();
        String filePath = analysis.getInputFile();
        List<String> lines = analysis.getLines();

        Path jsonFile = null;
        Path txtFile = null;
        Path verFile = null;
        Path pendingJsonFile = null;
        Path pendingTxtFile = null;
        Path pendingVerFile = null;

        // 9. Write output files if output directory was specified
        if (outputDir != null) {
            Path outDir = Paths.get(outputDir);
            Files.createDirectories(outDir);

            String timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
            String inputLabel = buildInputLabel(filePath);
            String scorePrefix = "score_" + inputLabel + "_" + timestamp;

            jsonFile = outDir.resolve(scorePrefix + ".json");
            txtFile  = outDir.resolve(scorePrefix + ".txt");
            verFile  = outDir.resolve(scorePrefix + "_sentences.txt");

            Path jsonWriteTarget = jsonFile;
            Path txtWriteTarget  = txtFile;
            Path verWriteTarget  = verFile;
            if (persistDb) {
                pendingJsonFile = outDir.resolve(scorePrefix + ".json.pending");
                pendingTxtFile  = outDir.resolve(scorePrefix + ".txt.pending");
                pendingVerFile  = outDir.resolve(scorePrefix + "_sentences.txt.pending");
                jsonWriteTarget = pendingJsonFile;
                txtWriteTarget  = pendingTxtFile;
                verWriteTarget  = pendingVerFile;
            }

            writeJsonOutput(jsonWriteTarget, filePath, resolved, scored,
                            interactions, totalMs);
            writeTextReport(txtWriteTarget, verbose, resolved, scored,
                            interactions, analysis.getAliases(), totalMs);
            writeSentenceVerificationReport(verWriteTarget, scored);
        }

        // 10. Persist to database
        boolean dbPersisted = false;
        String dbMessage = "DB persistence disabled for this run.";
        ScoringPersistence.PersistenceResult dbSummary = null;
        if (persistDb) {
            try {
                System.out.println("[DB] Persisting scoring run to MySQL...");
                dbSummary = ScoringPersistence.persistRun(
                    filePath,
                    lines,
                    resolved,
                    scored,
                    interactions,
                    totalMs
                );
                System.out.println("[DB] Persistence complete.");
                System.out.println();
                dbPersisted = true;
                dbMessage = "Persistence complete (hearing="
                    + dbSummary.getHearingId()
                    + ", run="
                    + dbSummary.getScoringRunId()
                    + ").";

                if (pendingJsonFile != null && pendingTxtFile != null) {
                    promotePendingOutput(pendingJsonFile, jsonFile);
                    promotePendingOutput(pendingTxtFile, txtFile);
                    promotePendingOutput(pendingVerFile, verFile);
                }
            } catch (Throwable t) {
                LOG.error("DB persistence failed: {}", t.getMessage(), t);
                deleteIfExistsQuietly(pendingJsonFile);
                deleteIfExistsQuietly(pendingTxtFile);
                deleteIfExistsQuietly(pendingVerFile);
                jsonFile = null;
                txtFile  = null;
                verFile  = null;
                dbMessage = "Persistence failed: " + t.getMessage();
                if (t instanceof VirtualMachineError) {
                    throw (VirtualMachineError) t;
                }
            }
        } else {
            dbMessage = "DB persistence skipped (--no-db).";
        }

        if (jsonFile != null && txtFile != null) {
            printOutputFilesBanner(jsonFile, txtFile, verFile);
        }

        return new RunResult(
            jsonFile,
            txtFile,
            verFile,
            resolved.size(),
            scored.size(),
            interactions.size(),
            dbPersisted,
            dbMessage,
            dbSummary
        );
    }

    private static void promotePendingOutput(Path pending, Path target) throws IOException {
        if (pending == null || target == null || !Files.exists(pending)) return;
        Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteIfExistsQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup for temporary files.
        }
    }

    private static void printOutputFilesBanner(Path jsonFile, Path txtFile, Path verFile) {
        System.out.println("=".repeat(70));
        System.out.println("  OUTPUT FILES");
        System.out.println("=".repeat(70));
        System.out.printf("  JSON: %s%n", jsonFile);
        System.out.printf("  TXT:  %s%n", txtFile);
        if (verFile != null) System.out.printf("  VER:  %s%n", verFile);
        System.out.printf("  Naming: score_<inputLabel>_<timestamp>.{json,txt,_sentences.txt}%n");
        System.out.println();
    }

    public static RunResult runPipeline(String filePath,
                                        String outputDir,
                                        boolean verbose,
                                        boolean persistDb) throws IOException {
        AnalysisBundle analysis = analyzeOnly(filePath, verbose, true);
        return finalizeRun(analysis, outputDir, verbose, persistDb);
    }

    public static void main(String[] args) throws IOException {

        // Parse CLI arguments and delegate to reusable runner.
        boolean verbose = false;
        boolean persistDb = true;
        String filePath = null;
        String outputDir = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-v") || args[i].equals("--verbose")) {
                verbose = true;
            } else if (args[i].equals("--no-db")) {
                persistDb = false;
            } else if (args[i].equals("-o") && i + 1 < args.length) {
                outputDir = args[++i];
            } else {
                filePath = args[i];
            }
        }

        if (filePath == null) {
            System.err.println("Usage: java TurnScorer [-v] [-o outputDir] [--no-db] <transcript.txt>");
            System.err.println("  -v            Print per-turn detail");
            System.err.println("  -o <dir>      Write JSON + TXT reports to <dir>");
            System.err.println("  --no-db       Disable MySQL persistence for this run");
            System.exit(1);
            return;
        }

        runPipeline(filePath, outputDir, verbose, persistDb);
    }
}
