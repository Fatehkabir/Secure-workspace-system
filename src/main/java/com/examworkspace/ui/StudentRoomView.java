package com.examworkspace.ui;

import com.examworkspace.ExamWorkspaceApp;
import com.examworkspace.apps.AppLauncher;
import com.examworkspace.model.AppEntry;
import com.examworkspace.model.Room;
import com.examworkspace.model.Student;
import com.examworkspace.monitor.StudentRoomMonitor;
import com.examworkspace.room.StudentRoomService;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
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
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class StudentRoomView extends StudentShell {

    private final StudentRoomService rooms;
    private final Room room;
    private final Student student;

    private final ObservableList<AppEntry> apps = FXCollections.observableArrayList();
    private final FilteredList<AppEntry> visible = new FilteredList<>(apps, app -> true);
    private final ListView<AppEntry> list = new ListView<>(visible);
    private final StackPane body = new StackPane();
    private final VBox listArea = new VBox(12);
    private final VBox emptyArea;
    private final Label subtitle = new Label();
    private final Label selectionLabel = new Label();
    private final TextField search = Ui.searchField("Search allowed apps");

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

        search.textProperty().addListener((obs, old, text) -> applyFilter(text));

        Button leave = new Button("Leave room");
        leave.getStyleClass().add("quiet");
        leave.setOnAction(e -> app.showStudentDashboard(student));

        HBox header = new HBox(12,
                new VBox(4, breadcrumb, title, subtitle), Ui.spacer(), search, leave);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("page-header");

        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setCellFactory(view -> new AppRow());
        list.getStyleClass().add("app-list");
        list.setPlaceholder(new Label("No app in this room matches your search."));
        VBox.setVgrow(list, Priority.ALWAYS);

        selectionLabel.getStyleClass().add("selection-count");
        Button launchSelected = new Button("Launch", Icons.of(Icons.PLAY, 15));
        launchSelected.getStyleClass().add("sm");
        launchSelected.setOnAction(e -> launch(List.copyOf(list.getSelectionModel().getSelectedItems())));

        HBox selectionBar = new HBox(8, selectionLabel, Ui.spacer(), launchSelected);
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

        emptyArea = Ui.emptyState(Icons.APPS, "No apps allowed yet",
                "Your teacher has not added any programs to this room. Check back once they finish setup.");

        setPage(header, body);
        refresh();

        // Foreground-app check: while the student is in this room, an app that isn't on the
        // room's allowed list (and isn't this workspace) is flagged to the teacher after a short
        // grace period. It runs only while this view is on screen and stops when the student
        // leaves the room, logs out, or closes the app.
        StudentRoomMonitor monitor = new StudentRoomMonitor(
                room.id(),
                student == null ? null : student.id(),
                student == null ? "Guest" : student.fullName(),
                message -> Platform.runLater(() -> Ui.toast(this, message, false)),
                message -> Platform.runLater(() -> Ui.toast(this, message, false)));
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                monitor.start();
            } else {
                monitor.stop();
            }
        });
    }

    private void applyFilter(String text) {
        String query = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        visible.setPredicate(entry -> query.isEmpty()
                || entry.name().toLowerCase(Locale.ROOT).contains(query)
                || entry.launchTarget().toLowerCase(Locale.ROOT).contains(query));
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
        } catch (RuntimeException ex) {
            Ui.showError(this, "Could not load apps", ex);
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
            kind.setText(app.targetType() == AppEntry.TargetType.EXECUTABLE ? "Program file" : "Installed app");
            setGraphic(box);
        }
    }
}
