package com.examworkspace.room;

import com.examworkspace.browser.UrlAllowlistPolicy;
import com.examworkspace.db.DataAccessException;
import com.examworkspace.db.Database;
import com.examworkspace.model.Room;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class RoomUrlPolicyService {

    private static final java.util.concurrent.ConcurrentHashMap<Long, UrlAllowlistPolicy> POLICY_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private RoomUrlPolicyService() {
    }

    public static void savePolicy(Room room, UrlAllowlistPolicy policy) {
        if (room == null) {
            throw new IllegalArgumentException("Room is required");
        }

        UrlAllowlistPolicy effective = policy == null ? UrlAllowlistPolicy.disabled() : policy;
        POLICY_CACHE.put(room.id(), effective);

        try (Connection conn = Database.connect()) {
            boolean exists;
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT 1 FROM room_url_policy WHERE room_id = ?")) {
                check.setLong(1, room.id());
                try (ResultSet rs = check.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                try (PreparedStatement update = conn.prepareStatement("""
                        UPDATE room_url_policy
                        SET enabled = ?, enforce_https = ?, block_internal_pages = ?, policy_text = ?, updated_at = ?
                        WHERE room_id = ?
                        """)) {
                    update.setBoolean(1, effective.enabled());
                    update.setBoolean(2, effective.enforceHttps());
                    update.setBoolean(3, effective.blockInternalPages());
                    update.setString(4, effective.serialize());
                    update.setLong(5, System.currentTimeMillis());
                    update.setLong(6, room.id());
                    update.executeUpdate();
                }
            } else {
                try (PreparedStatement insert = conn.prepareStatement("""
                        INSERT INTO room_url_policy (room_id, enabled, enforce_https, block_internal_pages, policy_text, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setLong(1, room.id());
                    insert.setBoolean(2, effective.enabled());
                    insert.setBoolean(3, effective.enforceHttps());
                    insert.setBoolean(4, effective.blockInternalPages());
                    insert.setString(5, effective.serialize());
                    insert.setLong(6, System.currentTimeMillis());
                    insert.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not save URL policy for room", e);
        }
    }

    public static UrlAllowlistPolicy loadPolicy(Room room) {
        if (room == null) {
            return UrlAllowlistPolicy.disabled();
        }
        return loadPolicy(room.id());
    }

    /** Same, for the student side which only knows the room id. Never throws, uses offline cache if disconnected. */
    public static UrlAllowlistPolicy loadPolicy(long roomId) {
        try {
            UrlAllowlistPolicy policy = loadPolicyFromDb(roomId);
            POLICY_CACHE.put(roomId, policy);
            return policy;
        } catch (SQLException | RuntimeException e) {
            // When offline or disconnected, check if we have a cached policy for this room
            UrlAllowlistPolicy cached = POLICY_CACHE.get(roomId);
            if (cached != null) {
                return cached;
            }
            return UrlAllowlistPolicy.disabled();
        }
    }

    private static UrlAllowlistPolicy loadPolicyFromDb(long roomId) throws SQLException {
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT enabled, enforce_https, block_internal_pages, policy_text
                     FROM room_url_policy
                     WHERE room_id = ?
                     """)) {
            ps.setLong(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return UrlAllowlistPolicy.disabled();
                }

                boolean enabled = rs.getBoolean("enabled");
                boolean enforceHttps = rs.getBoolean("enforce_https");
                boolean blockInternalPages = rs.getBoolean("block_internal_pages");
                String serialized = rs.getString("policy_text");
                UrlAllowlistPolicy parsed = UrlAllowlistPolicy.parse(serialized);

                return new UrlAllowlistPolicy(
                        enabled,
                        enforceHttps,
                        blockInternalPages,
                        parsed.rules()
                );
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not load URL policy for room", e);
        }
    }

    /** Convenience for the student monitor: is the website allowlist switched on for this room? */
    public static boolean isPolicyEnabled(long roomId) {
        return loadPolicy(roomId).enabled();
    }

    public static boolean isAllowed(Room room, String candidateUrl) {
        UrlAllowlistPolicy policy = loadPolicy(room);
        if (policy == null || !policy.enabled()) {
            return false;
        }
        return UrlAllowlistPolicy.isAllowed(candidateUrl, policy);
    }
}