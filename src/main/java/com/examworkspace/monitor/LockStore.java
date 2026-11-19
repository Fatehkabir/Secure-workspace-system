package com.examworkspace.monitor;

import com.examworkspace.db.Database;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where a locked student's state lives -- in the shared database, same as {@link ViolationStore},
 * so it survives the teacher's app restarting and needs no extra address or setup for students.
 *
 * <p>A lock is released two ways, both of which just delete the row:
 * <ul>
 *   <li>the teacher clicks Unlock on the Live Monitor panel ({@link #unlock}), or</li>
 *   <li>the student enters the unlock code the teacher gives them, in person or otherwise
 *       ({@link #tryUnlockWithCode}) -- the code is generated fresh for each lock, shown only
 *       to the teacher, and not sent anywhere else, so only someone who got it from the teacher
 *       can use it.</li>
 * </ul>
 *
 * <p>Matched by student name, case-insensitively -- the same identity StudentRoomService already
 * uses for room_members. The database index on (room_id, student_name) is case-SENSITIVE (H2 has
 * no portable case-insensitive expression index), so -- exactly like StudentRoomService already
 * does for the roster -- every write here first looks up any existing row case-insensitively and
 * reuses it, rather than relying on the index to catch a case-variant duplicate.
 */
public final class LockStore {

    public record LockedStudent(String studentName, String reason, String unlockCode, Instant lockedAt) {}

    @FunctionalInterface
    private interface SqlCall<T> {
        T run() throws SQLException;
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> localLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private static String localKey(long roomId, String studentName) {
        return roomId + ":" + (studentName == null ? "" : studentName.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public static void setLocalLock(long roomId, String studentName, String code) {
        localLocks.put(localKey(roomId, studentName), code);
    }

    public static boolean hasLocalLock(long roomId, String studentName) {
        return localLocks.containsKey(localKey(roomId, studentName));
    }

    public static boolean checkLocalUnlockCode(long roomId, String studentName, String enteredCode) {
        if (enteredCode == null || enteredCode.isBlank()) return false;
        String code = localLocks.get(localKey(roomId, studentName));
        return code != null && code.equalsIgnoreCase(enteredCode.trim());
    }

    public static void clearLocalLock(long roomId, String studentName) {
        localLocks.remove(localKey(roomId, studentName));
    }

    private LockStore() {
    }

    /**
     * Locks the student (or refreshes an existing lock with a new code/reason/timestamp if
     * they're somehow already locked). Returns the unlock code so the caller can log it,
     * though the teacher's Live Monitor panel is the normal way to see it.
     */
    public static String lock(long roomId, String studentName, String reason) throws SQLException {
        String name = blankTo(studentName, "Student");
        String code = generateCode();
        long now = System.currentTimeMillis();
        setLocalLock(roomId, name, code);

        return withRetry(() -> {
            try (Connection conn = Database.connect()) {
                Long existingRowKey = findRow(conn, roomId, name); // null if not currently locked

                if (existingRowKey != null) {
                    try (PreparedStatement update = conn.prepareStatement(
                            "UPDATE room_locks SET reason = ?, unlock_code = ?, locked_at = ? "
                                    + "WHERE room_id = ? AND student_name = ?")) {
                        update.setString(1, clip(reason, 512));
                        update.setString(2, code);
                        update.setLong(3, now);
                        update.setLong(4, roomId);
                        update.setString(5, nameAtRow(conn, roomId, name));
                        update.executeUpdate();
                    }
                } else {
                    try (PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO room_locks (room_id, student_name, reason, unlock_code, locked_at) "
                                    + "VALUES (?, ?, ?, ?, ?)")) {
                        insert.setLong(1, roomId);
                        insert.setString(2, clip(name, 200));
                        insert.setString(3, clip(reason, 512));
                        insert.setString(4, code);
                        insert.setLong(5, now);
                        insert.executeUpdate();
                    }
                }
                return code;
            }
        });
    }

    public static boolean isLocked(long roomId, String studentName) throws SQLException {
        return withRetry(() -> {
            try (Connection conn = Database.connect()) {
                return findRow(conn, roomId, studentName) != null;
            }
        });
    }

    /** @return the locked entry for this student if currently locked in this room, else empty. */
    public static Optional<LockedStudent> findLock(long roomId, String studentName) throws SQLException {
        return withRetry(() -> {
            try (Connection conn = Database.connect();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT student_name, reason, unlock_code, locked_at FROM room_locks "
                                 + "WHERE room_id = ? AND LOWER(student_name) = LOWER(?)")) {
                ps.setLong(1, roomId);
                ps.setString(2, studentName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new LockedStudent(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                Instant.ofEpochMilli(rs.getLong(4))));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    /** Teacher-direct unlock: no code needed. @return true if they were actually locked. */
    public static boolean unlock(long roomId, String studentName) throws SQLException {
        clearLocalLock(roomId, studentName);
        return withRetry(() -> {
            try (Connection conn = Database.connect();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM room_locks WHERE room_id = ? AND LOWER(student_name) = LOWER(?)")) {
                ps.setLong(1, roomId);
                ps.setString(2, studentName);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /**
     * The student entering the code themselves. Unlocks only if the code matches exactly;
     * a wrong code changes nothing (the caller is expected to rate-limit repeated attempts).
     */
    public static boolean tryUnlockWithCode(long roomId, String studentName, String enteredCode) throws SQLException {
        if (enteredCode == null || enteredCode.isBlank()) {
            return false;
        }
        if (checkLocalUnlockCode(roomId, studentName, enteredCode)) {
            clearLocalLock(roomId, studentName);
        }
        return withRetry(() -> {
            try (Connection conn = Database.connect();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM room_locks WHERE room_id = ? AND LOWER(student_name) = LOWER(?) AND unlock_code = ?")) {
                ps.setLong(1, roomId);
                ps.setString(2, studentName);
                ps.setString(3, enteredCode.strip());
                boolean removed = ps.executeUpdate() > 0;
                if (removed) {
                    clearLocalLock(roomId, studentName);
                }
                return removed;
            }
        });
    }

    /** Everyone currently locked in this room, most recently locked first -- for the teacher's panel. */
    public static List<LockedStudent> list(long roomId) throws SQLException {
        return withRetry(() -> {
            List<LockedStudent> result = new ArrayList<>();
            try (Connection conn = Database.connect();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT student_name, reason, unlock_code, locked_at FROM room_locks "
                                 + "WHERE room_id = ? ORDER BY locked_at DESC")) {
                ps.setLong(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new LockedStudent(rs.getString(1), rs.getString(2), rs.getString(3),
                                Instant.ofEpochMilli(rs.getLong(4))));
                    }
                }
            }
            return result;
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** @return a row's own (as-stored) student_name if a case-insensitive match exists, else null. */
    private static Long findRow(Connection conn, long roomId, String studentName) throws SQLException {
        // Only used as a non-null/null signal -- see nameAtRow for the actual stored name.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM room_locks WHERE room_id = ? AND LOWER(student_name) = LOWER(?)")) {
            ps.setLong(1, roomId);
            ps.setString(2, studentName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? 1L : null;
            }
        }
    }

    private static String nameAtRow(Connection conn, long roomId, String studentName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT student_name FROM room_locks WHERE room_id = ? AND LOWER(student_name) = LOWER(?)")) {
            ps.setLong(1, roomId);
            ps.setString(2, studentName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : studentName;
            }
        }
    }

    private static String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

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

    private static boolean isLockError(SQLException e) {
        if (e.getErrorCode() == 90020 || e.getErrorCode() == 50200) {
            return true;
        }
        String message = e.getMessage();
        return message != null && (message.contains("already in use") || message.contains("Locked by another process")
                || message.contains("Timeout trying to lock"));
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
