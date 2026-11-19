package com.examworkspace.ui;

import com.examworkspace.ExamWorkspaceApp;
import com.examworkspace.apps.AppLauncher;
import com.examworkspace.browser.UrlAllowlistPolicy;
import com.examworkspace.db.Database;
import com.examworkspace.model.AppEntry;
import com.examworkspace.model.Room;
import com.examworkspace.model.Student;
import com.examworkspace.monitor.LockStore;
import com.examworkspace.monitor.StudentRoomMonitor;
import com.examworkspace.room.RoomUrlPolicyService;
import com.examworkspace.room.StudentRoomService;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class StudentRoomView extends StudentShell implements ExamWorkspaceApp.Leavable {

    private final StudentRoomService rooms;
    private final Room room;
    private final Student student;

    private final ObservableList<AppEntry> apps = FXCollections.observableArrayList();
    private final FilteredList<AppEntry> visibleApps = new FilteredList<>(apps, app -> true);
    private final ListView<AppEntry> appList = new ListView<>(visibleApps);

    private final StackPane mainContentStack = new StackPane();
    private final VBox appsArea = new VBox(12);
    private final VBox websiteArea = new VBox(14);
    private final VBox emptyAppsArea;

    private final Label subtitle = new Label();
    private final Label selectionLabel = new Label();
    private final TextField search = Ui.searchField("Search allowed apps");

    private final Button showAppsBtn = new Button("Allowed Applications");
    private final Button showWebsitesBtn = new Button("Allowed Websites & URLs");

    private UrlAllowlistPolicy currentUrlPolicy = UrlAllowlistPolicy.disabled();

    // Lock screen: shown the instant StudentRoomMonitor reports a lock
    private final StackPane lockOverlay = new StackPane();
    private final Label lockReasonLabel = new Label();
    private final TextField codeField = new TextField();
    private final Label codeError = new Label();
    private volatile boolean locked = false;
    private StudentRoomMonitor monitor;
    private Button leaveButton;
    private int wrongCodeAttempts = 0;
    private long codeLockedUntil = 0;

    public StudentRoomView(ExamWorkspaceApp app, Room room, Student student) {
        super(app, student, room.name());
        this.rooms = new StudentRoomService();
        this.room = room;
        this.student = student;

        Hyperlink crumb = new Hyperlink("Available rooms");
        crumb.setOnAction(e -> app.showStudentDashboard(student));
        Label crumbSeparator = new Label("/");
        Label crumbCurrent = new Label(room.name());
        crumbCurrent.getStyleClass().add("breadcrumb-current");
        HBox breadcrumb = new HBox(6, crumb, crumbSeparator, crumbCurrent);
        breadcrumb.setAlignment(Pos.CENTER_LEFT);
        breadcrumb.getStyleClass().add("breadcrumb");

        Label title = new Label(room.name());
        title.getStyleClass().add("page-title");
        subtitle.getStyleClass().add("page-subtitle");

        Label roomCodeChip = new Label("Room: " + room.roomCode());
        roomCodeChip.setStyle("-fx-background-color: rgba(59, 108, 255, 0.18); -fx-text-fill: #93c5fd; "
                + "-fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12; -fx-font-size: 11.5px;");

        search.textProperty().addListener((obs, old, text) -> applyFilter(text));

        leaveButton = new Button("Leave room");
        leaveButton.getStyleClass().add("quiet");
        leaveButton.setOnAction(e -> {
            leaveRoom();
            app.showStudentDashboard(student);
        });

        HBox titleBox = new HBox(10, title, roomCodeChip);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(12,
                new VBox(4, breadcrumb, titleBox, subtitle), Ui.spacer(), search, leaveButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("page-header");

        emptyAppsArea = Ui.emptyState(Icons.APPS, "No apps allowed yet",
                "Your teacher has not added any programs to this room. Check back once they finish setup.");

        // Segmented Tabs: [ Allowed Applications ] [ Allowed Websites & URLs ]
        showAppsBtn.getStyleClass().add("primary");
        showWebsitesBtn.getStyleClass().add("quiet");

        showAppsBtn.setOnAction(e -> {
            showAppsBtn.getStyleClass().removeAll("primary", "quiet");
            showAppsBtn.getStyleClass().add("primary");
            showWebsitesBtn.getStyleClass().removeAll("primary", "quiet");
            showWebsitesBtn.getStyleClass().add("quiet");
            mainContentStack.getChildren().setAll(apps.isEmpty() ? emptyAppsArea : appsArea);
            search.setVisible(!apps.isEmpty());
            search.setManaged(!apps.isEmpty());
        });

        showWebsitesBtn.setOnAction(e -> {
            showWebsitesBtn.getStyleClass().removeAll("primary", "quiet");
            showWebsitesBtn.getStyleClass().add("primary");
            showAppsBtn.getStyleClass().removeAll("primary", "quiet");
            showAppsBtn.getStyleClass().add("quiet");
            renderWebsiteSection();
            mainContentStack.getChildren().setAll(websiteArea);
            search.setVisible(false);
            search.setManaged(false);
        });

        HBox segmentBar = new HBox(8, showAppsBtn, showWebsitesBtn);
        segmentBar.setAlignment(Pos.CENTER_LEFT);
        segmentBar.setPadding(new Insets(2, 0, 8, 0));

        // Apps List setup
        appList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        appList.setCellFactory(view -> new AppRow());
        appList.getStyleClass().add("app-list");
        appList.setPlaceholder(new Label("No app in this room matches your search."));
        VBox.setVgrow(appList, Priority.ALWAYS);

        selectionLabel.getStyleClass().add("selection-count");
        Button launchSelected = new Button("Launch", Icons.of(Icons.PLAY, 15));
        launchSelected.getStyleClass().add("sm");
        launchSelected.setOnAction(e -> launch(List.copyOf(appList.getSelectionModel().getSelectedItems())));

        HBox selectionBar = new HBox(8, selectionLabel, Ui.spacer(), launchSelected);
        selectionBar.setAlignment(Pos.CENTER_LEFT);
        selectionBar.getStyleClass().add("selection-bar");
        selectionBar.visibleProperty().bind(Bindings.isNotEmpty(appList.getSelectionModel().getSelectedItems()));
        selectionBar.managedProperty().bind(selectionBar.visibleProperty());
        appList.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<AppEntry>) change -> {
                    int count = appList.getSelectionModel().getSelectedItems().size();
                    selectionLabel.setText(count == 1 ? "1 app selected" : count + " apps selected");
                });

        appsArea.getChildren().setAll(appList, selectionBar);

        mainContentStack.getChildren().setAll(appsArea);

        VBox fullContent = new VBox(8, segmentBar, mainContentStack);
        VBox.setVgrow(mainContentStack, Priority.ALWAYS);

        buildLockOverlay();
        checkInitialLock();

        StackPane roomStack = new StackPane(fullContent, lockOverlay);
        setPage(header, roomStack);
        refresh();

        // Foreground-app check: actively monitors foreground process & address bar
        this.monitor = new StudentRoomMonitor(
                room.id(),
                student == null || student.id() == 0 ? null : student.id(),
                studentDisplayName(),
                message -> Platform.runLater(() -> Ui.toast(this, message, false)),
                message -> Platform.runLater(() -> Ui.toast(this, message, false)),
                appName -> Platform.runLater(() -> showLockOverlay(appName)));

        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                monitor.start();
                startAppListRefresh();
                startLockPolling();
            } else {
                monitor.stop();
                stopAppListRefresh();
                stopLockPolling();
                leaveRoom();
            }
        });
    }

    private String studentDisplayName() {
        return student == null || student.fullName().isBlank() ? "Student" : student.fullName();
    }

    @Override
    public void leaveRoom() {
        rooms.leave(room.id());
    }

    private ScheduledExecutorService appListRefresher;

    private void startAppListRefresh() {
        if (appListRefresher != null) {
            return;
        }
        appListRefresher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "room-student-refresher");
            t.setDaemon(true);
            return t;
        });
        appListRefresher.scheduleWithFixedDelay(() -> {
            try {
                List<AppEntry> loaded = rooms.listApps(room);
                UrlAllowlistPolicy loadedPolicy = RoomUrlPolicyService.loadPolicy(room.id());
                Platform.runLater(() -> {
                    if (!loaded.equals(apps)) {
                        applyLoadedApps(loaded);
                    }
                    if (loadedPolicy != null && !loadedPolicy.equals(currentUrlPolicy)) {
                        currentUrlPolicy = loadedPolicy;
                        updateSubtitle();
                        if (mainContentStack.getChildren().contains(websiteArea)) {
                            renderWebsiteSection();
                        }
                    }
                });
            } catch (RuntimeException ignored) {
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    private void stopAppListRefresh() {
        if (appListRefresher != null) {
            appListRefresher.shutdownNow();
            appListRefresher = null;
        }
    }

    private void applyFilter(String text) {
        String query = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        visibleApps.setPredicate(entry -> query.isEmpty()
                || entry.name().toLowerCase(Locale.ROOT).contains(query)
                || entry.launchTarget().toLowerCase(Locale.ROOT).contains(query));
    }

    private void refresh() {
        try {
            applyLoadedApps(rooms.listApps(room));
            currentUrlPolicy = RoomUrlPolicyService.loadPolicy(room.id());
            updateSubtitle();
        } catch (RuntimeException ex) {
            System.err.println("[StudentRoomView] Failed to refresh: " + ex.getMessage());
            if (apps.isEmpty()) {
                mainContentStack.getChildren().setAll(emptyAppsArea);
            }
        }
    }

    private void applyLoadedApps(List<AppEntry> loaded) {
        apps.setAll(loaded);
        showAppsBtn.setText("Allowed Applications (" + loaded.size() + ")");
        updateSubtitle();
        if (!mainContentStack.getChildren().contains(websiteArea)) {
            boolean isEmpty = loaded.isEmpty();
            mainContentStack.getChildren().setAll(isEmpty ? emptyAppsArea : appsArea);
            search.setVisible(!isEmpty);
            search.setManaged(!isEmpty);
        }
    }

    private void updateSubtitle() {
        int appCount = apps.size();
        String appText = appCount == 1 ? "1 app allowed" : appCount + " apps allowed";

        String webText;
        if (currentUrlPolicy != null && currentUrlPolicy.enabled()) {
            int ruleCount = currentUrlPolicy.rules().size();
            webText = ruleCount == 1 ? "1 website allowed (Restrictions Active)"
                    : ruleCount + " websites allowed (Restrictions Active)";
            showWebsitesBtn.setText("Allowed Websites & URLs (" + ruleCount + ")");
        } else {
            webText = "Open Web Access (No site restrictions)";
            showWebsitesBtn.setText("Allowed Websites & URLs (Open)");
        }
        subtitle.setText(appText + "  \u00b7  " + webText);
    }

    // ── Websites & URLs Section ──────────────────────────────────────────────

    private void renderWebsiteSection() {
        websiteArea.getChildren().clear();

        if (currentUrlPolicy == null || !currentUrlPolicy.enabled()) {
            VBox openBox = new VBox(10);
            openBox.setPadding(new Insets(24));
            openBox.setStyle("-fx-background-color: rgba(30, 41, 69, 0.7); -fx-background-radius: 12; "
                    + "-fx-border-color: rgba(255, 255, 255, 0.1); -fx-border-radius: 12;");

            Label head = new Label("\uD83C\uDF10 General Web Browsing Allowed");
            head.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #93c5fd;");

            Label desc = new Label("The teacher has not placed URL restrictions on this room. "
                    + "If web browsers (Chrome, Edge, Firefox, etc.) are in your allowed apps list, you can freely browse the web.");
            desc.setWrapText(true);
            desc.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13px;");

            openBox.getChildren().setAll(head, desc);
            websiteArea.getChildren().add(openBox);
            return;
        }

        // Policy is active
        VBox banner = new VBox(6);
        banner.setPadding(new Insets(16, 20, 16, 20));
        banner.setStyle("-fx-background-color: rgba(21, 128, 61, 0.15); -fx-background-radius: 12; "
                + "-fx-border-color: rgba(34, 197, 94, 0.35); -fx-border-radius: 12;");

        Label badge = new Label("\uD83D\uDD12 WEBSITE ALLOWLIST ENFORCED");
        badge.setStyle("-fx-font-weight: bold; -fx-text-fill: #4ade80; -fx-font-size: 13px;");

        Label explanation = new Label("You are ONLY permitted to open the approved websites listed below in your browser. "
                + "Navigating to any other website is detected immediately and will lock your workspace.");
        explanation.setWrapText(true);
        explanation.setStyle("-fx-text-fill: #dcfce7; -fx-font-size: 12.5px;");

        banner.getChildren().setAll(badge, explanation);
        websiteArea.getChildren().add(banner);

        List<UrlAllowlistPolicy.Rule> rules = currentUrlPolicy.rules();
        if (rules.isEmpty()) {
            VBox noRules = new VBox(10);
            noRules.setPadding(new Insets(20));
            noRules.setStyle("-fx-background-color: rgba(180, 83, 9, 0.15); -fx-background-radius: 10; "
                    + "-fx-border-color: rgba(245, 158, 11, 0.3); -fx-border-radius: 10;");
            Label warnHead = new Label("\u26A0\uFE0F All external websites are currently blocked");
            warnHead.setStyle("-fx-font-weight: bold; -fx-text-fill: #fbbf24; -fx-font-size: 14px;");
            Label warnMsg = new Label("The website allowlist is active, but no websites have been added by your teacher yet. "
                    + "All web surfing will trigger a violation until the teacher adds permitted websites.");
            warnMsg.setWrapText(true);
            warnMsg.setStyle("-fx-text-fill: #fde68a; -fx-font-size: 12.5px;");
            noRules.getChildren().setAll(warnHead, warnMsg);
            websiteArea.getChildren().add(noRules);
            return;
        }

        Label listHeading = new Label("Approved Websites (" + rules.size() + ")");
        listHeading.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #e2e8f0;");
        websiteArea.getChildren().add(listHeading);

        VBox cards = new VBox(8);
        for (UrlAllowlistPolicy.Rule rule : rules) {
            cards.getChildren().add(buildWebsiteCard(rule));
        }

        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        websiteArea.getChildren().add(scroll);
    }

    private HBox buildWebsiteCard(UrlAllowlistPolicy.Rule rule) {
        String match = rule.match() == null ? "" : rule.match();
        String displayUrl = match;
        if (!displayUrl.startsWith("http://") && !displayUrl.startsWith("https://")) {
            displayUrl = "https://" + displayUrl;
        }

        Label icon = new Label("\uD83C\uDF10");
        icon.setStyle("-fx-font-size: 22px;");

        Label hostLabel = new Label(match);
        hostLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #f8fafc;");

        String typeDesc;
        String chipText;
        switch (rule.type()) {
            case HOST -> {
                typeDesc = "Permits " + match + " and all subdomains (including www." + match + ")";
                chipText = "Domain + Subdomains";
            }
            case PATH_PREFIX -> {
                typeDesc = "Permits access to this specific path prefix";
                chipText = "Specific Section";
            }
            case EXACT_URL -> {
                typeDesc = "Permits only this exact page";
                chipText = "Exact Page";
            }
            case ORIGIN -> {
                typeDesc = "Permits this exact origin scheme + host + port";
                chipText = "Origin";
            }
            default -> {
                typeDesc = "Approved website";
                chipText = "Allowed";
            }
        }

        Label descLabel = new Label(typeDesc);
        descLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11.5px;");

        VBox details = new VBox(2, hostLabel, descLabel);
        HBox.setHgrow(details, Priority.ALWAYS);

        Label chip = new Label(chipText);
        chip.setStyle("-fx-background-color: rgba(59, 108, 255, 0.2); -fx-text-fill: #bfdbfe; "
                + "-fx-padding: 3 8; -fx-background-radius: 8; -fx-font-size: 11px;");

        final String finalTarget = displayUrl;
        Button openBtn = new Button("Open", Icons.of(Icons.CHEVRON_RIGHT, 13));
        openBtn.getStyleClass().addAll("primary", "sm");
        openBtn.setOnAction(e -> openUrlInBrowser(finalTarget));

        Button copyBtn = new Button("Copy", Icons.of(Icons.FILE, 13));
        copyBtn.getStyleClass().add("sm");
        copyBtn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(finalTarget);
            Clipboard.getSystemClipboard().setContent(content);
            Ui.toast(this, "Copied URL to clipboard: " + finalTarget, true);
        });

        HBox card = new HBox(12, icon, details, chip, copyBtn, openBtn);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(12, 16, 12, 16));
        card.setStyle("-fx-background-color: rgba(255, 255, 255, 0.05); -fx-background-radius: 10; "
                + "-fx-border-color: rgba(255, 255, 255, 0.08); -fx-border-radius: 10;");
        return card;
    }

    private void openUrlInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                new ProcessBuilder("cmd", "/c", "start", "", url).start();
            }
            Ui.toast(this, "Opening " + url + " in your browser", true);
        } catch (Exception ex) {
            Ui.toast(this, "Could not open browser: " + ex.getMessage(), false);
        }
    }

    // ── Lock Screen Overlay ──────────────────────────────────────────────────

    private void buildLockOverlay() {
        Label icon = new Label("\uD83D\uDD12");
        icon.setStyle("-fx-font-size: 48px; -fx-text-fill: #ef4444;");

        Label heading = new Label("Workspace Locked");
        heading.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        lockReasonLabel.setWrapText(true);
        lockReasonLabel.setMaxWidth(440);
        lockReasonLabel.setStyle("-fx-text-fill: #b91c1c; -fx-font-weight: bold; -fx-font-size: 13.5px; "
                + "-fx-background-color: #fef2f2; -fx-padding: 8 12; -fx-background-radius: 6;");
        lockReasonLabel.setAlignment(Pos.CENTER);

        Label ssNotice = new Label("\uD83D\uDCF8 A screenshot of your screen was recorded and delivered to the teacher.");
        ssNotice.setWrapText(true);
        ssNotice.setMaxWidth(440);
        ssNotice.setAlignment(Pos.CENTER);
        ssNotice.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");

        Label waiting = new Label("Only your teacher can unlock this \u2014 from their Live Monitor dashboard, "
                + "by providing you the 6-character unlock code, or in person by entering the room password.");
        waiting.setWrapText(true);
        waiting.setMaxWidth(440);
        waiting.setAlignment(Pos.CENTER);
        waiting.setStyle("-fx-text-fill: #475569; -fx-font-size: 12.5px;");

        codeField.setPromptText("Unlock code / Room password");
        codeField.setMaxWidth(260);
        codeField.setAlignment(Pos.CENTER);
        codeField.setStyle("-fx-font-size: 14px; -fx-padding: 8 12;");

        codeError.setStyle("-fx-text-fill: #b91c1c; -fx-font-weight: bold; -fx-font-size: 12px;");
        codeError.setWrapText(true);
        codeError.setMaxWidth(380);
        codeError.setVisible(false);

        Button submit = new Button("Unlock Workspace");
        submit.getStyleClass().add("primary");
        submit.setStyle("-fx-font-size: 13.5px; -fx-padding: 8 20;");
        submit.setDefaultButton(true);
        submit.setOnAction(e -> submitUnlockCode());
        codeField.setOnAction(e -> submitUnlockCode());

        VBox box = new VBox(14, icon, heading, lockReasonLabel, ssNotice, waiting, codeField, submit, codeError);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(500);
        box.setPadding(new Insets(32));
        box.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 16; "
                + "-fx-border-color: #dc2626; -fx-border-width: 2.5; -fx-border-radius: 16; "
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 24, 0, 0, 8);");

        lockOverlay.getChildren().setAll(box);
        lockOverlay.setAlignment(Pos.CENTER);
        lockOverlay.setStyle("-fx-background-color: rgba(10, 16, 32, 0.94);");
        lockOverlay.setFocusTraversable(true);
        lockOverlay.setOnMouseClicked(javafx.event.Event::consume);
        lockOverlay.setVisible(false);
        lockOverlay.setManaged(false);
    }

    private void checkInitialLock() {
        try {
            java.util.Optional<LockStore.LockedStudent> lock = LockStore.findLock(room.id(), studentDisplayName());
            if (lock.isPresent() || LockStore.hasLocalLock(room.id(), studentDisplayName())) {
                String reason = (lock.isPresent() && lock.get().reason() != null && !lock.get().reason().isBlank())
                        ? lock.get().reason()
                        : "an unapproved application or website";
                showLockOverlay(reason);
            }
        } catch (SQLException | RuntimeException ex) {
            if (LockStore.hasLocalLock(room.id(), studentDisplayName())) {
                showLockOverlay("an unapproved application or website");
            }
        }
    }

    private void showLockOverlay(String reason) {
        locked = true;
        if (monitor != null) {
            monitor.setLocked(true);
        }
        lockReasonLabel.setText("Violation: You opened \"" + reason + "\", which is not permitted.");
        codeField.clear();
        codeError.setVisible(false);
        lockOverlay.setVisible(true);
        lockOverlay.setManaged(true);
        if (leaveButton != null) {
            leaveButton.setDisable(true);
        }
        Platform.runLater(lockOverlay::requestFocus);
    }

    private void hideLockOverlay() {
        locked = false;
        if (monitor != null) {
            monitor.setLocked(false);
        }
        // Bug 5 fix: always reset error state so no stale "invalid code" message lingers
        codeError.setVisible(false);
        codeError.setText("");
        codeField.clear();
        wrongCodeAttempts = 0;
        codeLockedUntil = 0;
        lockOverlay.setVisible(false);
        lockOverlay.setManaged(false);
        if (leaveButton != null) {
            leaveButton.setDisable(false);
        }
    }

    private void submitUnlockCode() {
        long now = System.currentTimeMillis();
        if (now < codeLockedUntil) {
            long secondsLeft = (codeLockedUntil - now + 999) / 1000;
            codeError.setText("Too many wrong attempts. Try again in " + secondsLeft + "s.");
            codeError.setVisible(true);
            return;
        }
        String entered = codeField.getText() == null ? "" : codeField.getText().trim();
        if (entered.isBlank()) {
            codeError.setText("Please enter the code from your teacher.");
            codeError.setVisible(true);
            return;
        }

        codeField.setDisable(true);
        Thread worker = new Thread(() -> {
            boolean ok = false;
            try {
                ok = LockStore.tryUnlockWithCode(room.id(), studentDisplayName(), entered);
            } catch (SQLException | RuntimeException ex) {
                ok = false;
            }

            // Fallback for offline / in-person teacher unlock using room password or local code
            if (!ok) {
                if (room.roomPassword() != null && entered.equals(room.roomPassword())) {
                    ok = true;
                    LockStore.clearLocalLock(room.id(), studentDisplayName());
                    try {
                        LockStore.unlock(room.id(), studentDisplayName());
                    } catch (Exception ignored) {
                    }
                } else if (LockStore.checkLocalUnlockCode(room.id(), studentDisplayName(), entered)) {
                    ok = true;
                    LockStore.clearLocalLock(room.id(), studentDisplayName());
                }
            }

            boolean finalOk = ok;
            Platform.runLater(() -> {
                codeField.setDisable(false);
                if (finalOk) {
                    wrongCodeAttempts = 0;
                    codeError.setVisible(false);
                    codeError.setText("");
                    hideLockOverlay();
                    Ui.toast(this, "Unlocked. You may continue your exam.", true);
                } else {
                    wrongCodeAttempts++;
                    if (wrongCodeAttempts >= 5) {
                        codeLockedUntil = System.currentTimeMillis() + 30_000;
                        wrongCodeAttempts = 0;
                        codeError.setText("Too many wrong attempts. Try again in 30 seconds.");
                    } else {
                        codeError.setText("Invalid code or password. Please verify with your teacher.");
                    }
                    codeError.setVisible(true);
                    codeField.clear();
                    codeField.requestFocus();
                }
            });
        }, "unlock-code-submit");
        worker.setDaemon(true);
        worker.start();
    }

    private ScheduledExecutorService lockPoller;

    private void startLockPolling() {
        if (lockPoller != null) {
            return;
        }
        lockPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "room-lock-poller");
            t.setDaemon(true);
            return t;
        });
        lockPoller.scheduleWithFixedDelay(() -> {
            try {
                rooms.heartbeat(room.id(), studentDisplayName());

                java.util.Optional<LockStore.LockedStudent> lock = LockStore.findLock(room.id(), studentDisplayName());
                // Bug 1 fix: stillLocked is determined by DB only (teacher's JVM writes DB).
                // hasLocalLock() is only for initial detection — NOT for keeping the lock alive
                // after the teacher has removed it from DB.
                boolean stillLocked = lock.isPresent();
                if (!locked && stillLocked) {
                    String reason = (lock.isPresent() && lock.get().reason() != null && !lock.get().reason().isBlank())
                            ? lock.get().reason()
                            : "an unapproved application or website";
                    Platform.runLater(() -> showLockOverlay(reason));
                } else if (locked && !stillLocked) {
                    // Clear local lock so next heartbeat cycle stays clean
                    LockStore.clearLocalLock(room.id(), studentDisplayName());
                    Platform.runLater(() -> {
                        hideLockOverlay();
                        Ui.toast(this, "Your teacher unlocked you. You can continue.", true);
                    });
                }
            } catch (SQLException | RuntimeException ignored) {
                if (!locked && LockStore.hasLocalLock(room.id(), studentDisplayName())) {
                    Platform.runLater(() -> showLockOverlay("an unapproved application or website"));
                }
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    private void stopLockPolling() {
        if (lockPoller != null) {
            lockPoller.shutdownNow();
            lockPoller = null;
        }
    }

    private void launch(List<AppEntry> chosen) {
        for (AppEntry entry : chosen) {
            if (!AppLauncher.isAvailable(entry)) {
                Ui.toast(this, "“" + entry.name() + "” is allowed for this room, but is not installed on this PC. Please contact your supervisor if you need it.", false);
                return;
            }
            try {
                AppLauncher.launch(entry);
            } catch (IOException ex) {
                Ui.showError(this, "Could not launch " + entry.name(), ex);
                return;
            }
        }
        if (chosen.size() == 1) {
            Ui.toast(this, "Starting " + chosen.getFirst().name(), true);
        }
    }

    private final class AppRow extends ListCell<AppEntry> {

        private final AppGlyph glyph = new AppGlyph(36);
        private final Label name = new Label();
        private final Label target = new Label();
        private final Label kind = new Label();
        private final Button launch = Ui.iconButton(Icons.PLAY, "Launch now");
        private final HBox box;

        private AppEntry current;

        AppRow() {
            name.getStyleClass().add("cell-title");
            target.getStyleClass().add("cell-subtitle");
            kind.getStyleClass().add("chip");

            launch.setOnAction(e -> {
                if (current != null) {
                    StudentRoomView.this.launch(List.of(current));
                }
            });

            VBox text = new VBox(2, name, target);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);

            box = new HBox(12, glyph, text, kind, launch);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("app-row");
        }

        @Override
        protected void updateItem(AppEntry app, boolean empty) {
            super.updateItem(app, empty);
            current = empty ? null : app;
            if (empty || app == null) {
                setGraphic(null);
                return;
            }
            glyph.setApp(app);
            name.setText(app.name());
            target.setText(app.launchTarget());
            boolean available = AppLauncher.isAvailable(app);
            if (available) {
                kind.setText(app.targetType() == AppEntry.TargetType.EXECUTABLE ? "Ready to launch" : "Installed app");
                kind.setStyle("-fx-background-color: rgba(34, 197, 94, 0.18); -fx-text-fill: #86efac; -fx-padding: 3 8; -fx-background-radius: 6; -fx-font-size: 11px;");
                launch.setDisable(false);
            } else {
                kind.setText("Not installed on this PC");
                kind.setStyle("-fx-background-color: rgba(239, 68, 68, 0.2); -fx-text-fill: #fca5a5; -fx-padding: 3 8; -fx-background-radius: 6; -fx-font-size: 11px;");
                launch.setDisable(true);
            }
            setGraphic(box);
        }
    }
}
