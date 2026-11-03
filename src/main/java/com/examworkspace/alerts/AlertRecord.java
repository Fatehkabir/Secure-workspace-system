package com.examworkspace.alerts;

import java.time.Instant;

/**
 * One "student left the allowed app" event received from a student client.
 *
 * @param screenshotBase64 PNG bytes of the student's screen at the moment of
 *                         the violation, base64-encoded; null if none was sent.
 */
public record AlertRecord(
        String studentId,
        String detectedApp,
        String executablePath,
        boolean allowed,
        Instant receivedAt,
        String screenshotBase64) {
}
