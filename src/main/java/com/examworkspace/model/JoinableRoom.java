package com.examworkspace.model;

import java.time.Instant;

/** Public room summary for the student browse list — never includes the room password. */
public record JoinableRoom(
        long id,
        String name,
        String roomCode,
        Instant startsAt,
        Instant endsAt,
        int appCount) {

    public RoomStatus statusAt(Instant now) {
        return RoomStatus.at(startsAt, endsAt, now);
    }
}
