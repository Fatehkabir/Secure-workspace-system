package com.examworkspace.db;

import java.io.IOException;
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
import java.util.Set;

public final class Database {

    private static final Path DATA_DIR = Path.of(System.getProperty("examworkspace.dataDir",
            Path.of(System.getProperty("user.home"), ".examworkspace").toString()));
    private static final String URL = "jdbc:h2:file:" + DATA_DIR.resolve("examworkspace").toAbsolutePath();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Database() {
    }

    public static Path dataDir() {
        return DATA_DIR;
    }

    public static Connection connect() throws SQLException {
        return DriverManager();
    }

    private static Connection DriverManager() throws SQLException {
        return java.sql.DriverManager.getConnection(URL, "sa", "");
    }

    public static void initialize() {
        try {
            Files.createDirectories(DATA_DIR);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create data directory " + DATA_DIR, e);
        }

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS teachers (
                        id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                        username      VARCHAR(64)  NOT NULL UNIQUE,
                        full_name     VARCHAR(128) NOT NULL,
                        password_hash VARCHAR(512) NOT NULL,
                        google_sub    VARCHAR(255),
                        email         VARCHAR(255),
                        created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS rooms (
                        id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                        teacher_id    BIGINT NOT NULL REFERENCES teachers(id) ON DELETE CASCADE,
                        name          VARCHAR(128) NOT NULL,
                        room_code     VARCHAR(32)  NOT NULL,
                        room_password VARCHAR(128) NOT NULL,
                        starts_at     BIGINT       NOT NULL,
                        ends_at       BIGINT       NOT NULL,
                        created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (teacher_id, name)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS room_apps (
                        id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                        room_id       BIGINT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
                        name          VARCHAR(256)  NOT NULL,
                        launch_target VARCHAR(1024) NOT NULL,
                        target_type   VARCHAR(16)   NOT NULL,
                        UNIQUE (room_id, launch_target)
                    )""");

            addColumn(st, "teachers", "google_sub", "VARCHAR(255)");
            addColumn(st, "teachers", "email", "VARCHAR(255)");
            addColumn(st, "rooms", "room_code", "VARCHAR(32)");
            addColumn(st, "rooms", "room_password", "VARCHAR(128)");
            addColumn(st, "rooms", "starts_at", "BIGINT");
            addColumn(st, "rooms", "ends_at", "BIGINT");
            backfillRooms(conn);
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_teachers_google_sub ON teachers(google_sub)");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_rooms_code ON rooms(room_code)");
            try {
                st.execute("DROP INDEX IF EXISTS ux_rooms_password");
            } catch (SQLException ignored) {
                // Older databases may not have this index.
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
