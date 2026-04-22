import org.junit.jupiter.api.Test;

/**
 * Unit tests for TurnScorer.
 * Covers: score mapping, weighted score formula, aggregation correctness.
 *
 * Note: tests that require the full CoreNLP pipeline (lazy-loaded, ~67s)
 * should be kept in a separate integration test or tagged @Tag("slow").
 */
class TurnScorerTest {

    // ── Score mapping ─────────────────────────────────────────────────

    @Test
    void veryNegativeSentimentMapsToMinusTwo() {
        // CoreNLP class index 0 (Very Negative) → toScore(0 - 2) = -2
        // TODO: expose or call the toScore() helper and assert result == -2.0
    }

    @Test
    void veryPositiveSentimentMapsToPlusTwo() {
        // CoreNLP class index 4 (Very Positive) → toScore(4 - 2) = +2
        // TODO: assert result == 2.0
    }

    @Test
    void neutralSentimentMapsToZero() {
        // CoreNLP class index 2 (Neutral) → toScore(2 - 2) = 0
        // TODO: assert result == 0.0
    }

    // ── Weighted score formula ────────────────────────────────────────

    @Test
    void weightedScoreIsAvgScoreTimesConfidence() {
        // weightedScore = avgScore * resolutionConfidence
        // TODO: construct a ScoredTurn with known avgScore and confidence,
        //       call getWeightedScore(), assert the product is correct.
    }

    @Test
    void zeroConfidenceProducesZeroWeightedScore() {
        // TODO: verify a turn with confidence 0.0 produces weightedScore == 0.0
    }

    // ── Interaction aggregation ───────────────────────────────────────

    @Test
    void multipleScoresForSamePairAggregateCorrectly() {
        // TODO: create two ScoredTurns for the same senator|nominee pair,
        //       run aggregateByPair(), and verify turnCount == 2 and
        //       totalWeightedScore is the sum of both weighted scores.
    }

    @Test
    void differentPairsProduceSeparateEntries() {
        // TODO: create turns for two distinct senator|nominee pairs and verify
        //       the resulting interaction map has exactly 2 entries.
    }

    // ── Pipeline guards ───────────────────────────────────────────────

    @Test
    void emptyTranscriptThrowsOrReturnsEmptyBundle() {
        // TODO: call analyzeOnly() with an empty string and verify the pipeline
        //       aborts gracefully (exception or zero-turn AnalysisBundle).
    }
}
