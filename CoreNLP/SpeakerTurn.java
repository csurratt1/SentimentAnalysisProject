/**
 * SpeakerTurn.java
 *
 * Model class representing a single speaker turn in a Senate hearing transcript.
 * A "turn" is the contiguous block of text attributed to one speaker, starting
 * when they are introduced (e.g., "Senator Sessions.") and ending when another
 * speaker is introduced.
 *
 * This is a simple immutable POJO — no dependencies beyond the JDK.
 */
public class SpeakerTurn {

    private final String title;       // "Chairman", "Senator", "Mr.", "Judge", etc.
    private final String lastName;    // "Leahy", "Sessions", "Kappos", etc.
    private final int    turnNumber;  // sequential turn index (1-based)
    private final String text;        // accumulated speech text (may span multiple lines)
    private final int    startLine;   // 0-based line index in original source

    public SpeakerTurn(String title, String lastName, int turnNumber,
                       String text, int startLine) {
        this.title      = title;
        this.lastName   = lastName;
        this.turnNumber = turnNumber;
        this.text       = text;
        this.startLine  = startLine;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getTitle()      { return title; }
    public String getLastName()   { return lastName; }
    public int    getTurnNumber() { return turnNumber; }
    public String getText()       { return text; }
    public int    getStartLine()  { return startLine; }

    /**
     * Returns a human-readable speaker label, e.g. "Senator Sessions" or
     * "Chairman Leahy".  Title abbreviations keep the period ("Mr. Kappos").
     */
    public String getSpeakerLabel() {
        return title + " " + lastName;
    }

    /**
     * True if this turn contains text beyond just bracketed annotations
     * like "[Off microphone.]" or "[Laughter.]".
     */
    public boolean hasSubstantiveText() {
        // Strip all bracketed annotations and see if anything is left
        String stripped = text.replaceAll("\\[.*?\\]", "").trim();
        return !stripped.isEmpty();
    }

    @Override
    public String toString() {
        String preview = text.length() > 60
            ? text.substring(0, 57) + "..."
            : text;
        return String.format("Turn %d [%s]: %s", turnNumber, getSpeakerLabel(), preview);
    }
}
