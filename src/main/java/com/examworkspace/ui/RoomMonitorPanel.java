package com.examworkspace.ui;

import com.examworkspace.alerts.RoomLookup;
import com.examworkspace.browser.UrlAllowlistPolicy;
import com.examworkspace.model.Room;
import com.examworkspace.monitor.AllowedApp;
import com.examworkspace.monitor.LockStore;
import com.examworkspace.monitor.ViolationStore;
import com.examworkspace.room.RoomUrlPolicyService;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.awt.Toolkit;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The "Live Monitor" tab inside a room: displays realtime student activity,
 * active locks, violation history, and screenshots with quick-unlock capabilities.
 */
public class RoomMonitorPanel extends VBox {

    private static final long POLL_SECONDS = 2;
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter TIME_TODAY = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZONE);
    private static final DateTimeFormatter TIME_OTHER_DAY = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss").withZone(ZONE);

    private final Room room;
    private final ObservableList<AlertRow> allRows = FXCollections.observableArrayList();
    private final FilteredList<AlertRow> filteredRows = new FilteredList<>(allRows, r -> true);
    private final ObservableMap<String, LockStore.LockedStudent> activeLocks = FXCollections.observableHashMap();

    private final Label allowedLabel = new Label();
    private final Label bannerLabel = new Label("\u2705 No violations yet \u2014 waiting for students.");
    private final VBox bannerBox = new VBox(bannerLabel);

    private final Label statViolations = new Label("0");
    private final Label statLocked = new Label("0");
    private final Label statWebsites = new Label("None");

    private final TextField searchField = Ui.searchField("Filter violations by student or app/website...");

    private ScheduledExecutorService poller;
    private long lastSeenId = 0;
    private boolean historyLoaded = false;
    private Consumer<String> notifier = message -> { };

    private final ObservableList<String> lockedNames = FXCollections.observableArrayList();
    private final VBox lockedSection = new VBox(8);
    private final Label lockedHeading = new Label();

    public static class AlertRow {
        private final String studentLabel;
        private final String studentRawName;
        private final String app;
        private final String path;
        private final String time;
        private final String screenshotBase64;
        private Image image;
        private boolean imageLoaded;

        public AlertRow(ViolationStore.Violation violation) {
            this.studentLabel = violation.studentLabel();
            this.studentRawName = violation.studentName();
            this.app = violation.detectedApp();
            this.path = violation.executablePath() == null ? "" : violation.executablePath();
            this.time = formatTime(violation.occurredAt());
            this.screenshotBase64 = violation.screenshotBase64();
        }

        public String getStudent() { return studentLabel; }
        public String getStudentRawName() { return studentRawName; }
        public String getApp() { return app; }
        public String getPath() { return path; }
        public String getTime() { return time; }

        public synchronized Image getImage() {
            if (!imageLoaded) {
                imageLoaded = true;
                if (screenshotBase64 != null && !screenshotBase64.isBlank()) {
                    try {
                        image = new Image(new ByteArrayInputStream(Base64.getDecoder().decode(screenshotBase64)));
                    } catch (Exception ignored) {
                        image = null;
                    }
                }
            }
            return image;
        }
    }

    public RoomMonitorPanel(Room room) {
        this.room = room;
        getStyleClass().add("room-monitor-panel");
        setSpacing(14);
        setPadding(new Insets(4, 0, 0, 0));

        // Top Status Cards Bar
        HBox statsBar = buildStatsBar();

        Label liveLabel = new Label("Live Monitoring \u2014 student foreground activity and browser URLs are checked in realtime. "
                + "If an unapproved app or unallowed website is opened, the student's screen is captured and locked immediately. "
                + "You can unlock any student with one click below or give them their 6-character unlock code.");
        liveLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 12.5px;");
        liveLabel.setWrapText(true);

        allowedLabel.setWrapText(true);
        allowedLabel.setStyle("-fx-text-fill: #cbd5e1; -fx-font-size: 12px;");
        refreshAllowedApps();

        // Locked Students Alert Section
        lockedHeading.setStyle("-fx-font-weight: bold; -fx-font-size: 13.5px; -fx-text-fill: #fca5a5;");
        lockedSection.setPadding(new Insets(12, 16, 12, 16));
        lockedSection.setStyle("-fx-background-color: rgba(185, 28, 28, 0.2); -fx-border-color: rgba(239, 68, 68, 0.45); "
                + "-fx-border-radius: 10; -fx-background-radius: 10;");
        lockedSection.setVisible(false);
        lockedSection.setManaged(false);
        renderLocked(List.of());

        // Banner Box
        bannerLabel.setStyle("-fx-text-fill: #86efac; -fx-font-weight: bold; -fx-font-size: 13px;");
        bannerBox.setPadding(new Insets(12, 16, 12, 16));
        bannerBox.setStyle("-fx-background-color: rgba(22, 101, 52, 0.25); -fx-border-color: rgba(34, 197, 94, 0.35); "
                + "-fx-background-radius: 8; -fx-border-radius: 8;");

        // Filter / Search Bar
        searchField.textProperty().addListener((obs, old, text) -> applyFilter(text));
        HBox filterBar = new HBox(12, searchField);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        // Violations Table
        TableColumn<AlertRow, String> studentCol = col("Student", AlertRow::getStudent, 180, "cell-strong");
        TableColumn<AlertRow, String> appCol     = col("App / Website", AlertRow::getApp, 250, "cell-accent");
        appCol.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    setWrapText(true);
                    setStyle("-fx-text-fill: #f87171; -fx-font-weight: bold; -fx-font-size: 12.5px;");
                }
            }
        });
        TableColumn<AlertRow, String> pathCol    = col("Executable / Target",    AlertRow::getPath,    190, "cell-dim");
        TableColumn<AlertRow, String> timeCol    = col("Time",                   AlertRow::getTime,    100, "cell-dim");

        TableColumn<AlertRow, AlertRow> screenshotCol = new TableColumn<>("Screenshot");
        screenshotCol.setPrefWidth(95);
        screenshotCol.setSortable(false);
        screenshotCol.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        screenshotCol.setCellFactory(c -> new TableCell<>() {
            private final ImageView view = new ImageView();
            {
                view.setFitWidth(68);
                view.setFitHeight(42);
                view.setPreserveRatio(true);
                view.setStyle("-fx-cursor: hand; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 4, 0, 0, 1);");
                Tooltip.install(view, new Tooltip("Click to view full screenshot"));
            }
            @Override
            protected void updateItem(AlertRow row, boolean empty) {
                super.updateItem(row, empty);
                Image img = (empty || row == null) ? null : row.getImage();
                if (img == null) {
                    setGraphic(null);
                    setText(empty ? null : "\u2014");
                    setOnMouseClicked(null);
                } else {
                    setText(null);
                    view.setImage(img);
                    setGraphic(view);
                    setOnMouseClicked(e -> showFullScreenshot(row, img));
                }
            }
        });

        // Combined Action & Unlock Column
        // Bug 2 fix: Unlock button and lock code only appear on the MOST RECENT row for a
        // locked student (i.e. the first occurrence of that student name in filteredRows,
        // since new violations are prepended at index 0). All older rows for the same student
        // just show "✅ Unlocked / Active" regardless of lock state.
        TableColumn<AlertRow, AlertRow> actionCol = new TableColumn<>("Lock Status & Quick Action");
        actionCol.setPrefWidth(240);
        actionCol.setSortable(false);
        actionCol.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        actionCol.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(AlertRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }

                String rawName = row.getStudentRawName();
                LockStore.LockedStudent lock = activeLocks.get(rawName == null ? "" : rawName.toLowerCase(Locale.ROOT));

                // Only the FIRST occurrence of this student in the visible list shows the
                // lock widget. Every earlier row (higher index = older) shows the plain label.
                boolean isFirstOccurrence = true;
                int myIndex = getIndex();
                if (myIndex > 0 && getTableView() != null) {
                    java.util.List<AlertRow> items = getTableView().getItems();
                    for (int i = 0; i < myIndex && i < items.size(); i++) {
                        AlertRow earlier = items.get(i);
                        if (earlier != null && rawName != null
                                && rawName.equalsIgnoreCase(earlier.getStudentRawName())) {
                            isFirstOccurrence = false;
                            break;
                        }
                    }
                }

                if (lock != null && isFirstOccurrence) {
                    Label badge = new Label("\uD83D\uDD12 LOCKED");
                    badge.setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold; -fx-font-size: 11px;");

                    Label codeLbl = new Label("Code: " + lock.unlockCode());
                    codeLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #fde047; -fx-font-size: 11px; "
                            + "-fx-background-color: rgba(234, 179, 8, 0.25); -fx-padding: 3 7; -fx-background-radius: 4;");

                    Button unlockBtn = new Button("Unlock");
                    unlockBtn.getStyleClass().addAll("primary", "sm");
                    unlockBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 10;");
                    unlockBtn.setOnAction(e -> {
                        unlockBtn.setDisable(true);
                        unlockStudent(rawName);
                    });

                    HBox box = new HBox(8, badge, codeLbl, unlockBtn);
                    box.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(box);
                    setText(null);
                } else {
                    Label okLbl = new Label(lock != null ? "\uD83D\uDD12 LOCKED" : "\u2705 Unlocked / Active");
                    okLbl.setStyle(lock != null
                            ? "-fx-text-fill: #ef4444; -fx-font-weight: bold; -fx-font-size: 11px;"
                            : "-fx-text-fill: #4ade80; -fx-font-weight: bold; -fx-font-size: 11px;");
                    setGraphic(okLbl);
                    setText(null);
                }
            }
        });

        TableView<AlertRow> table = new TableView<>(filteredRows);
        table.getColumns().setAll(studentCol, appCol, screenshotCol, actionCol, pathCol, timeCol);
        table.setPlaceholder(new Label("No alerts yet \u2014 waiting for a student to switch to an unapproved app."));
        table.getStyleClass().add("violation-table");
        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(AlertRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setStyle("-fx-background-color: transparent;");
                } else if (isSelected()) {
                    setStyle("-fx-background-color: rgba(59, 108, 255, 0.25); -fx-border-color: rgba(59, 108, 255, 0.45); -fx-border-width: 0 0 1 0;");
                } else {
                    setStyle("-fx-background-color: rgba(255, 255, 255, 0.03); -fx-border-color: rgba(255, 255, 255, 0.05); -fx-border-width: 0 0 1 0;");
                }
            }
        });
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().setAll(statsBar, liveLabel, allowedLabel, lockedSection, bannerBox, filterBar, table);
    }

    private HBox buildStatsBar() {
        Label roomCodeChip = new Label("Room: " + room.roomCode());
        roomCodeChip.setStyle("-fx-background-color: rgba(59, 108, 255, 0.2); -fx-text-fill: #93c5fd; "
                + "-fx-font-weight: bold; -fx-padding: 4 12; -fx-background-radius: 12; -fx-font-size: 12px;");

        Label liveDot = new Label("\u25CF LIVE MONITORING");
        liveDot.setStyle("-fx-text-fill: #4ade80; -fx-font-weight: bold; -fx-font-size: 12px;");

        HBox cardViolations = buildMetricCard("Violations", statViolations, "#f87171");
        HBox cardLocked = buildMetricCard("Locked Students", statLocked, "#fbbf24");
        HBox cardWeb = buildMetricCard("Websites Policy", statWebsites, "#60a5fa");

        HBox bar = new HBox(12, roomCodeChip, liveDot, Ui.spacer(), cardViolations, cardLocked, cardWeb);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(2, 0, 4, 0));
        return bar;
    }

    private HBox buildMetricCard(String title, Label valLabel, String accentColor) {
        valLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: " + accentColor + ";");
        Label tLabel = new Label(title);
        tLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px;");
        VBox v = new VBox(1, valLabel, tLabel);
        HBox box = new HBox(v);
        box.setPadding(new Insets(4, 12, 4, 12));
        box.setStyle("-fx-background-color: rgba(255, 255, 255, 0.04); -fx-background-radius: 8; "
                + "-fx-border-color: rgba(255, 255, 255, 0.08); -fx-border-radius: 8;");
        return box;
    }

    private void applyFilter(String text) {
        String query = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        filteredRows.setPredicate(row -> {
            if (query.isEmpty()) return true;
            return (row.getStudent() != null && row.getStudent().toLowerCase(Locale.ROOT).contains(query))
                    || (row.getApp() != null && row.getApp().toLowerCase(Locale.ROOT).contains(query))
                    || (row.getPath() != null && row.getPath().toLowerCase(Locale.ROOT).contains(query));
        });
    }

    public void setNotifier(Consumer<String> notifier) {
        this.notifier = notifier == null ? message -> { } : notifier;
    }

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
        poller.scheduleWithFixedDelay(this::pollLocked, 0, POLL_SECONDS, TimeUnit.SECONDS);
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

            List<AlertRow> freshRows = new ArrayList<>(fresh.size());
            for (ViolationStore.Violation v : fresh) {
                AlertRow row = new AlertRow(v);
                row.getImage();
                freshRows.add(row);
            }

            Platform.runLater(() -> {
                if (first) {
                    List<AlertRow> history = new ArrayList<>();
                    for (AlertRow r : freshRows) {
                        history.add(0, r);
                    }
                    allRows.setAll(history);
                    statViolations.setText(String.valueOf(allRows.size()));
                    bannerLabel.setText("\u26A0 " + fresh.size() + (fresh.size() == 1 ? " violation" : " violations")
                            + " recorded in this room \u2014 newest first.");
                    bannerBox.setStyle("-fx-background-color: rgba(185, 28, 28, 0.25); -fx-border-color: rgba(239, 68, 68, 0.4); "
                            + "-fx-border-radius: 8; -fx-background-radius: 8;");
                    bannerLabel.setStyle("-fx-text-fill: #fca5a5; -fx-font-weight: bold;");
                } else {
                    for (int i = 0; i < freshRows.size(); i++) {
                        allRows.add(0, freshRows.get(i));
                        flashAlarm(fresh.get(i));
                    }
                    statViolations.setText(String.valueOf(allRows.size()));
                }
            });
        } catch (Exception ex) {
            System.err.println("[RoomMonitorPanel] Could not read flags: " + ex.getMessage());
        }
    }

    private void pollLocked() {
        try {
            List<LockStore.LockedStudent> locked = LockStore.list(room.id());
            Platform.runLater(() -> {
                statLocked.setText(String.valueOf(locked.size()));
                Map<String, LockStore.LockedStudent> map = new HashMap<>();
                for (LockStore.LockedStudent s : locked) {
                    map.put(s.studentName().toLowerCase(Locale.ROOT), s);
                }
                activeLocks.clear();
                activeLocks.putAll(map);

                List<String> names = locked.stream().map(LockStore.LockedStudent::studentName).toList();
                if (!names.equals(lockedNames)) {
                    lockedNames.setAll(names);
                    renderLocked(locked);
                }
            });
        } catch (Exception ex) {
            System.err.println("[RoomMonitorPanel] Could not read locked students: " + ex.getMessage());
        }
    }

    private void renderLocked(List<LockStore.LockedStudent> locked) {
        lockedSection.setVisible(!locked.isEmpty());
        lockedSection.setManaged(!locked.isEmpty());
        if (locked.isEmpty()) {
            lockedSection.getChildren().clear();
            return;
        }
        lockedHeading.setText("\uD83D\uDD12 " + locked.size()
                + (locked.size() == 1 ? " student is currently locked" : " students are currently locked")
                + " \u2014 click Unlock or provide their code:");

        VBox rowsBox = new VBox(8);
        for (LockStore.LockedStudent s : locked) {
            Label name = new Label(s.studentName());
            name.setStyle("-fx-font-weight: bold; -fx-text-fill: #ffffff; -fx-font-size: 13.5px;");

            String reasonText = s.reason() == null || s.reason().isBlank() ? "an unapproved app" : s.reason();
            Label detail = new Label("opened " + reasonText + "  \u00b7  " + formatTime(s.lockedAt()));
            detail.setStyle("-fx-text-fill: #fca5a5; -fx-font-size: 12px;");
            VBox text = new VBox(2, name, detail);

            Label codeBadge = new Label("Unlock Code: " + s.unlockCode());
            codeBadge.setStyle("-fx-font-weight: bold; -fx-text-fill: #fde047; -fx-font-size: 12.5px; "
                    + "-fx-background-color: rgba(234, 179, 8, 0.25); -fx-padding: 4 8; -fx-background-radius: 6;");

            Button unlock = new Button("Unlock Student");
            unlock.getStyleClass().add("primary");
            unlock.setOnAction(e -> {
                unlock.setDisable(true);
                unlockStudent(s.studentName());
            });

            HBox row = new HBox(12, text, Ui.spacer(), codeBadge, unlock);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 10, 6, 10));
            row.setStyle("-fx-background-color: rgba(255, 255, 255, 0.05); -fx-background-radius: 6;");
            rowsBox.getChildren().add(row);
        }
        lockedSection.getChildren().setAll(lockedHeading, rowsBox);
    }

    private void unlockStudent(String studentName) {
        Thread worker = new Thread(() -> {
            try {
                LockStore.unlock(room.id(), studentName);
            } catch (Exception ex) {
                System.err.println("[RoomMonitorPanel] Could not unlock " + studentName + ": " + ex.getMessage());
            }
            pollLocked();
        }, "unlock-student");
        worker.setDaemon(true);
        worker.start();
    }

    public void refreshAllowedApps() {
        try {
            List<AllowedApp> fresh = RoomLookup.loadAllowedApps(room.id());
            String text = "Allowed apps: " + (fresh.isEmpty()
                    ? "(none yet \u2014 add apps in the Apps tab; the workspace app itself is always allowed)"
                    : String.join(", ", fresh.stream().map(AllowedApp::describe).toList())
                    + "  \u00b7  the workspace app itself is always allowed");

            UrlAllowlistPolicy policy = RoomUrlPolicyService.loadPolicy(room.id());
            String webSummary;
            if (policy != null && policy.enabled()) {
                webSummary = "Active (" + policy.rules().size() + " sites)";
            } else {
                webSummary = "Open (No restrictions)";
            }

            Platform.runLater(() -> {
                allowedLabel.setText(text);
                statWebsites.setText(webSummary);
            });
        } catch (Exception ignored) {
        }
    }

    private void flashAlarm(ViolationStore.Violation v) {
        String pathInfo = v.executablePath() != null && !v.executablePath().isBlank()
                ? " (" + v.executablePath() + ")" : "";
        bannerLabel.setText("\uD83D\uDEA8 VIOLATION: " + v.studentLabel() + " opened " + v.detectedApp() + pathInfo);
        bannerBox.setStyle("-fx-background-color: rgba(220, 38, 38, 0.35); -fx-border-color: #ef4444; -fx-border-radius: 8; -fx-background-radius: 8;");
        bannerLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold;");
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (Exception ignored) {
        }
        notifier.accept(v.studentLabel() + " opened " + v.detectedApp());
    }

    private void showFullScreenshot(AlertRow row, Image image) {
        Stage popup = new Stage();
        popup.initModality(Modality.NONE);
        popup.setTitle("Evidence Capture \u2014 " + row.getStudent() + " \u00b7 " + row.getApp() + " \u00b7 " + row.getTime());

        ImageView full = new ImageView(image);
        full.setPreserveRatio(true);
        full.setFitWidth(Math.min(1100, Math.max(700, image.getWidth())));

        Label title = new Label("Student: " + row.getStudent() + "  \u00b7  Detected: " + row.getApp() + "  \u00b7  " + row.getTime());
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;");

        Button closeBtn = new Button("Close");
        closeBtn.getStyleClass().add("primary");
        closeBtn.setOnAction(e -> popup.close());

        HBox top = new HBox(12, title, Ui.spacer(), closeBtn);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(10, 16, 10, 16));
        top.setStyle("-fx-background-color: #1e293b;");

        VBox root = new VBox(8, top, new StackPane(full));
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #0b0f19; -fx-padding: 0 0 16 0;");

        popup.setScene(new javafx.scene.Scene(root));
        popup.show();
    }

    private static String formatTime(Instant at) {
        boolean today = at.atZone(ZONE).toLocalDate().equals(LocalDate.now(ZONE));
        return (today ? TIME_TODAY : TIME_OTHER_DAY).format(at);
    }

    private static TableColumn<AlertRow, String> col(String title, Function<AlertRow, String> extractor,
                                                     double width, String styleClass) {
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
                    setStyle(switch (styleClass) {
                        case "cell-strong" -> "-fx-text-fill: #f8fafc; -fx-font-weight: bold;";
                        case "cell-dim" -> "-fx-text-fill: #94a3b8; -fx-font-size: 11.5px;";
                        default -> "-fx-text-fill: #e2e8f0;";
                    });
                }
            }
        });
        column.setPrefWidth(width);
        return column;
    }
}
