/**
 * ScoredTurn.java
 *
 * Pairs a resolved speaker turn with its CoreNLP sentiment scores.
 * Each turn's text is scored sentence-by-sentence; the turn-level score
 * is the mean of those sentence scores on a [-2, +2] scale.
 *
 * <p>The {@link #getWeightedScore()} method multiplies the average
 * sentiment by the target-resolution confidence, so that high-confidence
 * pairings (direct address, response pair) weigh more than ambiguous
 * section-default assignments.</p>
 */
public class ScoredTurn {

    private final ResolvedTarget target;
    private final int    sentenceCount;   // how many sentences CoreNLP found
    private final int    totalScore;      // sum of per-sentence [-2,+2] scores
    private final double avgScore;        // totalScore / sentenceCount
    private final double avgSentimentConfidence;

    public ScoredTurn(ResolvedTarget target, int sentenceCount,
                      int totalScore) {
        this(target, sentenceCount, totalScore, 1.0);
    }

    public ScoredTurn(ResolvedTarget target,
                      int sentenceCount,
                      int totalScore,
                      double avgSentimentConfidence) {
        this.target        = target;
        this.sentenceCount = sentenceCount;
        this.totalScore    = totalScore;
        this.avgScore      = sentenceCount > 0
            ? (double) totalScore / sentenceCount
            : 0.0;
        this.avgSentimentConfidence = avgSentimentConfidence;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public ResolvedTarget getTarget()        { return target; }
    public int            getSentenceCount() { return sentenceCount; }
    public int            getTotalScore()    { return totalScore; }
    public double         getAvgScore()      { return avgScore; }
    public double         getAvgSentimentConfidence() { return avgSentimentConfidence; }

    /**
     * Returns the average score weighted by target-resolution confidence.
     * Range: approximately [-2.0, +2.0] but damped by confidence.
     */
    public double getWeightedScore() {
        return avgScore * target.getConfidence();
    }

    /** Convenience: the speaker label (e.g., "Senator Sessions"). */
    public String getSpeakerLabel() {
        return target.getTurn().getSpeakerLabel();
    }

    /** Convenience: whether this is a nominee's own turn (SELF). */
    public boolean isSelfTurn() {
        return target.isSelfTurn();
    }

    /** Convenience: whether this turn has a specific nominee target. */
    public boolean hasSpecificTarget() {
        return target.hasSpecificTarget();
    }

    @Override
    public String toString() {
        return String.format("Turn %d [%s] avg=%.2f weighted=%.2f (%d sentences) %s",
            target.getTurn().getTurnNumber(),
            target.getTurn().getSpeakerLabel(),
            avgScore, getWeightedScore(),
            sentenceCount,
            target.getTargetLabel());
    }
}
