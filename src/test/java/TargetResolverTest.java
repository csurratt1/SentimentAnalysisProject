import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

/**
 * Tests for resolution result correctness via the ResolvedTarget model.
 *
 * Full TargetResolver integration tests (section detection, NER fallback)
 * require CoreNLP and live in a separate integration suite.
 * These tests cover the ResolvedTarget model logic that the resolver produces:
 * hasSpecificTarget, isSelfTurn, confidence storage, and label formatting.
 */
class TargetResolverTest {

    // ── Fixtures ──────────────────────────────────────────────────────

    private static SpeakerTurn turn(String title, String lastName) {
        return new SpeakerTurn(title, lastName, 1, "Some text.", 0);
    }

    private static NomineeInfo nominee() {
        return new NomineeInfo("Jane", "Doe", "U.S. Circuit Judge", "Judge");
    }

    private static ResolvedTarget resolve(ResolvedTarget.Method method,
                                          NomineeInfo nom, double conf) {
        return new ResolvedTarget(
            turn("Senator", "Smith"), nom,
            Collections.emptyList(), method, conf);
    }

    // ── hasSpecificTarget ─────────────────────────────────────────────

    @Test
    void hasSpecificTarget_trueForDirectAddress() {
        ResolvedTarget rt = resolve(ResolvedTarget.Method.DIRECT_ADDRESS, nominee(), 0.95);
        assertTrue(rt.hasSpecificTarget());
    }

    @Test
    void hasSpecificTarget_trueForResponsePair() {
        ResolvedTarget rt = resolve(ResolvedTarget.Method.RESPONSE_PAIR, nominee(), 0.85);
        assertTrue(rt.hasSpecificTarget());
    }

    @Test
    void hasSpecificTarget_trueForPriorContext() {
        ResolvedTarget rt = resolve(ResolvedTarget.Method.PRIOR_CONTEXT, nominee(), 0.70);
        assertTrue(rt.hasSpecificTarget());
    }

    @Test
    void hasSpecificTarget_trueForSectionDefault() {
        ResolvedTarget rt = resolve(ResolvedTarget.Method.SECTION_DEFAULT, nominee(), 0.30);
        assertTrue(rt.hasSpecificTarget());
    }

    @Test
    void hasSpecificTarget_falseForUnknown() {
        ResolvedTarget rt = resolve(ResolvedTarget.Method.UNKNOWN, null, 0.0);
        assertFalse(rt.hasSpecificTarget());
    }

    @Test
    void hasSpecificTarget_falseWhenNomineeIsNull() {
        ResolvedTarget rt = new ResolvedTarget(
            turn("Senator", "Smith"), null,
            Collections.emptyList(),
            ResolvedTarget.Method.SECTION_DEFAULT, 0.30);
        // Null nominee → no specific target even if method is known
        assertFalse(rt.hasSpecificTarget());
    }

    // ── isSelfTurn ────────────────────────────────────────────────────

    @Test
    void isSelfTurn_trueOnlyForSelfMethod() {
        ResolvedTarget self = resolve(ResolvedTarget.Method.SELF, nominee(), 1.0);
        assertTrue(self.isSelfTurn());
    }

    @Test
    void isSelfTurn_falseForAllOtherMethods() {
        for (ResolvedTarget.Method m : ResolvedTarget.Method.values()) {
            if (m == ResolvedTarget.Method.SELF) continue;
            NomineeInfo nom = m == ResolvedTarget.Method.UNKNOWN ? null : nominee();
            ResolvedTarget rt = resolve(m, nom, 0.5);
            assertFalse(rt.isSelfTurn(), "Expected isSelfTurn=false for method " + m);
        }
    }

    // ── Confidence storage ────────────────────────────────────────────

    @Test
    void confidence_storedAndRetrieved() {
        ResolvedTarget rt = resolve(ResolvedTarget.Method.DIRECT_ADDRESS, nominee(), 0.95);
        assertEquals(0.95, rt.getConfidence(), 1e-10);
    }

    @Test
    void confidence_zeroForUnknown() {
        ResolvedTarget rt = resolve(ResolvedTarget.Method.UNKNOWN, null, 0.0);
        assertEquals(0.0, rt.getConfidence(), 1e-10);
    }

    // ── Method stored ─────────────────────────────────────────────────

    @Test
    void method_storedAndRetrieved() {
        for (ResolvedTarget.Method m : ResolvedTarget.Method.values()) {
            NomineeInfo nom = m == ResolvedTarget.Method.UNKNOWN ? null : nominee();
            ResolvedTarget rt = resolve(m, nom, 0.5);
            assertEquals(m, rt.getMethod());
        }
    }

    // ── Panel nominees (immutability) ─────────────────────────────────

    @Test
    void panelNominees_stored_andUnmodifiable() {
        List<NomineeInfo> panel = new ArrayList<>(List.of(
            new NomineeInfo("Jane", "Doe",    "Judge", "Judge"),
            new NomineeInfo("John", "Martin", "Judge", "Judge")
        ));
        ResolvedTarget rt = new ResolvedTarget(
            turn("Senator", "Smith"), null,
            panel, ResolvedTarget.Method.SECTION_DEFAULT, 0.30);

        // Cannot mutate the stored list
        assertThrows(UnsupportedOperationException.class,
            () -> rt.getPanelNominees().add(nominee()));
    }

    @Test
    void panelNominees_nullInput_treatedAsEmpty() {
        ResolvedTarget rt = new ResolvedTarget(
            turn("Senator", "Smith"), nominee(),
            null, ResolvedTarget.Method.DIRECT_ADDRESS, 0.95);
        assertNotNull(rt.getPanelNominees());
        assertTrue(rt.getPanelNominees().isEmpty());
    }

    // ── Confidence ranking sanity (method hierarchy) ──────────────────
    // Direct address should carry higher confidence than section default
    // in typical usage — these assertions document the expected ordering.

    @Test
    void directAddress_typicallyHigherConfidenceThan_sectionDefault() {
        double directConf  = 0.95;
        double sectionConf = 0.30;
        ResolvedTarget direct  = resolve(ResolvedTarget.Method.DIRECT_ADDRESS,  nominee(), directConf);
        ResolvedTarget section = resolve(ResolvedTarget.Method.SECTION_DEFAULT, nominee(), sectionConf);
        assertTrue(direct.getConfidence() > section.getConfidence());
    }
}
