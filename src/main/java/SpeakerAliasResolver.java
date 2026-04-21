import java.util.*;

/**
 * SpeakerAliasResolver.java
 *
 * Groups senator-tier speaker labels that refer to the same person and
 * elects a single canonical label for each group. This maximises turn
 * coverage: every turn labelled "Ranking Member Sessions" is counted
 * alongside every turn labelled "Senator Sessions" in aggregation.
 *
 * <h3>Grouping rule:</h3>
 * Two labels are merged when they share the same last name AND both carry
 * a senator-tier title (Senator, Chairman, The Chairman, Ranking Member,
 * Vice Chairman). Within a single transcript this produces no false merges
 * because two different senators with the same last name in the same hearing
 * is extraordinarily rare.
 *
 * <h3>Canonical label selection:</h3>
 * The label with the highest occurrence count in the transcript is elected
 * canonical. Ties are broken by title preference order:
 * Senator &gt; Chairman &gt; Ranking Member &gt; Vice Chairman &gt; The Chairman.
 * This keeps the most-common, most-general name in the output rather than
 * an incidental alternate title.
 */
public class SpeakerAliasResolver {

    static final Set<String> SENATOR_TIER_TITLES = Set.of(
        "Senator", "Chairman", "The Chairman", "Ranking Member", "Vice Chairman"
    );

    private static final List<String> TITLE_PREFERENCE = List.of(
        "Senator", "Chairman", "Ranking Member", "Vice Chairman", "The Chairman"
    );

    // ── Public result object ─────────────────────────────────────────────

    public static class AliasMap {
        private final Map<String, String>       labelToCanonical;
        private final Map<String, List<String>> canonicalToAliases;
        private final Map<String, Integer>      aliasTurnCounts;

        AliasMap(Map<String, String> ltc,
                 Map<String, List<String>> cta,
                 Map<String, Integer> atc) {
            this.labelToCanonical   = ltc;
            this.canonicalToAliases = cta;
            this.aliasTurnCounts    = atc;
        }

        /** Returns the canonical label for {@code label}, or label itself if it is already canonical. */
        public String canonicalize(String label) {
            return labelToCanonical.getOrDefault(label, label);
        }

        /** Returns all non-canonical labels that were merged into {@code canonicalLabel}. */
        public List<String> getAliases(String canonicalLabel) {
            return canonicalToAliases.getOrDefault(canonicalLabel, Collections.emptyList());
        }

        /** Returns how many turns in the source transcript used {@code aliasLabel}. */
        public int getAliasTurnCount(String aliasLabel) {
            return aliasTurnCounts.getOrDefault(aliasLabel, 0);
        }

        public boolean hasAliases(String canonicalLabel) {
            return !getAliases(canonicalLabel).isEmpty();
        }

        /** All canonical labels produced by this resolver. */
        public Set<String> getAllCanonicals() {
            return Collections.unmodifiableSet(canonicalToAliases.keySet());
        }

        /** True if at least one merge was performed. */
        public boolean hasAnyAliases() {
            for (List<String> aliases : canonicalToAliases.values()) {
                if (!aliases.isEmpty()) return true;
            }
            return false;
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Builds an {@link AliasMap} from a list of parsed speaker turns.
     * Only senator-tier turns are considered; nominee/presenter turns are
     * left unchanged.
     *
     * @param turns the full ordered list of parsed speaker turns
     * @return an AliasMap that can canonicalize any speaker label
     */
    public static AliasMap resolve(List<SpeakerTurn> turns) {
        // 1. Count how many turns each senator-tier label appears in
        Map<String, Integer> labelCounts = new LinkedHashMap<>();
        for (SpeakerTurn turn : turns) {
            if (!SENATOR_TIER_TITLES.contains(turn.getTitle())) continue;
            labelCounts.merge(turn.getSpeakerLabel(), 1, Integer::sum);
        }

        // 2. Group labels by lower-cased last name
        Map<String, List<String>> byLastName = new LinkedHashMap<>();
        for (String label : labelCounts.keySet()) {
            String lastName = extractLastName(label).toLowerCase(Locale.ROOT);
            byLastName.computeIfAbsent(lastName, k -> new ArrayList<>()).add(label);
        }

        // 3. For each group elect a canonical label and build the maps
        Map<String, String>       labelToCanonical   = new LinkedHashMap<>();
        Map<String, List<String>> canonicalToAliases = new LinkedHashMap<>();
        Map<String, Integer>      aliasTurnCounts    = new LinkedHashMap<>();

        for (List<String> group : byLastName.values()) {
            String canonical = group.size() == 1
                ? group.get(0)
                : pickCanonical(group, labelCounts);

            List<String> aliases = new ArrayList<>();
            for (String label : group) {
                labelToCanonical.put(label, canonical);
                if (!label.equals(canonical)) {
                    aliases.add(label);
                    aliasTurnCounts.put(label, labelCounts.getOrDefault(label, 0));
                }
            }
            canonicalToAliases.put(canonical, aliases);
        }

        return new AliasMap(labelToCanonical, canonicalToAliases, aliasTurnCounts);
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private static String pickCanonical(List<String> labels,
                                        Map<String, Integer> counts) {
        return labels.stream()
            .max(Comparator
                .<String>comparingInt(l -> counts.getOrDefault(l, 0))
                .thenComparingInt(l -> {
                    String title = extractTitle(l);
                    int idx = TITLE_PREFERENCE.indexOf(title);
                    // Higher index in TITLE_PREFERENCE = lower preference,
                    // so invert: prefer lower index (higher-priority title).
                    return idx == -1 ? 0 : TITLE_PREFERENCE.size() - idx;
                })
            )
            .orElse(labels.get(0));
    }

    /** Last word of a space-separated label, e.g. "Senator Sessions" → "Sessions". */
    static String extractLastName(String label) {
        if (label == null || label.isBlank()) return "";
        String[] parts = label.trim().split("\\s+");
        return parts[parts.length - 1];
    }

    /** Everything except the last word, e.g. "Ranking Member Sessions" → "Ranking Member". */
    static String extractTitle(String label) {
        if (label == null || label.isBlank()) return label;
        String[] parts = label.trim().split("\\s+");
        if (parts.length <= 1) return label;
        return String.join(" ", Arrays.copyOfRange(parts, 0, parts.length - 1));
    }
}
