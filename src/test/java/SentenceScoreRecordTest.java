import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SentenceScoreRecord — verifies constructor mapping and field accessors.
 * No database connection required.
 */
class SentenceScoreRecordTest {

    @Test
    void constructor_mapsAllSentenceScoreFields() {
        SentenceScore ss = new SentenceScore("Hello world.", 3, 1, 0.82);
        SentenceScoreRecord rec = new SentenceScoreRecord(10, 20, 30, 2, ss);

        assertEquals(10,             rec.getTurnScoreId());
        assertEquals(20,             rec.getScoringRunId());
        assertEquals(30,             rec.getTurnId());
        assertEquals(2,              rec.getSentenceIndex());
        assertEquals("Hello world.", rec.getSentenceText());
        assertEquals(3,              rec.getSentimentClass());
        assertEquals("Positive",     rec.getSentimentLabel());
        assertEquals(1,              rec.getSentimentScore());
        assertEquals(0.82,           rec.getConfidence(), 1e-10);
    }

    @Test
    void constructor_veryNegativeClass_mapsCorrectly() {
        SentenceScore ss = new SentenceScore("Troubling.", 0, -2, 0.91);
        SentenceScoreRecord rec = new SentenceScoreRecord(1, 1, 1, 1, ss);

        assertEquals(0,               rec.getSentimentClass());
        assertEquals("Very Negative", rec.getSentimentLabel());
        assertEquals(-2,              rec.getSentimentScore());
    }

    @Test
    void constructor_neutralClass_mapsCorrectly() {
        SentenceScore ss = new SentenceScore("You wrote that.", 2, 0, 0.55);
        SentenceScoreRecord rec = new SentenceScoreRecord(1, 1, 1, 3, ss);

        assertEquals(2,         rec.getSentimentClass());
        assertEquals("Neutral", rec.getSentimentLabel());
        assertEquals(0,         rec.getSentimentScore());
    }

    @Test
    void constructor_veryPositiveClass_mapsCorrectly() {
        SentenceScore ss = new SentenceScore("Outstanding.", 4, 2, 0.95);
        SentenceScoreRecord rec = new SentenceScoreRecord(5, 10, 15, 1, ss);

        assertEquals(4,               rec.getSentimentClass());
        assertEquals("Very Positive", rec.getSentimentLabel());
        assertEquals(2,               rec.getSentimentScore());
    }

    @Test
    void defaultConstructor_idIsZero() {
        SentenceScoreRecord rec = new SentenceScoreRecord();
        assertEquals(0, rec.getId());
    }

    @Test
    void sentenceText_preservedExactly() {
        String text = "This is a sentence with punctuation, and some CAPS.";
        SentenceScore ss = new SentenceScore(text, 2, 0, 0.5);
        SentenceScoreRecord rec = new SentenceScoreRecord(1, 1, 1, 1, ss);
        assertEquals(text, rec.getSentenceText());
    }

    @Test
    void sentenceText_emptyString_stored() {
        SentenceScore ss = new SentenceScore("", 2, 0, 0.5);
        SentenceScoreRecord rec = new SentenceScoreRecord(1, 1, 1, 1, ss);
        assertEquals("", rec.getSentenceText());
    }
}
