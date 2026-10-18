package com.examworkspace.room;

import com.examworkspace.db.DataAccessException;
import com.examworkspace.db.Database;
import com.examworkspace.model.AppEntry;
import com.examworkspace.model.JoinableRoom;
import com.examworkspace.model.Room;
import com.examworkspace.model.RoomStatus;
import com.examworkspace.model.Student;
import com.examworkspace.monitor.SessionIdentity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Student-facing room lookup and join. Not scoped to a teacher — lists joinable rooms
 * from the local database and records roll-number memberships.
 */
public class StudentRoomService {

    private static final Pattern ROLL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,63}");

    public List<JoinableRoom> listAvailableRooms() {
        Instant now = Instant.now();
        String sql = """
                SELECT r.id, r.name, r.room_code, r.starts_at, r.ends_at,
                       COUNT(a.id) AS app_count
                FROM rooms r LEFT JOIN room_apps a ON a.room_id = r.id
                GROUP BY r.id, r.name, r.room_code, r.starts_at, r.ends_at
                ORDER BY r.starts_at, LOWER(r.name)""";
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<JoinableRoom> rooms = new ArrayList<>();
            while (rs.next()) {
                Instant startsAt = Instant.ofEpochMilli(rs.getLong("starts_at"));
                Instant endsAt = Instant.ofEpochMilli(rs.getLong("ends_at"));
                RoomStatus status = RoomStatus.at(startsAt, endsAt, now);
                if (status == RoomStatus.ENDED) {
                    continue;
                }
                rooms.add(new JoinableRoom(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("room_code"),
                        startsAt,
                        endsAt,
                        rs.getInt("app_count")));
            }
            return rooms;
        } catch (SQLException e) {
            throw new DataAccessException("Could not load available rooms", e);
        }
    }

    public Map<Long, List<AppEntry>> appsByRoom() {
        Instant now = Instant.now();
        String sql = """
                SELECT a.room_id, a.id, a.name, a.launch_target, a.target_type,
                       r.starts_at, r.ends_at
                FROM room_apps a JOIN rooms r ON r.id = a.room_id
                ORDER BY LOWER(a.name)""";
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            Map<Long, List<AppEntry>> byRoom = new LinkedHashMap<>();
            while (rs.next()) {
                Instant startsAt = Instant.ofEpochMilli(rs.getLong("starts_at"));
                Instant endsAt = Instant.ofEpochMilli(rs.getLong("ends_at"));
                if (RoomStatus.at(startsAt, endsAt, now) == RoomStatus.ENDED) {
                    continue;
                }
                byRoom.computeIfAbsent(rs.getLong("room_id"), id -> new ArrayList<>())
                        .add(new AppEntry(rs.getLong("id"), rs.getString("name"), rs.getString("launch_target"),
                                AppEntry.TargetType.valueOf(rs.getString("target_type"))));
            }
            return byRoom;
        } catch (SQLException e) {
            throw new DataAccessException("Could not load room apps", e);
        }
    }

    public Room join(long roomId, String password, String rollNumber, Student student)
            throws RoomService.RoomException {
        String entered = password == null ? "" : password.strip();
        if (entered.isEmpty()) {
            throw new RoomService.RoomException("Enter the room password.");
        }
        String roll = validateRoll(rollNumber);
        String displayName = student != null ? student.fullName() : "Guest";

        String sql = """
                SELECT r.id, r.teacher_id, r.name, r.room_code, r.room_password, r.starts_at, r.ends_at,
                       COUNT(a.id) AS app_count
                FROM rooms r LEFT JOIN room_apps a ON a.room_id = r.id
                WHERE r.id = ?
                GROUP BY r.id, r.teacher_id, r.name, r.room_code, r.room_password, r.starts_at, r.ends_at""";
        try (Connection conn = Database.connect()) {
            Room room;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new RoomService.RoomException("That room is no longer available.");
                    }
                    room = new Room(
                            rs.getLong("id"),
                            rs.getLong("teacher_id"),
                            rs.getString("name"),
                            rs.getString("room_code"),
                            rs.getString("room_password"),
                            Instant.ofEpochMilli(rs.getLong("starts_at")),
                            Instant.ofEpochMilli(rs.getLong("ends_at")),
                            rs.getInt("app_count"));
                }
            }
            RoomStatus status = room.statusAt(Instant.now());
            if (status == RoomStatus.ENDED) {
                throw new RoomService.RoomException("This room has ended and can no longer be joined.");
            }
            if (!room.roomPassword().equals(entered)) {
                throw new RoomService.RoomException("Incorrect room password.");
            }
            if (rollTaken(conn, room.id(), roll)) {
                throw new RoomService.RoomException(
                        "That roll number has already joined this room.");
            }
            try (PreparedStatement insert = conn.prepareStatement("""
                    INSERT INTO room_members (room_id, student_id, roll_number, display_name)
                    VALUES (?, ?, ?, ?)""")) {
                insert.setLong(1, room.id());
                if (student == null) {
                    insert.setNull(2, Types.BIGINT);
                } else {
                    insert.setLong(2, student.id());
                }
                insert.setString(3, roll);
                insert.setString(4, displayName);
                insert.executeUpdate();
            }
            // Remember who joined, so foreground-app flags carry the same roll number/name as the roster.
            SessionIdentity.remember(room.id(), roll, displayName);
            return room;
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new RoomService.RoomException("That roll number has already joined this room.");
            }
            throw new DataAccessException("Could not join room", e);
        }
    }

    public List<AppEntry> listApps(Room room) {
        String sql = """
                SELECT a.id, a.name, a.launch_target, a.target_type
                FROM room_apps a
                WHERE a.room_id = ?
                ORDER BY LOWER(a.name)""";
        try (Connection conn = Database.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, room.id());
            List<AppEntry> apps = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    apps.add(new AppEntry(rs.getLong(1), rs.getString(2), rs.getString(3),
                            AppEntry.TargetType.valueOf(rs.getString(4))));
                }
            }
            return apps;
        } catch (SQLException e) {
            throw new DataAccessException("Could not load room apps", e);
        }
    }

    private static String validateRoll(String rollNumber) throws RoomService.RoomException {
        String value = rollNumber == null ? "" : rollNumber.strip();
        if (value.isEmpty()) {
            throw new RoomService.RoomException("Enter your roll number.");
        }
        if (value.length() > 64) {
            throw new RoomService.RoomException("Roll number must be at most 64 characters.");
        }
        if (!ROLL.matcher(value).matches()) {
            throw new RoomService.RoomException(
                    "Roll number may use letters, digits, '.', '_', '/' or '-'.");
        }
        // Normalize so the unique index on (room_id, roll_number) is case-insensitive.
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean rollTaken(Connection conn, long roomId, String roll) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM room_members WHERE room_id = ? AND roll_number = ?")) {
            ps.setLong(1, roomId);
            ps.setString(2, roll);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        return "23505".equals(state);
    }
}
