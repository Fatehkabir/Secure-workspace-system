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
        String defaultName = student != null && !student.fullName().isBlank() ? student.fullName() : rollNumber;
        return join(roomId, password, rollNumber, defaultName, student);
    }

    public Room join(long roomId, String password, String rollNumber, String studentName, Student student)
            throws RoomService.RoomException {
        String entered = password == null ? "" : password.strip();
        if (entered.isEmpty()) {
            throw new RoomService.RoomException("Enter the room password.");
        }
        String name = studentName == null ? "" : studentName.strip();
        if (name.isEmpty()) {
            if (student != null && !student.fullName().isBlank()) {
                name = student.fullName().strip();
            } else {
                throw new RoomService.RoomException("Enter your name / username.");
            }
        }
        if (name.length() > 128) {
            throw new RoomService.RoomException("Name must be at most 128 characters.");
        }
        String roll = validateRoll(rollNumber, name);

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

            // A single student can enter multiple times, but no two or more students can have the same name.
            // Check if there is an existing member row in this room with this name.
            Long existingMemberId = null;
            Long existingStudentId = null;
            boolean isCurrentlyActive = false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, student_id, left_at FROM room_members WHERE room_id = ? AND LOWER(display_name) = LOWER(?)")) {
                ps.setLong(1, room.id());
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        existingMemberId = rs.getLong("id");
                        long sid = rs.getLong("student_id");
                        existingStudentId = rs.wasNull() ? null : sid;
                        rs.getLong("left_at");
                        isCurrentlyActive = rs.wasNull();
                    }
                }
            }

            if (existingMemberId != null) {
                // If another session is actively in the room with this name right now:
                if (isCurrentlyActive) {
                    throw new RoomService.RoomException("A student named \"" + name + "\" is already in this room.");
                }
                // If the name belongs to a registered student account, only that account can rejoin:
                if (existingStudentId != null) {
                    if (student == null || student.id() != existingStudentId) {
                        throw new RoomService.RoomException("A student named \"" + name + "\" is already registered in this room.");
                    }
                }
            }

            // Check if roll number is already used by another student with a different name:
            if (!roll.equalsIgnoreCase(name)) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, display_name FROM room_members WHERE room_id = ? AND LOWER(roll_number) = LOWER(?) AND LOWER(display_name) <> LOWER(?)")) {
                    ps.setLong(1, room.id());
                    ps.setString(2, roll);
                    ps.setString(3, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw new RoomService.RoomException("Roll number \"" + roll + "\" is already in use by another student.");
                        }
                    }
                }
            }

            if (existingMemberId != null) {
                // Re-entering room! Update timestamp and clear left_at (same student can enter multiple times)
                try (PreparedStatement update = conn.prepareStatement("""
                        UPDATE room_members
                        SET student_id = ?, roll_number = ?, display_name = ?, joined_at = CURRENT_TIMESTAMP, left_at = NULL
                        WHERE id = ?""")) {
                    if (student == null || student.id() == 0) {
                        update.setNull(1, Types.BIGINT);
                    } else {
                        update.setLong(1, student.id());
                    }
                    update.setString(2, roll);
                    update.setString(3, name);
                    update.setLong(4, existingMemberId);
                    update.executeUpdate();
                }
            } else {
                // Fresh join
                try (PreparedStatement insert = conn.prepareStatement("""
                        INSERT INTO room_members (room_id, student_id, roll_number, display_name, joined_at, left_at)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, NULL)""")) {
                    insert.setLong(1, room.id());
                    if (student == null || student.id() == 0) {
                        insert.setNull(2, Types.BIGINT);
                    } else {
                        insert.setLong(2, student.id());
                    }
                    insert.setString(3, roll);
                    insert.setString(4, name);
                    insert.executeUpdate();
                }
            }
            // Remember who joined, so foreground-app flags carry the same roll number/name as the roster.
            SessionIdentity.remember(room.id(), roll, name);
            return room;
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new RoomService.RoomException("A student named \"" + name + "\" is already in this room.");
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

    /**
     * Marks the student (identified by what they joined THIS room as, via SessionIdentity) as
     * having left: their roster row gets a left_at timestamp instead of being deleted, so the
     * teacher's roster can show they left, and the same roll number/name can rejoin later.
     * Safe to call even if they never actually joined (does nothing).
     */
    public void leave(long roomId) {
        SessionIdentity.Identity who = SessionIdentity.recall(roomId);
        if (who == null || (who.displayName() == null && who.rollNumber() == null)) {
            return;
        }
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE room_members SET left_at = ? WHERE room_id = ? AND (LOWER(display_name) = LOWER(?) OR LOWER(roll_number) = LOWER(?)) AND left_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, roomId);
            ps.setString(3, who.displayName() != null ? who.displayName() : "");
            ps.setString(4, who.rollNumber() != null ? who.rollNumber() : "");
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[StudentRoomService] Could not record that the student left: " + e.getMessage());
        }
    }

    private static String validateRoll(String rollNumber, String fallbackName) {
        String value = rollNumber == null ? "" : rollNumber.strip();
        if (value.isEmpty()) {
            value = fallbackName != null ? fallbackName.strip() : "";
        }
        if (value.length() > 64) {
            value = value.substring(0, 64);
        }
        return value;
    }

    private static String validateRoll(String rollNumber) throws RoomService.RoomException {
        return validateRoll(rollNumber, "");
    }

    private static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        return "23505".equals(state);
    }
}
