package com.examworkspace.room;

import com.examworkspace.apps.AppIdResolver;
import com.examworkspace.db.DataAccessException;
import com.examworkspace.db.Database;
import com.examworkspace.model.AppEntry;
import com.examworkspace.model.Room;
import com.examworkspace.model.RoomMember;
import com.examworkspace.model.Teacher;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Room and room-app persistence. Every operation is scoped to the logged-in teacher,
 * so a teacher can never read or modify another teacher's rooms.
 * Room IDs are unique across every room. Passwords do not have to be unique.
 */
public class RoomService {

    public static final int MIN_ROOM_PASSWORD = 4;
    private static final long PAST_GRACE_SECONDS = 45;

    public static class RoomException extends Exception {
        public RoomException(String message) {
            super(message);
        }
    }

    private final Teacher teacher;

    public RoomService(Teacher teacher) {
        this.teacher = teacher;
    }

    public List<Room> listRooms() {
        String sql = """
                SELECT r.id, r.teacher_id, r.name, r.room_code, r.room_password, r.starts_at, r.ends_at,
                       COUNT(a.id) AS app_count
                FROM rooms r LEFT JOIN room_apps a ON a.room_id = r.id
                WHERE r.teacher_id = ?
                GROUP BY r.id, r.teacher_id, r.name, r.room_code, r.room_password, r.starts_at, r.ends_at
                ORDER BY r.starts_at, LOWER(r.name)""";
        try (Connection conn = Database.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, teacher.id());
            List<Room> rooms = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rooms.add(readRoom(rs));
                }
            }
            return rooms;
        } catch (SQLException e) {
            throw new DataAccessException("Could not load rooms", e);
        }
    }

    public Map<Long, List<AppEntry>> appsByRoom() {
        String sql = """
                SELECT a.room_id, a.id, a.name, a.launch_target, a.target_type, a.process_name
                FROM room_apps a JOIN rooms r ON r.id = a.room_id
                WHERE r.teacher_id = ?
                ORDER BY LOWER(a.name)""";
        try (Connection conn = Database.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, teacher.id());
            Map<Long, List<AppEntry>> byRoom = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byRoom.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>())
                            .add(new AppEntry(rs.getLong(2), rs.getString(3), rs.getString(4),
                                    AppEntry.TargetType.valueOf(rs.getString(5)), rs.getString(6)));
                }
            }
            return byRoom;
        } catch (SQLException e) {
            throw new DataAccessException("Could not load room apps", e);
        }
    }

    public Room createRoom(String name, String roomCode, String password, Instant start, Instant end)
            throws RoomException {
        String roomName = validateName(name);
        String code = validateRoomCode(roomCode);
        String roomPassword = validatePassword(password);
        validateSchedule(start, end, null);
        try (Connection conn = Database.connect()) {
            ensureNameFree(conn, roomName, -1);
            ensureCodeFree(conn, code, -1);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO rooms (teacher_id, name, room_code, room_password, starts_at, ends_at)
                    VALUES (?, ?, ?, ?, ?, ?)""", PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, teacher.id());
                ps.setString(2, roomName);
                ps.setString(3, code);
                ps.setString(4, roomPassword);
                ps.setLong(5, start.toEpochMilli());
                ps.setLong(6, end.toEpochMilli());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return new Room(keys.getLong(1), teacher.id(), roomName, code, roomPassword, start, end, 0);
                }
            }
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new RoomException("That room ID is already used. Choose a different one.");
            }
            throw new DataAccessException("Could not create room", e);
        }
    }

    private static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        if ("23505".equals(state)) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("unique");
    }

    public void renameRoom(Room room, String newName) throws RoomException {
        String roomName = validateName(newName);
        try (Connection conn = Database.connect()) {
            ensureNameFree(conn, roomName, room.id());
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE rooms SET name = ? WHERE id = ? AND teacher_id = ?")) {
                ps.setString(1, roomName);
                ps.setLong(2, room.id());
                ps.setLong(3, teacher.id());
                if (ps.executeUpdate() == 0) {
                    throw new RoomException("That room is no longer available.");
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not rename room", e);
        }
    }

    public void changePassword(Room room, String newPassword) throws RoomException {
        String roomPassword = validatePassword(newPassword);
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE rooms SET room_password = ? WHERE id = ? AND teacher_id = ?")) {
            ps.setString(1, roomPassword);
            ps.setLong(2, room.id());
            ps.setLong(3, teacher.id());
            if (ps.executeUpdate() == 0) {
                throw new RoomException("That room is no longer available.");
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not change the room password", e);
        }
    }

    /**
     * @param previousStart the room's current start, so a live room can keep a start that is
     *                      already in the past. Any newly chosen time must be in the future.
     */
    public void updateSchedule(Room room, Instant start, Instant end) throws RoomException {
        validateSchedule(start, end, room.startsAt());
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE rooms SET starts_at = ?, ends_at = ? WHERE id = ? AND teacher_id = ?")) {
            ps.setLong(1, start.toEpochMilli());
            ps.setLong(2, end.toEpochMilli());
            ps.setLong(3, room.id());
            ps.setLong(4, teacher.id());
            if (ps.executeUpdate() == 0) {
                throw new RoomException("That room is no longer available.");
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not update the schedule", e);
        }
    }

    public void deleteRoom(Room room) {
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM rooms WHERE id = ? AND teacher_id = ?")) {
            ps.setLong(1, room.id());
            ps.setLong(2, teacher.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Could not delete room", e);
        }
    }

    public List<AppEntry> listApps(Room room) {
        return appsByRoom().getOrDefault(room.id(), List.of());
    }

    public List<RoomMember> listMembers(Room room) {
        String sql = """
                SELECT m.id, m.room_id, m.student_id, m.roll_number, m.display_name, m.joined_at, m.left_at
                FROM room_members m
                JOIN rooms r ON r.id = m.room_id
                WHERE m.room_id = ? AND r.teacher_id = ? AND m.left_at IS NULL
                ORDER BY LOWER(m.display_name)""";
        try (Connection conn = Database.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, room.id());
            ps.setLong(2, teacher.id());
            List<RoomMember> members = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long studentId = rs.getLong("student_id");
                    Long studentRef = rs.wasNull() ? null : studentId;
                    Instant joinedAt = rs.getTimestamp("joined_at") == null
                            ? Instant.EPOCH
                            : rs.getTimestamp("joined_at").toInstant();
                    long leftAtMillis = rs.getLong("left_at");
                    Instant leftAt = rs.wasNull() ? null : Instant.ofEpochMilli(leftAtMillis);
                    members.add(new RoomMember(
                            rs.getLong("id"),
                            rs.getLong("room_id"),
                            studentRef,
                            rs.getString("roll_number"),
                            rs.getString("display_name"),
                            joinedAt,
                            leftAt));
                }
            }
            return members;
        } catch (SQLException e) {
            throw new DataAccessException("Could not load room members", e);
        }
    }

    /**
     * Adds apps to the room, silently skipping any that are already in it.
     *
     * @return number of apps actually added
     */
    public int addApps(Room room, List<AppEntry> apps) {
        // Resolve process names for APP_ID entries that don't already have one.
        List<String> appIds = apps.stream()
                .filter(a -> a.targetType() == AppEntry.TargetType.APP_ID && a.processName() == null)
                .map(AppEntry::launchTarget)
                .toList();
        Map<String, String> resolved = appIds.isEmpty() ? Map.of() : AppIdResolver.resolve(appIds);

        String sql = """
                INSERT INTO room_apps (room_id, name, launch_target, target_type, process_name)
                SELECT r.id, ?, ?, ?, ? FROM rooms r
                WHERE r.id = ? AND r.teacher_id = ?
                  AND NOT EXISTS (SELECT 1 FROM room_apps WHERE room_id = r.id AND launch_target = ?)""";
        try (Connection conn = Database.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (AppEntry app : apps) {
                // Determine process name: use existing value, or resolved for APP_ID, or derive from path.
                String procName = app.processName();
                if (procName == null && app.targetType() == AppEntry.TargetType.APP_ID) {
                    procName = resolved.get(app.launchTarget().toLowerCase(Locale.ROOT));
                }
                if (procName == null) {
                    procName = app.resolvedProcessName(); // derives from EXECUTABLE path
                }
                ps.setString(1, app.name());
                ps.setString(2, app.launchTarget());
                ps.setString(3, app.targetType().name());
                ps.setString(4, procName); // may be null for unresolvable APP_ID entries
                ps.setLong(5, room.id());
                ps.setLong(6, teacher.id());
                ps.setString(7, app.launchTarget());
                ps.addBatch();
            }
            int added = 0;
            for (int count : ps.executeBatch()) {
                added += Math.max(count, 0);
            }
            return added;
        } catch (SQLException e) {
            throw new DataAccessException("Could not add apps to room", e);
        }
    }

    public void removeApps(Room room, List<AppEntry> apps) {
        String sql = """
                DELETE FROM room_apps
                WHERE id = ? AND room_id IN (SELECT id FROM rooms WHERE id = ? AND teacher_id = ?)""";
        try (Connection conn = Database.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (AppEntry app : apps) {
                ps.setLong(1, app.id());
                ps.setLong(2, room.id());
                ps.setLong(3, teacher.id());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new DataAccessException("Could not remove apps from room", e);
        }
    }

    private static Room readRoom(ResultSet rs) throws SQLException {
        return new Room(
                rs.getLong("id"),
                rs.getLong("teacher_id"),
                rs.getString("name"),
                rs.getString("room_code"),
                rs.getString("room_password"),
                Instant.ofEpochMilli(rs.getLong("starts_at")),
                Instant.ofEpochMilli(rs.getLong("ends_at")),
                rs.getInt("app_count"));
    }

    private void ensureCodeFree(Connection conn, String code, long exceptRoomId)
            throws SQLException, RoomException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM rooms WHERE LOWER(room_code) = LOWER(?) AND id <> ?")) {
            ps.setString(1, code);
            ps.setLong(2, exceptRoomId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new RoomException("That room ID is already used. Choose a different one.");
                }
            }
        }
    }

    private void ensureNameFree(Connection conn, String name, long exceptRoomId) throws SQLException, RoomException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM rooms WHERE teacher_id = ? AND LOWER(name) = LOWER(?) AND id <> ?")) {
            ps.setLong(1, teacher.id());
            ps.setString(2, name);
            ps.setLong(3, exceptRoomId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new RoomException("You already have a room named \"" + name + "\".");
                }
            }
        }
    }

    private static String validateName(String name) throws RoomException {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            throw new RoomException("Room name cannot be empty.");
        }
        if (trimmed.length() > 128) {
            throw new RoomException("Room name must be at most 128 characters.");
        }
        return trimmed;
    }

    public static String checkPassword(String password) throws RoomException {
        return validatePassword(password);
    }

    public static String checkRoomCode(String roomCode) throws RoomException {
        return validateRoomCode(roomCode);
    }

    public static void checkSchedule(Instant start, Instant end, Instant keepStart) throws RoomException {
        validateSchedule(start, end, keepStart);
    }

    private static String validateRoomCode(String roomCode) throws RoomException {
        String value = roomCode == null ? "" : roomCode.strip().toUpperCase();
        if (value.length() < 3) {
            throw new RoomException("Room ID must be at least 3 characters.");
        }
        if (value.length() > 32) {
            throw new RoomException("Room ID must be at most 32 characters.");
        }
        if (!value.matches("[A-Z0-9][A-Z0-9._-]{2,31}")) {
            throw new RoomException("Room ID may use letters, digits, '.', '_' or '-'.");
        }
        return value;
    }

    private static String validatePassword(String password) throws RoomException {
        String value = password == null ? "" : password.strip();
        if (value.length() < MIN_ROOM_PASSWORD) {
            throw new RoomException("Room password must be at least " + MIN_ROOM_PASSWORD + " characters.");
        }
        if (value.length() > 128) {
            throw new RoomException("Room password must be at most 128 characters.");
        }
        return value;
    }

    /**
     * @param keepStart when editing, the start currently saved on the room. It may stay even if it
     *                  is already in the past. Every other chosen time has to be in the future.
     */
    private static void validateSchedule(Instant start, Instant end, Instant keepStart) throws RoomException {
        if (start == null || end == null) {
            throw new RoomException("Choose both a start and an end.");
        }
        if (!end.isAfter(start)) {
            throw new RoomException("The end must be after the start.");
        }
        Instant earliest = Instant.now().minusSeconds(PAST_GRACE_SECONDS);
        boolean keepingStart = keepStart != null
                && Math.abs(start.toEpochMilli() - keepStart.toEpochMilli()) < 60_000;
        if (!keepingStart && start.isBefore(earliest)) {
            throw new RoomException("The start cannot be in the past.");
        }
        if (end.isBefore(earliest)) {
            throw new RoomException("The end cannot be in the past.");
        }
    }
}
