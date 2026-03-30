import java.sql.*;
import java.util.*;

/**
 * Nomination.java
 *
 * Database entity for the nominations table.
 */
public class Nomination {

    private int id;
    private int hearingId;
    private int hearingSectionId;
    private int speakerId;
    private String position;
    private String titleUsed;

    public Nomination() {
        this.id = 0;
    }

    public Nomination(int hearingId, int hearingSectionId, int speakerId,
                      String position, String titleUsed) {
        this.id = 0;
        this.hearingId = hearingId;
        this.hearingSectionId = hearingSectionId;
        this.speakerId = speakerId;
        this.position = position;
        this.titleUsed = titleUsed;
    }

    public Nomination save(DatabaseManager db) throws SQLException {
        if (id == 0) {
            String sql = "INSERT INTO nominations "
                + "(hearing_id, hearing_section_id, speaker_id, position, title_used) "
                + "VALUES (?, ?, ?, ?, ?)";

            Map<Integer, Object> params = new LinkedHashMap<>();
            params.put(1, hearingId);
            params.put(2, hearingSectionId);
            params.put(3, speakerId);
            params.put(4, position);
            params.put(5, titleUsed);

            long generatedId = db.executeInsert(sql, params);
            if (generatedId > 0) {
                this.id = (int) generatedId;
            }
            System.out.printf("[Nomination] Inserted id=%d (section=%d, speaker=%d)%n",
                id, hearingSectionId, speakerId);
        } else {
            String sql = "UPDATE nominations SET "
                + "hearing_id = ?, hearing_section_id = ?, speaker_id = ?, "
                + "position = ?, title_used = ? "
                + "WHERE id = ?";

            Map<Integer, Object> params = new LinkedHashMap<>();
            params.put(1, hearingId);
            params.put(2, hearingSectionId);
            params.put(3, speakerId);
            params.put(4, position);
            params.put(5, titleUsed);
            params.put(6, id);

            int affected = db.execute(sql, params);
            System.out.printf("[Nomination] Updated id=%d (%d row%s affected)%n",
                id, affected, affected == 1 ? "" : "s");
        }

        return this;
    }

    public static Nomination load(DatabaseManager db, int id) throws SQLException {
        String sql = "SELECT * FROM nominations WHERE id = ?";
        List<Map<String, Object>> rows = db.executeQuery(sql, Map.of(1, id));

        if (rows.isEmpty()) {
            System.out.printf("[Nomination] No record found with id=%d%n", id);
            return null;
        }

        Nomination nomination = fromRow(rows.get(0));
        System.out.printf("[Nomination] Loaded id=%d%n", nomination.id);
        return nomination;
    }

    public static List<Nomination> loadAll(DatabaseManager db) throws SQLException {
        String sql = "SELECT * FROM nominations ORDER BY hearing_id, hearing_section_id, id";
        List<Map<String, Object>> rows = db.executeQuery(sql);

        List<Nomination> nominations = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            nominations.add(fromRow(row));
        }

        System.out.printf("[Nomination] Loaded %d record(s)%n", nominations.size());
        return nominations;
    }

    public static boolean delete(DatabaseManager db, int id) throws SQLException {
        String sql = "DELETE FROM nominations WHERE id = ?";
        int affected = db.execute(sql, Map.of(1, id));

        System.out.printf("[Nomination] Deleted id=%d (%d row%s affected)%n",
            id, affected, affected == 1 ? "" : "s");
        return affected > 0;
    }

    private static Nomination fromRow(Map<String, Object> row) {
        Nomination nomination = new Nomination();
        nomination.id = toInt(row.get("id"));
        nomination.hearingId = toInt(row.get("hearing_id"));
        nomination.hearingSectionId = toInt(row.get("hearing_section_id"));
        nomination.speakerId = toInt(row.get("speaker_id"));
        nomination.position = (String) row.get("position");
        nomination.titleUsed = (String) row.get("title_used");
        return nomination;
    }

    private static int toInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    public int getId() { return id; }
    public int getHearingId() { return hearingId; }
    public int getHearingSectionId() { return hearingSectionId; }
    public int getSpeakerId() { return speakerId; }
    public String getPosition() { return position; }
    public String getTitleUsed() { return titleUsed; }

    public void setId(int id) { this.id = id; }
    public void setHearingId(int hearingId) { this.hearingId = hearingId; }
    public void setHearingSectionId(int hearingSectionId) { this.hearingSectionId = hearingSectionId; }
    public void setSpeakerId(int speakerId) { this.speakerId = speakerId; }
    public void setPosition(String position) { this.position = position; }
    public void setTitleUsed(String titleUsed) { this.titleUsed = titleUsed; }

    @Override
    public String toString() {
        return String.format("Nomination[id=%d, hearingId=%d, sectionId=%d, speakerId=%d]",
            id, hearingId, hearingSectionId, speakerId);
    }
}
