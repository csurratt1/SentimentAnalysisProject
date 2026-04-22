import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

/**
 * Unit tests for TurnScorer.InteractionScore and TurnScorer.aggregate().
 *
 * InteractionScore accumulates turn-level data into per-pair aggregates.
 * These tests verify:
 *   - turn count and running totals accumulate correctly
 *   - avgWeighted() = totalWeighted / turns
 *   - avgResolutionConf() drives the reliability tier thresholds
 *   - aggregate() groups by canonicalized senator|nominee key
 *   - aggregate() skips self-turns
 *   - aggregate() applies alias mapping before keying
 */
class AggregationTest {

    // ── Fixtures ──────────────────────────────────────────────────────

    private static SpeakerTurn senatorTurn(String title, String lastName, int num) {
        return new SpeakerTurn(title, lastName, num, "Some substantive question.", 0);
    }

    private static NomineeInfo nominee(String last) {
        return new NomineeInfo("Jane", last, "U.S. Circuit Judge", "Judge");
    }

    private static ResolvedTarget direct(SpeakerTurn turn, NomineeInfo nom, double conf) {
        return new ResolvedTarget(turn, nom, Collections.emptyList(),
            ResolvedTarget.Method.DIRECT_ADDRESS, conf);
    }

    private static ResolvedTarget self(SpeakerTurn turn, NomineeInfo nom) {
        return new ResolvedTarget(turn, nom, Collections.emptyList(),
            ResolvedTarget.Method.SELF, 1.0);
    }

    /** Build a ScoredTurn with a single sentence and a given integer score. */
    private static ScoredTurn scored(ResolvedTarget target, int score) {
        return new ScoredTurn(target, 1, score);
    }

    // ── InteractionScore: turn accumulation ───────────────────────────

    @Test
    void addTurn_incrementsTurnCount() {
        TurnScorer.InteractionScore is = new TurnScorer.InteractionScore("Senator Smith", "Judge Doe");
        SpeakerTurn st = senatorTurn("Senator", "Smith", 1);
        is.addTurn(scored(direct(st, nominee("Doe"), 1.0), 2));
        assertEquals(1, is.turns);
    }

    @Test
    void addTurn_accumulatesSentenceCount() {
        TurnScorer.InteractionScore is = new TurnScorer.InteractionScore("Senator Smith", "Judge Doe");
        SpeakerTurn st = senatorTurn("Senator", "Smith", 1);
        NomineeInfo nom = nominee("Doe");
        is.addTurn(new ScoredTurn(direct(st, nom, 1.0), 3, 3));
        is.addTurn(new ScoredTurn(direct(st, nom, 1.0), 2, 2));
        assertEquals(5, is.sentences);
    }

    @Test
    void addTurn_accumulatesTotalWeightedScore() {
        TurnScorer.InteractionScore is = new TurnScorer.InteractionScore("Senator Smith", "Judge Doe");
        SpeakerTurn st = senatorTurn("Senator", "Smith", 1);
        NomineeInfo nom = nominee("Doe");
        // Turn 1: avgScore=2.0, confidence=0.5 → weighted=1.0
        is.addTurn(new ScoredTurn(direct(st, nom, 0.5), 1, 2));
        // Turn 2: avgScore=1.0, confidence=1.0 → weighted=1.0
        is.addTurn(new ScoredTurn(direct(st, nom, 1.0), 1, 1));
        assertEquals(2.0, is.totalWeighted, 1e-10);
    }

    @Test
    void avgWeighted_dividesTotalByTurnCount() {
        TurnScorer.InteractionScore is = new TurnScorer.InteractionScore("Senator Smith", "Judge Doe");
        SpeakerTurn st = senatorTurn("Senator", "Smith", 1);
        NomineeInfo nom = nominee("Doe");
        // Turn 1: weighted = 2.0 * 1.0 = 2.0
        is.addTurn(new ScoredTurn(direct(st, nom, 1.0), 1, 2));
        // Turn 2: weighted = 0.0 * 1.0 = 0.0
        is.addTurn(new ScoredTurn(direct(st, nom, 1.0), 1, 0));
        // avg = (2.0 + 0.0) / 2 = 1.0
        assertEquals(1.0, is.avgWeighted(), 1e-10);
    }

    @Test
    void avgWeighted_zeroWhenNoTurns() {
        TurnScorer.InteractionScore is = new TurnScorer.InteractionScore("Senator Smith", "Judge Doe");
        assertEquals(0.0, is.avgWeighted(), 1e-10);
    }

    // ── Reliability tier thresholds ───────────────────────────────────
    // Tier boundaries from InteractionScore.reliabilityTier():
    //   >= 0.85 → "HIGH",  >= 0.65 → "MED",  < 0.65 → "LOW"

    private TurnScorer.InteractionScore isWithAvgConf(double confidence) {
        TurnScorer.InteractionScore is = new TurnScorer.InteractionScore("Senator Smith", "Judge Doe");
        SpeakerTurn st = senatorTurn("Senator", "Smith", 1);
        NomineeInfo nom = nominee("Doe");
        is.addTurn(new ScoredTurn(direct(st, nom, confidence), 1, 0));
        return is;
    }

    @Test
    void reliabilityTier_high_above85() {
        assertEquals("HIGH", isWithAvgConf(0.90).reliabilityTier());
        assertEquals("HIGH", isWithAvgConf(1.00).reliabilityTier());
    }

    @Test
    void reliabilityTier_high_atBoundary85() {
        assertEquals("HIGH", isWithAvgConf(0.85).reliabilityTier());
    }

    @Test
    void reliabilityTier_med_between65and84() {
        assertEquals("MED", isWithAvgConf(0.75).reliabilityTier());
        assertEquals("MED", isWithAvgConf(0.70).reliabilityTier());
    }

    @Test
    void reliabilityTier_med_atBoundary65() {
        assertEquals("MED", isWithAvgConf(0.65).reliabilityTier());
    }

    @Test
    void reliabilityTier_low_below65() {
        assertEquals("LOW", isWithAvgConf(0.64).reliabilityTier());
        assertEquals("LOW", isWithAvgConf(0.30).reliabilityTier());
        assertEquals("LOW", isWithAvgConf(0.00).reliabilityTier());
    }

    // ── avgResolutionConf accumulation ────────────────────────────────

    @Test
    void avgResolutionConf_averagesAcrossAllTurns() {
        TurnScorer.InteractionScore is = new TurnScorer.InteractionScore("Senator Smith", "Judge Doe");
        SpeakerTurn st = senatorTurn("Senator", "Smith", 1);
        NomineeInfo nom = nominee("Doe");
        is.addTurn(new ScoredTurn(direct(st, nom, 0.9), 1, 0));
        is.addTurn(new ScoredTurn(direct(st, nom, 0.7), 1, 0));
        // avg = (0.9 + 0.7) / 2 = 0.8
        assertEquals(0.8, is.avgResolutionConf(), 1e-10);
    }

    // ── TurnScorer.aggregate() ────────────────────────────────────────

    @Test
    void aggregate_createsSeparateEntryPerPair() {
        SpeakerTurn smit = senatorTurn("Senator", "Smith",   1);
        SpeakerTurn john = senatorTurn("Senator", "Johnson", 2);
        NomineeInfo doe  = nominee("Doe");

        List<ScoredTurn> scored = List.of(
            scored(direct(smit, doe, 0.9), 1),
            scored(direct(john, doe, 0.9), 1)
        );

        SpeakerAliasResolver.AliasMap aliases =
            SpeakerAliasResolver.resolve(List.of(smit, john));

        Map<String, TurnScorer.InteractionScore> result =
            TurnScorer.aggregate(scored, aliases);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("Senator Smith|Judge Doe"));
        assertTrue(result.containsKey("Senator Johnson|Judge Doe"));
    }

    @Test
    void aggregate_groupsMultipleTurnsForSamePair() {
        SpeakerTurn st = senatorTurn("Senator", "Smith", 1);
        NomineeInfo nom = nominee("Doe");

        List<ScoredTurn> scored = List.of(
            scored(direct(st, nom, 0.9), 2),
            scored(direct(st, nom, 0.9), -1)
        );

        SpeakerAliasResolver.AliasMap aliases =
            SpeakerAliasResolver.resolve(List.of(st));

        Map<String, TurnScorer.InteractionScore> result =
            TurnScorer.aggregate(scored, aliases);

        assertEquals(1, result.size());
        TurnScorer.InteractionScore is = result.get("Senator Smith|Judge Doe");
        assertNotNull(is);
        assertEquals(2, is.turns);
    }

    @Test
    void aggregate_skipsSelfTurns() {
        SpeakerTurn st = senatorTurn("Senator", "Smith", 1);
        NomineeInfo nom = nominee("Doe");
        ScoredTurn selfTurn = scored(self(st, nom), 0);

        SpeakerAliasResolver.AliasMap aliases =
            SpeakerAliasResolver.resolve(List.of(st));

        Map<String, TurnScorer.InteractionScore> result =
            TurnScorer.aggregate(List.of(selfTurn), aliases);

        assertTrue(result.isEmpty());
    }

    @Test
    void aggregate_appliesAlias_canonicalizesKey() {
        // "Ranking Member Sessions" and "Senator Sessions" should land in the
        // same entry after alias resolution elects "Senator Sessions" canonical.
        SpeakerTurn rankTurn = senatorTurn("Ranking Member", "Sessions", 1);
        SpeakerTurn senTurn  = senatorTurn("Senator",        "Sessions", 2);
        SpeakerTurn senTurn2 = senatorTurn("Senator",        "Sessions", 3);
        NomineeInfo nom = nominee("Doe");

        List<SpeakerTurn> allTurns = List.of(rankTurn, senTurn, senTurn2);
        SpeakerAliasResolver.AliasMap aliases = SpeakerAliasResolver.resolve(allTurns);

        // "Senator Sessions" appears twice → higher count → elected canonical
        List<ScoredTurn> scored = List.of(
            scored(direct(rankTurn, nom, 0.9), 1),
            scored(direct(senTurn,  nom, 0.9), 1)
        );

        Map<String, TurnScorer.InteractionScore> result =
            TurnScorer.aggregate(scored, aliases);

        // Both turns should merge into the canonical key
        assertEquals(1, result.size());
        assertTrue(result.containsKey("Senator Sessions|Judge Doe"));
        assertEquals(2, result.get("Senator Sessions|Judge Doe").turns);
    }

    @Test
    void aggregate_avgWeighted_correctAcrossMultipleTurns() {
        SpeakerTurn st = senatorTurn("Senator", "Smith", 1);
        NomineeInfo nom = nominee("Doe");

        // Turn 1: avgScore=2.0, confidence=1.0 → weighted=2.0
        // Turn 2: avgScore=-1.0, confidence=1.0 → weighted=-1.0
        // avg = (2.0 + -1.0) / 2 = 0.5
        List<ScoredTurn> scored = List.of(
            new ScoredTurn(direct(st, nom, 1.0), 1, 2),
            new ScoredTurn(direct(st, nom, 1.0), 1, -1)
        );

        SpeakerAliasResolver.AliasMap aliases =
            SpeakerAliasResolver.resolve(List.of(st));

        TurnScorer.InteractionScore is =
            TurnScorer.aggregate(scored, aliases).get("Senator Smith|Judge Doe");

        assertEquals(0.5, is.avgWeighted(), 1e-10);
    }
}
