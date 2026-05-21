import java.sql.*;
import java.util.*;

/**
 * SentenceScoreRecord.java
 *
 * Database entity for the sentence_scores table.
 * Uses JDBC batch insert — do not log per row since a hearing produces thousands of sentences.
 */
public class SentenceScoreRecord {

    private int id;
    private int turnScoreId;
    private int scoringRunId;
    private int turnId;
    private int sentenceIndex;
    private String sentenceText;
    private int sentimentClass;
    private String sentimentLabel;
    private int sentimentScore;
    private double confidence;

    public SentenceScoreRecord() {}

    public SentenceScoreRecord(int turnScoreId, int scoringRunId, int turnId,
                                int sentenceIndex, SentenceScore ss) {
        this.turnScoreId    = turnScoreId;
        this.scoringRunId   = scoringRunId;
        this.turnId         = turnId;
        this.sentenceIndex  = sentenceIndex;
        this.sentenceText   = ss.getText();
        this.sentimentClass = ss.getClassIndex();
        this.sentimentLabel = ss.getLabel();
        this.sentimentScore = ss.getScore();
        this.confidence     = ss.getConfidence();
    }

    /**
     * Batch-inserts all sentences for one turn_score row.
     * Silent — no logging per row.
     *
     * @return number of rows inserted
     */
    public static int saveBatch(Connection conn, int turnScoreId, int scoringRunId,
                                int turnId, List<SentenceScore> sentences)
            throws SQLException {
        if (sentences.isEmpty()) return 0;

        String sql = "INSERT INTO sentence_scores "
            + "(turn_score_id, scoring_run_id, turn_id, sentence_index, sentence_text, "
            + "sentiment_class, sentiment_label, sentiment_score, confidence) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < sentences.size(); i++) {
                SentenceScore ss = sentences.get(i);
                ps.setInt(1, turnScoreId);
                ps.setInt(2, scoringRunId);
                ps.setInt(3, turnId);
                ps.setInt(4, i + 1);          // 1-based sentence index within the turn
                ps.setString(5, ss.getText());
                ps.setInt(6, ss.getClassIndex());
                ps.setString(7, ss.getLabel());
                ps.setInt(8, ss.getScore());
                ps.setDouble(9, ss.getConfidence());
                ps.addBatch();
            }
            ps.executeBatch();
            return sentences.size();
        }
    }

    public static List<SentenceScoreRecord> loadByScoringRun(DatabaseManager db, int scoringRunId)
            throws SQLException {
        String sql = "SELECT * FROM sentence_scores WHERE scoring_run_id = ?"
            + " ORDER BY turn_id, sentence_index";
        List<Map<String, Object>> rows = db.executeQuery(sql, Map.of(1, scoringRunId));
        List<SentenceScoreRecord> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) result.add(fromRow(row));
        return result;
    }

    public static List<SentenceScoreRecord> loadAll(DatabaseManager db) throws SQLException {
        String sql = "SELECT * FROM sentence_scores ORDER BY scoring_run_id, turn_id, sentence_index";
        List<Map<String, Object>> rows = db.executeQuery(sql);
        List<SentenceScoreRecord> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) result.add(fromRow(row));
        return result;
    }

    private static SentenceScoreRecord fromRow(Map<String, Object> row) {
        SentenceScoreRecord r = new SentenceScoreRecord();
        r.id             = toInt(row.get("id"));
        r.turnScoreId    = toInt(row.get("turn_score_id"));
        r.scoringRunId   = toInt(row.get("scoring_run_id"));
        r.turnId         = toInt(row.get("turn_id"));
        r.sentenceIndex  = toInt(row.get("sentence_index"));
        r.sentenceText   = row.get("sentence_text") != null ? row.get("sentence_text").toString() : "";
        r.sentimentClass = toInt(row.get("sentiment_class"));
        r.sentimentLabel = row.get("sentiment_label") != null ? row.get("sentiment_label").toString() : null;
        r.sentimentScore = toInt(row.get("sentiment_score"));
        r.confidence     = toDouble(row.get("confidence"));
        return r;
    }

    private static int    toInt(Object v)    { return v == null ? 0    : ((Number) v).intValue(); }
    private static double toDouble(Object v) { return v == null ? 0.0  : ((Number) v).doubleValue(); }

    public int    getId()             { return id; }
    public int    getTurnScoreId()    { return turnScoreId; }
    public int    getScoringRunId()   { return scoringRunId; }
    public int    getTurnId()         { return turnId; }
    public int    getSentenceIndex()  { return sentenceIndex; }
    public String getSentenceText()   { return sentenceText; }
    public int    getSentimentClass() { return sentimentClass; }
    public String getSentimentLabel() { return sentimentLabel; }
    public int    getSentimentScore() { return sentimentScore; }
    public double getConfidence()     { return confidence; }
}
