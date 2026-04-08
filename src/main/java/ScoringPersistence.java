import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * ScoringPersistence.java
 *
 * Persists one scoring run into MySQL using the project entity classes.
 */
public class ScoringPersistence {

    public static class PersistenceResult {
        private final int hearingId;
        private final int scoringRunId;
        private final int turnsInserted;
        private final int turnScoresInserted;
        private final boolean replacedExistingRun;

        public PersistenceResult(int hearingId,
                                 int scoringRunId,
                                 int turnsInserted,
                                 int turnScoresInserted,
                                 boolean replacedExistingRun) {
            this.hearingId = hearingId;
            this.scoringRunId = scoringRunId;
            this.turnsInserted = turnsInserted;
            this.turnScoresInserted = turnScoresInserted;
            this.replacedExistingRun = replacedExistingRun;
        }

        public int getHearingId() { return hearingId; }
        public int getScoringRunId() { return scoringRunId; }
        public int getTurnsInserted() { return turnsInserted; }
        public int getTurnScoresInserted() { return turnScoresInserted; }
        public boolean isReplacedExistingRun() { return replacedExistingRun; }
    }

    private static final Set<String> SENATOR_TITLES = Set.of(
        "Chairman", "The Chairman", "Senator"
    );

    private static final Set<String> NOMINEE_TITLES = Set.of(
        "Mr.", "Ms.", "Mrs.", "Judge"
    );

    private static final DateTimeFormatter SECTION_DATE_FORMAT =
        DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    private static final DateTimeFormatter SCORED_AT_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static PersistenceResult persistRun(String inputFile,
                                               List<String> lines,
                                               List<ResolvedTarget> resolved,
                                               List<ScoredTurn> scored,
                                               Map<String, TurnScorer.InteractionScore> interactions,
                                               long totalMs) throws SQLException {

        DatabaseManager db = new DatabaseManager();
        Connection conn = null;
        boolean originalAutoCommit = true;

        try {
            db.connect();
            conn = db.getConnection();
            originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            List<HearingSection> sections = TargetResolver.detectSections(lines);
            String sourceFile = inputFile != null
                ? java.nio.file.Paths.get(inputFile).getFileName().toString()
                : "unknown.txt";

            Integer existingHearingId = findHearingIdBySourceFile(db, sourceFile);
            int hearingId = upsertHearing(db, sourceFile, sections, existingHearingId);

            boolean replacedExistingRun = false;
            if (existingHearingId != null) {
                if (hasExistingScoringRun(db, existingHearingId)) {
                    replacedExistingRun = true;
                    System.out.printf("[DB] Existing run found for source_file='%s'. Replacing prior data to keep exactly one run per file.%n",
                        sourceFile);
                }
                clearHearingChildren(db, hearingId);
            }

            Map<String, NomineeInfo> nomineeByKey = collectNomineesByKey(resolved, sections);
            Map<Integer, Map<String, NomineeInfo>> sectionNominees = collectNomineesBySection(resolved, sections);

            Map<Integer, Integer> sectionDbIds = insertSections(db, hearingId, sections);
            Map<String, Integer> nominationIds = insertNominations(
                db, hearingId, sections, sectionNominees, nomineeByKey, sectionDbIds
            );
            Map<Integer, Integer> turnIds = insertTurns(
                db, hearingId, resolved, sections, sectionDbIds, nomineeByKey, sectionNominees
            );

            int scoringRunId = insertScoringRun(db, hearingId, resolved, scored, interactions, totalMs);
            int turnScoresInserted = insertTurnScores(db, scoringRunId, scored, turnIds, sections, nominationIds);

            conn.commit();
            System.out.printf("[DB] Persisted hearing=%d run=%d turns=%d scored=%d%n",
                hearingId, scoringRunId, resolved.size(), scored.size());

            return new PersistenceResult(
                hearingId,
                scoringRunId,
                turnIds.size(),
                turnScoresInserted,
                replacedExistingRun
            );

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                    System.err.println("[DB] Transaction rolled back.");
                } catch (SQLException rollbackEx) {
                    System.err.println("[DB] Rollback failed: " + rollbackEx.getMessage());
                }
            }
            throw e;
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignored) {
                    // Ignore reset failures during cleanup.
                }
            }
            db.disconnect();
        }
    }

    private static Integer findHearingIdBySourceFile(DatabaseManager db, String sourceFile)
            throws SQLException {
        String sql = "SELECT id FROM hearings WHERE source_file = ? LIMIT 1";
        List<Map<String, Object>> rows = db.executeQuery(sql, Map.of(1, sourceFile));
        if (rows.isEmpty()) return null;
        return ((Number) rows.get(0).get("id")).intValue();
    }

    private static int upsertHearing(DatabaseManager db,
                                     String sourceFile,
                                     List<HearingSection> sections,
                                     Integer existingId) throws SQLException {
        Hearing hearing = new Hearing();
        if (existingId != null) {
            hearing.setId(existingId);
        }

        hearing.setHearingDate(inferHearingDate(sections));
        hearing.setSession(null);
        hearing.setSerialNumber(null);
        hearing.setCommittee("Senate Judiciary Committee");
        hearing.setSourceFile(sourceFile);
        hearing.setTitle("Transcript: " + sourceFile);
        hearing.save(db);

        return hearing.getId();
    }

    private static String inferHearingDate(List<HearingSection> sections) {
        for (HearingSection section : sections) {
            if (section.getDate() == null || section.getDate().isBlank()) continue;
            try {
                LocalDate date = LocalDate.parse(section.getDate(), SECTION_DATE_FORMAT);
                return date.toString();
            } catch (DateTimeParseException ignored) {
                // Keep searching if one section date format is unexpected.
            }
        }
        return null;
    }

    private static void clearHearingChildren(DatabaseManager db, int hearingId) throws SQLException {
        db.execute("DELETE FROM scoring_runs WHERE hearing_id = ?", Map.of(1, hearingId));
        db.execute("DELETE FROM turns WHERE hearing_id = ?", Map.of(1, hearingId));
        db.execute("DELETE FROM nominations WHERE hearing_id = ?", Map.of(1, hearingId));
        db.execute("DELETE FROM hearing_sections WHERE hearing_id = ?", Map.of(1, hearingId));
    }

    private static boolean hasExistingScoringRun(DatabaseManager db, int hearingId) throws SQLException {
        String sql = "SELECT COUNT(*) AS cnt FROM scoring_runs WHERE hearing_id = ?";
        List<Map<String, Object>> rows = db.executeQuery(sql, Map.of(1, hearingId));
        if (rows.isEmpty()) return false;
        Number count = (Number) rows.get(0).get("cnt");
        return count != null && count.intValue() > 0;
    }

    private static Map<String, NomineeInfo> collectNomineesByKey(
            List<ResolvedTarget> resolved,
            List<HearingSection> sections) {
        Map<String, NomineeInfo> nominees = new LinkedHashMap<>();

        for (HearingSection section : sections) {
            for (NomineeInfo nominee : section.getNominees()) {
                nominees.put(nomineeKey(nominee), nominee);
            }
        }

        for (ResolvedTarget rt : resolved) {
            if (rt.getNominee() != null) {
                NomineeInfo nominee = rt.getNominee();
                nominees.put(nomineeKey(nominee), nominee);
            }
            for (NomineeInfo nominee : rt.getPanelNominees()) {
                nominees.put(nomineeKey(nominee), nominee);
            }
        }

        return nominees;
    }

    private static Map<Integer, Map<String, NomineeInfo>> collectNomineesBySection(
            List<ResolvedTarget> resolved,
            List<HearingSection> sections) {
        Map<Integer, Map<String, NomineeInfo>> bySection = new LinkedHashMap<>();

        for (HearingSection section : sections) {
            Map<String, NomineeInfo> bucket = bySection.computeIfAbsent(
                section.getSectionNumber(), k -> new LinkedHashMap<>()
            );
            for (NomineeInfo nominee : section.getNominees()) {
                bucket.put(nomineeKey(nominee), nominee);
            }
        }

        for (ResolvedTarget rt : resolved) {
            HearingSection section = findSection(rt.getTurn().getStartLine(), sections);
            if (section == null) continue;

            Map<String, NomineeInfo> bucket = bySection.computeIfAbsent(
                section.getSectionNumber(), k -> new LinkedHashMap<>()
            );

            for (NomineeInfo nominee : rt.getPanelNominees()) {
                bucket.put(nomineeKey(nominee), nominee);
            }
            if (rt.getNominee() != null) {
                NomineeInfo nominee = rt.getNominee();
                bucket.put(nomineeKey(nominee), nominee);
            }
        }

        return bySection;
    }

    private static Map<Integer, Integer> insertSections(DatabaseManager db,
                                                        int hearingId,
                                                        List<HearingSection> sections)
            throws SQLException {
        Map<Integer, Integer> sectionDbIds = new LinkedHashMap<>();

        for (HearingSection section : sections) {
            String sql = "INSERT INTO hearing_sections "
                + "(hearing_id, section_number, header_text, section_date, start_line, end_line) "
                + "VALUES (?, ?, ?, ?, ?, ?)";

            Map<Integer, Object> params = new LinkedHashMap<>();
            params.put(1, hearingId);
            params.put(2, section.getSectionNumber());
            params.put(3, section.getHeaderText());
            params.put(4, section.getDate());
            params.put(5, section.getStartLine());
            params.put(6, section.getEndLine() == Integer.MAX_VALUE ? null : section.getEndLine());

            long id = db.executeInsert(sql, params);
            sectionDbIds.put(section.getSectionNumber(), (int) id);
        }

        return sectionDbIds;
    }

    private static Map<String, Integer> insertNominations(DatabaseManager db,
                                                          int hearingId,
                                                          List<HearingSection> sections,
                                                          Map<Integer, Map<String, NomineeInfo>> sectionNominees,
                                                          Map<String, NomineeInfo> nomineeByKey,
                                                          Map<Integer, Integer> sectionDbIds)
            throws SQLException {
        Map<String, Integer> nominationIds = new LinkedHashMap<>();

        for (HearingSection section : sections) {
            Integer sectionId = sectionDbIds.get(section.getSectionNumber());
            if (sectionId == null) continue;

            Map<String, NomineeInfo> nominees = sectionNominees.getOrDefault(
                section.getSectionNumber(), Collections.emptyMap()
            );

            for (NomineeInfo nominee : nominees.values()) {
                int speakerId = getOrCreateSpeaker(
                    db,
                    nominee.getFirstName(),
                    nominee.getLastName(),
                    "NOMINEE"
                );

                Nomination row = new Nomination(
                    hearingId,
                    sectionId,
                    speakerId,
                    nominee.getPosition(),
                    nominee.getTitleUsed()
                );
                row.save(db);

                String nomKey = nomineeKey(nominee);
                String bySectionKey = section.getSectionNumber() + "|" + nomKey;
                nominationIds.put(bySectionKey, row.getId());
                nominationIds.putIfAbsent("NOM|" + nomKey, row.getId());

                nomineeByKey.put(nomKey, nominee);
            }
        }

        return nominationIds;
    }

    private static Map<Integer, Integer> insertTurns(DatabaseManager db,
                                                     int hearingId,
                                                     List<ResolvedTarget> resolved,
                                                     List<HearingSection> sections,
                                                     Map<Integer, Integer> sectionDbIds,
                                                     Map<String, NomineeInfo> nomineeByKey,
                                                     Map<Integer, Map<String, NomineeInfo>> sectionNominees)
            throws SQLException {
        Map<Integer, Integer> turnIds = new LinkedHashMap<>();

        for (ResolvedTarget rt : resolved) {
            SpeakerTurn turn = rt.getTurn();
            HearingSection section = findSection(turn.getStartLine(), sections);
            Integer sectionId = section != null ? sectionDbIds.get(section.getSectionNumber()) : null;

            NomineeInfo nomineeSpeaker = findNomineeSpeakerForTurn(
                turn,
                section,
                sectionNominees,
                nomineeByKey
            );
            String role = inferRole(turn, nomineeSpeaker);
            String firstName = ("NOMINEE".equals(role) && nomineeSpeaker != null)
                ? nomineeSpeaker.getFirstName()
                : null;

            int speakerId = getOrCreateSpeaker(db, firstName, turn.getLastName(), role);

            Turn row = new Turn(
                hearingId,
                sectionId,
                speakerId,
                turn.getTurnNumber(),
                turn.getSpeakerLabel(),
                turn.getText(),
                turn.getStartLine(),
                turn.getText() != null ? turn.getText().length() : 0,
                turn.hasSubstantiveText()
            );
            row.save(db);
            turnIds.put(turn.getTurnNumber(), row.getId());
        }

        return turnIds;
    }

    private static int insertScoringRun(DatabaseManager db,
                                        int hearingId,
                                        List<ResolvedTarget> resolved,
                                        List<ScoredTurn> scored,
                                        Map<String, TurnScorer.InteractionScore> interactions,
                                        long totalMs) throws SQLException {
        int selfTurns = 0;
        int senatorTurns = 0;
        int totalSentences = 0;
        double totalConfidence = 0.0;

        for (ScoredTurn st : scored) {
            totalSentences += st.getSentenceCount();
            totalConfidence += st.getTarget().getConfidence();
            if (st.isSelfTurn()) selfTurns++;
            else senatorTurns++;
        }

        ScoringRun run = new ScoringRun(
            hearingId,
            LocalDateTime.now().format(SCORED_AT_FORMAT),
            "englishSR.beam.ser.gz",
            resolved.size(),
            scored.size(),
            senatorTurns,
            selfTurns,
            resolved.size() - scored.size(),
            totalSentences,
            interactions.size(),
            scored.isEmpty() ? 0.0 : totalConfidence / scored.size(),
            totalMs / 1000.0
        );

        run.save(db);
        return run.getId();
    }

    private static int insertTurnScores(DatabaseManager db,
                                        int scoringRunId,
                                        List<ScoredTurn> scored,
                                        Map<Integer, Integer> turnIds,
                                        List<HearingSection> sections,
                                        Map<String, Integer> nominationIds)
            throws SQLException {
        int inserted = 0;
        for (ScoredTurn st : scored) {
            ResolvedTarget rt = st.getTarget();
            SpeakerTurn turn = rt.getTurn();

            Integer turnId = turnIds.get(turn.getTurnNumber());
            if (turnId == null) continue;

            Integer targetNominationId = null;
            if (rt.getNominee() != null) {
                String nomineeKey = nomineeKey(rt.getNominee());
                HearingSection section = findSection(turn.getStartLine(), sections);
                if (section != null) {
                    targetNominationId = nominationIds.get(section.getSectionNumber() + "|" + nomineeKey);
                }
                if (targetNominationId == null) {
                    targetNominationId = nominationIds.get("NOM|" + nomineeKey);
                }
            }

            TurnScore row = new TurnScore(
                turnId,
                scoringRunId,
                targetNominationId,
                rt.getMethod().toString(),
                rt.getConfidence(),
                st.getSentenceCount(),
                st.getTotalScore(),
                st.getAvgScore(),
                st.getWeightedScore()
            );
            row.save(db);
            inserted++;
        }
        return inserted;
    }

    private static HearingSection findSection(int lineNumber, List<HearingSection> sections) {
        for (int i = sections.size() - 1; i >= 0; i--) {
            if (sections.get(i).containsLine(lineNumber)) {
                return sections.get(i);
            }
        }
        return null;
    }

    private static String inferRole(SpeakerTurn turn, NomineeInfo nomineeSpeaker) {
        String title = turn.getTitle();

        if (SENATOR_TITLES.contains(title)) {
            return "SENATOR";
        }

        if (nomineeSpeaker != null) {
            return "NOMINEE";
        }

        if (NOMINEE_TITLES.contains(title)) {
            return "PRESENTER";
        }

        return "OTHER";
    }

    private static NomineeInfo findNomineeSpeakerForTurn(
            SpeakerTurn turn,
            HearingSection section,
            Map<Integer, Map<String, NomineeInfo>> sectionNominees,
            Map<String, NomineeInfo> nomineeByKey) {
        if (turn.getLastName() == null || turn.getLastName().isBlank()) {
            return null;
        }

        List<NomineeInfo> candidates = new ArrayList<>();
        String last = turn.getLastName();

        if (section != null) {
            Map<String, NomineeInfo> scoped = sectionNominees.getOrDefault(
                section.getSectionNumber(), Collections.emptyMap()
            );
            for (NomineeInfo nominee : scoped.values()) {
                if (nominee.getLastName().equalsIgnoreCase(last)) {
                    candidates.add(nominee);
                }
            }
        }

        if (candidates.isEmpty()) {
            for (NomineeInfo nominee : nomineeByKey.values()) {
                if (nominee.getLastName().equalsIgnoreCase(last)) {
                    candidates.add(nominee);
                }
            }
        }

        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        if (NOMINEE_TITLES.contains(turn.getTitle())) {
            for (NomineeInfo nominee : candidates) {
                if (nominee.getTitleUsed().equalsIgnoreCase(turn.getTitle())) {
                    return nominee;
                }
            }
        }

        return null;
    }

    private static String nomineeKey(NomineeInfo nominee) {
        String first = nominee.getFirstName() != null ? nominee.getFirstName().trim().toLowerCase(Locale.ROOT) : "";
        String last = nominee.getLastName() != null ? nominee.getLastName().trim().toLowerCase(Locale.ROOT) : "";
        return first + "|" + last;
    }

    private static int getOrCreateSpeaker(DatabaseManager db,
                                          String firstName,
                                          String lastName,
                                          String role) throws SQLException {
        if (lastName == null || lastName.isBlank()) {
            throw new SQLException("Speaker last name is required.");
        }

        Integer existing = findSpeakerId(db, firstName, lastName, role);
        if (existing != null) return existing;

        Speaker speaker = new Speaker(
            firstName,
            lastName,
            null,
            null,
            null,
            role
        );
        speaker.save(db);
        return speaker.getId();
    }

    private static Integer findSpeakerId(DatabaseManager db,
                                         String firstName,
                                         String lastName,
                                         String role) throws SQLException {
        if (firstName != null && !firstName.isBlank()) {
            String sql = "SELECT id FROM speakers WHERE last_name = ? AND role = ? AND first_name = ? LIMIT 1";
            List<Map<String, Object>> rows = db.executeQuery(sql, Map.of(1, lastName, 2, role, 3, firstName));
            if (!rows.isEmpty()) {
                return ((Number) rows.get(0).get("id")).intValue();
            }
        }

        String sql = "SELECT id FROM speakers WHERE last_name = ? AND role = ? LIMIT 1";
        List<Map<String, Object>> rows = db.executeQuery(sql, Map.of(1, lastName, 2, role));
        if (rows.isEmpty()) return null;
        return ((Number) rows.get(0).get("id")).intValue();
    }
}
