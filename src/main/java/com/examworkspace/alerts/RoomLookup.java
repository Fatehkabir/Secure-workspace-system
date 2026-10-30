package com.examworkspace.alerts;

import com.examworkspace.db.Database;
import com.examworkspace.monitor.AllowedApp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Read-only helper that finds a room by its room code and loads its allowed
 * apps, for the Alerts Monitor to check incoming detections against.
 *
 * <p>Deliberately does not go through RoomService, since RoomService scopes
 * everything to a logged-in Teacher and this tool is meant to be a standalone
 * monitor you point at a room code — no login flow needed.
 * Purely additive: only reads from the existing database via Database.connect(),
 * never writes, and does not modify any existing class.
 *
 * <h3>How process-name matching works</h3>
 * <ul>
 *   <li>For {@code EXECUTABLE} entries the DB stores a full path like
 *       {@code C:\…\Code.exe}.  We reduce it to the filename
 *       ({@code code.exe}) for matching.</li>
 *   <li>For {@code APP_ID} (Windows Store) entries the DB now also stores a
 *       {@code process_name} column populated by {@code AppIdResolver} at
 *       save-time.  That name is used directly.  Legacy rows where the column
 *       is {@code NULL} still won't match — they were saved before this feature
 *       existed; the teacher should remove and re-add those apps.</li>
 * </ul>
 */
public final class RoomLookup {

    public record RoomInfo(long roomId, String name, String roomCode, List<String> allowedProcessNames) {

        /**
         * Case-insensitive match against the process name (e.g. "chrome.exe").
         * Returns {@code true} if the process is in the allowed list for this room.
         */
        public boolean isAllowed(String processName) {
            if (processName == null) return false;
            String normalized = processName.toLowerCase(Locale.ROOT).trim();
            int sep = Math.max(normalized.lastIndexOf('\\'), normalized.lastIndexOf('/'));
            String file = (sep >= 0 ? normalized.substring(sep + 1) : normalized).trim();
            return allowedProcessNames.stream().anyMatch(a -> a.equalsIgnoreCase(normalized) || a.equalsIgnoreCase(file));
        }
    }

    private RoomLookup() {
    }

    public static Optional<RoomInfo> findByCode(String roomCode) throws SQLException {
        try (Connection conn = Database.connect()) {
            long roomId;
            String name;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, name FROM rooms WHERE room_code = ?")) {
                ps.setString(1, roomCode);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    roomId = rs.getLong("id");
                    name = rs.getString("name");
                }
            }

            List<String> allowed = loadAllowedProcessNames(conn, roomId);
            return Optional.of(new RoomInfo(roomId, name, roomCode, allowed));
        }
    }

    public record AllowedSnapshot(List<AllowedApp> apps, List<String> processNames) {}

    /** Loads both full app records and process names in a single fast database round-trip. */
    public static AllowedSnapshot loadAllowedSnapshot(long roomId) throws SQLException {
        List<AllowedApp> apps = new ArrayList<>();
        List<String> processNames = new ArrayList<>();
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name, launch_target, target_type, process_name FROM room_apps WHERE room_id = ?")) {
            ps.setLong(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    String launchTarget = rs.getString("launch_target");
                    String targetType = rs.getString("target_type");
                    String processName = rs.getString("process_name");

                    apps.add(new AllowedApp(name, launchTarget, targetType, processName));

                    if (processName != null && !processName.isBlank()) {
                        String clean = cleanExeName(processName);
                        if (!clean.isEmpty() && !processNames.contains(clean)) {
                            processNames.add(clean);
                        }
                    }
                    if (launchTarget != null && !launchTarget.isBlank()) {
                        String cleanTarget = cleanExeName(launchTarget);
                        if (!cleanTarget.isEmpty() && !processNames.contains(cleanTarget)) {
                            processNames.add(cleanTarget);
                        }
                    }
                }
            }
        }
        return new AllowedSnapshot(apps, processNames);
    }

    /**
     * Reloads the allowed process-name list for an already-known room.
     * Used by the Alerts Monitor to refresh the list live after the teacher
     * adds or removes apps while monitoring is active.
     */
    public static List<String> reloadAllowedProcessNames(long roomId) throws SQLException {
        return loadAllowedSnapshot(roomId).processNames();
    }

    /**
     * Checks a candidate room password against what's stored for this room.
     * Room passwords are stored as plain text (consistent with how
     * RoomService already handles them), so this is a plain comparison.
     */
    public static boolean checkPassword(long roomId, String candidatePassword) throws SQLException {
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement("SELECT room_password FROM rooms WHERE id = ?")) {
            ps.setLong(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String stored = rs.getString("room_password");
                return stored != null && stored.equals(candidatePassword);
            }
        }
    }

    /** Full app records (name, target, type, process name) the student client matches against. */
    public static List<AllowedApp> loadAllowedApps(long roomId) throws SQLException {
        List<AllowedApp> apps = new ArrayList<>();
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name, launch_target, target_type, process_name FROM room_apps WHERE room_id = ?")) {
            ps.setLong(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    apps.add(new AllowedApp(rs.getString("name"), rs.getString("launch_target"),
                            rs.getString("target_type"), rs.getString("process_name")));
                }
            }
        }
        return apps;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private static List<String> loadAllowedProcessNames(Connection conn, long roomId) throws SQLException {
        List<String> allowed = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT launch_target, target_type, process_name FROM room_apps WHERE room_id = ?")) {
            ps.setLong(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String launchTarget = rs.getString("launch_target");
                    String targetType   = rs.getString("target_type");
                    String processName  = rs.getString("process_name");

                    if (processName != null && !processName.isBlank()) {
                        String clean = cleanExeName(processName);
                        if (!clean.isEmpty() && !allowed.contains(clean)) {
                            allowed.add(clean);
                        }
                    }

                    if (launchTarget != null && !launchTarget.isBlank()) {
                        String cleanTarget = cleanExeName(launchTarget);
                        if (!cleanTarget.isEmpty() && !allowed.contains(cleanTarget)) {
                            allowed.add(cleanTarget);
                        }
                    }
                }
            }
        }
        return allowed;
    }

    private static String cleanExeName(String raw) {
        if (raw == null || raw.isBlank()) return "";
        int sep = Math.max(raw.lastIndexOf('\\'), raw.lastIndexOf('/'));
        return (sep >= 0 ? raw.substring(sep + 1) : raw).trim().toLowerCase(Locale.ROOT);
    }
}
