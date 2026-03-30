import java.sql.*;
import java.util.*;

/**
 * Speaker.java
 *
 * Database entity for the speakers table.
 */
public class Speaker {

    private int id;
    private String firstName;
    private String lastName;
    private String canonicalName;
    private String party;
    private String state;
    private String role;

    public Speaker() {
        this.id = 0;
    }

    public Speaker(String firstName, String lastName, String canonicalName,
                   String party, String state, String role) {
        this.id = 0;
        this.firstName = firstName;
        this.lastName = lastName;
        this.canonicalName = canonicalName;
        this.party = party;
        this.state = state;
        this.role = role;
    }

    public Speaker save(DatabaseManager db) throws SQLException {
        if (id == 0) {
            String sql = "INSERT INTO speakers "
                + "(first_name, last_name, canonical_name, party, state, role) "
                + "VALUES (?, ?, ?, ?, ?, ?)";

            Map<Integer, Object> params = new LinkedHashMap<>();
            params.put(1, firstName);
            params.put(2, lastName);
            params.put(3, canonicalName);
            params.put(4, party);
            params.put(5, state);
            params.put(6, role);

            long generatedId = db.executeInsert(sql, params);
            if (generatedId > 0) {
                this.id = (int) generatedId;
            }
            System.out.printf("[Speaker] Inserted id=%d: %s%n", id, getDisplayName());
        } else {
            String sql = "UPDATE speakers SET "
                + "first_name = ?, last_name = ?, canonical_name = ?, "
                + "party = ?, state = ?, role = ? "
                + "WHERE id = ?";

            Map<Integer, Object> params = new LinkedHashMap<>();
            params.put(1, firstName);
            params.put(2, lastName);
            params.put(3, canonicalName);
            params.put(4, party);
            params.put(5, state);
            params.put(6, role);
            params.put(7, id);

            int affected = db.execute(sql, params);
            System.out.printf("[Speaker] Updated id=%d (%d row%s affected)%n",
                id, affected, affected == 1 ? "" : "s");
        }

        return this;
    }

    public static Speaker load(DatabaseManager db, int id) throws SQLException {
        String sql = "SELECT * FROM speakers WHERE id = ?";
        List<Map<String, Object>> rows = db.executeQuery(sql, Map.of(1, id));

        if (rows.isEmpty()) {
            System.out.printf("[Speaker] No record found with id=%d%n", id);
            return null;
        }

        Speaker speaker = fromRow(rows.get(0));
        System.out.printf("[Speaker] Loaded id=%d: %s%n", speaker.id, speaker.getDisplayName());
        return speaker;
    }

    public static List<Speaker> loadAll(DatabaseManager db) throws SQLException {
        String sql = "SELECT * FROM speakers ORDER BY last_name, first_name, id";
        List<Map<String, Object>> rows = db.executeQuery(sql);

        List<Speaker> speakers = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            speakers.add(fromRow(row));
        }

        System.out.printf("[Speaker] Loaded %d record(s)%n", speakers.size());
        return speakers;
    }

    public static boolean delete(DatabaseManager db, int id) throws SQLException {
        String sql = "DELETE FROM speakers WHERE id = ?";
        int affected = db.execute(sql, Map.of(1, id));

        System.out.printf("[Speaker] Deleted id=%d (%d row%s affected)%n",
            id, affected, affected == 1 ? "" : "s");
        return affected > 0;
    }

    private static Speaker fromRow(Map<String, Object> row) {
        Speaker speaker = new Speaker();
        speaker.id = toInt(row.get("id"));
        speaker.firstName = (String) row.get("first_name");
        speaker.lastName = (String) row.get("last_name");
        speaker.canonicalName = (String) row.get("canonical_name");
        speaker.party = (String) row.get("party");
        speaker.state = (String) row.get("state");
        speaker.role = row.get("role") != null ? row.get("role").toString() : null;
        return speaker;
    }

    private static int toInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    public String getDisplayName() {
        if (firstName != null && !firstName.isBlank()) {
            return firstName + " " + lastName;
        }
        return lastName;
    }

    public int getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getCanonicalName() { return canonicalName; }
    public String getParty() { return party; }
    public String getState() { return state; }
    public String getRole() { return role; }

    public void setId(int id) { this.id = id; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public void setCanonicalName(String canonicalName) { this.canonicalName = canonicalName; }
    public void setParty(String party) { this.party = party; }
    public void setState(String state) { this.state = state; }
    public void setRole(String role) { this.role = role; }

    @Override
    public String toString() {
        return String.format("Speaker[id=%d, name=%s, role=%s]", id, getDisplayName(), role);
    }
}
