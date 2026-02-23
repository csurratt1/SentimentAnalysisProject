import java.util.*;

/**
 * ResolvedTarget.java
 *
 * The result of target resolution for a single speaker turn.
 * Answers the question: "WHO is this speaker talking to/about?"
 *
 * <p>A turn may target a specific nominee (e.g., Senator Sessions questioning
 * Judge Chen) or may be general (e.g., a senator's opening statement that
 * addresses the full panel). The {@link #confidence} field indicates how
 * certain the resolution is.</p>
 *
 * <p>Resolution methods, ranked by confidence:</p>
 * <ol>
 *   <li>{@link Method#DIRECT_ADDRESS} — speaker names the nominee in their text
 *       ("Judge Chen, let me ask you...")</li>
 *   <li>{@link Method#RESPONSE_PAIR} — a nominee responds immediately after
 *       this senator's turn, confirming the target</li>
 *   <li>{@link Method#PRIOR_CONTEXT} — no new target cue; inherits the target
 *       from the preceding Q&A exchange in the same section</li>
 *   <li>{@link Method#SECTION_DEFAULT} — falls back to the section's nominee
 *       panel (multi-nominee; target is ambiguous)</li>
 * </ol>
 */
public class ResolvedTarget {

    /** How the target was determined. */
    public enum Method {
        /** Speaker explicitly names the nominee in their turn text. */
        DIRECT_ADDRESS,
        /** A nominee responds right after this turn (Q&A pairing). */
        RESPONSE_PAIR,
        /** Inherited from the ongoing Q&A exchange context. */
        PRIOR_CONTEXT,
        /** Fall-back: the turn is within a nominee panel section. */
        SECTION_DEFAULT,
        /** The speaker IS the nominee (nominee's own turn). */
        SELF,
        /** Unable to determine a target (pre-panel material, procedural turns). */
        UNKNOWN
    }

    private final SpeakerTurn  turn;
    private final NomineeInfo  nominee;     // null if UNKNOWN or SECTION_DEFAULT with multi-nominee
    private final List<NomineeInfo> panelNominees;  // all nominees in this section
    private final Method       method;
    private final double       confidence;  // 0.0 – 1.0

    public ResolvedTarget(SpeakerTurn turn, NomineeInfo nominee,
                          List<NomineeInfo> panelNominees,
                          Method method, double confidence) {
        this.turn          = turn;
        this.nominee       = nominee;
        this.panelNominees = panelNominees != null
            ? Collections.unmodifiableList(panelNominees)
            : Collections.emptyList();
        this.method        = method;
        this.confidence    = confidence;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public SpeakerTurn       getTurn()          { return turn; }
    public NomineeInfo       getNominee()       { return nominee; }
    public List<NomineeInfo> getPanelNominees() { return panelNominees; }
    public Method            getMethod()        { return method; }
    public double            getConfidence()    { return confidence; }

    /** True if this turn has a specific single nominee as its target. */
    public boolean hasSpecificTarget() {
        return nominee != null && method != Method.UNKNOWN;
    }

    /** True if the speaker IS the nominee (their own testimony). */
    public boolean isSelfTurn() {
        return method == Method.SELF;
    }

    /**
     * Returns the target label for display. Examples:
     * "→ Judge Chen (direct address, 0.95)",
     * "→ Panel [Martin, Viken, Kappos] (section default, 0.30)"
     */
    public String getTargetLabel() {
        if (method == Method.UNKNOWN) {
            return "→ (unknown target)";
        }
        if (method == Method.SELF) {
            return "→ SELF (" + nominee.getDisplayName() + ")";
        }
        if (nominee != null) {
            return String.format("→ %s (%s, %.2f)",
                nominee.getDisplayName(), methodLabel(), confidence);
        }
        // Multi-nominee panel fallback
        List<String> names = new ArrayList<>();
        for (NomineeInfo n : panelNominees) names.add(n.getLastName());
        return String.format("→ Panel [%s] (%s, %.2f)",
            String.join(", ", names), methodLabel(), confidence);
    }

    private String methodLabel() {
        switch (method) {
            case DIRECT_ADDRESS: return "direct";
            case RESPONSE_PAIR:  return "response";
            case PRIOR_CONTEXT:  return "context";
            case SECTION_DEFAULT:return "section";
            case SELF:           return "self";
            default:             return "unknown";
        }
    }

    @Override
    public String toString() {
        return String.format("Turn %d [%s] %s",
            turn.getTurnNumber(), turn.getSpeakerLabel(), getTargetLabel());
    }
}
