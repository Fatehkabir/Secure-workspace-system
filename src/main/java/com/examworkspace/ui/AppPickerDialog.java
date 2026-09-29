package com.examworkspace.ui;

import com.examworkspace.apps.InstalledAppScanner;
import com.examworkspace.model.AppEntry;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Lets the teacher tick any number of apps installed on this PC. Returns the ticked apps. */
class AppPickerDialog extends Dialog<List<AppEntry>> {

    private static List<AppEntry> cachedApps;

    private final Set<String> alreadyInRoom;
    private final ObservableList<AppEntry> allApps = FXCollections.observableArrayList();
    private final FilteredList<AppEntry> visible = new FilteredList<>(allApps, app -> true);
    private final Map<AppEntry, BooleanProperty> checked = new HashMap<>();

    private final Label status = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Button rescan = Ui.iconButton(Icons.REFRESH, "Scan this PC again");
    private final Button selectAll = new Button("Select all");
    private final Button clear = new Button("Clear");
    private final ListView<AppEntry> list = new ListView<>(visible);
    private Button addButton;

    AppPickerDialog(Set<String> alreadyInRoomLowercase) {
        this.alreadyInRoom = alreadyInRoomLowercase;

        setTitle("Add apps from this PC");
        setResizable(true);

        DialogPane pane = getDialogPane();
        Theme.applyTo(pane);
        pane.setHeaderText(null);

        Label heading = new Label("Add apps from this PC");
        heading.getStyleClass().add("dialog-title");
        Label blurb = new Label("Tick the programs students may open during the exam. "
                + "This list comes from the apps installed on this computer.");
        blurb.getStyleClass().add("dialog-message");
        blurb.setWrapText(true);
        blurb.setMaxWidth(470);
        blurb.setMinHeight(Region.USE_PREF_SIZE);

        TextField search = Ui.searchField("Search installed apps");
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((obs, old, text) -> {
            String query = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
            visible.setPredicate(app -> query.isEmpty() || app.name().toLowerCase(Locale.ROOT).contains(query));
            updateStatus();
        });

        selectAll.getStyleClass().addAll("ghost", "sm");
        selectAll.setOnAction(e -> visible.forEach(app -> checkedProperty(app).set(true)));
        clear.getStyleClass().addAll("ghost", "sm");
        clear.setOnAction(e -> checked.values().forEach(property -> property.set(false)));
        rescan.setOnAction(e -> load(true));
        HBox toolbar = new HBox(8, search, selectAll, clear, rescan);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        list.setCellFactory(view -> new PickerCell());
        list.getStyleClass().add("app-list");
        list.setPlaceholder(new Label("No installed app matches your search."));

        spinner.setMaxSize(42, 42);
        StackPane listArea = new StackPane(list, spinner);
        VBox.setVgrow(listArea, Priority.ALWAYS);

        status.getStyleClass().add("picker-status");
        VBox content = new VBox(14, new VBox(6, heading, blurb), toolbar, listArea, status);
        content.getStyleClass().add("dialog-content");
        content.setPrefSize(560, 540);
        pane.setContent(content);

        ButtonType add = new ButtonType("Add selected", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(ButtonType.CANCEL, add);
        addButton = (Button) pane.lookupButton(add);
        addButton.getStyleClass().add("primary");
        addButton.setDisable(true);

        setResultConverter(button -> button == add
                ? allApps.stream().filter(app -> checkedProperty(app).get()).toList()
                : null);

        load(false);
    }

    private BooleanProperty checkedProperty(AppEntry app) {
        return checked.computeIfAbsent(app, key -> {
            SimpleBooleanProperty property = new SimpleBooleanProperty(false);
            property.addListener((obs, was, now) -> updateStatus());
            return property;
        });
    }

    private void load(boolean forceRescan) {
        if (cachedApps != null && !forceRescan) {
            show(cachedApps);
            return;
        }
        spinner.setVisible(true);
        setControlsDisabled(true);
        status.setText("Scanning the apps installed on this PC\u2026");
        Ui.runAsync(InstalledAppScanner::scan, scanned -> {
            cachedApps = scanned;
            show(scanned);
        }, error -> {
            spinner.setVisible(false);
            setControlsDisabled(false);
            rescan.setDisable(false);
            status.setText("Could not list installed apps: " + error.getMessage());
        });
    }

    private void show(List<AppEntry> scanned) {
        spinner.setVisible(false);
        setControlsDisabled(false);
        checked.clear();
        allApps.setAll(scanned.stream()
                .filter(app -> !alreadyInRoom.contains(app.launchTarget().toLowerCase(Locale.ROOT)))
                .toList());
        updateStatus();
    }

    private void setControlsDisabled(boolean disabled) {
        selectAll.setDisable(disabled);
        clear.setDisable(disabled);
        rescan.setDisable(disabled);
    }

    private void updateStatus() {
        long selected = checked.values().stream().filter(BooleanProperty::get).count();
        int shown = visible.size();
        status.setText(shown == allApps.size()
                ? allApps.size() + " apps available on this PC  \u2022  " + selected + " selected"
                : shown + " of " + allApps.size() + " apps shown  \u2022  " + selected + " selected");
        if (addButton != null) {
            addButton.setDisable(selected == 0);
            addButton.setText(selected == 0 ? "Add selected"
                    : selected == 1 ? "Add 1 app" : "Add " + selected + " apps");
        }
    }

    /** One tickable app row. Cells are reused, so the checkbox rebinds on every update. */
    private final class PickerCell extends ListCell<AppEntry> {

        private final CheckBox check = new CheckBox();
        private final AppGlyph glyph = new AppGlyph(28);
        private final Label name = new Label();
        private final Label target = new Label();
        private final HBox box;

        private BooleanProperty bound;

        PickerCell() {
            name.getStyleClass().add("cell-title");
            target.getStyleClass().add("cell-subtitle");
            VBox text = new VBox(1, name, target);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);

            box = new HBox(12, check, glyph, text);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("app-row");

            setOnMouseClicked(event -> {
                if (bound != null && !Ui.isWithin(event.getTarget(), check)) {
                    bound.set(!bound.get());
                }
            });
        }

        @Override
        protected void updateItem(AppEntry app, boolean empty) {
            super.updateItem(app, empty);
            if (bound != null) {
                check.selectedProperty().unbindBidirectional(bound);
                bound = null;
            }
            if (empty || app == null) {
                setGraphic(null);
                return;
            }
            glyph.setApp(app);
            name.setText(app.name());
            target.setText(app.launchTarget());
            bound = checkedProperty(app);
            check.selectedProperty().bindBidirectional(bound);
            setGraphic(box);
        }
    }
}
