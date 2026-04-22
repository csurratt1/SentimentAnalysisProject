import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NomineeInfo and SpeakerTurn model correctness.
 *
 * These are the foundational data objects used throughout the pipeline.
 * Errors here would silently propagate into target labels, aggregation keys,
 * and all downstream scoring output.
 */
class TurnScorerTest {

    // ── NomineeInfo ───────────────────────────────────────────────────

    @Test
    void nomineeInfo_displayName_combinesTitleAndLastName() {
        NomineeInfo n = new NomineeInfo("Beverly", "Martin", "U.S. Circuit Judge", "Judge");
        assertEquals("Judge Martin", n.getDisplayName());
    }

    @Test
    void nomineeInfo_displayName_withMrTitle() {
        NomineeInfo n = new NomineeInfo("David", "Kappos", "Under Secretary of Commerce", "Mr.");
        assertEquals("Mr. Kappos", n.getDisplayName());
    }

    @Test
    void nomineeInfo_fullName_combinesFirstAndLast() {
        NomineeInfo n = new NomineeInfo("Beverly", "Martin", "U.S. Circuit Judge", "Judge");
        assertEquals("Beverly Martin", n.getFullName());
    }

    @Test
    void nomineeInfo_matchesLastName_caseInsensitive() {
        NomineeInfo n = new NomineeInfo("Beverly", "Martin", "U.S. Circuit Judge", "Judge");
        assertTrue(n.matchesLastName("martin"));
        assertTrue(n.matchesLastName("MARTIN"));
        assertTrue(n.matchesLastName("Martin"));
    }

    @Test
    void nomineeInfo_matchesLastName_falseForDifferentName() {
        NomineeInfo n = new NomineeInfo("Beverly", "Martin", "U.S. Circuit Judge", "Judge");
        assertFalse(n.matchesLastName("Kappos"));
        assertFalse(n.matchesLastName(""));
    }

    @Test
    void nomineeInfo_getters_returnConstructorValues() {
        NomineeInfo n = new NomineeInfo("David", "Kappos",
            "Under Secretary of Commerce for Intellectual Property", "Mr.");
        assertEquals("David",   n.getFirstName());
        assertEquals("Kappos",  n.getLastName());
        assertEquals("Mr.",     n.getTitleUsed());
        assertEquals("Under Secretary of Commerce for Intellectual Property",
            n.getPosition());
    }

    // ── SpeakerTurn ───────────────────────────────────────────────────

    @Test
    void speakerTurn_speakerLabel_combinesTitleAndLastName() {
        SpeakerTurn t = new SpeakerTurn("Senator", "Grassley", 1, "Thank you.", 0);
        assertEquals("Senator Grassley", t.getSpeakerLabel());
    }

    @Test
    void speakerTurn_speakerLabel_multiWordTitle() {
        SpeakerTurn t = new SpeakerTurn("Ranking Member", "Sessions", 1, "Thank you.", 0);
        assertEquals("Ranking Member Sessions", t.getSpeakerLabel());
    }

    @Test
    void speakerTurn_hasSubstantiveText_trueForRealText() {
        SpeakerTurn t = new SpeakerTurn("Senator", "Smith", 1,
            "I have serious concerns about this nomination.", 0);
        assertTrue(t.hasSubstantiveText());
    }

    @Test
    void speakerTurn_hasSubstantiveText_falseForAnnotationOnly() {
        SpeakerTurn t = new SpeakerTurn("Senator", "Smith", 1, "[Laughter.]", 0);
        assertFalse(t.hasSubstantiveText());
    }

    @Test
    void speakerTurn_hasSubstantiveText_falseForMultipleAnnotations() {
        SpeakerTurn t = new SpeakerTurn("Senator", "Smith", 1,
            "[Laughter.] [Applause.] [Off microphone.]", 0);
        assertFalse(t.hasSubstantiveText());
    }

    @Test
    void speakerTurn_hasSubstantiveText_trueWhenTextSurroundsAnnotation() {
        SpeakerTurn t = new SpeakerTurn("Senator", "Smith", 1,
            "I strongly agree [nodding] with the nominee's position.", 0);
        assertTrue(t.hasSubstantiveText());
    }

    @Test
    void speakerTurn_hasSubstantiveText_falseForWhitespaceAfterStrip() {
        SpeakerTurn t = new SpeakerTurn("Senator", "Smith", 1, "   [Note.]   ", 0);
        assertFalse(t.hasSubstantiveText());
    }

    @Test
    void speakerTurn_getters_returnConstructorValues() {
        SpeakerTurn t = new SpeakerTurn("Chairman", "Leahy", 5, "Good morning.", 42);
        assertEquals("Chairman",      t.getTitle());
        assertEquals("Leahy",         t.getLastName());
        assertEquals(5,               t.getTurnNumber());
        assertEquals("Good morning.", t.getText());
        assertEquals(42,              t.getStartLine());
    }
}
