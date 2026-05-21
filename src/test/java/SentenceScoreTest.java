import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Tests for SentenceScore and the per-sentence verification report formatting
 * produced by TurnScorer.printSentenceVerification().
 *
 * No CoreNLP pipeline required — all data is constructed directly.
 */
class SentenceScoreTest {

    // ── SentenceScore model ───────────────────────────────────────────────

    @Test
    void getLabel_returnsCorrectLabelForEachClassIndex() {
        assertEquals("Very Negative", new SentenceScore("", 0, -2, 0.9).getLabel());
        assertEquals("Negative",      new SentenceScore("", 1, -1, 0.9).getLabel());
        assertEquals("Neutral",       new SentenceScore("", 2,  0, 0.9).getLabel());
        assertEquals("Positive",      new SentenceScore("", 3,  1, 0.9).getLabel());
        assertEquals("Very Positive", new SentenceScore("", 4,  2, 0.9).getLabel());
    }

    @Test
    void getLabel_unknownClassIndex_returnsUnknown() {
        assertEquals("Unknown", new SentenceScore("", 5, 3, 0.5).getLabel());
        assertEquals("Unknown", new SentenceScore("", -1, -3, 0.5).getLabel());
    }

    @Test
    void nullText_isNormalizedToEmptyString() {
        SentenceScore ss = new SentenceScore(null, 2, 0, 0.5);
        assertNotNull(ss.getText());
        assertEquals("", ss.getText());
    }

    @Test
    void getters_returnConstructorValues() {
        SentenceScore ss = new SentenceScore("Test sentence.", 3, 1, 0.75);
        assertEquals("Test sentence.", ss.getText());
        assertEquals(3,    ss.getClassIndex());
        assertEquals(1,    ss.getScore());
        assertEquals(0.75, ss.getConfidence(), 1e-10);
    }

    // ── ScoredTurn sentenceScores integration ─────────────────────────────

    @Test
    void scoredTurn_threeArgConstructor_givesEmptySentenceScores() {
        ScoredTurn st = new ScoredTurn(target(1.0), 2, 2);
        assertNotNull(st.getSentenceScores());
        assertTrue(st.getSentenceScores().isEmpty());
    }

    @Test
    void scoredTurn_fourArgConstructor_givesEmptySentenceScores() {
        ScoredTurn st = new ScoredTurn(target(1.0), 2, 2, 0.8);
        assertTrue(st.getSentenceScores().isEmpty());
    }

    @Test
    void scoredTurn_fiveArgConstructor_storesSentenceScores() {
        List<SentenceScore> scores = List.of(
            new SentenceScore("Sentence one.", 3, 1, 0.80),
            new SentenceScore("Sentence two.", 1, -1, 0.70)
        );
        ScoredTurn st = new ScoredTurn(target(1.0), 2, 0, 0.75, scores);
        assertEquals(2, st.getSentenceScores().size());
        assertEquals("Sentence one.", st.getSentenceScores().get(0).getText());
    }

    @Test
    void scoredTurn_sentenceScoresList_isUnmodifiable() {
        List<SentenceScore> scores = new ArrayList<>();
        scores.add(new SentenceScore("One.", 2, 0, 0.5));
        ScoredTurn st = new ScoredTurn(target(1.0), 1, 0, 0.5, scores);
        assertThrows(UnsupportedOperationException.class,
            () -> st.getSentenceScores().add(new SentenceScore("Two.", 2, 0, 0.5)));
    }

    @Test
    void scoredTurn_nullSentenceScoresList_givesEmptyList() {
        ScoredTurn st = new ScoredTurn(target(1.0), 1, 0, 0.5, null);
        assertNotNull(st.getSentenceScores());
        assertTrue(st.getSentenceScores().isEmpty());
    }

    // ── Verification report formatting ────────────────────────────────────

    @Test
    void verificationReport_containsTurnHeaderWithAbbreviatedSpeaker() throws IOException {
        List<ScoredTurn> scored = List.of(scoredTurnWith(
            "Senator", "Smith", 5, 1.0,
            List.of(new SentenceScore("Good work.", 3, 1, 0.80))
        ));

        String report = captureVerification(scored);
        assertTrue(report.contains("Turn 5"), "should contain turn number");
        assertTrue(report.contains("Sen. Smith"), "Senator should be abbreviated to Sen.");
    }

    @Test
    void verificationReport_abbreviatesChairman() throws IOException {
        List<ScoredTurn> scored = List.of(scoredTurnWith(
            "Chairman", "Leahy", 1, 1.0,
            List.of(new SentenceScore("Let us proceed.", 2, 0, 0.55))
        ));

        String report = captureVerification(scored);
        assertTrue(report.contains("Chair. Leahy"), "Chairman should be abbreviated to Chair.");
    }

    @Test
    void verificationReport_formatsSentenceLineWithBracketAndConf() throws IOException {
        List<ScoredTurn> scored = List.of(scoredTurnWith(
            "Senator", "Jones", 3, 1.0,
            List.of(new SentenceScore("Impressive record.", 3, 1, 0.82))
        ));

        String report = captureVerification(scored);
        assertTrue(report.contains("[+1 Positive"), "positive sentence should show +1");
        assertTrue(report.contains("conf=0.82"), "confidence should appear");
        assertTrue(report.contains("Impressive record."), "sentence text should appear");
    }

    @Test
    void verificationReport_showsScoreDistributionHeader() throws IOException {
        List<ScoredTurn> scored = List.of(scoredTurnWith(
            "Senator", "Brown", 2, 0.9,
            List.of(
                new SentenceScore("A.", 0, -2, 0.6),
                new SentenceScore("B.", 2,  0, 0.5),
                new SentenceScore("C.", 4,  2, 0.9)
            )
        ));

        String report = captureVerification(scored);
        assertTrue(report.contains("Sentences scored:"), "should show sentence count");
        assertTrue(report.contains("Score distribution:"), "should show score distribution");
    }

    @Test
    void verificationReport_turnsWithEmptySentenceScores_areSkipped() throws IOException {
        ScoredTurn noSentences = new ScoredTurn(target(1.0), 2, 2);
        List<ScoredTurn> scored = List.of(noSentences);

        String report = captureVerification(scored);
        assertTrue(report.contains("Turns with sentence data:  0"), "should report zero turns");
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private static ResolvedTarget target(double conf) {
        SpeakerTurn turn = new SpeakerTurn("Senator", "Smith", 1, "Some text.", 0);
        NomineeInfo nom  = new NomineeInfo("Jane", "Doe", "U.S. Circuit Judge", "Judge");
        return new ResolvedTarget(turn, nom, Collections.emptyList(),
            ResolvedTarget.Method.DIRECT_ADDRESS, conf);
    }

    private static ScoredTurn scoredTurnWith(String title, String lastName,
                                              int turnNum, double conf,
                                              List<SentenceScore> sentences) {
        SpeakerTurn turn = new SpeakerTurn(title, lastName, turnNum, "Text.", 0);
        NomineeInfo nom  = new NomineeInfo("Jane", "Doe", "U.S. Circuit Judge", "Judge");
        ResolvedTarget rt = new ResolvedTarget(turn, nom, Collections.emptyList(),
            ResolvedTarget.Method.DIRECT_ADDRESS, conf);
        int total = sentences.stream().mapToInt(SentenceScore::getScore).sum();
        double avgConf = sentences.stream().mapToDouble(SentenceScore::getConfidence).average().orElse(0.0);
        return new ScoredTurn(rt, sentences.size(), total, avgConf, sentences);
    }

    private static String captureVerification(List<ScoredTurn> scored) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(baos, true, "UTF-8")) {
            TurnScorer.printSentenceVerification(out, scored);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
