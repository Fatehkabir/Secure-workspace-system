package com.examworkspace.screenshare;

/**
 * Immutable snapshot of an active teacher screen-share session as stored in the database.
 *
 * @param hostAddress teacher's LAN IP address students should connect to
 * @param port        TCP port the {@link ScreenShareServer} is listening on
 */
public record ScreenShareSession(String hostAddress, int port) {

    /** Returns whether this session points to a usable address. */
    public boolean isValid() {
        return hostAddress != null && !hostAddress.isBlank() && port > 0 && port < 65536;
    }

    @Override
    public String toString() {
        return hostAddress + ":" + port;
    }
}
