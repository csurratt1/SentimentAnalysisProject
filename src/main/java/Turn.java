import java.sql.*;
import java.util.*;

/**
 * Turn.java
 *
 * Database entity for the turns table.
 */
public class Turn {

    private int id;
    private int hearingId;
    private Integer hearingSectionId;
    private int speakerId;
    private int turnNumber;
    private String speakerLabel;
    private String text;
    private Integer startLine;
    private Integer charCount;
    private boolean substantive;

    public Turn() {
        this.id = 0;
        this.substantive = true;
    }

    public Turn(int hearingId, Integer hearingSectionId, int speakerId, int turnNumber,
                String speakerLabel, String text, Integer startLine,
                Integer charCount, boolean substantive) {
        this.id = 0;
        this.hearingId = hearingId;
        this.hearingSectionId = hearingSectionId;
        this.speakerId = speakerId;
        this.turnNumber = turnNumber;
        this.speakerLabel = speakerLabel;
        this.text = text;
        this.startLine = startLine;
        this.charCount = charCount;
        this.substantive = substantive;
    }

    public Turn save(DatabaseManager db) throws SQLException {
        if (id == 0) {
            String sql = "INSERT INTO turns "
                + "(hearing_id, hearing_section_id, speaker_id, turn_number, speaker_label, "
                + "text, start_line, char_count, is_substantive) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

            Map<Integer, Object> params = new LinkedHashMap<>();
            params.put(1, hearingId);
            params.put(2, hearingSectionId);
            params.put(3, speakerId);
            params.put(4, turnNumber);
            params.put(5, speakerLabel);
            params.put(6, text);
            params.put(7, startLine);
            params.put(8, charCount);
            params.put(9, substantive);

            long generatedId = db.executeInsert(sql, params);
            if (generatedId > 0) {
                this.id = (int) generatedId;
            }
            System.out.printf("[Turn] Inserted id=%d (hearing=%d, turn=%d)%n",
                id, hearingId, turnNumber);
        } else {
            String sql = "UPDATE turns SET "
                + "hearing_id = ?, hearing_section_id = ?, speaker_id = ?, turn_number = ?, "
                + "speaker_label = ?, text = ?, start_line = ?, char_count = ?, is_substantive = ? "
                + "WHERE id = ?";

            Map<Integer, Object> params = new LinkedHashMap<>();
            params.put(1, hearingId);
            params.put(2, hearingSectionId);
            params.put(3, speakerId);
            params.put(4, turnNumber);
            params.put(5, speakerLabel);
            params.put(6, text);
            params.put(7, startLine);
            params.put(8, charCount);
            params.put(9, substantive);
            params.put(10, id);

            int affected = db.execute(sql, params);
            System.out.printf("[Turn] Updated id=%d (%d row%s affected)%n",
                id, affected, affected == 1 ? "" : "s");
        }

        return this;
    }

    public static Turn load(DatabaseManager db, int id) throws SQLException {
        String sql = "SELECT * FROM turns WHERE id = ?";
        List<Map<String, Object>> rows = db.executeQuery(sql, Map.of(1, id));

        if (rows.isEmpty()) {
            System.out.printf("[Turn] No record found with id=%d%n", id);
            return null;
        }

        Turn turn = fromRow(rows.get(0));
        System.out.printf("[Turn] Loaded id=%d%n", turn.id);
        return turn;
    }

    public static List<Turn> loadAll(DatabaseManager db) throws SQLException {
        String sql = "SELECT * FROM turns ORDER BY hearing_id, turn_number";
        List<Map<String, Object>> rows = db.executeQuery(sql);

        List<Turn> turns = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            turns.add(fromRow(row));
        }

        System.out.printf("[Turn] Loaded %d record(s)%n", turns.size());
        return turns;
    }

    public static boolean delete(DatabaseManager db, int id) throws SQLException {
        String sql = "DELETE FROM turns WHERE id = ?";
        int affected = db.execute(sql, Map.of(1, id));

        System.out.printf("[Turn] Deleted id=%d (%d row%s affected)%n",
            id, affected, affected == 1 ? "" : "s");
        return affected > 0;
    }

    private static Turn fromRow(Map<String, Object> row) {
        Turn turn = new Turn();
        turn.id = toInt(row.get("id"));
        turn.hearingId = toInt(row.get("hearing_id"));
        turn.hearingSectionId = toInteger(row.get("hearing_section_id"));
        turn.speakerId = toInt(row.get("speaker_id"));
        turn.turnNumber = toInt(row.get("turn_number"));
        turn.speakerLabel = (String) row.get("speaker_label");
        turn.text = (String) row.get("text");
        turn.startLine = toInteger(row.get("start_line"));
        turn.charCount = toInteger(row.get("char_count"));
        turn.substantive = toBoolean(row.get("is_substantive"));
        return turn;
    }

    private static int toInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static Integer toInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static boolean toBoolean(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return Boolean.parseBoolean(value.toString());
    }

    public int getId() { return id; }
    public int getHearingId() { return hearingId; }
    public Integer getHearingSectionId() { return hearingSectionId; }
    public int getSpeakerId() { return speakerId; }
    public int getTurnNumber() { return turnNumber; }
    public String getSpeakerLabel() { return speakerLabel; }
    public String getText() { return text; }
    public Integer getStartLine() { return startLine; }
    public Integer getCharCount() { return charCount; }
    public boolean isSubstantive() { return substantive; }

    public void setId(int id) { this.id = id; }
    public void setHearingId(int hearingId) { this.hearingId = hearingId; }
    public void setHearingSectionId(Integer hearingSectionId) { this.hearingSectionId = hearingSectionId; }
    public void setSpeakerId(int speakerId) { this.speakerId = speakerId; }
    public void setTurnNumber(int turnNumber) { this.turnNumber = turnNumber; }
    public void setSpeakerLabel(String speakerLabel) { this.speakerLabel = speakerLabel; }
    public void setText(String text) { this.text = text; }
    public void setStartLine(Integer startLine) { this.startLine = startLine; }
    public void setCharCount(Integer charCount) { this.charCount = charCount; }
    public void setSubstantive(boolean substantive) { this.substantive = substantive; }

    @Override
    public String toString() {
        return String.format("Turn[id=%d, hearingId=%d, turnNumber=%d, speaker=%s]",
            id, hearingId, turnNumber, speakerLabel);
    }
}
