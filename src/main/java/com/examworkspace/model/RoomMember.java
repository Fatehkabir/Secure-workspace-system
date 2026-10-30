package com.examworkspace.model;

import java.time.Instant;

public record RoomMember(
        long id,
        long roomId,
        Long studentId,
        String rollNumber,
        String displayName,
        Instant joinedAt,
        Instant leftAt) {

    public boolean isGuest() {
        return studentId == null;
    }

    /** True while the student is actively in the room (hasn't left yet). */
    public boolean isActive() {
        return leftAt == null;
    }
}
