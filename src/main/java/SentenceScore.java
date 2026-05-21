import java.util.Objects;

/**
 * SentenceScore.java
 *
 * Captures one sentence's CoreNLP sentiment result: raw text, class index (0–4),
 * mapped score (−2..+2), and the model's max-prediction confidence.
 */
public class SentenceScore {

    private static final String[] LABELS = {
        "Very Negative", "Negative", "Neutral", "Positive", "Very Positive"
    };

    private final String text;
    private final int classIndex;
    private final int score;
    private final double confidence;

    public SentenceScore(String text, int classIndex, int score, double confidence) {
        this.text       = Objects.requireNonNullElse(text, "");
        this.classIndex = classIndex;
        this.score      = score;
        this.confidence = confidence;
    }

    public String getText()       { return text; }
    public int    getClassIndex() { return classIndex; }
    public int    getScore()      { return score; }
    public double getConfidence() { return confidence; }

    public String getLabel() {
        return (classIndex >= 0 && classIndex < LABELS.length)
            ? LABELS[classIndex]
            : "Unknown";
    }
}
