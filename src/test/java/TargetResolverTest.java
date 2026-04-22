import org.junit.jupiter.api.Test;

/**
 * Unit tests for TargetResolver.
 * Covers each resolution method: SELF, DIRECT_ADDRESS, RESPONSE_PAIR,
 * PRIOR_CONTEXT, SECTION, and UNKNOWN.
 */
class TargetResolverTest {

    // ── SELF resolution ───────────────────────────────────────────────

    @Test
    void nomineeTurnResolvedAsSelf() {
        // TODO: build turns where the nominee is the speaker and verify
        //       ResolvedTarget.isSelfTurn() == true
    }

    // ── DIRECT_ADDRESS resolution ─────────────────────────────────────

    @Test
    void directAddressPatternResolvesNominee() {
        // TODO: build a senator turn containing "Judge Chen," or similar
        //       direct address pattern and verify the resolved nominee matches
    }

    @Test
    void directAddressWithMrMsPatternResolved() {
        // TODO: verify "Mr. Smith," and "Ms. Johnson," patterns are detected
    }

    // ── RESPONSE_PAIR resolution ──────────────────────────────────────

    @Test
    void senatorTurnFollowingNomineeResolvesAsResponsePair() {
        // TODO: build a nominee turn immediately followed by a senator turn
        //       and verify the senator turn resolves via RESPONSE_PAIR
    }

    // ── SECTION resolution ────────────────────────────────────────────

    @Test
    void singleNomineeSectionResolvesAllTurns() {
        // TODO: build a transcript section with one nominee declared in header
        //       and verify all senator turns resolve to that nominee via SECTION
    }

    @Test
    void multipleNomineesInSectionProducesUnknown() {
        // TODO: build a panel section with multiple nominees and verify
        //       ambiguous turns resolve as UNKNOWN with lower confidence
    }

    // ── Section detection ─────────────────────────────────────────────

    @Test
    void nominationsHeaderDetectedCorrectly() {
        // TODO: verify detectSections() parses "NOMINATIONS OF" header and
        //       extracts nominee name and position
    }

    // ── Confidence scoring ────────────────────────────────────────────

    @Test
    void directAddressHasHigherConfidenceThanSection() {
        // TODO: verify that DIRECT_ADDRESS confidence > SECTION confidence
        //       for the same nominee
    }
}
