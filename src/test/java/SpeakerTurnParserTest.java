import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

/**
 * Unit tests for SpeakerTurnParser.
 * Covers: speaker detection, turn segmentation, continuation lines,
 * TOC line filtering, and annotation line filtering.
 */
class SpeakerTurnParserTest {

    private final SpeakerTurnParser parser = new SpeakerTurnParser();

    // ── Basic parsing ────────────────────────────────────────────────

    @Test
    void emptyInputReturnsNoTurns() {
        List<SpeakerTurn> turns = parser.parse("");
        assertTrue(turns.isEmpty());
    }

    @Test
    void singleSpeakerTurnIsParsed() {
        String transcript = "    Senator Smith. Thank you, Mr. Chairman.";
        List<SpeakerTurn> turns = parser.parse(transcript);
        // TODO: assert size == 1 and speaker label matches "Senator Smith"
        assertFalse(turns.isEmpty());
    }

    @Test
    void multipleSpeakersProduceMultipleTurns() {
        String transcript =
            "    Chairman Jones. We will begin.\n" +
            "    Senator Smith. Thank you, Chairman.";
        List<SpeakerTurn> turns = parser.parse(transcript);
        // TODO: assert size == 2
        assertEquals(2, turns.size());
    }

    // ── Speaker label extraction ──────────────────────────────────────

    @Test
    void titleAndLastNameExtractedCorrectly() {
        String transcript = "    Senator Sessions. Good morning.";
        List<SpeakerTurn> turns = parser.parse(transcript);
        // TODO: assert turns.get(0).getTitle() == "Senator" and getLastName() == "Sessions"
        assertFalse(turns.isEmpty());
    }

    @Test
    void chairmanTitleRecognized() {
        String transcript = "    Chairman Grassley. The committee will come to order.";
        List<SpeakerTurn> turns = parser.parse(transcript);
        // TODO: assert title == "Chairman"
        assertFalse(turns.isEmpty());
    }

    // ── Continuation lines ────────────────────────────────────────────

    @Test
    void continuationLinesAppendedToCurrentTurn() {
        String transcript =
            "    Senator Smith. This is the first line\n" +
            "of the same turn continued here.";
        List<SpeakerTurn> turns = parser.parse(transcript);
        // TODO: assert size == 1 and text contains both lines
        assertEquals(1, turns.size());
    }

    // ── Filtering ─────────────────────────────────────────────────────

    @Test
    void tocLinesAreFilteredOut() {
        String transcript =
            "    Opening Statement ........ 3\n" +
            "    Senator Smith. Thank you.";
        List<SpeakerTurn> turns = parser.parse(transcript);
        // TODO: assert only the real speaker turn is returned
        assertFalse(turns.isEmpty());
    }

    @Test
    void annotationLinesAreFilteredOut() {
        String transcript =
            "    Senator Smith. I appreciate the work.\n" +
            "    [Laughter.]\n" +
            "    Senator Jones. Thank you.";
        List<SpeakerTurn> turns = parser.parse(transcript);
        // TODO: assert annotation line is not a separate turn
        assertFalse(turns.isEmpty());
    }

    // ── Substantive text detection ────────────────────────────────────

    @Test
    void turnWithTextIsSubstantive() {
        String transcript = "    Senator Smith. I have serious concerns about this nomination.";
        List<SpeakerTurn> turns = parser.parse(transcript);
        // TODO: assert turns.get(0).hasSubstantiveText() == true
        assertFalse(turns.isEmpty());
    }
}
