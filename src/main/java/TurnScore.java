import java.sql.*;
import java.util.*;

/**
 * TurnScore.java
 *
 * Database entity for the turn_scores table.
 */
public class TurnScore {

    private int id;
    private int turnId;
    private int scoringRunId;
    private Integer targetNominationId;
    private String resolutionMethod;
    private Double confidence;
    private Integer sentenceCount;
    private Integer totalScore;
    private Double avgScore;
    private Double weightedScore;

    public TurnScore() {
        this.id = 0;
    }

    public TurnScore(int turnId, int scoringRunId, Integer targetNominationId,
                     String resolutionMethod, Double confidence, Integer sentenceCount,
                     Integer totalScore, Double avgScore, Double weightedScore) {
        this.id = 0;
        this.turnId = turnId;
        this.scoringRunId = scoringRunId;
        this.targetNominationId = targetNominationId;
        this.resolutionMethod = resolutionMethod;
        this.confidence = confidence;
        this.sentenceCount = sentenceCount;
        this.totalScore = totalScore;
        this.avgScore = avgScore;
        this.weightedScore = weightedScore;
    }

    public TurnScore save(DatabaseManager db) throws SQLException {
        if (id == 0) {
            String sql = "INSERT INTO turn_scores "
                + "(turn_id, scoring_run_id, target_nomination_id, resolution_method, confidence, "
                + "sentence_count, total_score, avg_score, weighted_score) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

            Map<Integer, Object> params = new LinkedHashMap<>();
            params.put(1, turnId);
            params.put(2, scoringRunId);
            params.put(3, targetNominationId);
            params.put(4, resolutionMethod);
            params.put(5, confidence);
            params.put(6, sentenceCount);
            params.put(7, totalScore);
            params.put(8, avgScore);
            params.put(9, weightedScore);

            long generatedId = db.executeInsert(sql, params);
            if (generatedId > 0) {
                this.id = (int) generatedId;
            }
            System.out.printf("[TurnScore] Inserted id=%d (turn=%d, run=%d)%n",
                id, turnId, scoringRunId);
        } else {
            String sql = "UPDATE turn_scores SET "
                + "turn_id = ?, scoring_run_id = ?, target_nomination_id = ?, resolution_method = ?, "
                + "confidence = ?, sentence_count = ?, total_score = ?, avg_score = ?, weighted_score = ? "
                + "WHERE id = ?";

            Map<Integer, Object> params = new LinkedHashMap<>();
            params.put(1, turnId);
            params.put(2, scoringRunId);
            params.put(3, targetNominationId);
            params.put(4, resolutionMethod);
            params.put(5, confidence);
            params.put(6, sentenceCount);
            params.put(7, totalScore);
            params.put(8, avgScore);
            params.put(9, weightedScore);
            params.put(10, id);

            int affected = db.execute(sql, params);
            System.out.printf("[TurnScore] Updated id=%d (%d row%s affected)%n",
                id, affected, affected == 1 ? "" : "s");
        }

        return this;
    }

    public static TurnScore load(DatabaseManager db, int id) throws SQLException {
        String sql = "SELECT * FROM turn_scores WHERE id = ?";
        List<Map<String, Object>> rows = db.executeQuery(sql, Map.of(1, id));

        if (rows.isEmpty()) {
            System.out.printf("[TurnScore] No record found with id=%d%n", id);
            return null;
        }

        TurnScore turnScore = fromRow(rows.get(0));
        System.out.printf("[TurnScore] Loaded id=%d%n", turnScore.id);
        return turnScore;
    }

    public static List<TurnScore> loadAll(DatabaseManager db) throws SQLException {
        String sql = "SELECT * FROM turn_scores ORDER BY scoring_run_id, turn_id, id";
        List<Map<String, Object>> rows = db.executeQuery(sql);

        List<TurnScore> turnScores = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            turnScores.add(fromRow(row));
        }

        System.out.printf("[TurnScore] Loaded %d record(s)%n", turnScores.size());
        return turnScores;
    }

    public static boolean delete(DatabaseManager db, int id) throws SQLException {
        String sql = "DELETE FROM turn_scores WHERE id = ?";
        int affected = db.execute(sql, Map.of(1, id));

        System.out.printf("[TurnScore] Deleted id=%d (%d row%s affected)%n",
            id, affected, affected == 1 ? "" : "s");
        return affected > 0;
    }

    private static TurnScore fromRow(Map<String, Object> row) {
        TurnScore turnScore = new TurnScore();
        turnScore.id = toInt(row.get("id"));
        turnScore.turnId = toInt(row.get("turn_id"));
        turnScore.scoringRunId = toInt(row.get("scoring_run_id"));
        turnScore.targetNominationId = toInteger(row.get("target_nomination_id"));
        turnScore.resolutionMethod = row.get("resolution_method") != null
            ? row.get("resolution_method").toString() : null;
        turnScore.confidence = toDouble(row.get("confidence"));
        turnScore.sentenceCount = toInteger(row.get("sentence_count"));
        turnScore.totalScore = toInteger(row.get("total_score"));
        turnScore.avgScore = toDouble(row.get("avg_score"));
        turnScore.weightedScore = toDouble(row.get("weighted_score"));
        return turnScore;
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
    public int getTurnId() { return turnId; }
    public int getScoringRunId() { return scoringRunId; }
    public Integer getTargetNominationId() { return targetNominationId; }
    public String getResolutionMethod() { return resolutionMethod; }
    public Double getConfidence() { return confidence; }
    public Integer getSentenceCount() { return sentenceCount; }
    public Integer getTotalScore() { return totalScore; }
    public Double getAvgScore() { return avgScore; }
    public Double getWeightedScore() { return weightedScore; }

    public void setId(int id) { this.id = id; }
    public void setTurnId(int turnId) { this.turnId = turnId; }
    public void setScoringRunId(int scoringRunId) { this.scoringRunId = scoringRunId; }
    public void setTargetNominationId(Integer targetNominationId) { this.targetNominationId = targetNominationId; }
    public void setResolutionMethod(String resolutionMethod) { this.resolutionMethod = resolutionMethod; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public void setSentenceCount(Integer sentenceCount) { this.sentenceCount = sentenceCount; }
    public void setTotalScore(Integer totalScore) { this.totalScore = totalScore; }
    public void setAvgScore(Double avgScore) { this.avgScore = avgScore; }
    public void setWeightedScore(Double weightedScore) { this.weightedScore = weightedScore; }

    @Override
    public String toString() {
        return String.format("TurnScore[id=%d, turnId=%d, runId=%d, method=%s]",
            id, turnId, scoringRunId, resolutionMethod);
    }
}
