package com.examworkspace.ui;

import com.examworkspace.ExamWorkspaceApp;
import com.examworkspace.model.AppEntry;
import com.examworkspace.model.JoinableRoom;
import com.examworkspace.model.Room;
import com.examworkspace.model.RoomStatus;
import com.examworkspace.model.Student;
import com.examworkspace.room.StudentRoomService;
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
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class StudentDashboardView extends BorderPane {

    private enum Filter {
        ALL("All"), LIVE("Live"), UPCOMING("Upcoming");

        final String label;

        Filter(String label) {
            this.label = label;
        }

        boolean matches(RoomStatus status) {
            return switch (this) {
                case ALL -> status == RoomStatus.LIVE || status == RoomStatus.UPCOMING;
                case LIVE -> status == RoomStatus.LIVE;
                case UPCOMING -> status == RoomStatus.UPCOMING;
            };
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a");

    private final ExamWorkspaceApp app;
    private final Student student;
    private final StudentRoomService rooms;

    private final List<JoinableRoom> loaded = new ArrayList<>();
    private final Map<Long, List<AppEntry>> apps = new HashMap<>();
    private final Map<Long, Label> statusBadges = new HashMap<>();
    private final Map<Long, StackPane> statusDots = new HashMap<>();
    private final Label livePill = new Label();
    private final Label panelStatus = new Label();
    private final FlowPane cards = new FlowPane(16, 16);
    private final VBox panelHost = new VBox();
    private final VBox roomNav = new VBox(4);

    private Filter filter = Filter.ALL;
    private Long selectedId;
    private PauseTransition boundary;

    public StudentDashboardView(ExamWorkspaceApp app, Student student) {
        this.app = app;
        this.student = student;
        this.rooms = new StudentRoomService();
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
        Label brandRole = new Label("STUDENT");
        brandRole.getStyleClass().add("brand-kicker");
        HBox brand = new HBox(10, mark, new VBox(1, brandName, brandRole));
        brand.setAlignment(Pos.CENTER_LEFT);

        Button available = new Button("Available rooms", Icons.of(Icons.DASHBOARD, 16, "icon-on-accent"));
        available.getStyleClass().add("side-nav-active");
        available.setMaxWidth(Double.MAX_VALUE);
        available.setAlignment(Pos.CENTER_LEFT);

        Label section = new Label("ROOMS");
        section.getStyleClass().add("side-section");

        ScrollPane roomScroll = new ScrollPane(roomNav);
        roomScroll.setFitToWidth(true);
        roomScroll.getStyleClass().addAll("edge-to-edge", "side-scroll");
        VBox.setVgrow(roomScroll, Priority.ALWAYS);

        String displayName = student != null ? student.fullName() : "Guest student";
        String handleText = student != null ? "@" + student.username() : "Guest session";
        Ui.Avatar avatar = new Ui.Avatar(36);
        avatar.setName(displayName);
        Label name = new Label(displayName);
        name.getStyleClass().add("user-name");
        Label role = new Label(handleText);
        role.getStyleClass().add("user-handle");
        Button signOut = new Button("Sign out");
        signOut.getStyleClass().add("quiet");
        signOut.setMaxWidth(Double.MAX_VALUE);
        signOut.setOnAction(e -> app.showStudentEntry());

        VBox user = new VBox(8, new HBox(10, avatar, new VBox(1, name, role)), signOut);
        user.getStyleClass().add("side-user");

        VBox side = new VBox(16, brand, available, section, roomScroll, user);
        side.getStyleClass().add("dash-sidebar");
        VBox.setVgrow(roomScroll, Priority.ALWAYS);

        StackPane frame = new StackPane(side);
        frame.getStyleClass().add("dash-sidebar-frame");
        return frame;
    }

    private Node main() {
        Label kicker = new Label("STUDENT");
        kicker.getStyleClass().add("dash-kicker");
        Label title = new Label("Available rooms");
        title.getStyleClass().add("dash-title");
        livePill.getStyleClass().add("live-pill");
        HBox header = new HBox(12, new VBox(2, kicker, title), Ui.spacer(), livePill);
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
        ScrollPane scroll = new ScrollPane(content);
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
            loaded.addAll(rooms.listAvailableRooms());
            apps.clear();
            apps.putAll(rooms.appsByRoom());
        } catch (RuntimeException ex) {
            Ui.showError(this, "Could not load rooms", ex);
            return;
        }
        if (selectedId != null && loaded.stream().noneMatch(room -> room.id() == selectedId)) {
            selectedId = null;
        }
        if (selectedId == null && !loaded.isEmpty()) {
            selectedId = loaded.getFirst().id();
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
        Instant now = Instant.now();
        for (JoinableRoom room : loaded) {
            StackPane dot = new StackPane();
            styleDot(dot, room.statusAt(now));
            statusDots.put(room.id(), dot);
            Label label = new Label(room.name());
            label.getStyleClass().add("side-room-name");
            label.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(label, Priority.ALWAYS);
            HBox row = new HBox(8, dot, label);
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
            cards.getChildren().add(Ui.emptyState(Icons.ROOM, "No rooms available",
                    "When a teacher creates a live or upcoming exam room on this computer, it will show up here."));
            panelHost.getChildren().clear();
            panelHost.getStyleClass().clear();
            return;
        }
        List<JoinableRoom> visible = loaded.stream().filter(room -> filter.matches(room.statusAt(now))).toList();
        if (visible.isEmpty()) {
            cards.getChildren().add(Ui.emptyState(Icons.SEARCH, "Nothing in this filter",
                    "No rooms are " + filter.label.toLowerCase(Locale.ROOT) + " right now."));
        } else {
            visible.forEach(room -> cards.getChildren().add(card(room, now)));
        }
        renderPanel(now);
    }

    private Node card(JoinableRoom room, Instant now) {
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
        roomApps.stream().limit(3).forEach(entry -> chips.getChildren().add(miniChip(entry)));
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

    private Node miniChip(AppEntry appEntry) {
        Label name = new Label(appEntry.name());
        name.getStyleClass().add("mini-chip-name");
        name.setMaxWidth(110);
        AppGlyph glyph = new AppGlyph(18);
        glyph.setApp(appEntry);
        HBox chip = new HBox(6, glyph, name);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("mini-chip");
        return chip;
    }

    private void renderPanel(Instant now) {
        panelHost.getChildren().clear();
        JoinableRoom room = selected();
        if (room == null) {
            return;
        }
        Label title = new Label(room.name());
        title.getStyleClass().add("panel-title");
        Label subtitle = new Label("Room ID " + room.roomCode() + "  ·  "
                + (room.appCount() == 1 ? "1 app allowed" : room.appCount() + " apps allowed"));
        subtitle.getStyleClass().add("panel-subtitle");

        Button join = new Button("Join room", Icons.of(Icons.LOCK, 14, "icon-on-accent"));
        join.getStyleClass().add("pink");
        join.setOnAction(e -> joinRoom(room));
        HBox actions = new HBox(8, join);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox head = new HBox(12, new VBox(2, title, subtitle), Ui.spacer(), actions);
        head.setAlignment(Pos.CENTER_LEFT);

        panelStatus.setText(room.statusAt(now).label() + "  ·  " + formatSchedule(room));
        panelStatus.getStyleClass().setAll("panel-status",
                "status-" + room.statusAt(now).name().toLowerCase(Locale.ROOT));

        Label note = new Label("Ask your teacher for the room password, then join to open the allowed apps.");
        note.getStyleClass().add("cred-note");

        FlowPane flow = new FlowPane(10, 10);
        for (AppEntry appEntry : apps.getOrDefault(room.id(), List.of())) {
            flow.getChildren().add(previewChip(appEntry));
        }
        if (flow.getChildren().isEmpty()) {
            Label empty = new Label("No apps have been added to this room yet.");
            empty.getStyleClass().add("panel-subtitle");
            flow.getChildren().add(empty);
        }

        panelHost.getChildren().add(new VBox(14, head, panelStatus, note, flow));
        panelHost.getStyleClass().setAll("exam-panel");
    }

    private Node previewChip(AppEntry appEntry) {
        Label name = new Label(appEntry.name());
        name.getStyleClass().add("app-chip-name");
        name.setMaxWidth(140);
        Label kind = new Label(appEntry.targetType() == AppEntry.TargetType.EXECUTABLE
                ? "Program file" : "Installed app");
        kind.getStyleClass().add("app-chip-kind");
        AppGlyph glyph = new AppGlyph(32);
        glyph.setApp(appEntry);
        HBox chip = new HBox(8, glyph, new VBox(1, name, kind));
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("app-chip");
        return chip;
    }

    private void joinRoom(JoinableRoom room) {
        Room joined = StudentJoinDialog.ask(this, rooms, room, student);
        if (joined == null) {
            return;
        }
        Ui.toast(this, "Joined " + joined.name(), true);
        app.showStudentRoom(joined, student);
    }

    private void select(long id) {
        if (selectedId != null && selectedId == id) {
            return;
        }
        selectedId = id;
        render();
    }

    private JoinableRoom selected() {
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
        for (JoinableRoom room : loaded) {
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
        boolean needsReload = loaded.stream().anyMatch(room -> room.statusAt(now) == RoomStatus.ENDED);
        if (needsReload) {
            reload();
            return;
        }
        boolean filterChanged = false;
        for (JoinableRoom room : loaded) {
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
        JoinableRoom room = selected();
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

    private static String formatSchedule(JoinableRoom room) {
        var start = room.startsAt().atZone(ZoneId.systemDefault());
        var end = room.endsAt().atZone(ZoneId.systemDefault());
        if (start.toLocalDate().equals(end.toLocalDate())) {
            return DAY.format(start) + "  ·  " + CLOCK.format(start) + " – " + CLOCK.format(end);
        }
        return DAY.format(start) + " " + CLOCK.format(start) + "  –  " + DAY.format(end) + " "
                + CLOCK.format(end);
    }
}
