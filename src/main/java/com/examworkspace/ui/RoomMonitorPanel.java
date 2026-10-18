package com.examworkspace.ui;

import com.examworkspace.alerts.RoomLookup;
import com.examworkspace.model.Room;
import com.examworkspace.monitor.AllowedApp;
import com.examworkspace.monitor.ViolationStore;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.awt.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;


public class RoomMonitorPanel extends VBox {

    private static final long POLL_SECONDS = 2;
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter TIME_TODAY = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZONE);
    private static final DateTimeFormatter TIME_OTHER_DAY = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss").withZone(ZONE);

    private final Room room;
    private final ObservableList<AlertRow> rows = FXCollections.observableArrayList();

    private final Label allowedLabel = new Label();
    private final Label bannerLabel = new Label("\u2705 No violations yet.");
    private final VBox bannerBox = new VBox(bannerLabel);

    private ScheduledExecutorService poller;
    private long lastSeenId = 0;
    private boolean historyLoaded = false;
    private Consumer<String> notifier = message -> { };

    public static class AlertRow {
        private final String student, app, path, time, status;

        public AlertRow(ViolationStore.Violation violation) {
            this.student = violation.studentLabel();
            this.app = violation.detectedApp();
            this.path = violation.executablePath() == null ? "" : violation.executablePath();
            this.time = formatTime(violation.occurredAt());
            this.status = "VIOLATION";
        }

        public String getStudent() { return student; }
        public String getApp() { return app; }
        public String getPath() { return path; }
        public String getTime() { return time; }
        public String getStatus() { return status; }
    }

    public RoomMonitorPanel(Room room) {
        this.room = room;
        getStyleClass().add("room-monitor-panel");
        setSpacing(14);
        setPadding(new Insets(4, 0, 0, 0));

        Label liveLabel = new Label("Live \u2014 students in this room are checked automatically while they have it open. "
                + "A flag appears here the moment someone stays on an app that isn't allowed.");
        liveLabel.getStyleClass().add("page-subtitle");
        liveLabel.setWrapText(true);

        allowedLabel.setWrapText(true);
        allowedLabel.getStyleClass().add("page-subtitle");
        refreshAllowedApps();

        bannerLabel.setStyle("-fx-text-fill: #1e7e34; -fx-font-weight: bold;");
        bannerBox.setPadding(new Insets(12));
        bannerBox.setStyle("-fx-background-color: #e6f4ea; -fx-background-radius: 6;");

        TableColumn<AlertRow, String> studentCol = col("Rule Breaker (Student)", AlertRow::getStudent, 190);
        TableColumn<AlertRow, String> appCol     = col("Executed App",           AlertRow::getApp,     170);
        TableColumn<AlertRow, String> pathCol    = col("Executable Path",        AlertRow::getPath,    280);
        TableColumn<AlertRow, String> timeCol    = col("Time",                   AlertRow::getTime,    110);
        TableColumn<AlertRow, String> statusCol  = col("Status",                 AlertRow::getStatus,  100);

        TableView<AlertRow> table = new TableView<>(rows);
        table.getColumns().setAll(studentCol, appCol, pathCol, timeCol, statusCol);
        table.setPlaceholder(new Label("No alerts yet \u2014 waiting for a student to switch to an unapproved app."));
        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(AlertRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setStyle("");
                } else if (isSelected()) {
                    setStyle("-fx-background-color: #fee2e2; -fx-border-color: #fca5a5; -fx-border-width: 0 0 1 0;");
                } else {
                    setStyle("-fx-background-color: #fdf2f2; -fx-border-color: #fecaca; -fx-border-width: 0 0 1 0;");
                }
            }
        });
        VBox.setVgrow(table, Priority.ALWAYS);

        HBox topRow = new HBox(12, new Label("Room: " + room.roomCode()), Ui.spacer());
        topRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().setAll(topRow, liveLabel, allowedLabel, bannerBox, table);
    }

    /** Extra notification shown whenever a NEW flag arrives (the room view uses it for a toast). */
    public void setNotifier(Consumer<String> notifier) {
        this.notifier = notifier == null ? message -> { } : notifier;
    }

    // ── polling ──────────────────────────────────────────────────────────────

    public synchronized void startPolling() {
        if (poller != null) {
            return;
        }
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "room-violation-poller");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::poll, 0, POLL_SECONDS, TimeUnit.SECONDS);
    }

    public synchronized void stopPolling() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
    }

    private void poll() {
        try {
            boolean first = !historyLoaded;
            List<ViolationStore.Violation> fresh = ViolationStore.listAfter(room.id(), lastSeenId);
            historyLoaded = true;
            if (fresh.isEmpty()) {
                return;
            }
            lastSeenId = fresh.get(fresh.size() - 1).id();

            Platform.runLater(() -> {
                if (first) {
                    // Flags recorded before the teacher opened the monitor: list them, no alarm.
                    List<AlertRow> history = new ArrayList<>();
                    for (ViolationStore.Violation v : fresh) {
                        history.add(0, new AlertRow(v));
                    }
                    rows.setAll(history);
                    bannerLabel.setText("\u26A0 " + fresh.size() + (fresh.size() == 1 ? " violation" : " violations")
                            + " recorded in this room \u2014 newest first.");
                    bannerBox.setStyle("-fx-background-color: #fdf2f2; -fx-border-color: #fecaca; "
                            + "-fx-border-radius: 6; -fx-background-radius: 6;");
                    bannerLabel.setStyle("-fx-text-fill: #a61b1b; -fx-font-weight: bold;");
                } else {
                    for (ViolationStore.Violation v : fresh) {
                        rows.add(0, new AlertRow(v));
                        flashAlarm(v);
                    }
                }
            });
        } catch (Exception ex) {
            System.err.println("[RoomMonitorPanel] Could not read flags: " + ex.getMessage());
        }
    }

    // ── allowed apps line ────────────────────────────────────────────────────

    public void refreshAllowedApps() {
        try {
            List<AllowedApp> fresh = RoomLookup.loadAllowedApps(room.id());
            String text = "Allowed apps: " + (fresh.isEmpty()
                    ? "(none yet \u2014 add apps in the Apps tab; the workspace app itself is always allowed)"
                    : String.join(", ", fresh.stream().map(AllowedApp::describe).toList())
                    + "  \u00b7  the workspace app itself is always allowed");
            Platform.runLater(() -> allowedLabel.setText(text));
        } catch (Exception ignored) {
            // Leave the previous text if the database is briefly busy.
        }
    }

    // ── alert presentation ───────────────────────────────────────────────────

    private void flashAlarm(ViolationStore.Violation v) {
        String pathInfo = v.executablePath() != null && !v.executablePath().isBlank()
                ? " (" + v.executablePath() + ")" : "";
        bannerLabel.setText("\uD83D\uDEA8 VIOLATION: " + v.studentLabel() + " opened " + v.detectedApp() + pathInfo);
        bannerBox.setStyle("-fx-background-color: #fdf2f2; -fx-border-color: #fecaca; -fx-border-radius: 6; -fx-background-radius: 6;");
        bannerLabel.setStyle("-fx-text-fill: #a61b1b; -fx-font-weight: bold;");
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (Exception ignored) {
            // No audio device -- not critical.
        }
        notifier.accept(v.studentLabel() + " opened " + v.detectedApp());
    }

    private static String formatTime(Instant at) {
        boolean today = at.atZone(ZONE).toLocalDate().equals(LocalDate.now(ZONE));
        return (today ? TIME_TODAY : TIME_OTHER_DAY).format(at);
    }

    private static TableColumn<AlertRow, String> col(String title, Function<AlertRow, String> extractor, double width) {
        TableColumn<AlertRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue() != null ? extractor.apply(cell.getValue()) : ""));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    setStyle("-fx-text-fill: #a61b1b; -fx-font-weight: bold;");
                }
            }
        });
        column.setPrefWidth(width);
        return column;
    }
}
