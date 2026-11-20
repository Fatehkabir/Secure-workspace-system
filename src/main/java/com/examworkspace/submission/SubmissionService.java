package com.examworkspace.submission;

import com.examworkspace.db.Database;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Manages the exam answer file workspace and submission lifecycle.
 * <p>
 * The fixed folder for each student is:
 * {@code ~/ExamWorkspace/room-{roomCode}/student-{safeDisplayName}/work/Answer.docx}
 * <p>
 * Submissions are stored entirely in the shared PostgreSQL/H2 database (no HTTP server).
 */
public final class SubmissionService {

    /** Maximum allowed file size for submission (50 MB). */
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private SubmissionService() {}

    // ── Folder / file helpers ────────────────────────────────────────────────

    /**
     * Returns the fixed answer file path for a student in a room.
     * Does NOT create any directories.
     */
    public static Path getAnswerFilePath(String roomCode, String displayName) {
        return Path.of(
                System.getProperty("user.home"),
                "ExamWorkspace",
                "room-" + sanitize(roomCode),
                "student-" + sanitize(displayName),
                "work",
                "Answer.docx");
    }

    /**
     * Creates the workspace folder and a blank Answer.docx if they don't already exist.
     */
    public static void ensureExamFolder(String roomCode, String displayName) throws IOException {
        Path file = getAnswerFilePath(roomCode, displayName);
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            byte[] blank = createMinimalDocx();
            Files.write(file, blank);
            System.out.println("[SubmissionService] Created blank Answer.docx at: " + file);
        }
    }

    // ── Submission DB operations ─────────────────────────────────────────────

    /**
     * Reads Answer.docx from disk and stores it in the exam_submissions table.
     * If a submission already exists for this student+room it is overwritten
     * and the resubmitted_count is incremented.
     *
     * @throws IOException  if the file cannot be read
     * @throws IllegalArgumentException if the file is too large or has a wrong type
     * @throws SQLException if the DB write fails
     */
    public static void submitAnswer(long roomId, String studentName, String rollNumber, Path filePath)
            throws IOException, SQLException {
        if (!Files.exists(filePath)) {
            throw new IOException("Answer file not found: " + filePath);
        }
        long size = Files.size(filePath);
        if (size > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File too large (" + (size / (1024 * 1024)) + " MB). Maximum is 50 MB.");
        }
        String lower = filePath.getFileName().toString().toLowerCase();
        if (!lower.endsWith(".docx") && !lower.endsWith(".pdf") && !lower.endsWith(".doc")) {
            throw new IllegalArgumentException("Only .docx, .doc, or .pdf files are accepted.");
        }
        byte[] bytes = Files.readAllBytes(filePath);
        String fileName = filePath.getFileName().toString();
        long now = Instant.now().toEpochMilli();

        try (Connection conn = Database.connect()) {
            // Check for existing submission
            String checkSql = "SELECT id, resubmitted_count FROM exam_submissions " +
                    "WHERE room_id = ? AND student_name = ?";
            try (PreparedStatement check = conn.prepareStatement(checkSql)) {
                check.setLong(1, roomId);
                check.setString(2, studentName);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        long existingId = rs.getLong("id");
                        int resubCount = rs.getInt("resubmitted_count") + 1;
                        // Update existing
                        String updateSql = "UPDATE exam_submissions " +
                                "SET file_name = ?, file_bytes = ?, file_size = ?, submitted_at = ?, " +
                                "    resubmitted_count = ?, roll_number = ? " +
                                "WHERE id = ?";
                        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                            ps.setString(1, fileName);
                            ps.setBytes(2, bytes);
                            ps.setLong(3, size);
                            ps.setLong(4, now);
                            ps.setInt(5, resubCount);
                            ps.setString(6, rollNumber);
                            ps.setLong(7, existingId);
                            ps.executeUpdate();
                        }
                        System.out.println("[SubmissionService] Resubmission #" + resubCount
                                + " stored for student: " + studentName);
                        return;
                    }
                }
            }
            // Insert new submission
            String insertSql = "INSERT INTO exam_submissions " +
                    "(room_id, student_name, roll_number, file_name, file_bytes, file_size, submitted_at, resubmitted_count) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 0)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setLong(1, roomId);
                ps.setString(2, studentName);
                ps.setString(3, rollNumber);
                ps.setString(4, fileName);
                ps.setBytes(5, bytes);
                ps.setLong(6, size);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
            System.out.println("[SubmissionService] New submission stored for student: " + studentName);
        }
    }

    /**
     * Returns all submissions for a room (for the teacher dashboard).
     */
    public static List<SubmissionRecord> listSubmissions(long roomId) throws SQLException {
        String sql = "SELECT id, student_name, roll_number, file_name, file_size, submitted_at, resubmitted_count " +
                "FROM exam_submissions WHERE room_id = ? ORDER BY submitted_at DESC";
        List<SubmissionRecord> result = new ArrayList<>();
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new SubmissionRecord(
                            rs.getLong("id"),
                            rs.getString("student_name"),
                            rs.getString("roll_number"),
                            rs.getString("file_name"),
                            rs.getLong("file_size"),
                            rs.getLong("submitted_at"),
                            rs.getInt("resubmitted_count")));
                }
            }
        }
        return result;
    }

    /**
     * Downloads the submission bytes for a specific submission ID.
     */
    public static byte[] downloadSubmission(long submissionId) throws SQLException {
        String sql = "SELECT file_bytes FROM exam_submissions WHERE id = ?";
        try (Connection conn = Database.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBytes("file_bytes");
                }
            }
        }
        throw new SQLException("Submission not found: id=" + submissionId);
    }

    // ── Minimal .docx creation ───────────────────────────────────────────────

    /**
     * Creates a minimal valid OOXML (.docx) byte array using only the standard library.
     * The document contains "Answer Sheet" as a placeholder paragraph.
     */
    public static byte[] createMinimalDocx() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            // [Content_Types].xml
            addZipEntry(zip, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n" +
                            "  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n" +
                            "  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n" +
                            "  <Override PartName=\"/word/document.xml\"\n" +
                            "    ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>\n" +
                            "</Types>");

            // _rels/.rels
            addZipEntry(zip, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                            "  <Relationship Id=\"rId1\"\n" +
                            "    Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\"\n" +
                            "    Target=\"word/document.xml\"/>\n" +
                            "</Relationships>");

            // word/_rels/document.xml.rels
            addZipEntry(zip, "word/_rels/document.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                            "</Relationships>");

            // word/document.xml
            addZipEntry(zip, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                            "<w:document xmlns:wpc=\"http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas\"\n" +
                            "            xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">\n" +
                            "  <w:body>\n" +
                            "    <w:p>\n" +
                            "      <w:pPr><w:jc w:val=\"center\"/></w:pPr>\n" +
                            "      <w:r><w:t>Answer Sheet</w:t></w:r>\n" +
                            "    </w:p>\n" +
                            "    <w:p><w:r><w:t></w:t></w:r></w:p>\n" +
                            "  </w:body>\n" +
                            "</w:document>");
        }
        return baos.toByteArray();
    }

    private static void addZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        zip.write(bytes, 0, bytes.length);
        zip.closeEntry();
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    /** Strips characters unsafe for file/folder names. */
    public static String sanitize(String input) {
        if (input == null) return "unknown";
        return input.replaceAll("[^a-zA-Z0-9_\\-]", "_").replaceAll("_+", "_");
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    public static String formatTime(long epochMillis) {
        return TIME_FMT.format(Instant.ofEpochMilli(epochMillis));
    }

    // ── Record ───────────────────────────────────────────────────────────────

    public record SubmissionRecord(
            long id,
            String studentName,
            String rollNumber,
            String fileName,
            long fileSize,
            long submittedAt,
            int resubmittedCount) {}
}
