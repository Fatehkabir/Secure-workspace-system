package com.examworkspace.model;

/**
 * An application that can be launched on this PC.
 *
 * @param id           database id, or 0 when the app is not stored in a room
 * @param name         display name
 * @param launchTarget a Windows AppUserModelID (for {@link TargetType#APP_ID}) or an absolute path to an executable
 * @param targetType   how {@code launchTarget} should be interpreted
 */
public record AppEntry(long id, String name, String launchTarget, TargetType targetType) {

    public enum TargetType {
        APP_ID,
        EXECUTABLE
    }

    public static AppEntry unsaved(String name, String launchTarget, TargetType targetType) {
        return new AppEntry(0, name, launchTarget, targetType);
    }
}
