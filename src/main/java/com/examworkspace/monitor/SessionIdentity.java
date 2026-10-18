package com.examworkspace.monitor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
