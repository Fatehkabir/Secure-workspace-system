package com.examworkspace.db;

import com.examworkspace.net.LanDiscoveryService;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

public final class Database {

    private static final Path DATA_DIR = Path.of(System.getProperty("examworkspace.dataDir",
            Path.of(System.getProperty("user.home"), ".examworkspace").toString()));
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile org.h2.jdbcx.JdbcConnectionPool h2Pool;
    private static volatile org.postgresql.ds.PGSimpleDataSource pgDataSource;
    private static volatile boolean usePostgres = false;

    private static String dbUrl;
    private static String dbUser;
    private static String dbPassword;

    private Database() {
    }

    public static Path dataDir() {
        String prop = System.getProperty("examworkspace.dataDir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        return DATA_DIR;
    }

    public static boolean canBindTcpPort() {
        if (System.getProperty("examworkspace.dataDir") != null) {
            return false;
        }
        try (java.net.ServerSocket ss = new java.net.ServerSocket()) {
            ss.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0));
            int port = ss.getLocalPort();
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static String getLocalDeviceName() {
        return LanDiscoveryService.getLocalDeviceName();
    }

    public static String getDbUrl() {
        if (dbUrl == null) {
            synchronized (Database.class) {
                if (dbUrl == null) loadConfig();
            }
        }
        return dbUrl;
    }

    public static String extractHost(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) return "";
        try {
            String stripped = jdbcUrl.substring("jdbc:postgresql://".length());
            int slashIdx = stripped.indexOf('/');
            String hostPort = slashIdx >= 0 ? stripped.substring(0, slashIdx) : stripped;
            return hostPort.split(":")[0];
        } catch (Exception e) {
            return "";
        }
    }

    public static int extractPort(String jdbcUrl, int defaultPort) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) return defaultPort;
        try {
            String stripped = jdbcUrl.substring("jdbc:postgresql://".length());
            int slashIdx = stripped.indexOf('/');
            String hostPort = slashIdx >= 0 ? stripped.substring(0, slashIdx) : stripped;
            String[] parts = hostPort.split(":");
            if (parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
        } catch (Exception ignored) {
        }
        return defaultPort;
    }

    public static String extractDbName(String jdbcUrl, String defaultDb) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) return defaultDb;
        try {
            String stripped = jdbcUrl.substring("jdbc:postgresql://".length());
            int slashIdx = stripped.indexOf('/');
            if (slashIdx >= 0) {
                String afterSlash = stripped.substring(slashIdx + 1);
                int qIdx = afterSlash.indexOf('?');
                return qIdx >= 0 ? afterSlash.substring(0, qIdx) : afterSlash;
            }
        } catch (Exception ignored) {
        }
        return defaultDb;
    }

    public static boolean isThisDevice(String host) {
        if (host == null || host.isBlank()) return false;
        if ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host)) {
            return true;
        }
        String local = getLocalDeviceName();
        if (local.equalsIgnoreCase(host)) {
            return true;
        }
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            if (addr.isLoopbackAddress()) return true;
            return java.net.NetworkInterface.getByInetAddress(addr) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String getServerDisplayName() {
        if (dbUrl == null) {
            synchronized (Database.class) {
                if (dbUrl == null) loadConfig();
            }
        }
        if (!usePostgres) {
            return "Local H2 (Embedded)";
        }
        String host = extractHost(dbUrl);
        if (isThisDevice(host)) {
            return getLocalDeviceName() + " (Teacher Host)";
        }
        return !host.isBlank() ? host : dbUrl;
    }

    public static boolean isPostgres() {
        if (dbUrl == null) {
            synchronized (Database.class) {
                if (dbUrl == null) {
                    loadConfig();
                }
            }
        }
        return usePostgres;
    }

    public static boolean testConnection(String url, String user, String password) {
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            return false;
        }
        try {
            String testUrl = url;
            String host = extractHost(url);
            if (isThisDevice(host)) {
                testUrl = "jdbc:postgresql://127.0.0.1:" + extractPort(url, 5432) + "/" + extractDbName(url, "examworkspace");
            }
            org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
            ds.setUrl(testUrl);
            ds.setUser(user != null ? user : "examuser");
            ds.setPassword(password != null ? password : "");
            ds.setConnectTimeout(3);
            ds.setSocketTimeout(3);
            try (Connection c = ds.getConnection()) {
                return c.isValid(2);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized void saveConfig(String url, String user, String password) {
        try {
            Files.createDirectories(DATA_DIR);
            Path cfgPath = DATA_DIR.resolve("db.properties");
            Properties props = new Properties();
            props.setProperty("db.url", url != null ? url : "");
            props.setProperty("db.user", user != null ? user : "examuser");
            props.setProperty("db.password", password != null ? password : "");
            try (OutputStream out = Files.newOutputStream(cfgPath)) {
                props.store(out, "Exam Workspace Database Configuration");
            }
        } catch (IOException ignored) {
        }
        String connectUrl = url;
        String host = extractHost(url);
        if (isThisDevice(host)) {
            connectUrl = "jdbc:postgresql://127.0.0.1:" + extractPort(url, 5432) + "/" + extractDbName(url, "examworkspace");
        }
        configure(connectUrl, user, password);
    }

    private static void loadConfig() {
        Properties props = new Properties();
        Path cfgPath = DATA_DIR.resolve("db.properties");
        if (Files.exists(cfgPath)) {
            try (InputStream in = new FileInputStream(cfgPath.toFile())) {
                props.load(in);
            } catch (IOException ignored) {
            }
        }

        String sysUrl = System.getProperty("examworkspace.db.url",
                System.getenv().getOrDefault("EXAMWORKSPACE_DB_URL", null));
        String sysUser = System.getProperty("examworkspace.db.user",
                System.getenv().getOrDefault("EXAMWORKSPACE_DB_USER", null));
        String sysPass = System.getProperty("examworkspace.db.password",
                System.getenv().getOrDefault("EXAMWORKSPACE_DB_PASSWORD", null));

        String user = sysUser != null ? sysUser : props.getProperty("db.user", "examuser");
        String pass = sysPass != null ? sysPass : props.getProperty("db.password", "StrongPassword123!");
        String url = sysUrl != null ? sysUrl : props.getProperty("db.url", "").trim();

        if (url != null && !url.isEmpty() && url.startsWith("jdbc:postgresql:")) {
            usePostgres = true;
            String host = extractHost(url);
            if (isThisDevice(host)) {
                // Connecting to this local machine: use loopback 127.0.0.1 directly!
                // Loopback is 100% immune to Wi-Fi changes, router NAT issues, and AP isolation.
                dbUrl = "jdbc:postgresql://127.0.0.1:" + extractPort(url, 5432) + "/" + extractDbName(url, "examworkspace");
            } else {
                dbUrl = url;
            }
            dbUser = user;
            dbPassword = pass;
            return;
        }

        // No PostgreSQL URL configured — fall back to embedded H2
        usePostgres = false;
        boolean canAutoServer = canBindTcpPort();
        dbUrl = "jdbc:h2:file:" + dataDir().resolve("examworkspace").toAbsolutePath()
                + (canAutoServer ? ";AUTO_SERVER=TRUE" : "")
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000;DEFAULT_LOCK_TIMEOUT=15000";
        dbUser = "sa";
        dbPassword = "";
    }

    public static Connection connect() throws SQLException {
        if (dbUrl == null) {
            synchronized (Database.class) {
                if (dbUrl == null) {
                    loadConfig();
                }
            }
        }

        if (usePostgres) {
            if (pgDataSource == null) {
                synchronized (Database.class) {
                    if (pgDataSource == null) {
                        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
                        ds.setUrl(dbUrl);
                        ds.setUser(dbUser);
                        ds.setPassword(dbPassword);
                        ds.setConnectTimeout(5);
                        pgDataSource = ds;
                    }
                }
            }
            try {
                return pgDataSource.getConnection();
            } catch (SQLException ex) {
                // If connecting failed and this machine is the host, fallback to 127.0.0.1 loopback
                String host = extractHost(dbUrl);
                if (!"127.0.0.1".equals(host) && isThisDevice(host)) {
                    String loopback = "jdbc:postgresql://127.0.0.1:" + extractPort(dbUrl, 5432) + "/" + extractDbName(dbUrl, "examworkspace");
                    configure(loopback, dbUser, dbPassword);
                    return connect();
                }
                throw ex;
            }
        } else {
            if (h2Pool == null) {
                synchronized (Database.class) {
                    if (h2Pool == null) {
                        try {
                            h2Pool = org.h2.jdbcx.JdbcConnectionPool.create(dbUrl, dbUser, dbPassword);
                            h2Pool.setMaxConnections(20);
                            try (Connection testConn = h2Pool.getConnection()) {
                                // OK
                            }
                        } catch (Exception ex) {
                            if (dbUrl != null && dbUrl.contains("AUTO_SERVER=TRUE")) {
                                System.err.println("[Database] H2 AUTO_SERVER failed (" + ex.getMessage() + "), falling back to pure embedded H2.");
                                if (h2Pool != null) {
                                    try { h2Pool.dispose(); } catch (Exception ignored) {}
                                    h2Pool = null;
                                }
                                dbUrl = dbUrl.replace(";AUTO_SERVER=TRUE", "").replace("AUTO_SERVER=TRUE;", "").replace("AUTO_SERVER=TRUE", "");
                                h2Pool = org.h2.jdbcx.JdbcConnectionPool.create(dbUrl, dbUser, dbPassword);
                                h2Pool.setMaxConnections(20);
                            } else {
                                throw (ex instanceof SQLException se) ? se : new SQLException(ex);
                            }
                        }
                    }
                }
            }
            try {
                return h2Pool.getConnection();
            } catch (SQLException ex) {
                if (dbUrl != null && dbUrl.contains("AUTO_SERVER=TRUE")) {
                    synchronized (Database.class) {
                        if (dbUrl.contains("AUTO_SERVER=TRUE")) {
                            System.err.println("[Database] H2 getConnection failed with AUTO_SERVER, falling back to embedded: " + ex.getMessage());
                            if (h2Pool != null) {
                                try { h2Pool.dispose(); } catch (Exception ignored) {}
                                h2Pool = null;
                            }
                            dbUrl = dbUrl.replace(";AUTO_SERVER=TRUE", "").replace("AUTO_SERVER=TRUE;", "").replace("AUTO_SERVER=TRUE", "");
                            h2Pool = org.h2.jdbcx.JdbcConnectionPool.create(dbUrl, dbUser, dbPassword);
                            h2Pool.setMaxConnections(20);
                            return h2Pool.getConnection();
                        }
                    }
                }
                throw ex;
            }
        }
    }

    public static synchronized void configure(String url, String user, String password) {
        if (h2Pool != null) {
            try {
                h2Pool.dispose();
            } catch (Exception ignored) {
            }
            h2Pool = null;
        }
        pgDataSource = null;
        dbUrl = url;
        dbUser = user;
        dbPassword = password;
        usePostgres = (url != null && url.startsWith("jdbc:postgresql:"));
    }

    public static void initialize() {
        Path dir = dataDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create data directory " + dir, e);
        }

        if (dbUrl == null) {
            loadConfig();
        }

        // --- Smart auto-detection (runs once at app startup, not during tests) ---
        // Only run auto-detection if the standard data dir (~/.examworkspace) is being used.
        // In tests, dataDir is set to a temp directory, so we skip auto-detection there.
        boolean isStandardDataDir = dir.equals(Path.of(System.getProperty("user.home"), ".examworkspace"));
        if (!usePostgres && isStandardDataDir) {
            // Try local PostgreSQL on this machine (Teacher PC)
            Properties initProps = new Properties();
            Path initCfgPath = DATA_DIR.resolve("db.properties");
            if (Files.exists(initCfgPath)) {
                try (InputStream in = new FileInputStream(initCfgPath.toFile())) {
                    initProps.load(in);
                } catch (IOException ignored) {
                }
            }
            String user = initProps.getProperty("db.user", "examuser");
            String pass = initProps.getProperty("db.password", "StrongPassword123!");

            if (testConnection("jdbc:postgresql://127.0.0.1:5432/examworkspace", user, pass)) {
                String localUrl = "jdbc:postgresql://127.0.0.1:5432/examworkspace";
                configure(localUrl, user, pass);
                saveConfig(localUrl, user, pass);
                LanDiscoveryService.startBeacon(5432, "examworkspace");
            } else {
                // Try auto-discover Teacher PC on LAN (Student PC scenario)
                var discovered = LanDiscoveryService.discover(1500);
                if (discovered != null) {
                    String targetUrl = discovered.toJdbcUrl();
                    if (testConnection(targetUrl, user, pass)) {
                        configure(targetUrl, user, pass);
                        saveConfig(targetUrl, user, pass);
                    } else {
                        String ipUrl = discovered.toIpJdbcUrl();
                        if (testConnection(ipUrl, user, pass)) {
                            configure(ipUrl, user, pass);
                            saveConfig(ipUrl, user, pass);
                        }
                    }
                }
            }
        } else if (usePostgres) {
            // PostgreSQL URL is configured. If this machine is the host, start broadcasting for students.
            String host = extractHost(dbUrl);
            if (isThisDevice(host)) {
                LanDiscoveryService.startBeacon(extractPort(dbUrl, 5432), extractDbName(dbUrl, "examworkspace"));
            }
        }

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            String idDef = usePostgres ? "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY"
                    : "BIGINT AUTO_INCREMENT PRIMARY KEY";
            String textType = usePostgres ? "TEXT" : "CLOB";

            st.execute("CREATE TABLE IF NOT EXISTS teachers ("
                    + "id " + idDef + ", "
                    + "username VARCHAR(64) NOT NULL UNIQUE, "
                    + "full_name VARCHAR(128) NOT NULL, "
                    + "password_hash VARCHAR(512) NOT NULL, "
                    + "google_sub VARCHAR(255), "
                    + "email VARCHAR(255), "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            st.execute("CREATE TABLE IF NOT EXISTS rooms ("
                    + "id " + idDef + ", "
                    + "teacher_id BIGINT NOT NULL REFERENCES teachers(id) ON DELETE CASCADE, "
                    + "name VARCHAR(128) NOT NULL, "
                    + "room_code VARCHAR(32) NOT NULL, "
                    + "room_password VARCHAR(128) NOT NULL, "
                    + "starts_at BIGINT NOT NULL, "
                    + "ends_at BIGINT NOT NULL, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "UNIQUE (teacher_id, name))");

            st.execute("CREATE TABLE IF NOT EXISTS room_apps ("
                    + "id " + idDef + ", "
                    + "room_id BIGINT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE, "
                    + "name VARCHAR(256) NOT NULL, "
                    + "launch_target VARCHAR(1024) NOT NULL, "
                    + "target_type VARCHAR(16) NOT NULL, "
                    + "process_name VARCHAR(256), "
                    + "UNIQUE (room_id, launch_target))");

            st.execute("CREATE TABLE IF NOT EXISTS room_url_policy ("
                    + "room_id BIGINT NOT NULL PRIMARY KEY REFERENCES rooms(id) ON DELETE CASCADE, "
                    + "enabled BOOLEAN NOT NULL DEFAULT TRUE, "
                    + "enforce_https BOOLEAN NOT NULL DEFAULT TRUE, "
                    + "block_internal_pages BOOLEAN NOT NULL DEFAULT TRUE, "
                    + "policy_text " + textType + " NOT NULL DEFAULT '', "
                    + "updated_at BIGINT NOT NULL DEFAULT 0)");

            st.execute("CREATE TABLE IF NOT EXISTS students ("
                    + "id " + idDef + ", "
                    + "username VARCHAR(64) NOT NULL UNIQUE, "
                    + "full_name VARCHAR(128) NOT NULL, "
                    + "password_hash VARCHAR(512) NOT NULL, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            st.execute("CREATE TABLE IF NOT EXISTS room_members ("
                    + "id " + idDef + ", "
                    + "room_id BIGINT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE, "
                    + "student_id BIGINT REFERENCES students(id) ON DELETE SET NULL, "
                    + "roll_number VARCHAR(64) NOT NULL, "
                    + "display_name VARCHAR(128) NOT NULL, "
                    + "joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "left_at BIGINT, "
                    + "last_seen_at BIGINT)");

            st.execute("CREATE TABLE IF NOT EXISTS room_violations ("
                    + "id " + idDef + ", "
                    + "room_id BIGINT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE, "
                    + "student_id BIGINT REFERENCES students(id) ON DELETE SET NULL, "
                    + "roll_number VARCHAR(64), "
                    + "student_name VARCHAR(128) NOT NULL, "
                    + "detected_app VARCHAR(512) NOT NULL, "
                    + "executable_path VARCHAR(1024), "
                    + "occurred_at BIGINT NOT NULL, "
                    + "screenshot_base64 " + textType + ")");

            st.execute("CREATE TABLE IF NOT EXISTS room_locks ("
                    + "room_id BIGINT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE, "
                    + "student_name VARCHAR(200) NOT NULL, "
                    + "reason VARCHAR(512), "
                    + "unlock_code VARCHAR(16) NOT NULL, "
                    + "locked_at BIGINT NOT NULL)");

            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_room_locks_name ON room_locks(room_id, student_name)");

            addColumn(st, "teachers", "google_sub", "VARCHAR(255)");
            addColumn(st, "teachers", "email", "VARCHAR(255)");
            addColumn(st, "room_apps", "process_name", "VARCHAR(256)");
            addColumn(st, "room_members", "left_at", "BIGINT");
            addColumn(st, "room_members", "last_seen_at", "BIGINT");
            addColumn(st, "room_violations", "screenshot_base64", textType);
            addColumn(st, "rooms", "room_code", "VARCHAR(32)");
            addColumn(st, "rooms", "room_password", "VARCHAR(128)");
            addColumn(st, "rooms", "starts_at", "BIGINT");
            addColumn(st, "rooms", "ends_at", "BIGINT");

            backfillRooms(conn);

            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_teachers_google_sub ON teachers(google_sub)");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_rooms_code ON rooms(room_code)");

            try {
                st.execute("DROP INDEX IF EXISTS ux_room_members_roll");
            } catch (SQLException ignored) {
            }

            try {
                st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_room_members_name ON room_members(room_id, display_name)");
            } catch (SQLException e) {
                try {
                    st.execute("DELETE FROM room_members WHERE display_name = 'Guest'");
                    st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_room_members_name ON room_members(room_id, display_name)");
                } catch (SQLException ignored) {
                }
            }

            try {
                st.execute("DROP INDEX IF EXISTS ux_rooms_password");
            } catch (SQLException ignored) {
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not initialize database", e);
        }
    }

    private static void addColumn(Statement st, String table, String column, String type) throws SQLException {
        st.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column + " " + type);
    }

    /** Rooms created before schedules existed get a future window and a generated code and password. */
    private static void backfillRooms(Connection conn) throws SQLException {
        Set<String> codes = new HashSet<>();
        Set<String> passwords = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT room_code, room_password FROM rooms")) {
            while (rs.next()) {
                if (rs.getString(1) != null) {
                    codes.add(rs.getString(1));
                }
                if (rs.getString(2) != null) {
                    passwords.add(rs.getString(2));
                }
            }
        }

        Instant start = Instant.now().plusSeconds(3600);
        Instant end = start.plusSeconds(2 * 3600);
        String select = "SELECT id, room_code, room_password, starts_at, ends_at FROM rooms";
        String update = "UPDATE rooms SET room_code = ?, room_password = ?, starts_at = ?, ends_at = ? WHERE id = ?";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(select);
             PreparedStatement ps = conn.prepareStatement(update)) {
            while (rs.next()) {
                String code = rs.getString("room_code");
                String password = rs.getString("room_password");
                long starts = rs.getLong("starts_at");
                boolean missingStart = rs.wasNull() || starts == 0;
                long ends = rs.getLong("ends_at");
                boolean missingEnd = rs.wasNull() || ends == 0;
                if (code != null && password != null && !missingStart && !missingEnd) {
                    continue;
                }
                if (code == null) {
                    code = uniqueCode(codes);
                }
                if (password == null) {
                    password = uniqueCode(passwords);
                }
                ps.setString(1, code);
                ps.setString(2, password);
                ps.setLong(3, missingStart ? start.toEpochMilli() : starts);
                ps.setLong(4, missingEnd ? end.toEpochMilli() : ends);
                ps.setLong(5, rs.getLong("id"));
                ps.executeUpdate();
            }
        }
    }

    private static String uniqueCode(Set<String> taken) {
        String code;
        do {
            code = randomCode();
        } while (!taken.add(code));
        return code;
    }

    public static String randomCode() {
        StringBuilder code = new StringBuilder("EW-");
        for (int i = 0; i < 4; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        code.append('-');
        for (int i = 0; i < 4; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }
}