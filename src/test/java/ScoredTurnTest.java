import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Collections;

/**
 * Unit tests for ScoredTurn — the core scoring math.
 *
 * The weighted score formula: weightedScore = avgScore * resolutionConfidence
 * avgScore = totalScore (sum of per-sentence [-2,+2] integers) / sentenceCount
 *
 * CoreNLP class index → integer score mapping (from TurnScorer.toScore):
 *   0 (Very Negative)  → -2
 *   1 (Negative)       → -1
 *   2 (Neutral)        →  0
 *   3 (Positive)       → +1
 *   4 (Very Positive)  → +2
 *
 * These tests exercise that math directly — no CoreNLP pipeline required.
 */
class ScoredTurnTest {

    // ── Fixtures ──────────────────────────────────────────────────────

    private static SpeakerTurn turn(String text) {
        return new SpeakerTurn("Senator", "Smith", 1, text, 0);
    }

    private static NomineeInfo nominee() {
        return new NomineeInfo("Jane", "Doe", "U.S. Circuit Judge", "Judge");
    }

    private static ResolvedTarget target(double confidence) {
        return new ResolvedTarget(
            turn("Some text."), nominee(),
            Collections.emptyList(),
            ResolvedTarget.Method.DIRECT_ADDRESS, confidence);
    }

    // ── avgScore calculation ──────────────────────────────────────────

    @Test
    void avgScore_dividesTotalScoreByCount() {
        ScoredTurn st = new ScoredTurn(target(1.0), 3, 3);
        // totalScore=3, count=3 → avg = 1.0
        assertEquals(1.0, st.getAvgScore(), 1e-10);
    }

    @Test
    void avgScore_negativeTotalYieldsNegativeAvg() {
        ScoredTurn st = new ScoredTurn(target(1.0), 3, -6);
        // totalScore=-6, count=3 → avg = -2.0
        assertEquals(-2.0, st.getAvgScore(), 1e-10);
    }

    @Test
    void avgScore_zeroWhenNoSentences() {
        // Guard: sentenceCount=0 must not throw and must return 0.0
        ScoredTurn st = new ScoredTurn(target(1.0), 0, 0);
        assertEquals(0.0, st.getAvgScore(), 1e-10);
    }

    @Test
    void avgScore_fractional_computedAsDouble() {
        // 3 sentences, totalScore=2 (e.g. +2, +2, -2) → avg = 0.666...
        ScoredTurn st = new ScoredTurn(target(1.0), 3, 2);
        assertEquals(2.0 / 3.0, st.getAvgScore(), 1e-10);
    }

    @Test
    void avgScore_singleSentence_equalsRawScore() {
        ScoredTurn positive = new ScoredTurn(target(1.0), 1, 2);
        assertEquals(2.0, positive.getAvgScore(), 1e-10);

        ScoredTurn negative = new ScoredTurn(target(1.0), 1, -2);
        assertEquals(-2.0, negative.getAvgScore(), 1e-10);
    }

    // ── weightedScore = avgScore * resolutionConfidence ───────────────

    @Test
    void weightedScore_fullConfidence_equalsAvgScore() {
        ScoredTurn st = new ScoredTurn(target(1.0), 2, 2);
        // avgScore = 1.0, confidence = 1.0 → weighted = 1.0
        assertEquals(1.0, st.getWeightedScore(), 1e-10);
    }

    @Test
    void weightedScore_halfConfidence_halvesTheScore() {
        ScoredTurn st = new ScoredTurn(target(0.5), 1, 2);
        // avgScore = 2.0, confidence = 0.5 → weighted = 1.0
        assertEquals(1.0, st.getWeightedScore(), 1e-10);
    }

    @Test
    void weightedScore_zeroConfidence_yieldsZero() {
        ScoredTurn st = new ScoredTurn(target(0.0), 1, 2);
        assertEquals(0.0, st.getWeightedScore(), 1e-10);
    }

    @Test
    void weightedScore_negativeAvg_remainsNegative() {
        ScoredTurn st = new ScoredTurn(target(0.9), 1, -2);
        // avgScore = -2.0, confidence = 0.9 → weighted = -1.8
        assertEquals(-1.8, st.getWeightedScore(), 1e-10);
    }

    @Test
    void weightedScore_highConfidenceAmplifies_comparedToLow() {
        ScoredTurn highConf = new ScoredTurn(target(0.95), 1, 2);
        ScoredTurn lowConf  = new ScoredTurn(target(0.30), 1, 2);
        // Same raw score, but high-confidence turn must carry more weight
        assertTrue(highConf.getWeightedScore() > lowConf.getWeightedScore());
    }

    @Test
    void weightedScore_preciseCalculation() {
        // 4 sentences, totalScore=3, confidence=0.8
        // avgScore = 0.75, weightedScore = 0.75 * 0.8 = 0.6
        ScoredTurn st = new ScoredTurn(target(0.8), 4, 3);
        assertEquals(0.6, st.getWeightedScore(), 1e-10);
    }

    // ── Sentiment confidence tracking ─────────────────────────────────

    @Test
    void sentimentConfidence_storedAndRetrieved() {
        ScoredTurn st = new ScoredTurn(target(1.0), 2, 2, 0.73);
        assertEquals(0.73, st.getAvgSentimentConfidence(), 1e-10);
    }

    @Test
    void sentimentConfidence_defaultsToOne() {
        // Three-arg constructor should default sentimentConfidence to 1.0
        ScoredTurn st = new ScoredTurn(target(1.0), 2, 2);
        assertEquals(1.0, st.getAvgSentimentConfidence(), 1e-10);
    }

    // ── Convenience delegation ────────────────────────────────────────

    @Test
    void isSelfTurn_delegatesToTarget() {
        ResolvedTarget selfTarget = new ResolvedTarget(
            turn("I am honored."), nominee(), Collections.emptyList(),
            ResolvedTarget.Method.SELF, 1.0);
        ScoredTurn st = new ScoredTurn(selfTarget, 1, 1);
        assertTrue(st.isSelfTurn());
    }

    @Test
    void hasSpecificTarget_delegatesToTarget() {
        ScoredTurn st = new ScoredTurn(target(0.9), 1, 1);
        assertTrue(st.hasSpecificTarget());
    }

    @Test
    void speakerLabel_delegatesToTurn() {
        ScoredTurn st = new ScoredTurn(target(1.0), 1, 1);
        assertEquals("Senator Smith", st.getSpeakerLabel());
    }
}
