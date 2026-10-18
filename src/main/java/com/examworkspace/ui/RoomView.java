package com.examworkspace.ui;

import com.examworkspace.ExamWorkspaceApp;
import com.examworkspace.apps.AppLauncher;
import com.examworkspace.model.AppEntry;
import com.examworkspace.model.Room;
import com.examworkspace.model.Teacher;
import com.examworkspace.room.RoomService;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

public class RoomView extends AppShell {

    private final RoomService rooms;
    private final Room room;

    private final ObservableList<AppEntry> apps = FXCollections.observableArrayList();
    private final FilteredList<AppEntry> visible = new FilteredList<>(apps, app -> true);
    private final ListView<AppEntry> list = new ListView<>(visible);
    private final StackPane body = new StackPane();
    private final VBox listArea = new VBox(12);
    private final VBox emptyArea;
    private final Label subtitle = new Label();
    private final Label selectionLabel = new Label();
    private final TextField search = Ui.searchField("Search apps in this room");
    private final RoomMonitorPanel monitorPanel;

    public RoomView(ExamWorkspaceApp app, Teacher teacher, RoomService rooms, Room room) {
        this(app, teacher, rooms, room, false);
    }

    public RoomView(ExamWorkspaceApp app, Teacher teacher, RoomService rooms, Room room, boolean selectMonitorTab) {
        super(app, teacher, room.name());
        this.rooms = rooms;
        this.room = room;

        Hyperlink crumb = new Hyperlink("Rooms");
        crumb.setOnAction(e -> app.showDashboard(teacher));
        Label crumbSeparator = new Label("/");
        Label crumbCurrent = new Label(room.name());
        crumbCurrent.getStyleClass().add("breadcrumb-current");
        HBox breadcrumb = new HBox(6, crumb, crumbSeparator, crumbCurrent);
        breadcrumb.setAlignment(Pos.CENTER_LEFT);
        breadcrumb.getStyleClass().add("breadcrumb");

        Label title = new Label(room.name());
        title.getStyleClass().add("page-title");
        subtitle.getStyleClass().add("page-subtitle");

        search.textProperty().addListener((obs, old, text) -> applyFilter(text));
        Button addInstalled = Ui.primaryButton("Add apps", Icons.PLUS);
        addInstalled.setOnAction(e -> addInstalledApps());
        Button browse = new Button("Browse for a program", Icons.of(Icons.FILE, 16));
        browse.setOnAction(e -> browseForExecutable());

        HBox header = new HBox(12,
                new VBox(4, breadcrumb, title, subtitle), Ui.spacer(), search, browse, addInstalled);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("page-header");

        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setCellFactory(view -> new AppRow());
        list.getStyleClass().add("app-list");
        list.setPlaceholder(new Label("No app in this room matches your search."));
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) {
                removeApps(List.copyOf(list.getSelectionModel().getSelectedItems()));
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        selectionLabel.getStyleClass().add("selection-count");
        Button launchSelected = new Button("Launch", Icons.of(Icons.PLAY, 15));
        launchSelected.getStyleClass().add("sm");
        launchSelected.setOnAction(e -> launch(List.copyOf(list.getSelectionModel().getSelectedItems())));
        Button removeSelected = new Button("Remove from room", Icons.of(Icons.TRASH, 15));
        removeSelected.getStyleClass().addAll("danger", "sm");
        removeSelected.setOnAction(e -> removeApps(List.copyOf(list.getSelectionModel().getSelectedItems())));

        HBox selectionBar = new HBox(8, selectionLabel, Ui.spacer(), launchSelected, removeSelected);
        selectionBar.setAlignment(Pos.CENTER_LEFT);
        selectionBar.getStyleClass().add("selection-bar");
        selectionBar.visibleProperty().bind(Bindings.isNotEmpty(list.getSelectionModel().getSelectedItems()));
        selectionBar.managedProperty().bind(selectionBar.visibleProperty());
        list.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<AppEntry>) change -> {
                    int count = list.getSelectionModel().getSelectedItems().size();
                    selectionLabel.setText(count == 1 ? "1 app selected" : count + " apps selected");
                });

        listArea.getChildren().setAll(list, selectionBar);

        Button addFirst = Ui.primaryButton("Add apps from this PC", Icons.PLUS);
        addFirst.setOnAction(e -> addInstalledApps());
        emptyArea = Ui.emptyState(Icons.APPS, "No apps in this room yet",
                "Add the programs students are allowed to open during this exam. "
                        + "Everything installed on this computer is available.", addFirst);

        Tab appsTab = new Tab("Apps", body);
        appsTab.setClosable(false);
        this.monitorPanel = new RoomMonitorPanel(room);
        Tab monitorTab = new Tab("Live Monitor", monitorPanel);
        monitorTab.setClosable(false);
        TabPane tabs = new TabPane(appsTab, monitorTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == monitorTab) {
                monitorPanel.refreshAllowedApps();
            }
        });
        if (selectMonitorTab) {
            tabs.getSelectionModel().select(monitorTab);
        }

        // Flags from students are read for as long as this room page is on screen -- whichever tab
        // is showing -- so the teacher hears the beep and sees a toast even while on the Apps tab.
        monitorPanel.setNotifier(message -> Ui.toast(this, "\uD83D\uDEA8 " + message, false));
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                monitorPanel.startPolling();
            } else {
                monitorPanel.stopPolling();
            }
        });

        setPage(header, tabs);
        refresh();
    }

    private void applyFilter(String text) {
        String query = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        visible.setPredicate(app -> query.isEmpty()
                || app.name().toLowerCase(Locale.ROOT).contains(query)
                || app.launchTarget().toLowerCase(Locale.ROOT).contains(query));
    }

    private void refresh() {
        try {
            List<AppEntry> loaded = rooms.listApps(room);
            apps.setAll(loaded);
            subtitle.setText(switch (loaded.size()) {
                case 0 -> "No apps allowed yet";
                case 1 -> "1 app allowed in this room";
                default -> loaded.size() + " apps allowed in this room";
            });
            boolean isEmpty = loaded.isEmpty();
            body.getChildren().setAll(isEmpty ? emptyArea : listArea);
            search.setVisible(!isEmpty);
            search.setManaged(!isEmpty);
            if (monitorPanel != null) {
                monitorPanel.refreshAllowedApps();
            }
        } catch (RuntimeException ex) {
            Ui.showError(this, "Could not load apps", ex);
        }
    }

    private void addInstalledApps() {
        Set<String> existing = apps.stream()
                .map(app -> app.launchTarget().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        AppPickerDialog dialog = new AppPickerDialog(existing);
        dialog.initOwner(Ui.windowOf(this));
        dialog.showAndWait().filter(chosen -> !chosen.isEmpty()).ifPresent(this::addApps);
    }

    private void browseForExecutable() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a program");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Programs (*.exe)", "*.exe"));
        List<File> files = chooser.showOpenMultipleDialog(Ui.windowOf(this));
        if (files == null || files.isEmpty()) {
            return;
        }
        addApps(files.stream()
                .map(file -> AppEntry.unsaved(file.getName().replaceFirst("(?i)\\.exe$", ""),
                        file.getAbsolutePath(), AppEntry.TargetType.EXECUTABLE))
                .toList());
    }

    private void addApps(List<AppEntry> chosen) {
        try {
            int added = rooms.addApps(room, chosen);
            refresh();
            if (added == 0) {
                Ui.toast(this, "Those apps are already in this room", false);
            } else {
                Ui.toast(this, added == 1 ? "1 app added to this room" : added + " apps added to this room", true);
            }
        } catch (RuntimeException ex) {
            Ui.showError(this, "Could not add apps", ex);
        }
    }

    private void removeApps(List<AppEntry> chosen) {
        if (chosen.isEmpty()) {
            return;
        }
        String what = chosen.size() == 1 ? "\u201c" + chosen.getFirst().name() + "\u201d" : chosen.size() + " apps";
        if (!Ui.confirmDestructive(this, "Remove " + what + " from this room?",
                "Students will no longer be able to use " + what + " in \u201c" + room.name()
                        + "\u201d. The app stays installed on this PC.",
                chosen.size() == 1 ? "Remove app" : "Remove apps")) {
            return;
        }
        try {
            rooms.removeApps(room, chosen);
            list.getSelectionModel().clearSelection();
            refresh();
            Ui.toast(this, chosen.size() == 1 ? "1 app removed" : chosen.size() + " apps removed", true);
        } catch (RuntimeException ex) {
            Ui.showError(this, "Could not remove apps", ex);
        }
    }

    private void launch(List<AppEntry> chosen) {
        for (AppEntry entry : chosen) {
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

    /** One row of the room's app list: avatar, name, launch target and per-row actions. */
    private final class AppRow extends ListCell<AppEntry> {

        private final AppGlyph glyph = new AppGlyph(36);
        private final Label name = new Label();
        private final Label target = new Label();
        private final Label kind = new Label();
        private final Button launch = Ui.iconButton(Icons.PLAY, "Launch now");
        private final Button remove = Ui.iconButton(Icons.TRASH, "Remove from room");
        private final HBox box;

        private AppEntry current;

        AppRow() {
            name.getStyleClass().add("cell-title");
            target.getStyleClass().add("cell-subtitle");
            kind.getStyleClass().add("chip");
            remove.getStyleClass().add("icon-button-danger");

            launch.setOnAction(e -> {
                if (current != null) {
                    RoomView.this.launch(List.of(current));
                }
            });
            remove.setOnAction(e -> {
                if (current != null) {
                    RoomView.this.removeApps(List.of(current));
                }
            });

            VBox text = new VBox(2, name, target);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);

            box = new HBox(12, glyph, text, kind, launch, remove);
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
            kind.setText(app.targetType() == AppEntry.TargetType.EXECUTABLE ? "Program file" : "Installed app");
            setGraphic(box);
        }
    }
}
