package com.examworkspace.ui;

import com.examworkspace.model.Room;
import com.examworkspace.submission.SubmissionService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Student-side exam answer panel: shows the Answer.docx status and provides
 * [Open Answer File] and [Submit Answer] buttons.
 * <p>
 * Uses a fixed file path — no FileChooser is ever shown to the student.
 */
public class SubmissionPanel extends VBox {

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Room room;
    private final String displayName;
    private final String rollNumber;
    private final Path answerFile;

    private final Label fileStatusLabel = new Label("Checking Answer.docx…");
    private final Label submissionStatusLabel = new Label("");
    private final Button openBtn = new Button("📄  Open Answer File");
    private final Button submitBtn = new Button("📤  Submit Answer");

    private ScheduledExecutorService refresher;

    public SubmissionPanel(Room room, String displayName, String rollNumber) {
        this.room = room;
        this.displayName = displayName;
        this.rollNumber = rollNumber;
        this.answerFile = SubmissionService.getAnswerFilePath(room.roomCode(), displayName);

        buildUi();
        startFileStatusRefresh();
    }

    private void buildUi() {
        setSpacing(10);
        setPadding(new Insets(14, 16, 14, 16));
        setStyle("-fx-background-color: rgba(30, 58, 138, 0.18); " +
                "-fx-border-color: rgba(96, 165, 250, 0.3); " +
                "-fx-border-radius: 10; -fx-background-radius: 10;");

        // ── Heading ──────────────────────────────────────────────────────────
        Label heading = new Label("📋  Exam Answer Submission");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #93c5fd;");

        Label info = new Label("Your answer file is saved at a fixed location.\n"
                + "Open it with the button below, write your answer, save it, then submit.");
        info.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11.5px;");
        info.setWrapText(true);

        // ── File path chip ────────────────────────────────────────────────────
        Label pathLabel = new Label("📁  " + answerFile.toString());
        pathLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 10.5px; -fx-font-style: italic;");
        pathLabel.setWrapText(true);

        // ── File status ───────────────────────────────────────────────────────
        fileStatusLabel.setStyle("-fx-text-fill: #cbd5e1; -fx-font-size: 12px;");

        // ── Action buttons ────────────────────────────────────────────────────
        openBtn.getStyleClass().addAll("primary", "sm");
        openBtn.setStyle("-fx-min-width: 170px;");
        openBtn.setOnAction(e -> openAnswerFile());

        submitBtn.getStyleClass().addAll("sm");
        submitBtn.setStyle("-fx-min-width: 170px; -fx-background-color: rgba(16,185,129,0.25); " +
                "-fx-border-color: rgba(16,185,129,0.5); -fx-text-fill: #34d399; " +
                "-fx-border-radius: 6; -fx-background-radius: 6;");
        submitBtn.setOnAction(e -> submitAnswer());

        HBox btnRow = new HBox(12, openBtn, submitBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        // ── Submission status ─────────────────────────────────────────────────
        submissionStatusLabel.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #4ade80;");
        submissionStatusLabel.setWrapText(true);

        // ── Notice about word processor allowlist ─────────────────────────────
        Label notice = new Label("ℹ️  Note: Ensure your teacher has allowed a word processor "
                + "(e.g. Microsoft Word, LibreOffice) for this room, or opening the answer file will trigger a violation.");
        notice.setWrapText(true);
        notice.setStyle("-fx-text-fill: #fbbf24; -fx-font-size: 11px; "
                + "-fx-background-color: rgba(234,179,8,0.08); -fx-padding: 6 10; "
                + "-fx-background-radius: 6; -fx-border-color: rgba(234,179,8,0.25); -fx-border-radius: 6;");

        getChildren().setAll(heading, info, pathLabel, fileStatusLabel, btnRow, submissionStatusLabel, notice);
    }

    // ── File status refresh ───────────────────────────────────────────────────

    private void startFileStatusRefresh() {
        refresher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "submission-file-watcher");
            t.setDaemon(true);
            return t;
        });
        refresher.scheduleWithFixedDelay(this::refreshFileStatus, 0, 5, TimeUnit.SECONDS);
    }

    public void stopRefresh() {
        if (refresher != null) {
            refresher.shutdownNow();
            refresher = null;
        }
    }

    private void refreshFileStatus() {
        try {
            if (!Files.exists(answerFile)) {
                Platform.runLater(() -> {
                    fileStatusLabel.setText("⚠️  Answer.docx not found at the workspace folder.");
                    fileStatusLabel.setStyle("-fx-text-fill: #f87171; -fx-font-size: 12px;");
                });
                return;
            }
            long size = Files.size(answerFile);
            long modified = Files.getLastModifiedTime(answerFile).toMillis();
            String modTime = FILE_TIME.format(Instant.ofEpochMilli(modified));
            String statusText = "✅  Answer.docx  —  " + SubmissionService.formatSize(size)
                    + "  —  Last saved: " + modTime;
            Platform.runLater(() -> {
                fileStatusLabel.setText(statusText);
                fileStatusLabel.setStyle("-fx-text-fill: #86efac; -fx-font-size: 12px;");
            });
        } catch (IOException ex) {
            Platform.runLater(() -> {
                fileStatusLabel.setText("⚠️  Could not read file status.");
                fileStatusLabel.setStyle("-fx-text-fill: #f87171; -fx-font-size: 12px;");
            });
        }
    }

    // ── Open answer file ──────────────────────────────────────────────────────

    private void openAnswerFile() {
        openBtn.setDisable(true);
        Thread worker = new Thread(() -> {
            try {
                // Ensure file exists (in case it was deleted)
                SubmissionService.ensureExamFolder(room.roomCode(), displayName);

                File file = answerFile.toFile();
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(file);
                } else {
                    // Fallback: start the file with explorer on Windows
                    new ProcessBuilder("cmd", "/c", "start", "", file.getAbsolutePath()).start();
                }
                Platform.runLater(() -> Ui.toast(this, "Answer.docx opened — write your answer, save it, then click Submit.", true));
            } catch (IOException ex) {
                Platform.runLater(() -> Ui.toast(this, "Could not open Answer.docx: " + ex.getMessage(), false));
            } finally {
                Platform.runLater(() -> openBtn.setDisable(false));
            }
        }, "open-answer-file");
        worker.setDaemon(true);
        worker.start();
    }

    // ── Submit answer ─────────────────────────────────────────────────────────

    private void submitAnswer() {
        submitBtn.setDisable(true);
        submissionStatusLabel.setText("Submitting…");
        submissionStatusLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11.5px;");

        Thread worker = new Thread(() -> {
            try {
                SubmissionService.submitAnswer(room.id(), displayName, rollNumber, answerFile);
                long now = System.currentTimeMillis();
                Platform.runLater(() -> {
                    submissionStatusLabel.setText("✅  Submitted successfully at "
                            + FILE_TIME.format(Instant.ofEpochMilli(now))
                            + "  (you may re-submit to overwrite)");
                    submissionStatusLabel.setStyle("-fx-text-fill: #4ade80; -fx-font-size: 11.5px;");
                    Ui.toast(this, "Answer submitted! The teacher can now see your file.", true);
                });
            } catch (IOException | IllegalArgumentException ex) {
                Platform.runLater(() -> {
                    submissionStatusLabel.setText("❌  " + ex.getMessage());
                    submissionStatusLabel.setStyle("-fx-text-fill: #f87171; -fx-font-size: 11.5px;");
                    Ui.toast(this, "Submission failed: " + ex.getMessage(), false);
                });
            } catch (SQLException ex) {
                Platform.runLater(() -> {
                    submissionStatusLabel.setText("❌  Database error — check your connection and try again.");
                    submissionStatusLabel.setStyle("-fx-text-fill: #f87171; -fx-font-size: 11.5px;");
                    Ui.toast(this, "Submission failed: DB error — " + ex.getMessage(), false);
                });
            } finally {
                Platform.runLater(() -> submitBtn.setDisable(false));
            }
        }, "submit-answer");
        worker.setDaemon(true);
        worker.start();
    }
}
