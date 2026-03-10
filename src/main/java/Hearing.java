import java.sql.*;
import java.util.*;

/**
 * Hearing.java
 *
 * Represents a Senate confirmation hearing record. Maps to the {@code hearings}
 * table in the database.
 *
 * <p>Provides CRUD operations via {@link DatabaseManager}:</p>
 * <ul>
 *   <li>{@link #save(DatabaseManager)} — INSERT new or UPDATE existing (upsert by id)</li>
 *   <li>{@link #load(DatabaseManager, int)} — SELECT by primary key</li>
 *   <li>{@link #delete(DatabaseManager, int)} — DELETE by primary key</li>
 * </ul>
 *
 * <h3>Table schema:</h3>
 * <pre>
 * CREATE TABLE hearings (
 *     id              INT AUTO_INCREMENT PRIMARY KEY,
 *     hearing_date    DATE,
 *     session         VARCHAR(50),
 *     serial_number   VARCHAR(100),
 *     committee       VARCHAR(255),
 *     source_file     VARCHAR(500),
 *     title           VARCHAR(1000),
 *     created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
 * );
 * </pre>
 */
public class Hearing {

    // ── Fields (map to database columns) ─────────────────────────────

    private int    id;             // 0 = new record, >0 = existing
    private String hearingDate;    // ISO format: "YYYY-MM-DD"
    private String session;        // e.g. "111th Congress, 1st Session"
    private String serialNumber;   // e.g. "S.Hrg. 111-695, Pt. 3"
    private String committee;      // e.g. "Senate Judiciary Committee"
    private String sourceFile;     // e.g. "hearing_text.txt"
    private String title;          // e.g. "Nominations of Martin, Viken, ..."

    // ── Constructors ─────────────────────────────────────────────────

    /** Creates an empty Hearing (for loading from DB). */
    public Hearing() {
        this.id = 0;
    }

    /** Creates a new Hearing with all fields (no id — will be assigned on save). */
    public Hearing(String hearingDate, String session, String serialNumber,
                   String committee, String sourceFile, String title) {
        this.id            = 0;
        this.hearingDate   = hearingDate;
        this.session       = session;
        this.serialNumber  = serialNumber;
        this.committee     = committee;
        this.sourceFile    = sourceFile;
        this.title         = title;
    }

    // ── CRUD Operations ──────────────────────────────────────────────

    /**
     * Saves this Hearing to the database.
     * <ul>
     *   <li>If {@code id == 0}: INSERTs a new record and sets {@code this.id}
     *       to the generated key.</li>
     *   <li>If {@code id > 0}: UPDATEs the existing record with that id.</li>
     * </ul>
     *
     * @param db an open DatabaseManager connection
     * @return this Hearing (with id populated after insert)
     * @throws SQLException if the operation fails
     */
    public Hearing save(DatabaseManager db) throws SQLException {
        if (id == 0) {
            // INSERT new record
            String sql = "INSERT INTO hearings "
                + "(hearing_date, session, serial_number, committee, source_file, title) "
                + "VALUES (?, ?, ?, ?, ?, ?)";

            Map<Integer, Object> params = new LinkedHashMap<>();
            params.put(1, hearingDate);
            params.put(2, session);
            params.put(3, serialNumber);
            params.put(4, committee);
            params.put(5, sourceFile);
            params.put(6, title);

            long generatedId = db.executeInsert(sql, params);
            if (generatedId > 0) {
                this.id = (int) generatedId;
            }
            System.out.printf("[Hearing] Inserted id=%d: %s%n", this.id, this.title);

        } else {
            // UPDATE existing record
            String sql = "UPDATE hearings SET "
                + "hearing_date = ?, session = ?, serial_number = ?, "
                + "committee = ?, source_file = ?, title = ? "
                + "WHERE id = ?";

            Map<Integer, Object> params = new LinkedHashMap<>();
            params.put(1, hearingDate);
            params.put(2, session);
            params.put(3, serialNumber);
            params.put(4, committee);
            params.put(5, sourceFile);
            params.put(6, title);
            params.put(7, id);

            int affected = db.execute(sql, params);
            System.out.printf("[Hearing] Updated id=%d (%d row%s affected)%n",
                this.id, affected, affected == 1 ? "" : "s");
        }

        return this;
    }

    /**
     * Loads a Hearing from the database by primary key.
     *
     * @param db an open DatabaseManager connection
     * @param id the primary key to look up
     * @return the loaded Hearing, or null if not found
     * @throws SQLException if the query fails
     */
    public static Hearing load(DatabaseManager db, int id) throws SQLException {
        String sql = "SELECT * FROM hearings WHERE id = ?";

        List<Map<String, Object>> rows = db.executeQuery(sql, Map.of(1, id));

        if (rows.isEmpty()) {
            System.out.printf("[Hearing] No record found with id=%d%n", id);
            return null;
        }

        Map<String, Object> row = rows.get(0);
        Hearing h = new Hearing();
        h.id            = (int) row.get("id");
        h.hearingDate   = row.get("hearing_date") != null
            ? row.get("hearing_date").toString() : null;
        h.session       = (String) row.get("session");
        h.serialNumber  = (String) row.get("serial_number");
        h.committee     = (String) row.get("committee");
        h.sourceFile    = (String) row.get("source_file");
        h.title         = (String) row.get("title");

        System.out.printf("[Hearing] Loaded id=%d: %s%n", h.id, h.title);
        return h;
    }

    /**
     * Loads all Hearings from the database.
     *
     * @param db an open DatabaseManager connection
     * @return list of all hearings (may be empty)
     * @throws SQLException if the query fails
     */
    public static List<Hearing> loadAll(DatabaseManager db) throws SQLException {
        String sql = "SELECT * FROM hearings ORDER BY hearing_date, id";

        List<Map<String, Object>> rows = db.executeQuery(sql);
        List<Hearing> hearings = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            Hearing h = new Hearing();
            h.id            = (int) row.get("id");
            h.hearingDate   = row.get("hearing_date") != null
                ? row.get("hearing_date").toString() : null;
            h.session       = (String) row.get("session");
            h.serialNumber  = (String) row.get("serial_number");
            h.committee     = (String) row.get("committee");
            h.sourceFile    = (String) row.get("source_file");
            h.title         = (String) row.get("title");
            hearings.add(h);
        }

        System.out.printf("[Hearing] Loaded %d record(s)%n", hearings.size());
        return hearings;
    }

    /**
     * Deletes a Hearing from the database by primary key.
     *
     * @param db an open DatabaseManager connection
     * @param id the primary key to delete
     * @return true if a record was deleted, false if not found
     * @throws SQLException if the operation fails
     */
    public static boolean delete(DatabaseManager db, int id) throws SQLException {
        String sql = "DELETE FROM hearings WHERE id = ?";

        int affected = db.execute(sql, Map.of(1, id));
        System.out.printf("[Hearing] Deleted id=%d (%d row%s affected)%n",
            id, affected, affected == 1 ? "" : "s");

        return affected > 0;
    }

    // ── Getters and Setters ──────────────────────────────────────────

    public int    getId()            { return id; }
    public String getHearingDate()   { return hearingDate; }
    public String getSession()       { return session; }
    public String getSerialNumber()  { return serialNumber; }
    public String getCommittee()     { return committee; }
    public String getSourceFile()    { return sourceFile; }
    public String getTitle()         { return title; }

    public void setId(int id)                       { this.id = id; }
    public void setHearingDate(String hearingDate)   { this.hearingDate = hearingDate; }
    public void setSession(String session)           { this.session = session; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
    public void setCommittee(String committee)       { this.committee = committee; }
    public void setSourceFile(String sourceFile)     { this.sourceFile = sourceFile; }
    public void setTitle(String title)               { this.title = title; }

    @Override
    public String toString() {
        return String.format("Hearing[id=%d, date=%s, serial=%s, title=%s]",
            id, hearingDate, serialNumber, title);
    }
}
