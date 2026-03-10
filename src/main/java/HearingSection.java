import java.util.*;

/**
 * HearingSection.java
 *
 * Represents one nominee panel within a hearing transcript. A single hearing
 * document may contain multiple panels (e.g., morning panel, afternoon panel,
 * or hearings on different dates). Each panel has a set of nominees being
 * questioned.
 *
 * <p>The parser detects panel boundaries from the ALL-CAPS
 * {@code "NOMINATIONS OF ..."} header block that the GPO inserts at the
 * start of each panel, or from date headers like
 * {@code "WEDNESDAY, JULY 29, 2009"}.</p>
 *
 * <p>Turns that fall within a section's line range are associated with
 * that section's nominees.</p>
 */
public class HearingSection {

    private final int    sectionNumber;   // 1-based sequential index
    private final String headerText;      // the full multi-line header text
    private final String date;            // hearing date (e.g., "JULY 29, 2009"), null if unknown
    private final int    startLine;       // first line of this section (0-based)
    private       int    endLine;         // last line of this section (exclusive), set later

    /** Nominees on this panel, keyed by last name. */
    private final Map<String, NomineeInfo> nominees = new LinkedHashMap<>();

    public HearingSection(int sectionNumber, String headerText, String date,
                          int startLine) {
        this.sectionNumber = sectionNumber;
        this.headerText    = headerText;
        this.date          = date;
        this.startLine     = startLine;
        this.endLine       = Integer.MAX_VALUE;  // until next section is found
    }

    // ── Nominee management ───────────────────────────────────────────────

    public void addNominee(NomineeInfo nominee) {
        nominees.put(nominee.getLastName().toLowerCase(), nominee);
    }

    public Collection<NomineeInfo> getNominees() {
        return Collections.unmodifiableCollection(nominees.values());
    }

    /**
     * Look up a nominee by last name (case-insensitive).
     * Returns null if no match.
     */
    public NomineeInfo findNominee(String lastName) {
        return nominees.get(lastName.toLowerCase());
    }

    /**
     * Returns true if the given line number falls within this section.
     */
    public boolean containsLine(int lineNumber) {
        return lineNumber >= startLine && lineNumber < endLine;
    }

    // ── Getters / setters ────────────────────────────────────────────────

    public int    getSectionNumber() { return sectionNumber; }
    public String getHeaderText()    { return headerText; }
    public String getDate()          { return date; }
    public int    getStartLine()     { return startLine; }
    public int    getEndLine()       { return endLine; }

    public void setEndLine(int endLine) { this.endLine = endLine; }

    @Override
    public String toString() {
        List<String> names = new ArrayList<>();
        for (NomineeInfo n : nominees.values()) names.add(n.getDisplayName());
        return String.format("Section %d [lines %d-%d] %s: %s",
            sectionNumber, startLine, endLine,
            date != null ? date : "(no date)",
            String.join(", ", names));
    }
}
