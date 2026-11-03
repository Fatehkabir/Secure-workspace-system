package com.examworkspace.ui;

import com.examworkspace.ExamWorkspaceApp;
import com.examworkspace.apps.AppLauncher;
import com.examworkspace.model.AppEntry;
import com.examworkspace.model.Room;
import com.examworkspace.model.RoomStatus;
import com.examworkspace.model.Teacher;
import com.examworkspace.room.RoomService;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

public class DashboardView extends BorderPane {

    private enum Filter {
        ALL("All"), LIVE("Live"), UPCOMING("Upcoming"), ENDED("Ended");

        final String label;

        Filter(String label) {
            this.label = label;
        }

        boolean matches(RoomStatus status) {
            return switch (this) {
                case ALL -> true;
                case LIVE -> status == RoomStatus.LIVE;
                case UPCOMING -> status == RoomStatus.UPCOMING;
                case ENDED -> status == RoomStatus.ENDED;
            };
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a");

    private final ExamWorkspaceApp app;
    private final Teacher teacher;
    private final RoomService rooms;

    private final List<Room> loaded = new ArrayList<>();
    private final Map<Long, List<AppEntry>> apps = new HashMap<>();
    private final Map<Long, Label> statusBadges = new HashMap<>();
    private final Map<Long, StackPane> statusDots = new HashMap<>();
    private final Label livePill = new Label();
    private final Label panelStatus = new Label();
    private final FlowPane cards = new FlowPane(16, 16);
    private final VBox panelHost = new VBox();
    private final VBox roomNav = new VBox(4);
    private ScrollPane scroll;

    private Filter filter = Filter.ALL;
    private Long selectedId;
    private boolean passwordVisible;
    private PauseTransition boundary;

    public DashboardView(ExamWorkspaceApp app, Teacher teacher, RoomService rooms) {
        this.app = app;
        this.teacher = teacher;
        this.rooms = rooms;
        getStyleClass().add("dash-shell");

        setLeft(sidebar());
        setCenter(main());
        parentProperty().addListener((obs, previous, parent) -> {
            if (parent == null && boundary != null) {
                boundary.stop();
            }
        });
        reload();
    }

    private Node sidebar() {
        StackPane mark = new StackPane(Icons.of(Icons.LOGO, 18, "icon-on-accent"));
        mark.getStyleClass().add("brand-mark");
        Label brandName = new Label("Exam Workspace");
        brandName.getStyleClass().add("brand-title");
        Label brandRole = new Label("TEACHER CONSOLE");
        brandRole.getStyleClass().add("brand-kicker");
        HBox brand = new HBox(10, mark, new VBox(1, brandName, brandRole));
        brand.setAlignment(Pos.CENTER_LEFT);

        Button exams = new Button("Exam Rooms", Icons.of(Icons.DASHBOARD, 16, "icon-on-accent"));
        exams.getStyleClass().add("side-nav-active");
        exams.setMaxWidth(Double.MAX_VALUE);
        exams.setAlignment(Pos.CENTER_LEFT);

        Label section = new Label("YOUR ROOMS");
        section.getStyleClass().add("side-section");

        ScrollPane roomScroll = new ScrollPane(roomNav);
        roomScroll.setFitToWidth(true);
        roomScroll.getStyleClass().addAll("edge-to-edge", "side-scroll");
        VBox.setVgrow(roomScroll, Priority.ALWAYS);

        Ui.Avatar avatar = new Ui.Avatar(36);
        avatar.setName(teacher.fullName());
        Label name = new Label(teacher.fullName());
        name.getStyleClass().add("user-name");
        Label role = new Label("Teaching staff");
        role.getStyleClass().add("user-handle");
        Button signOut = new Button("Sign out");
        signOut.getStyleClass().add("quiet");
        signOut.setMaxWidth(Double.MAX_VALUE);
        signOut.setOnAction(e -> app.showLogin());

        VBox user = new VBox(8, new HBox(10, avatar, new VBox(1, name, role)), signOut);
        user.getStyleClass().add("side-user");

        VBox side = new VBox(16, brand, exams, section, roomScroll, user);
        side.getStyleClass().add("dash-sidebar");
        VBox.setVgrow(roomScroll, Priority.ALWAYS);

        StackPane frame = new StackPane(side);
        frame.getStyleClass().add("dash-sidebar-frame");
        return frame;
    }

    private Node main() {
        Label kicker = new Label("DASHBOARD");
        kicker.getStyleClass().add("dash-kicker");
        Label title = new Label("Exam Rooms");
        title.getStyleClass().add("dash-title");
        livePill.getStyleClass().add("live-pill");
        Button create = new Button("+  New Room");
        create.getStyleClass().add("pink");
        create.setOnAction(e -> createRoom());
        HBox header = new HBox(12, new VBox(2, kicker, title), Ui.spacer(), livePill, create);
        header.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup group = new ToggleGroup();
        HBox filters = new HBox(8);
        filters.setAlignment(Pos.CENTER_LEFT);
        for (Filter option : Filter.values()) {
            ToggleButton chip = new ToggleButton(option.label);
            chip.setToggleGroup(group);
            chip.setUserData(option);
            chip.getStyleClass().add("filter-chip");
            chip.setSelected(option == Filter.ALL);
            filters.getChildren().add(chip);
        }
        group.selectedToggleProperty().addListener((obs, previous, selected) -> {
            if (selected == null && previous != null) {
                previous.setSelected(true);
                return;
            }
            if (selected != null) {
                filter = (Filter) selected.getUserData();
                renderBoard();
            }
        });

        cards.getStyleClass().add("exam-grid");
        VBox content = new VBox(18, cards, panelHost);
        scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox page = new VBox(18, header, filters, scroll);
        page.getStyleClass().add("dash-main");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return page;
    }

    private void reload() {
        try {
            loaded.clear();
            loaded.addAll(rooms.listRooms());
            apps.clear();
            apps.putAll(rooms.appsByRoom());
        } catch (RuntimeException ex) {
            Ui.showError(this, "Could not load rooms", ex);
        }
        if (selectedId != null && loaded.stream().noneMatch(room -> room.id() == selectedId)) {
            selectedId = null;
        }
        if (selectedId == null && !loaded.isEmpty()) {
            Instant now = Instant.now();
            selectedId = loaded.stream()
                    .filter(room -> room.statusAt(now) == RoomStatus.LIVE)
                    .findFirst()
                    .or(() -> loaded.stream().filter(room -> room.statusAt(now) == RoomStatus.UPCOMING).findFirst())
                    .orElse(loaded.getFirst())
                    .id();
        }
        render();
        armBoundary();
    }

    private void render() {
        renderNav();
        renderBoard();
        updateLivePill();
    }

    private void renderNav() {
        roomNav.getChildren().clear();
        statusDots.clear();
        Instant now = Instant.now();
        if (loaded.isEmpty()) {
            Label empty = new Label("No rooms yet");
            empty.getStyleClass().add("side-empty");
            roomNav.getChildren().add(empty);
            return;
        }
        for (Room room : loaded) {
            StackPane dot = new StackPane();
            dot.getStyleClass().add("status-dot");
            styleDot(dot, room.statusAt(now));
            statusDots.put(room.id(), dot);

            Label name = new Label(room.name());
            name.getStyleClass().add("side-room-name");
            name.setMaxWidth(150);
            Region spacer = Ui.spacer();
            Label count = new Label(Integer.toString(room.appCount()));
            count.getStyleClass().add("side-count");
            HBox row = new HBox(8, dot, name, spacer, count);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("side-room");
            if (selectedId != null && room.id() == selectedId) {
                row.getStyleClass().add("side-room-active");
            }
            row.setOnMouseClicked(e -> select(room.id()));
            roomNav.getChildren().add(row);
        }
    }

    private void renderBoard() {
        cards.getChildren().clear();
        statusBadges.clear();
        Instant now = Instant.now();
        if (loaded.isEmpty()) {
            cards.getChildren().add(Ui.emptyState(Icons.ROOM, "No rooms yet",
                    "Create a room, give it a schedule, and choose the apps allowed during the exam.",
                    newRoomButton()));
            panelHost.getChildren().clear();
            panelHost.getStyleClass().clear();
            return;
        }
        List<Room> visible = loaded.stream().filter(room -> filter.matches(room.statusAt(now))).toList();
        if (visible.isEmpty()) {
            cards.getChildren().add(Ui.emptyState(Icons.SEARCH, "Nothing in this filter",
                    "No rooms are " + filter.label.toLowerCase(Locale.ROOT) + " right now."));
        } else {
            visible.forEach(room -> cards.getChildren().add(card(room, now)));
        }
        renderPanel(now);
    }

    private Button newRoomButton() {
        Button create = new Button("+  New Room");
        create.getStyleClass().add("pink");
        create.setOnAction(e -> createRoom());
        return create;
    }

    private Node card(Room room, Instant now) {
        Label badge = new Label();
        badge.getStyleClass().add("status-pill");
        styleStatus(badge, room.statusAt(now));
        statusBadges.put(room.id(), badge);

        Label count = new Label(room.appCount() == 1 ? "1 app" : room.appCount() + " apps");
        count.getStyleClass().add("card-meta");
        HBox top = new HBox(8, badge, Ui.spacer(), count);
        top.setAlignment(Pos.CENTER_LEFT);

        Label name = new Label(room.name());
        name.getStyleClass().add("exam-card-name");
        Label code = new Label(room.roomCode());
        code.getStyleClass().add("exam-card-code");
        Label when = new Label(formatSchedule(room));
        when.getStyleClass().add("exam-card-when");

        FlowPane chips = new FlowPane(6, 6);
        List<AppEntry> roomApps = apps.getOrDefault(room.id(), List.of());
        roomApps.stream().limit(3).forEach(app -> chips.getChildren().add(miniChip(app)));
        if (roomApps.size() > 3) {
            Label more = new Label("+" + (roomApps.size() - 3));
            more.getStyleClass().add("mini-more");
            chips.getChildren().add(more);
        }

        VBox card = new VBox(8, top, name, code, when, chips);
        card.getStyleClass().add("exam-card");
        if (selectedId != null && room.id() == selectedId) {
            card.getStyleClass().add("exam-card-selected");
        }
        card.setPrefWidth(280);
        Motion.lift(card);
        card.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                select(room.id());
            }
        });
        return card;
    }

    private Node miniChip(AppEntry app) {
        Label name = new Label(app.name());
        name.getStyleClass().add("mini-chip-name");
        name.setMaxWidth(110);
        AppGlyph glyph = new AppGlyph(18);
        glyph.setApp(app);
        HBox chip = new HBox(6, glyph, name);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("mini-chip");
        chip.setOnMouseClicked(e -> launchApp(app));
        return chip;
    }

    private void renderPanel(Instant now) {
        panelHost.getChildren().clear();
        Room room = selected();
        if (room == null) {
            return;
        }
        Label title = new Label("Allowed Applications");
        title.getStyleClass().add("panel-title");
        Label subtitle = new Label("Apps permitted inside " + room.name());
        subtitle.getStyleClass().add("panel-subtitle");

        Button add = new Button("Add apps", Icons.of(Icons.PLUS, 14, "icon-on-accent"));
        add.getStyleClass().add("primary");
        add.setOnAction(e -> addInstalled(room));
        Button browse = new Button("Browse");
        browse.getStyleClass().add("quiet");
        browse.setOnAction(e -> browseExe(room));
        Button workspace = new Button("Open workspace", Icons.of(Icons.APPS, 14));
        workspace.getStyleClass().add("quiet");
        workspace.setOnAction(e -> app.showRoom(teacher, rooms, room));
        Button delete = new Button("Delete room");
        delete.getStyleClass().add("quiet");
        delete.setOnAction(e -> deleteRoom(room));
        HBox actions = new HBox(8, add, browse, workspace, delete);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox head = new HBox(12, new VBox(2, title, subtitle), Ui.spacer(), actions);
        head.setAlignment(Pos.CENTER_LEFT);

        panelStatus.setText(room.statusAt(now).label() + "  ·  " + formatSchedule(room));
        panelStatus.getStyleClass().setAll("panel-status", "status-" + room.statusAt(now).name().toLowerCase(Locale.ROOT));
        Button editSchedule = new Button("Edit schedule");
        editSchedule.getStyleClass().add("quiet");
        editSchedule.setOnAction(e -> editSchedule(room));
        Button changePassword = new Button("Change password");
        changePassword.getStyleClass().add("quiet");
        changePassword.setOnAction(e -> changePassword(room));
        HBox scheduleRow = new HBox(8, panelStatus, Ui.spacer(), editSchedule, changePassword);
        scheduleRow.setAlignment(Pos.CENTER_LEFT);

        panelHost.getChildren().add(new VBox(14, head, scheduleRow, credentials(room), appWrap(room)));
        panelHost.getStyleClass().setAll("exam-panel");
    }

    private Node credentials(Room room) {
        Label idCaption = new Label("ROOM ID");
        idCaption.getStyleClass().add("cred-caption");
        Label idValue = new Label(room.roomCode());
        idValue.getStyleClass().add("cred-value");
        Button copy = Ui.iconButton(Icons.FILE, "Copy room ID");
        copy.getStyleClass().add("icon-button-on-dark");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(room.roomCode());
            Clipboard.getSystemClipboard().setContent(content);
            Ui.toast(this, "Room ID copied", true);
        });
        HBox idRow = new HBox(8, new VBox(2, idCaption, idValue), copy);
        idRow.setAlignment(Pos.CENTER_LEFT);
        idRow.getStyleClass().add("cred-box");

        Label passCaption = new Label("PASSWORD");
        passCaption.getStyleClass().add("cred-caption");
        Label passValue = new Label(passwordVisible ? room.roomPassword() : masked(room.roomPassword()));
        passValue.getStyleClass().add("cred-value");
        Button reveal = Ui.iconButton(passwordVisible ? Icons.EYE_OFF : Icons.EYE,
                passwordVisible ? "Hide password" : "Show password");
        reveal.getStyleClass().add("icon-button-on-dark");
        reveal.setOnAction(e -> {
            passwordVisible = !passwordVisible;
            renderPanel(Instant.now());
        });
        HBox passRow = new HBox(8, new VBox(2, passCaption, passValue), reveal);
        passRow.setAlignment(Pos.CENTER_LEFT);
        passRow.getStyleClass().add("cred-box");
        HBox.setHgrow(idRow, Priority.ALWAYS);
        HBox.setHgrow(passRow, Priority.ALWAYS);

        Label note = new Label("Only you can see this room's ID and password.");
        note.getStyleClass().add("cred-note");
        VBox block = new VBox(8, new HBox(10, idRow, passRow), note);
        return block;
    }

    private Node appWrap(Room room) {
        FlowPane flow = new FlowPane(10, 10);
        for (AppEntry appEntry : apps.getOrDefault(room.id(), List.of())) {
            flow.getChildren().add(appChip(room, appEntry));
        }
        Button addTile = new Button("Add app");
        addTile.setGraphic(Icons.of(Icons.PLUS, 16));
        addTile.getStyleClass().add("add-tile");
        addTile.setOnAction(e -> addInstalled(room));
        flow.getChildren().add(addTile);
        return flow;
    }

    private Node appChip(Room room, AppEntry appEntry) {
        Label name = new Label(appEntry.name());
        name.getStyleClass().add("app-chip-name");
        name.setMaxWidth(140);
        Label kind = new Label(appEntry.targetType() == AppEntry.TargetType.EXECUTABLE ? "Program file" : "Installed app");
        kind.getStyleClass().add("app-chip-kind");
        Button launch = Ui.iconButton(Icons.PLAY, "Launch " + appEntry.name());
        launch.getStyleClass().add("icon-button-on-dark");
        launch.setOnAction(e -> launchApp(appEntry));
        Button remove = new Button("×");
        remove.getStyleClass().add("chip-remove");
        remove.setOnAction(e -> removeApp(room, appEntry));
        AppGlyph glyph = new AppGlyph(32);
        glyph.setApp(appEntry);
        HBox chip = new HBox(8, glyph, new VBox(1, name, kind), launch, remove);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("app-chip");
        chip.setOnMouseClicked(e -> {
            if (!Ui.isWithin(e.getTarget(), remove) && !Ui.isWithin(e.getTarget(), launch)) {
                launchApp(appEntry);
            }
        });
        return chip;
    }

    private void select(long id) {
        if (selectedId != null && selectedId == id) {
            return;
        }
        selectedId = id;
        passwordVisible = false;
        render();
    }

    private Room selected() {
        if (selectedId == null) {
            return null;
        }
        return loaded.stream().filter(room -> room.id() == selectedId).findFirst().orElse(null);
    }

    private void updateLivePill() {
        long live = loaded.stream().filter(room -> room.statusAt(Instant.now()) == RoomStatus.LIVE).count();
        livePill.setText(live == 1 ? "1 room live" : live + " rooms live");
        livePill.getStyleClass().setAll("live-pill", live == 0 ? "live-pill-idle" : "live-pill-on");
    }

    private void armBoundary() {
        if (boundary != null) {
            boundary.stop();
        }
        Instant now = Instant.now();
        Instant next = null;
        for (Room room : loaded) {
            for (Instant moment : List.of(room.startsAt(), room.endsAt())) {
                if (moment.isAfter(now) && (next == null || moment.isBefore(next))) {
                    next = moment;
                }
            }
        }
        if (next == null) {
            return;
        }
        long wait = Math.min(java.time.Duration.between(now, next).toMillis() + 400, 30_000);
        boundary = new PauseTransition(javafx.util.Duration.millis(Math.max(wait, 400)));
        boundary.setOnFinished(e -> refreshStatuses());
        boundary.play();
    }

    private void refreshStatuses() {
        Instant now = Instant.now();
        boolean filterChanged = false;
        for (Room room : loaded) {
            RoomStatus status = room.statusAt(now);
            Label badge = statusBadges.get(room.id());
            if (badge != null && !badge.getText().equals(status.label())) {
                if (filter != Filter.ALL) {
                    filterChanged = true;
                }
                styleStatus(badge, status);
            }
            StackPane dot = statusDots.get(room.id());
            if (dot != null) {
                styleDot(dot, status);
            }
        }
        updateLivePill();
        Room room = selected();
        if (room != null) {
            panelStatus.setText(room.statusAt(now).label() + "  ·  " + formatSchedule(room));
            panelStatus.getStyleClass().setAll("panel-status",
                    "status-" + room.statusAt(now).name().toLowerCase(Locale.ROOT));
        }
        if (filterChanged) {
            renderBoard();
        }
        armBoundary();
    }

    private void createRoom() {
        Room created = RoomDialogs.create(this, rooms);
        if (created == null) {
            return;
        }
        selectedId = created.id();
        passwordVisible = true;
        reload();
        Ui.toast(this, "Room " + created.roomCode() + " created", true);
    }

    private void editSchedule(Room room) {
        RoomDialogs.Schedule draft = RoomDialogs.schedule(this, room.startsAt(), room.endsAt());
        if (draft == null) {
            return;
        }
        try {
            rooms.updateSchedule(room, draft.start(), draft.end());
            reload();
            Ui.toast(this, "Schedule updated", true);
        } catch (RoomService.RoomException ex) {
            Ui.showError(this, "Could not update the schedule", ex.getMessage());
        } catch (RuntimeException ex) {
            Ui.showError(this, "Could not update the schedule", ex);
        }
    }

    private void changePassword(Room room) {
        String password = RoomDialogs.password(this);
        if (password == null) {
            return;
        }
        try {
            rooms.changePassword(room, password);
            passwordVisible = true;
            reload();
            Ui.toast(this, "Room password updated", true);
        } catch (RoomService.RoomException ex) {
            Ui.showError(this, "Could not change the password", ex.getMessage());
        } catch (RuntimeException ex) {
            Ui.showError(this, "Could not change the password", ex);
        }
    }

    private void deleteRoom(Room room) {
        if (!Ui.confirmDestructive(this, "Delete \"" + room.name() + "\"?",
                "The room, its ID, and its app list will be removed. Installed apps stay on this PC.",
                "Delete room")) {
            return;
        }
        try {
            rooms.deleteRoom(room);
            if (selectedId != null && selectedId == room.id()) {
                selectedId = null;
            }
            reload();
            Ui.toast(this, "Room deleted", true);
        } catch (RuntimeException ex) {
            Ui.showError(this, "Could not delete room", ex);
        }
    }

    private void addInstalled(Room room) {
        java.util.Set<String> existing = apps.getOrDefault(room.id(), List.of()).stream()
                .map(app -> app.launchTarget().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        AppPickerDialog dialog = new AppPickerDialog(existing);
        dialog.initOwner(Ui.windowOf(this));
        dialog.showAndWait().filter(chosen -> !chosen.isEmpty()).ifPresent(chosen -> addApps(room, chosen));
    }

    private void browseExe(Room room) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a program");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Programs (*.exe)", "*.exe"));
        List<File> files = chooser.showOpenMultipleDialog(Ui.windowOf(this));
        if (files == null || files.isEmpty()) {
            return;
        }
        addApps(room, files.stream()
                .map(file -> AppEntry.unsaved(file.getName().replaceFirst("(?i)\\.exe$", ""),
                        file.getAbsolutePath(), AppEntry.TargetType.EXECUTABLE))
                .toList());
    }

    private void addApps(Room room, List<AppEntry> chosen) {
        try {
            int added = rooms.addApps(room, chosen);
            reload();
            Ui.toast(this, added == 0 ? "Those apps are already in this room"
                    : added == 1 ? "1 app added" : added + " apps added", added > 0);
        } catch (RuntimeException ex) {
            Ui.showError(this, "Could not add apps", ex);
        }
    }

    private void launchApp(AppEntry appEntry) {
        try {
            AppLauncher.launch(appEntry);
            Ui.toast(this, "Starting " + appEntry.name(), true);
        } catch (IOException ex) {
            Ui.showError(this, "Could not launch " + appEntry.name(), ex);
        }
    }

    private void removeApp(Room room, AppEntry appEntry) {
        try {
            rooms.removeApps(room, List.of(appEntry));
            reload();
            Ui.toast(this, appEntry.name() + " removed", true);
        } catch (RuntimeException ex) {
            Ui.showError(this, "Could not remove the app", ex);
        }
    }

    private static void styleStatus(Label badge, RoomStatus status) {
        badge.setText(status.label());
        badge.getStyleClass().removeAll("status-live", "status-upcoming", "status-ended");
        if (!badge.getStyleClass().contains("status-pill")) {
            badge.getStyleClass().add("status-pill");
        }
        badge.getStyleClass().add("status-" + status.name().toLowerCase(Locale.ROOT));
    }

    private static void styleDot(StackPane dot, RoomStatus status) {
        dot.getStyleClass().removeAll("dot-live", "dot-upcoming", "dot-ended");
        if (!dot.getStyleClass().contains("status-dot")) {
            dot.getStyleClass().add("status-dot");
        }
        dot.getStyleClass().add("dot-" + status.name().toLowerCase(Locale.ROOT));
    }

    private static String formatSchedule(Room room) {
        var start = room.startsAt().atZone(ZoneId.systemDefault());
        var end = room.endsAt().atZone(ZoneId.systemDefault());
        if (start.toLocalDate().equals(end.toLocalDate())) {
            return DAY.format(start) + "  ·  " + CLOCK.format(start) + " – " + CLOCK.format(end);
        }
        return DAY.format(start) + " " + CLOCK.format(start) + "  –  " + DAY.format(end) + " " + CLOCK.format(end);
    }

    private static String masked(String password) {
        return "•".repeat(Math.max(6, Math.min(password.length(), 12)));
    }
}
