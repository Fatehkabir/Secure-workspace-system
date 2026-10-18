package com.examworkspace.monitor;

import com.examworkspace.db.Database;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ViolationStore {

    /** One flagged moment. */
    public record Violation(long id, String rollNumber, String studentName, String detectedApp,
                            String executablePath, Instant occurredAt) {

        /** "2024-CS-042 · Ali Khan", or just the name when there is no roll number. */
        public String studentLabel() {
            if (rollNumber == null || rollNumber.isBlank()) {
                return studentName;
            }
            return rollNumber + " \u00b7 " + studentName;
        }
    }

    @FunctionalInterface
    private interface SqlCall<T> {
        T run() throws SQLException;
    }

    private ViolationStore() {
    }

    /** Records that a student was flagged for this app. */
    public static void record(long roomId, Long studentId, String rollNumber, String studentName,
                              String detectedApp, String executablePath) throws SQLException {
        withRetry(() -> {
            try (Connection conn = Database.connect();
                 PreparedStatement ps = conn.prepareStatement("""
                         INSERT INTO room_violations
                             (room_id, student_id, roll_number, student_name, detected_app, executable_path, occurred_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setLong(1, roomId);
                if (studentId == null) {
                    ps.setNull(2, Types.BIGINT);
                } else {
                    ps.setLong(2, studentId);
                }
                ps.setString(3, clip(rollNumber, 64));
                ps.setString(4, clip(blankTo(studentName, "Student"), 128));
                ps.setString(5, clip(blankTo(detectedApp, "unknown app"), 512));
                ps.setString(6, clip(executablePath, 1024));
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
                return null;
            }
        });
    }

    /** Flags in this room newer than {@code afterId} (use 0 for all), oldest first. */
    public static List<Violation> listAfter(long roomId, long afterId) throws SQLException {
        return withRetry(() -> {
            List<Violation> result = new ArrayList<>();
            try (Connection conn = Database.connect();
                 PreparedStatement ps = conn.prepareStatement("""
                         SELECT id, roll_number, student_name, detected_app, executable_path, occurred_at
                         FROM room_violations
                         WHERE room_id = ? AND id > ?
                         ORDER BY id""")) {
                ps.setLong(1, roomId);
                ps.setLong(2, afterId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new Violation(rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), Instant.ofEpochMilli(rs.getLong(6))));
                    }
                }
            }
            return result;
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static <T> T withRetry(SqlCall<T> call) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= 6; attempt++) {
            try {
                return call.run();
            } catch (SQLException e) {
                if (!isLockError(e)) {
                    throw e;
                }
                last = e;
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    /** H2 reports "Database may be already in use" (90020) when another process holds the file. */
    private static boolean isLockError(SQLException e) {
        if (e.getErrorCode() == 90020) {
            return true;
        }
        String message = e.getMessage();
        return message != null && (message.contains("already in use") || message.contains("Locked by another process"));
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
