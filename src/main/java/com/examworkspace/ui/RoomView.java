package com.examworkspace.ui;

import com.examworkspace.ExamWorkspaceApp;
import com.examworkspace.apps.AppLauncher;
import com.examworkspace.browser.UrlAllowlistPolicy;
import com.examworkspace.model.AppEntry;
import com.examworkspace.model.Room;
import com.examworkspace.model.Teacher;
import com.examworkspace.room.RoomService;
import com.examworkspace.room.RoomUrlPolicyService;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

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

        Button webPolicy = new Button("Website restrictions");
        webPolicy.setOnAction(e -> openUrlPolicyDialog());

        HBox header = new HBox(12,
                new VBox(4, breadcrumb, title, subtitle), Ui.spacer(), search, browse, webPolicy, addInstalled);
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
        list.setPrefHeight(360);

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

        this.monitorPanel = new RoomMonitorPanel(room);

        Label monitorHeading = new Label("Live Monitor");
        monitorHeading.getStyleClass().add("page-title");
        Label monitorSubtitle = new Label("Updates automatically — no need to refresh.");
        monitorSubtitle.getStyleClass().add("page-subtitle");
        VBox monitorHeader = new VBox(2, monitorHeading, monitorSubtitle);

        VBox page = new VBox(28, body, new Separator(), monitorHeader, monitorPanel);
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        monitorPanel.setNotifier(message -> Ui.toast(this, "🚨 " + message, false));
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                monitorPanel.startPolling();
            } else {
                monitorPanel.stopPolling();
            }
        });

        setPage(header, scroll);
        refresh();
    }

    private void openUrlPolicyDialog() {
        UrlAllowlistPolicy current = RoomUrlPolicyService.loadPolicy(room);

        // ── Controls ──────────────────────────────────────────────────────────
        CheckBox enabled = new CheckBox("Enforce website allowlist while students are in this room");
        enabled.setSelected(current.enabled());
        enabled.setTooltip(new Tooltip("When on, browsers may only open the sites listed below. "
                + "Any other website is reported to the Live Monitor and the student is locked."));
        enabled.getStyleClass().add("check-title");

        CheckBox enforceHttps = new CheckBox("Require HTTPS (recommended)");
        enforceHttps.setSelected(current.enforceHttps());
        enforceHttps.setTooltip(new Tooltip("http:// pages will be blocked; only secure https:// pages count."));

        CheckBox blockInternal = new CheckBox("Only count real websites (ignore new-tab / browser pages)");
        blockInternal.setSelected(current.blockInternalPages());
        blockInternal.setTooltip(new Tooltip("New-tab and blank pages are never reported; students can open "
                + "the browser without being flagged until they navigate somewhere."));

        Label hint = new Label("Browsers do NOT need to be on the allowed-apps list \u2014 these site rules decide "
                + "what students may open. A bare domain (socrative.com) allows the whole site including www. "
                + "and subdomains; adding a path (docs.google.com/forms) allows only that section.");
        hint.setWrapText(true);
        hint.getStyleClass().add("cred-note");

        // Live draft of the rules; rows are added/removed individually instead of
        // making the teacher edit a raw text block.
        final ObservableList<UrlAllowlistPolicy.Rule> draftRules =
                FXCollections.observableArrayList(current.rules());

        VBox ruleList = new VBox(6);

        Label inputError = new Label();
        inputError.getStyleClass().add("error-label");
        inputError.setWrapText(true);
        inputError.setVisible(false);
        inputError.setManaged(false);

        TextField input = new TextField();
        input.setPromptText("Add a website, e.g. socrative.com");
        HBox.setHgrow(input, Priority.ALWAYS);
        Button addButton = Ui.primaryButton("Add site", Icons.PLUS);

        Runnable rebuild = () -> {
            ruleList.getChildren().setAll(buildRuleRows(draftRules));
        };

        Runnable addFromInput = () -> {
            String raw = input.getText() == null ? "" : input.getText().trim();
            if (raw.isEmpty()) {
                showInputError(inputError, "Type a website first, for example socrative.com");
                return;
            }
            boolean addedAny = false;
            String badLine = null;
            boolean badIsDuplicate = false;
            for (String line : raw.split("\\R|;")) {
                String candidate = line == null ? "" : line.trim();
                if (candidate.isEmpty() || candidate.startsWith("#")) {
                    continue;
                }
                java.util.List<UrlAllowlistPolicy.Rule> parsed =
                        UrlAllowlistPolicy.parseFriendly(candidate);
                if (parsed.isEmpty()) {
                    badLine = candidate;
                    badIsDuplicate = false;
                    continue;
                }
                for (UrlAllowlistPolicy.Rule rule : parsed) {
                    String key = rule.serialize();
                    boolean duplicate = draftRules.stream().anyMatch(x -> x.serialize().equals(key));
                    if (!duplicate) {
                        draftRules.add(rule);
                        addedAny = true;
                    } else {
                        badLine = candidate;
                        badIsDuplicate = true;
                    }
                }
            }
            if (addedAny) {
                input.clear();
                rebuild.run();
            }
            if (badLine != null) {
                showInputError(inputError, badIsDuplicate
                        ? "Already on the list: " + badLine
                        : "Not a website: " + badLine + " \u2014 try something like socrative.com");
            } else {
                inputError.setVisible(false);
                inputError.setManaged(false);
            }
        };
        addButton.setOnAction(e -> addFromInput.run());
        input.setOnAction(e -> addFromInput.run());

        rebuild.run();

        Label listCaption = new Label("Allowed websites");
        listCaption.getStyleClass().add("field-label");

        Label summary = new Label();
        summary.getStyleClass().add("url-summary");
        summary.setWrapText(true);
        summary.setText(UrlAllowlistPolicy.describeRules(draftRules));
        draftRules.addListener((javafx.collections.ListChangeListener<UrlAllowlistPolicy.Rule>) change ->
                summary.setText(UrlAllowlistPolicy.describeRules(draftRules)));

        ScrollPane rulesScroll = new ScrollPane(ruleList);
        rulesScroll.setFitToWidth(true);
        rulesScroll.setMaxHeight(230);
        rulesScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");

        HBox inputRow = new HBox(8, input, addButton);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        // ── Bug 3 fix: build a fully themed custom Stage ─────────────────────
        // Using a Stage + Scene with the app stylesheet gives the same dark look
        // as every other page, instead of the OS-native Dialog which ignores CSS.
        Stage dlg = new Stage();
        dlg.initOwner(Ui.windowOf(this));
        dlg.initModality(javafx.stage.Modality.WINDOW_MODAL);
        dlg.setTitle("Website restrictions \u2014 " + room.name());
        dlg.setResizable(true);

        // ── Dialog header ────────────────────────────────────────────────────
        Label dlgTitle = new Label("\uD83C\uDF10 Website Allowlist Settings");
        dlgTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;");
        Label dlgSub = new Label("Control which websites students may open during this exam.");
        dlgSub.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13px;");
        VBox dlgHeader = new VBox(4, dlgTitle, dlgSub);
        dlgHeader.setPadding(new javafx.geometry.Insets(20, 24, 12, 24));
        dlgHeader.setStyle("-fx-background-color: #0e1629; -fx-border-color: rgba(255,255,255,0.08); -fx-border-width: 0 0 1 0;");

        // ── Content area ─────────────────────────────────────────────────────
        VBox content = new VBox(12,
                enabled,
                new Separator(),
                hint,
                listCaption,
                inputRow,
                inputError,
                rulesScroll,
                summary,
                new Separator(),
                enforceHttps,
                blockInternal);
        content.setPadding(new javafx.geometry.Insets(16, 24, 16, 24));
        content.setStyle("-fx-background-color: #12192d;");

        ScrollPane contentScroll = new ScrollPane(content);
        contentScroll.setFitToWidth(true);
        contentScroll.setStyle("-fx-background-color: transparent; -fx-background: #12192d;");

        // ── Footer with Save / Cancel ─────────────────────────────────────────
        final boolean[] saved = {false};

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("quiet");
        cancelBtn.setOnAction(e -> dlg.close());

        Button saveBtn = Ui.primaryButton("Save settings", Icons.CHECK);
        saveBtn.setOnAction(e -> {
            if (enabled.isSelected() && draftRules.isEmpty()
                    && !Ui.confirmDestructive(this, "Allowlist is on but no websites are listed",
                    "Students will not be able to open any website. Save anyway?",
                    "Save anyway")) {
                return;
            }
            saved[0] = true;
            dlg.close();
        });

        HBox footer = new HBox(10, Ui.spacer(), cancelBtn, saveBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new javafx.geometry.Insets(14, 24, 14, 24));
        footer.setStyle("-fx-background-color: #0e1629; -fx-border-color: rgba(255,255,255,0.08); -fx-border-width: 1 0 0 0;");

        VBox root = new VBox(dlgHeader, contentScroll, footer);
        VBox.setVgrow(contentScroll, Priority.ALWAYS);
        root.setStyle("-fx-background-color: #12192d;");

        // Apply the app's own stylesheet so CSS classes (field-label, cred-note, chip, etc.) work
        javafx.scene.Scene scene = new javafx.scene.Scene(root, 620, 600);
        scene.getStylesheets().addAll(getScene() != null ? getScene().getStylesheets() : java.util.List.of());
        dlg.setScene(scene);
        dlg.showAndWait();

        // ── Persist if teacher clicked Save ──────────────────────────────────
        if (saved[0]) {
            UrlAllowlistPolicy policy = new UrlAllowlistPolicy(
                    enabled.isSelected(),
                    enforceHttps.isSelected(),
                    blockInternal.isSelected(),
                    List.copyOf(draftRules)
            );
            RoomUrlPolicyService.savePolicy(room, policy);
            monitorPanel.refreshAllowedApps();
            Ui.toast(this, enabled.isSelected()
                    ? "Website allowlist saved \u2014 " + draftRules.size()
                    + (draftRules.size() == 1 ? " site rule active" : " site rules active")
                    : "Website allowlist saved (currently off)", true);
        }
    }

    /** One removable row per rule: kind chip, friendly address, description, remove button. */
    private static java.util.List<Node> buildRuleRows(ObservableList<UrlAllowlistPolicy.Rule> rules) {
        java.util.List<Node> rows = new ArrayList<>();
        if (rules.isEmpty()) {
            Label empty = new Label("No websites allowed yet. With enforcement on, students "
                    + "cannot open any website.");
            empty.getStyleClass().add("cred-note");
            empty.setWrapText(true);
            rows.add(empty);
            return rows;
        }
        for (int i = 0; i < rules.size(); i++) {
            UrlAllowlistPolicy.Rule rule = rules.get(i);
            int index = i;

            Label kind = new Label(ruleKindLabel(rule));
            kind.getStyleClass().add("chip");

            Label address = new Label(friendlyRule(rule));
            address.getStyleClass().add("cell-title");
            Label description = new Label(ruleKindDescription(rule));
            description.getStyleClass().add("cell-subtitle");
            VBox text = new VBox(1, address, description);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            address.setWrapText(true);

            Button remove = new Button("\u00d7");
            remove.getStyleClass().add("chip-remove");
            remove.setTooltip(new Tooltip("Remove this website"));
            remove.setOnAction(e -> rules.remove(index));

            HBox row = new HBox(10, kind, text, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("app-row");
            rows.add(row);
        }
        return rows;
    }

    private static String ruleKindLabel(UrlAllowlistPolicy.Rule rule) {
        return switch (rule.type()) {
            case HOST -> "Whole site";
            case SUBDOMAIN -> "Subdomains";
            case PATH_PREFIX -> "Site section";
            case ORIGIN -> "Origin";
            case EXACT_URL -> "Exact page";
        };
    }

    private static String ruleKindDescription(UrlAllowlistPolicy.Rule rule) {
        return switch (rule.type()) {
            case HOST -> "this site and all its subdomains";
            case SUBDOMAIN -> "the listed subdomains only";
            case PATH_PREFIX -> "only this section of the site";
            case ORIGIN -> "scheme + host, any path";
            case EXACT_URL -> "only this exact page";
        };
    }

    private static String friendlyRule(UrlAllowlistPolicy.Rule rule) {
        String match = rule.match() == null ? "" : rule.match();
        String scheme = rule.scheme() == null ? "" : rule.scheme();
        return switch (rule.type()) {
            case HOST -> scheme.isBlank() ? match : scheme + "://" + match;
            case PATH_PREFIX, ORIGIN -> match.contains("://") ? match
                    : (scheme.isBlank() ? "https" : scheme) + "://" + match;
            default -> match;
        };
    }

    private static void showInputError(Label errorLabel, String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
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
            monitorPanel.refreshAllowedApps();
        } catch (RuntimeException ex) {
            System.err.println("[RoomView] Failed to refresh apps: " + ex.getMessage());
            if (apps.isEmpty()) {
                body.getChildren().setAll(emptyArea);
            }
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
        String what = chosen.size() == 1 ? "“" + chosen.getFirst().name() + "”" : chosen.size() + " apps";
        if (!Ui.confirmDestructive(this, "Remove " + what + " from this room?",
                "Students will no longer be able to use " + what + " in “" + room.name()
                        + "”. The app stays installed on this PC.",
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
