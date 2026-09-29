package com.examworkspace.model;

import java.time.Instant;

public enum RoomStatus {
    UPCOMING("Upcoming"),
    LIVE("Live"),
    ENDED("Ended");

    private final String label;

    RoomStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static RoomStatus at(Instant start, Instant end, Instant now) {
        if (now.isBefore(start)) {
            return UPCOMING;
        }
        if (!now.isBefore(end)) {
            return ENDED;
        }
        return LIVE;
    }
}
