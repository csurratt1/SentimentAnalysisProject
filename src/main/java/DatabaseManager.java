import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * DatabaseManager.java
 *
 * Reusable database class for interacting with MySQL.
 * Provides Connect/Disconnect/Execute operations with parameterized queries.
 *
 * <p>All other object classes (Hearing, etc.) use this single class for
 * database access, enforcing code reuse and a single point of change
 * for connection details.</p>
 *
 * <p>Connection credentials are loaded from {@code db.properties} in the
 * project root.</p>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 *   DatabaseManager db = new DatabaseManager();
 *   db.connect();
 *
 *   // INSERT / UPDATE / DELETE
 *   Map<Integer, Object> params = new LinkedHashMap<>();
 *   params.put(1, "some value");
 *   params.put(2, 42);
 *   int affected = db.execute("INSERT INTO t (col1, col2) VALUES (?, ?)", params);
 *
 *   // SELECT
 *   List<Map<String, Object>> rows = db.executeQuery("SELECT * FROM t WHERE id = ?",
 *       Map.of(1, someId));
 *
 *   db.disconnect();
 * }</pre>
 */
public class DatabaseManager {

    // ── Connection state ─────────────────────────────────────────────

    private Connection connection;
    private String host;
    private int    port;
    private String dbName;
    private String user;
    private String password;

    // ── Constructor ──────────────────────────────────────────────────

    /**
     * Creates a DatabaseManager and loads connection settings from
     * {@code db.properties}. Does NOT open the connection yet — call
     * {@link #connect()} when ready.
     */
    public DatabaseManager() {
        loadProperties();
    }

    /**
     * Creates a DatabaseManager with explicit credentials.
     * Useful for testing or overriding the properties file.
     */
    public DatabaseManager(String host, int port, String dbName,
                           String user, String password) {
        this.host     = host;
        this.port     = port;
        this.dbName   = dbName;
        this.user     = user;
        this.password = password;
    }

    // ── Connect / Disconnect ─────────────────────────────────────────

    /**
     * Establishes a connection to the MySQL database using the loaded
     * credentials. If already connected, does nothing.
     *
     * @throws SQLException if the connection cannot be established
     */
    public void connect() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;  // already connected
        }

        String url = String.format(
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
            host, port, dbName);

        connection = DriverManager.getConnection(url, user, password);
        System.out.printf("[DB] Connected to %s:%d/%s%n", host, port, dbName);
    }

    /**
     * Disconnects from the database. Safe to call even if not connected.
     */
    public void disconnect() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                    System.out.println("[DB] Disconnected.");
                }
            } catch (SQLException e) {
                System.err.println("[DB] Error closing connection: " + e.getMessage());
            } finally {
                connection = null;
            }
        }
    }

    /**
     * Returns true if the connection is open and valid.
     */
    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    // ── Execute (INSERT / UPDATE / DELETE) ────────────────────────────

    /**
     * Executes a parameterized SQL statement (INSERT, UPDATE, DELETE, DDL).
     *
     * <p>Parameters are bound by position using a {@code Map<Integer, Object>}
     * where the key is the 1-based parameter index and the value is the
     * parameter value. Use {@code null} values for SQL NULL.</p>
     *
     * <p><strong>Parameterized queries prevent SQL injection.</strong></p>
     *
     * @param sql    the SQL statement with {@code ?} placeholders
     * @param params positional parameters (1-based index → value), or null/empty for none
     * @return the number of rows affected
     * @throws SQLException if execution fails
     */
    public int execute(String sql, Map<Integer, Object> params) throws SQLException {
        requireConnection();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindParameters(ps, params);
            return ps.executeUpdate();
        }
    }

    /**
     * Executes a parameterized SQL statement with no parameters.
     */
    public int execute(String sql) throws SQLException {
        return execute(sql, null);
    }

    /**
     * Executes an INSERT and returns the generated auto-increment key.
     *
     * @param sql    the INSERT statement with {@code ?} placeholders
     * @param params positional parameters (1-based index → value)
     * @return the generated key, or -1 if none
     * @throws SQLException if execution fails
     */
    public long executeInsert(String sql, Map<Integer, Object> params) throws SQLException {
        requireConnection();

        try (PreparedStatement ps = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            bindParameters(ps, params);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        return -1;
    }

    // ── Execute Query (SELECT) ───────────────────────────────────────

    /**
     * Executes a parameterized SELECT query and returns results as a
     * list of maps (column name → value).
     *
     * <p>Each row is a {@code Map<String, Object>} with column names
     * as keys and column values as Java objects.</p>
     *
     * @param sql    the SELECT statement with {@code ?} placeholders
     * @param params positional parameters (1-based index → value), or null/empty for none
     * @return list of rows, each as a column→value map; empty list if no results
     * @throws SQLException if execution fails
     */
    public List<Map<String, Object>> executeQuery(String sql,
                                                   Map<Integer, Object> params) throws SQLException {
        requireConnection();

        List<Map<String, Object>> results = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindParameters(ps, params);

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();

                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    results.add(row);
                }
            }
        }

        return results;
    }

    /**
     * Executes a SELECT query with no parameters.
     */
    public List<Map<String, Object>> executeQuery(String sql) throws SQLException {
        return executeQuery(sql, null);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Binds parameters to a PreparedStatement.
     * Handles null values, Strings, Integers, Longs, Doubles, and
     * java.sql.Date / java.sql.Timestamp.
     */
    private void bindParameters(PreparedStatement ps,
                                Map<Integer, Object> params) throws SQLException {
        if (params == null || params.isEmpty()) return;

        for (Map.Entry<Integer, Object> entry : params.entrySet()) {
            int index = entry.getKey();
            Object value = entry.getValue();

            if (value == null) {
                ps.setNull(index, Types.NULL);
            } else if (value instanceof String) {
                ps.setString(index, (String) value);
            } else if (value instanceof Integer) {
                ps.setInt(index, (Integer) value);
            } else if (value instanceof Long) {
                ps.setLong(index, (Long) value);
            } else if (value instanceof Double) {
                ps.setDouble(index, (Double) value);
            } else if (value instanceof java.sql.Date) {
                ps.setDate(index, (java.sql.Date) value);
            } else if (value instanceof java.sql.Timestamp) {
                ps.setTimestamp(index, (java.sql.Timestamp) value);
            } else {
                ps.setObject(index, value);
            }
        }
    }

    /**
     * Throws if not connected.
     */
    private void requireConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("Not connected. Call connect() first.");
        }
    }

    /**
     * Loads connection properties from db.properties.
     * Searches: (1) current directory, (2) project root (parent of src/).
     */
    private void loadProperties() {
        // Try multiple locations for db.properties
        Path[] candidates = {
            Paths.get("db.properties"),
            Paths.get(System.getProperty("user.dir"), "db.properties"),
            Paths.get(System.getProperty("user.dir")).getParent()
                .resolve("db.properties")
        };

        Properties props = new Properties();
        boolean loaded = false;

        for (Path path : candidates) {
            if (path != null && Files.exists(path)) {
                try (InputStream is = Files.newInputStream(path)) {
                    props.load(is);
                    loaded = true;
                    System.out.println("[DB] Loaded config from: " + path);
                    break;
                } catch (IOException e) {
                    System.err.println("[DB] Error reading " + path + ": " + e.getMessage());
                }
            }
        }

        if (!loaded) {
            System.err.println("[DB] WARNING: db.properties not found. Using defaults.");
        }

        this.host     = props.getProperty("db.host", "localhost");
        this.port     = Integer.parseInt(props.getProperty("db.port", "3306"));
        this.dbName   = props.getProperty("db.name", "sentiment_analysis");
        this.user     = props.getProperty("db.user", "root");
        this.password = props.getProperty("db.password", "");
    }

    /**
     * Returns the underlying JDBC Connection (for advanced use).
     * Prefer using execute() and executeQuery() instead.
     */
    public Connection getConnection() {
        return connection;
    }
}
