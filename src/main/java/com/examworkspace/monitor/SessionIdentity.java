package com.examworkspace.monitor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, for this running app, which roll number and name a student joined each
 * room with -- so a flag raised by the foreground-app check carries the same identity
 * the teacher already sees on the room's roster, even for guest students.
 *
 * <p>In-memory only; it is filled in by {@code StudentRoomService.join} at the moment
 * the join succeeds.
 */
public final class SessionIdentity {

    public record Identity(String rollNumber, String displayName) {}

    private static final Map<Long, Identity> BY_ROOM = new ConcurrentHashMap<>();

    private SessionIdentity() {
    }

    public static void remember(long roomId, String rollNumber, String displayName) {
        BY_ROOM.put(roomId, new Identity(rollNumber, displayName));
    }

    /** @return what this app joined the room as, or null if unknown. */
    public static Identity recall(long roomId) {
        return BY_ROOM.get(roomId);
    }
}
