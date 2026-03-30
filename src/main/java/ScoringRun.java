import java.sql.*;
import java.util.*;

/**
 * ScoringRun.java
 *
 * Database entity for the scoring_runs table.
 */
public class ScoringRun {

    private int id;
    private int hearingId;
    private String scoredAt;
    private String parserModel;
    private Integer totalTurns;
    private Integer scoredTurns;
    private Integer senatorTurns;
    private Integer selfTurns;
    private Integer skippedTurns;
    private Integer sentencesProcessed;
    private Integer uniquePairs;
    private Double avgConfidence;
    private Double scoringTimeSeconds;

    public ScoringRun() {
        this.id = 0;
    }

    public ScoringRun(int hearingId, String scoredAt, String parserModel,
                      Integer totalTurns, Integer scoredTurns, Integer senatorTurns,
                      Integer selfTurns, Integer skippedTurns, Integer sentencesProcessed,
                      Integer uniquePairs, Double avgConfidence, Double scoringTimeSeconds) {
        this.id = 0;
        this.hearingId = hearingId;
        this.scoredAt = scoredAt;
        this.parserModel = parserModel;
        this.totalTurns = totalTurns;
        this.scoredTurns = scoredTurns;
        this.senatorTurns = senatorTurns;
        this.selfTurns = selfTurns;
        this.skippedTurns = skippedTurns;
        this.sentencesProcessed = sentencesProcessed;
        this.uniquePairs = uniquePairs;
        this.avgConfidence = avgConfidence;
        this.scoringTimeSeconds = scoringTimeSeconds;
    }

    public ScoringRun save(DatabaseManager db) throws SQLException {
        if (id == 0) {
            String sql = "INSERT INTO scoring_runs "
                + "(hearing_id, scored_at, parser_model, total_turns, scored_turns, senator_turns, "
                + "self_turns, skipped_turns, sentences_processed, unique_pairs, avg_confidence, scoring_time_seconds) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            Map<Integer, Object> params = new LinkedHashMap<>();
            params.put(1, hearingId);
            params.put(2, scoredAt);
            params.put(3, parserModel);
            params.put(4, totalTurns);
            params.put(5, scoredTurns);
            params.put(6, senatorTurns);
            params.put(7, selfTurns);
            params.put(8, skippedTurns);
            params.put(9, sentencesProcessed);
            params.put(10, uniquePairs);
            params.put(11, avgConfidence);
            params.put(12, scoringTimeSeconds);

            long generatedId = db.executeInsert(sql, params);
            if (generatedId > 0) {
                this.id = (int) generatedId;
            }
            System.out.printf("[ScoringRun] Inserted id=%d (hearing=%d)%n", id, hearingId);
        } else {
            String sql = "UPDATE scoring_runs SET "
                + "hearing_id = ?, scored_at = ?, parser_model = ?, total_turns = ?, scored_turns = ?, "
                + "senator_turns = ?, self_turns = ?, skipped_turns = ?, sentences_processed = ?, unique_pairs = ?, "
                + "avg_confidence = ?, scoring_time_seconds = ? "
                + "WHERE id = ?";

            Map<Integer, Object> params = new LinkedHashMap<>();
            params.put(1, hearingId);
            params.put(2, scoredAt);
            params.put(3, parserModel);
            params.put(4, totalTurns);
            params.put(5, scoredTurns);
            params.put(6, senatorTurns);
            params.put(7, selfTurns);
            params.put(8, skippedTurns);
            params.put(9, sentencesProcessed);
            params.put(10, uniquePairs);
            params.put(11, avgConfidence);
            params.put(12, scoringTimeSeconds);
            params.put(13, id);

            int affected = db.execute(sql, params);
            System.out.printf("[ScoringRun] Updated id=%d (%d row%s affected)%n",
                id, affected, affected == 1 ? "" : "s");
        }

        return this;
    }

    public static ScoringRun load(DatabaseManager db, int id) throws SQLException {
        String sql = "SELECT * FROM scoring_runs WHERE id = ?";
        List<Map<String, Object>> rows = db.executeQuery(sql, Map.of(1, id));

        if (rows.isEmpty()) {
            System.out.printf("[ScoringRun] No record found with id=%d%n", id);
            return null;
        }

        ScoringRun run = fromRow(rows.get(0));
        System.out.printf("[ScoringRun] Loaded id=%d%n", run.id);
        return run;
    }

    public static List<ScoringRun> loadAll(DatabaseManager db) throws SQLException {
        String sql = "SELECT * FROM scoring_runs ORDER BY hearing_id, scored_at, id";
        List<Map<String, Object>> rows = db.executeQuery(sql);

        List<ScoringRun> runs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            runs.add(fromRow(row));
        }

        System.out.printf("[ScoringRun] Loaded %d record(s)%n", runs.size());
        return runs;
    }

    public static boolean delete(DatabaseManager db, int id) throws SQLException {
        String sql = "DELETE FROM scoring_runs WHERE id = ?";
        int affected = db.execute(sql, Map.of(1, id));

        System.out.printf("[ScoringRun] Deleted id=%d (%d row%s affected)%n",
            id, affected, affected == 1 ? "" : "s");
        return affected > 0;
    }

    private static ScoringRun fromRow(Map<String, Object> row) {
        ScoringRun run = new ScoringRun();
        run.id = toInt(row.get("id"));
        run.hearingId = toInt(row.get("hearing_id"));
        run.scoredAt = row.get("scored_at") != null ? row.get("scored_at").toString() : null;
        run.parserModel = (String) row.get("parser_model");
        run.totalTurns = toInteger(row.get("total_turns"));
        run.scoredTurns = toInteger(row.get("scored_turns"));
        run.senatorTurns = toInteger(row.get("senator_turns"));
        run.selfTurns = toInteger(row.get("self_turns"));
        run.skippedTurns = toInteger(row.get("skipped_turns"));
        run.sentencesProcessed = toInteger(row.get("sentences_processed"));
        run.uniquePairs = toInteger(row.get("unique_pairs"));
        run.avgConfidence = toDouble(row.get("avg_confidence"));
        run.scoringTimeSeconds = toDouble(row.get("scoring_time_seconds"));
        return run;
    }

    private static int toInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static Integer toInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Double toDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    public int getId() { return id; }
    public int getHearingId() { return hearingId; }
    public String getScoredAt() { return scoredAt; }
    public String getParserModel() { return parserModel; }
    public Integer getTotalTurns() { return totalTurns; }
    public Integer getScoredTurns() { return scoredTurns; }
    public Integer getSenatorTurns() { return senatorTurns; }
    public Integer getSelfTurns() { return selfTurns; }
    public Integer getSkippedTurns() { return skippedTurns; }
    public Integer getSentencesProcessed() { return sentencesProcessed; }
    public Integer getUniquePairs() { return uniquePairs; }
    public Double getAvgConfidence() { return avgConfidence; }
    public Double getScoringTimeSeconds() { return scoringTimeSeconds; }

    public void setId(int id) { this.id = id; }
    public void setHearingId(int hearingId) { this.hearingId = hearingId; }
    public void setScoredAt(String scoredAt) { this.scoredAt = scoredAt; }
    public void setParserModel(String parserModel) { this.parserModel = parserModel; }
    public void setTotalTurns(Integer totalTurns) { this.totalTurns = totalTurns; }
    public void setScoredTurns(Integer scoredTurns) { this.scoredTurns = scoredTurns; }
    public void setSenatorTurns(Integer senatorTurns) { this.senatorTurns = senatorTurns; }
    public void setSelfTurns(Integer selfTurns) { this.selfTurns = selfTurns; }
    public void setSkippedTurns(Integer skippedTurns) { this.skippedTurns = skippedTurns; }
    public void setSentencesProcessed(Integer sentencesProcessed) { this.sentencesProcessed = sentencesProcessed; }
    public void setUniquePairs(Integer uniquePairs) { this.uniquePairs = uniquePairs; }
    public void setAvgConfidence(Double avgConfidence) { this.avgConfidence = avgConfidence; }
    public void setScoringTimeSeconds(Double scoringTimeSeconds) { this.scoringTimeSeconds = scoringTimeSeconds; }

    @Override
    public String toString() {
        return String.format("ScoringRun[id=%d, hearingId=%d, scoredAt=%s]",
            id, hearingId, scoredAt);
    }
}
