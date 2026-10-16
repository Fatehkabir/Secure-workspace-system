package com.examworkspace.model;

import java.time.Instant;

public record RoomMember(
        long id,
        long roomId,
        Long studentId,
        String rollNumber,
        String displayName,
        Instant joinedAt) {

    public boolean isGuest() {
        return studentId == null;
    }
}
