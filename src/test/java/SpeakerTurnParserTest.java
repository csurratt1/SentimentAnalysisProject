import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

/**
 * Unit tests for SpeakerTurnParser.
 *
 * All tests use the static parse(String) API — no CoreNLP required.
 * Covers: speaker detection, turn segmentation, continuation lines,
 * TOC filtering, annotation filtering, and edge cases.
 */
class SpeakerTurnParserTest {

    // ── Empty / trivial input ─────────────────────────────────────────

    @Test
    void emptyString_returnsNoTurns() {
        assertTrue(SpeakerTurnParser.parse("").isEmpty());
    }

    @Test
    void blankLines_returnsNoTurns() {
        assertTrue(SpeakerTurnParser.parse("   \n\n   \n").isEmpty());
    }

    @Test
    void contentBeforeFirstSpeaker_isDiscarded() {
        String transcript =
            "COMMITTEE ON THE JUDICIARY\n" +
            "United States Senate\n" +
            "    Senator Smith. Thank you.";
        List<SpeakerTurn> turns = SpeakerTurnParser.parse(transcript);
        assertEquals(1, turns.size());
        assertEquals("Smith", turns.get(0).getLastName());
    }

    // ── Basic turn detection ──────────────────────────────────────────

    @Test
    void singleSpeaker_parsedCorrectly() {
        String transcript = "    Senator Sessions. Thank you, Mr. Chairman.";
        List<SpeakerTurn> turns = SpeakerTurnParser.parse(transcript);
        assertEquals(1, turns.size());
        SpeakerTurn turn = turns.get(0);
        assertEquals("Senator",  turn.getTitle());
        assertEquals("Sessions", turn.getLastName());
        assertEquals("Thank you, Mr. Chairman.", turn.getText());
    }

    @Test
    void twoSpeakers_parsedAsTwoTurns() {
        String transcript =
            "    Chairman Jones. We will begin.\n" +
            "    Senator Smith. Thank you, Chairman.";
        List<SpeakerTurn> turns = SpeakerTurnParser.parse(transcript);
        assertEquals(2, turns.size());
        assertEquals("Jones", turns.get(0).getLastName());
        assertEquals("Smith", turns.get(1).getLastName());
    }

    @Test
    void turnNumbers_areOneBasedAndSequential() {
        String transcript =
            "    Senator Alpha. First.\n" +
            "    Senator Beta.  Second.\n" +
            "    Senator Gamma. Third.";
        List<SpeakerTurn> turns = SpeakerTurnParser.parse(transcript);
        assertEquals(3, turns.size());
        assertEquals(1, turns.get(0).getTurnNumber());
        assertEquals(2, turns.get(1).getTurnNumber());
        assertEquals(3, turns.get(2).getTurnNumber());
    }

    @Test
    void speakerLabel_combinesTitleAndLastName() {
        String transcript = "    Senator Grassley. Good morning.";
        SpeakerTurn turn = SpeakerTurnParser.parse(transcript).get(0);
        assertEquals("Senator Grassley", turn.getSpeakerLabel());
    }

    // ── Title recognition ─────────────────────────────────────────────

    @Test
    void chairmanTitle_recognized() {
        String transcript = "    Chairman Leahy. The committee will come to order.";
        SpeakerTurn turn = SpeakerTurnParser.parse(transcript).get(0);
        assertEquals("Chairman", turn.getTitle());
        assertEquals("Leahy",    turn.getLastName());
    }

    @Test
    void rankingMemberTitle_recognized() {
        String transcript = "    Ranking Member Sessions. I appreciate the opportunity.";
        SpeakerTurn turn = SpeakerTurnParser.parse(transcript).get(0);
        assertEquals("Ranking Member", turn.getTitle());
        assertEquals("Sessions",       turn.getLastName());
    }

    @Test
    void theChairmanTitle_recognized() {
        // "The Chairman" requires a last name in the transcript format, e.g. "The Chairman Leahy."
        String transcript = "    The Chairman Leahy. Good morning.";
        SpeakerTurn turn = SpeakerTurnParser.parse(transcript).get(0);
        assertEquals("The Chairman", turn.getTitle());
        assertEquals("Leahy",        turn.getLastName());
    }

    @Test
    void judgeTitle_recognized() {
        String transcript = "    Judge Chen. Thank you for the opportunity to testify.";
        SpeakerTurn turn = SpeakerTurnParser.parse(transcript).get(0);
        assertEquals("Judge", turn.getTitle());
        assertEquals("Chen",  turn.getLastName());
    }

    // ── Continuation lines ────────────────────────────────────────────

    @Test
    void continuationLine_appendedToCurrentTurn() {
        String transcript =
            "    Senator Smith. This is the beginning of my\n" +
            "question which continues on the next line.";
        List<SpeakerTurn> turns = SpeakerTurnParser.parse(transcript);
        assertEquals(1, turns.size());
        assertTrue(turns.get(0).getText().contains("beginning of my"));
        assertTrue(turns.get(0).getText().contains("question which continues"));
    }

    @Test
    void continuationLines_separatedBySpace_notNewline() {
        String transcript =
            "    Senator Smith. First part\n" +
            "second part.";
        String text = SpeakerTurnParser.parse(transcript).get(0).getText();
        // Lines joined with a space, not a newline
        assertTrue(text.contains("First part second part"));
    }

    @Test
    void multipleSpeakers_continuationsStayWithCorrectTurn() {
        String transcript =
            "    Senator Alpha. My question is\n" +
            "about the regulatory framework.\n" +
            "    Senator Beta. My answer is short.";
        List<SpeakerTurn> turns = SpeakerTurnParser.parse(transcript);
        assertEquals(2, turns.size());
        assertTrue(turns.get(0).getText().contains("regulatory framework"));
        assertTrue(turns.get(1).getText().contains("short"));
    }

    // ── TOC filtering ─────────────────────────────────────────────────

    @Test
    void tocLine_withDotLeaders_isFiltered() {
        String transcript =
            "    Opening Statement ........ 3\n" +
            "    Senator Smith. Thank you.";
        List<SpeakerTurn> turns = SpeakerTurnParser.parse(transcript);
        assertEquals(1, turns.size());
        assertEquals("Smith", turns.get(0).getLastName());
    }

    @Test
    void tocLine_withManyDots_isFiltered() {
        String transcript =
            "    Hearing on Nominations ....................... 1\n" +
            "    Senator Jones. We begin.";
        List<SpeakerTurn> turns = SpeakerTurnParser.parse(transcript);
        assertEquals(1, turns.size());
    }

    // ── Annotation filtering ──────────────────────────────────────────

    @Test
    void standaloneAnnotationLine_isFiltered() {
        String transcript =
            "    Senator Smith. I appreciate the work.\n" +
            "    [Laughter.]\n" +
            "    Senator Jones. Thank you.";
        List<SpeakerTurn> turns = SpeakerTurnParser.parse(transcript);
        // [Laughter.] must NOT become a third turn
        assertEquals(2, turns.size());
    }

    @Test
    void annotationOnlyTurn_isNotSubstantive() {
        SpeakerTurn turn = new SpeakerTurn("Senator", "Smith", 1, "[Applause.]", 0);
        assertFalse(turn.hasSubstantiveText());
    }

    @Test
    void realTextTurn_isSubstantive() {
        SpeakerTurn turn = new SpeakerTurn("Senator", "Smith", 1,
            "I have serious concerns about this nomination.", 0);
        assertTrue(turn.hasSubstantiveText());
    }

    @Test
    void textWithEmbeddedAnnotation_isSubstantive() {
        SpeakerTurn turn = new SpeakerTurn("Senator", "Smith", 1,
            "I agree [nodding] with the nominee.", 0);
        assertTrue(turn.hasSubstantiveText());
    }

    @Test
    void multipleAnnotationsOnly_isNotSubstantive() {
        SpeakerTurn turn = new SpeakerTurn("Senator", "Smith", 1,
            "[Laughter.] [Applause.]", 0);
        assertFalse(turn.hasSubstantiveText());
    }
}
