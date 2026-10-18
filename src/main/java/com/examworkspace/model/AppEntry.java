package com.examworkspace.model;

/**
 * An application that can be launched on this PC.
 *
 * @param id           database id, or 0 when the app is not stored in a room
 * @param name         display name
 * @param launchTarget a Windows AppUserModelID (for {@link TargetType#APP_ID}) or an absolute path to an executable
 * @param targetType   how {@code launchTarget} should be interpreted
 * @param processName  the foreground .exe filename seen by the student monitor (e.g. "calculator.exe").
 *                     For EXECUTABLE entries this is derived automatically from the launchTarget path.
 *                     For APP_ID entries it is resolved via PowerShell when the app is added.
 *                     May be {@code null} for legacy rows that pre-date this field.
 */
public record AppEntry(long id, String name, String launchTarget, TargetType targetType, String processName) {

    /**
     * Keeps every pre-existing 4-argument call site compiling unchanged;
     * the process name is simply unknown (null) for those entries.
     */
    public AppEntry(long id, String name, String launchTarget, TargetType targetType) {
        this(id, name, launchTarget, targetType, null);
    }

    public enum TargetType {
        APP_ID,
        EXECUTABLE
    }

    /** Convenience constructor for entries that don't yet have a resolved process name. */
    public static AppEntry unsaved(String name, String launchTarget, TargetType targetType) {
        return new AppEntry(0, name, launchTarget, targetType, null);
    }

    /** Convenience constructor with a known process name (used when saving APP_ID apps). */
    public static AppEntry unsaved(String name, String launchTarget, TargetType targetType, String processName) {
        return new AppEntry(0, name, launchTarget, targetType, processName);
    }

    /**
     * Returns the best-effort .exe filename that the student monitor is expected to report
     * when this app is in the foreground.
     * <ul>
     *   <li>For EXECUTABLE entries: the filename part of {@code launchTarget}.</li>
     *   <li>For APP_ID entries: {@code processName} if known, otherwise {@code null}.</li>
     * </ul>
     */
    public String resolvedProcessName() {
        if (targetType == TargetType.EXECUTABLE && launchTarget != null) {
            int sep = Math.max(launchTarget.lastIndexOf('\\'), launchTarget.lastIndexOf('/'));
            return launchTarget.substring(sep + 1).toLowerCase(java.util.Locale.ROOT);
        }
        return processName != null ? processName.toLowerCase(java.util.Locale.ROOT) : null;
    }
}
