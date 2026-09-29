package com.examworkspace.model;

import java.time.Instant;

public record Room(
        long id,
        long teacherId,
        String name,
        String roomCode,
        String roomPassword,
        Instant startsAt,
        Instant endsAt,
        int appCount) {

    public RoomStatus statusAt(Instant now) {
        return RoomStatus.at(startsAt, endsAt, now);
    }
}
