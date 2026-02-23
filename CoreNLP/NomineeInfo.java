/**
 * NomineeInfo.java
 *
 * Lightweight model for a nominee appearing in a hearing panel.
 * Stores the name and the position they are nominated for.
 *
 * <p>In future phases this will be enriched with party-of-nominating-president,
 * confirmation outcome, committee vote, etc. For now it is intentionally
 * minimal — just enough to serve as a target label for sentiment scoring.</p>
 */
public class NomineeInfo {

    private final String firstName;   // "Beverly" or "David"
    private final String lastName;    // "Martin" or "Kappos"
    private final String position;    // "U.S. Circuit Judge for the Eleventh Circuit"
    private final String titleUsed;   // how the transcript addresses them: "Ms.", "Judge", "Mr."

    public NomineeInfo(String firstName, String lastName,
                       String position, String titleUsed) {
        this.firstName = firstName;
        this.lastName  = lastName;
        this.position  = position;
        this.titleUsed = titleUsed;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getFirstName() { return firstName; }
    public String getLastName()  { return lastName; }
    public String getPosition()  { return position; }
    public String getTitleUsed() { return titleUsed; }

    /**
     * Returns the display name as used in transcript, e.g., "Judge Martin"
     * or "Mr. Kappos".
     */
    public String getDisplayName() {
        return titleUsed + " " + lastName;
    }

    /**
     * Returns the full formal name, e.g., "Beverly Baldwin Martin".
     */
    public String getFullName() {
        return firstName + " " + lastName;
    }

    /**
     * Check if a speaker turn's last name matches this nominee (case-insensitive).
     */
    public boolean matchesLastName(String name) {
        return lastName.equalsIgnoreCase(name);
    }

    @Override
    public String toString() {
        return String.format("%s %s → %s", titleUsed, lastName, position);
    }
}
