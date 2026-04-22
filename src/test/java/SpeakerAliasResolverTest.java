import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

/**
 * Unit tests for SpeakerAliasResolver.
 *
 * Alias merging is critical to scoring correctness: without it,
 * "Senator Sessions" and "Ranking Member Sessions" count as two separate
 * senators, splitting turn coverage and distorting aggregates.
 *
 * Covers: grouping by last name, canonical label election (count-then-title),
 * non-senator-tier exclusion, and the AliasMap query API.
 */
class SpeakerAliasResolverTest {

    // ── Fixture builder ───────────────────────────────────────────────

    private static SpeakerTurn turn(String title, String lastName, int num) {
        return new SpeakerTurn(title, lastName, num,
            "Substantive question about qualifications.", 0);
    }

    private static List<SpeakerTurn> turns(SpeakerTurn... turns) {
        return Arrays.asList(turns);
    }

    // ── extractLastName helper ────────────────────────────────────────

    @Test
    void extractLastName_singleWordLabel() {
        assertEquals("Sessions",
            SpeakerAliasResolver.extractLastName("Senator Sessions"));
    }

    @Test
    void extractLastName_multiWordTitle() {
        assertEquals("Sessions",
            SpeakerAliasResolver.extractLastName("Ranking Member Sessions"));
    }

    @Test
    void extractLastName_threeWordTitle() {
        assertEquals("Sessions",
            SpeakerAliasResolver.extractLastName("The Chairman Sessions"));
    }

    @Test
    void extractLastName_nullOrBlank_returnsEmpty() {
        assertEquals("", SpeakerAliasResolver.extractLastName(null));
        assertEquals("", SpeakerAliasResolver.extractLastName("  "));
    }

    // ── extractTitle helper ───────────────────────────────────────────

    @Test
    void extractTitle_twoWordLabel() {
        assertEquals("Senator",
            SpeakerAliasResolver.extractTitle("Senator Sessions"));
    }

    @Test
    void extractTitle_threeWordLabel() {
        assertEquals("Ranking Member",
            SpeakerAliasResolver.extractTitle("Ranking Member Sessions"));
    }

    @Test
    void extractTitle_fourWordLabel() {
        assertEquals("The Ranking Member",
            SpeakerAliasResolver.extractTitle("The Ranking Member Sessions"));
    }

    // ── No merge needed ───────────────────────────────────────────────

    @Test
    void singleLabel_remainsCanonical() {
        SpeakerAliasResolver.AliasMap map =
            SpeakerAliasResolver.resolve(turns(turn("Senator", "Smith", 1)));
        assertEquals("Senator Smith", map.canonicalize("Senator Smith"));
        assertFalse(map.hasAnyAliases());
    }

    @Test
    void twoDifferentLastNames_noMerge() {
        SpeakerAliasResolver.AliasMap map = SpeakerAliasResolver.resolve(turns(
            turn("Senator", "Smith",   1),
            turn("Senator", "Johnson", 2)
        ));
        assertEquals("Senator Smith",   map.canonicalize("Senator Smith"));
        assertEquals("Senator Johnson", map.canonicalize("Senator Johnson"));
        assertFalse(map.hasAnyAliases());
    }

    // ── Alias merging: count-based canonical election ─────────────────

    @Test
    void twoLabelsForSameLastName_mergedToHigherCount() {
        // "Senator Sessions" appears twice → higher count → canonical
        // "Ranking Member Sessions" appears once → alias
        List<SpeakerTurn> ts = turns(
            turn("Senator",        "Sessions", 1),
            turn("Senator",        "Sessions", 2),
            turn("Ranking Member", "Sessions", 3)
        );
        SpeakerAliasResolver.AliasMap map = SpeakerAliasResolver.resolve(ts);

        assertEquals("Senator Sessions", map.canonicalize("Ranking Member Sessions"));
        assertEquals("Senator Sessions", map.canonicalize("Senator Sessions"));
        assertTrue(map.hasAnyAliases());
    }

    @Test
    void aliases_retrievable_fromCanonical() {
        List<SpeakerTurn> ts = turns(
            turn("Senator",        "Sessions", 1),
            turn("Senator",        "Sessions", 2),
            turn("Ranking Member", "Sessions", 3)
        );
        SpeakerAliasResolver.AliasMap map = SpeakerAliasResolver.resolve(ts);

        List<String> aliases = map.getAliases("Senator Sessions");
        assertEquals(1, aliases.size());
        assertTrue(aliases.contains("Ranking Member Sessions"));
    }

    @Test
    void aliasTurnCount_tracksOriginalOccurrences() {
        List<SpeakerTurn> ts = turns(
            turn("Senator",        "Sessions", 1),
            turn("Senator",        "Sessions", 2),
            turn("Ranking Member", "Sessions", 3)
        );
        SpeakerAliasResolver.AliasMap map = SpeakerAliasResolver.resolve(ts);

        // "Ranking Member Sessions" appeared exactly once in the source
        assertEquals(1, map.getAliasTurnCount("Ranking Member Sessions"));
    }

    // ── Alias merging: title-preference tie-breaking ──────────────────

    @Test
    void tie_brokenByTitlePreference_senatorBeforeRankingMember() {
        // Equal appearances → title preference: Senator > Ranking Member
        List<SpeakerTurn> ts = turns(
            turn("Senator",        "Sessions", 1),
            turn("Ranking Member", "Sessions", 2)
        );
        SpeakerAliasResolver.AliasMap map = SpeakerAliasResolver.resolve(ts);

        assertEquals("Senator Sessions", map.canonicalize("Ranking Member Sessions"));
    }

    @Test
    void tie_brokenByTitlePreference_senatorBeforeChairman() {
        List<SpeakerTurn> ts = turns(
            turn("Chairman", "Leahy", 1),
            turn("Senator",  "Leahy", 2)
        );
        SpeakerAliasResolver.AliasMap map = SpeakerAliasResolver.resolve(ts);

        // Senator preferred over Chairman even at equal count
        assertEquals("Senator Leahy", map.canonicalize("Chairman Leahy"));
    }

    // ── Non-senator-tier turns excluded ──────────────────────────────

    @Test
    void judgeTurns_notIncludedInAliasMerging() {
        // "Judge Doe" is a nominee, not a senator — must not be merged or canonicalized
        List<SpeakerTurn> ts = turns(
            turn("Senator", "Smith", 1),
            turn("Judge",   "Doe",   2)
        );
        SpeakerAliasResolver.AliasMap map = SpeakerAliasResolver.resolve(ts);

        // Judge Doe is not in the resolver's map; canonicalize returns it unchanged
        assertEquals("Judge Doe", map.canonicalize("Judge Doe"));
        // No aliases in the senator-only universe
        assertFalse(map.hasAnyAliases());
    }

    @Test
    void mrTitle_notIncludedInAliasMerging() {
        List<SpeakerTurn> ts = turns(
            turn("Senator", "Smith",  1),
            turn("Mr.",     "Kappos", 2)
        );
        SpeakerAliasResolver.AliasMap map = SpeakerAliasResolver.resolve(ts);
        assertEquals("Mr. Kappos", map.canonicalize("Mr. Kappos"));
    }

    // ── AliasMap API ──────────────────────────────────────────────────

    @Test
    void canonicalize_returnsLabelItselfIfNotInMap() {
        SpeakerAliasResolver.AliasMap map =
            SpeakerAliasResolver.resolve(turns(turn("Senator", "Smith", 1)));
        // Unknown label → returned unchanged
        assertEquals("Unknown Person", map.canonicalize("Unknown Person"));
    }

    @Test
    void getAliases_returnsEmptyListWhenNoneExist() {
        SpeakerAliasResolver.AliasMap map =
            SpeakerAliasResolver.resolve(turns(turn("Senator", "Smith", 1)));
        assertTrue(map.getAliases("Senator Smith").isEmpty());
    }

    @Test
    void getAllCanonicals_containsExpectedLabels() {
        List<SpeakerTurn> ts = turns(
            turn("Senator", "Smith",   1),
            turn("Senator", "Johnson", 2)
        );
        SpeakerAliasResolver.AliasMap map = SpeakerAliasResolver.resolve(ts);
        Set<String> canonicals = map.getAllCanonicals();
        assertTrue(canonicals.contains("Senator Smith"));
        assertTrue(canonicals.contains("Senator Johnson"));
    }

    @Test
    void multipleDistinctSenators_allPresent_noFalseAliases() {
        List<SpeakerTurn> ts = turns(
            turn("Senator",  "Leahy",    1),
            turn("Senator",  "Grassley", 2),
            turn("Chairman", "Leahy",    3)
        );
        SpeakerAliasResolver.AliasMap map = SpeakerAliasResolver.resolve(ts);

        // Leahy group merges; Grassley stays alone
        assertEquals("Senator Leahy", map.canonicalize("Chairman Leahy"));
        assertEquals("Senator Grassley", map.canonicalize("Senator Grassley"));
        // Only Leahy has aliases
        assertTrue(map.hasAliases("Senator Leahy"));
        assertFalse(map.hasAliases("Senator Grassley"));
    }
}
