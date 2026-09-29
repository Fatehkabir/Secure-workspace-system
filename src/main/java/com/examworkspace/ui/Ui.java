package com.examworkspace.ui;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.event.EventTarget;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;
import javafx.util.Duration;

final class Ui {

    private Ui() {
    }

    /** Runs {@code work} off the JavaFX thread and delivers the result (or error) back on it. */
    static <T> void runAsync(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> onError.accept(task.getException()));
        Thread thread = new Thread(task, "exam-workspace-worker");
        thread.setDaemon(true);
        thread.start();
    }

    // ---------- small building blocks ----------

    static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    static Button iconButton(String svgPath, String tooltip) {
        Button button = new Button();
        button.setGraphic(Icons.of(svgPath, 17));
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);
        return button;
    }

    static Button primaryButton(String text, String svgPath) {
        Button button = new Button(text, Icons.of(svgPath, 16));
        button.getStyleClass().add("primary");
        return button;
    }

    static VBox field(String label, Node control) {
        Label caption = new Label(label);
        caption.getStyleClass().add("field-label");
        return new VBox(6, caption, control);
    }

    static TextField searchField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.getStyleClass().add("search-field");
        field.setPrefWidth(230);
        return field;
    }

    /** A hidden error banner. Call {@link #showBanner} to reveal it with a message. */
    static HBox banner(Label message) {
        message.setWrapText(true);
        HBox banner = new HBox(8, Icons.of(Icons.WARNING, 16, "icon-danger"), message);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().addAll("banner", "banner-danger");
        banner.managedProperty().bind(banner.visibleProperty());
        banner.setVisible(false);
        return banner;
    }

    static void showBanner(HBox banner, Label message, String text) {
        message.setText(text);
        banner.setVisible(true);
    }

    static VBox emptyState(String svgPath, String title, String message, Node... actions) {
        StackPane tile = new StackPane(Icons.of(svgPath, 28, "icon-empty"));
        tile.getStyleClass().add("empty-icon");

        Label heading = new Label(title);
        heading.getStyleClass().add("empty-title");

        Label body = new Label(message);
        body.getStyleClass().add("empty-message");
        body.setWrapText(true);
        body.setMaxWidth(420);
        body.setTextAlignment(TextAlignment.CENTER);

        VBox box = new VBox(10, tile, heading, body);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("empty-state");
        if (actions.length > 0) {
            HBox row = new HBox(8, actions);
            row.setAlignment(Pos.CENTER);
            box.getChildren().add(row);
        }
        return box;
    }

    /** True when {@code target} is {@code ancestor} or sits inside it. */
    static boolean isWithin(EventTarget target, Node ancestor) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node == ancestor) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    // ---------- toasts ----------

    static void toast(Node owner, String message, boolean success) {
        Scene scene = owner.getScene();
        if (scene == null || scene.getRoot() == null) {
            return;
        }
        if (!(scene.getRoot().lookup(".toast-layer") instanceof Pane layer)) {
            return;
        }

        HBox toast = new HBox(10,
                Icons.of(success ? Icons.CHECK : Icons.WARNING, 18, success ? "icon-toast-ok" : "icon-toast-bad"),
                new Label(message));
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.getStyleClass().addAll("toast", success ? "toast-success" : "toast-danger");
        toast.setOpacity(0);
        toast.setTranslateY(14);
        layer.getChildren().add(toast);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(160), toast);
        fadeIn.setToValue(1);
        TranslateTransition slideIn = new TranslateTransition(Duration.millis(160), toast);
        slideIn.setToY(0);
        FadeTransition fadeOut = new FadeTransition(Duration.millis(240), toast);
        fadeOut.setToValue(0);

        SequentialTransition animation = new SequentialTransition(
                new ParallelTransition(fadeIn, slideIn),
                new PauseTransition(Duration.seconds(2.8)),
                fadeOut);
        animation.setOnFinished(e -> layer.getChildren().remove(toast));
        animation.play();
    }

    // ---------- dialogs ----------

    static void showError(Node owner, String title, Throwable error) {
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        if (error.getCause() != null && error.getCause().getMessage() != null) {
            message += "\n\n" + error.getCause().getMessage();
        }
        showError(owner, title, message);
    }

    static void showError(Node owner, String title, String message) {
        Dialog<ButtonType> dialog = messageDialog(owner, Icons.WARNING, true, title, message);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CLOSE)).getStyleClass().add("primary");
        dialog.showAndWait();
    }

    static boolean confirmDestructive(Node owner, String title, String message, String confirmText) {
        Dialog<ButtonType> dialog = messageDialog(owner, Icons.TRASH, true, title, message);
        ButtonType confirm = new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, confirm);
        ((Button) dialog.getDialogPane().lookupButton(confirm)).getStyleClass().add("danger-solid");
        return dialog.showAndWait().filter(confirm::equals).isPresent();
    }

    static Optional<String> promptName(Node owner, String title, String message, String initial,
                                       String prompt, String confirmText) {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(windowOf(owner));
        dialog.setTitle(title);
        dialog.setResizable(false);

        DialogPane pane = dialog.getDialogPane();
        Theme.applyTo(pane);
        pane.setHeaderText(null);

        Label heading = new Label(title);
        heading.getStyleClass().add("dialog-title");
        Label body = new Label(message);
        body.getStyleClass().add("dialog-message");
        body.setWrapText(true);
        body.setMaxWidth(380);
        body.setMinHeight(Region.USE_PREF_SIZE);

        TextField input = new TextField(initial);
        input.setPromptText(prompt);
        input.setPrefWidth(380);

        VBox content = new VBox(16, new VBox(6, heading, body), field("Room name", input));
        content.getStyleClass().add("dialog-content");
        pane.setContent(content);

        ButtonType confirm = new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(ButtonType.CANCEL, confirm);
        Button confirmButton = (Button) pane.lookupButton(confirm);
        confirmButton.getStyleClass().add("primary");
        confirmButton.disableProperty().bind(
                Bindings.createBooleanBinding(() -> input.getText().strip().isEmpty(), input.textProperty()));

        dialog.setResultConverter(button -> button == confirm ? input.getText() : null);
        Platform.runLater(input::requestFocus);
        return dialog.showAndWait();
    }

    private static Dialog<ButtonType> messageDialog(Node owner, String svgPath, boolean danger,
                                                    String title, String message) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(windowOf(owner));
        dialog.setTitle(title);
        dialog.setResizable(false);

        DialogPane pane = dialog.getDialogPane();
        Theme.applyTo(pane);
        pane.setHeaderText(null);

        StackPane tile = new StackPane(Icons.of(svgPath, 20, danger ? "icon-on-danger" : "icon-on-accent"));
        tile.getStyleClass().addAll("dialog-icon", danger ? "dialog-icon-danger" : "dialog-icon-accent");

        Label heading = new Label(title);
        heading.getStyleClass().add("dialog-title");
        heading.setWrapText(true);
        Label body = new Label(message);
        body.getStyleClass().add("dialog-message");
        body.setWrapText(true);
        body.setMinHeight(Region.USE_PREF_SIZE);

        VBox text = new VBox(6, heading, body);
        text.setMaxWidth(340);
        VBox content = new VBox(14, tile, text);
        content.getStyleClass().add("dialog-content");
        pane.setContent(content);
        return dialog;
    }

    static Window windowOf(Node node) {
        return node.getScene() == null ? null : node.getScene().getWindow();
    }

    // ---------- avatar ----------

    /** Round tile showing a name's initials, tinted from a hash of the name. */
    static final class Avatar extends StackPane {

        private final Label label = new Label();

        Avatar(double size) {
            label.getStyleClass().add("avatar-text");
            label.setStyle("-fx-font-size: " + Math.round(size * 0.38) + "px;");
            getChildren().add(label);
            getStyleClass().add("avatar");
            setMinSize(size, size);
            setPrefSize(size, size);
            setMaxSize(size, size);
        }

        void setName(String name) {
            String text = name == null ? "" : name.strip();
            label.setText(initials(text));
            int hue = Math.abs(text.toLowerCase(Locale.ROOT).hashCode() % 360);
            setStyle("-fx-background-color: hsb(" + hue + ", 45%, 96%);");
            label.setTextFill(javafx.scene.paint.Color.hsb(hue, 0.62, 0.52));
        }

        private static String initials(String name) {
            String[] words = name.split("[\\s._-]+");
            StringBuilder initials = new StringBuilder();
            for (String word : words) {
                if (!word.isBlank() && initials.length() < 2) {
                    initials.append(Character.toUpperCase(word.charAt(0)));
                }
            }
            return initials.isEmpty() ? "?" : initials.toString();
        }
    }
}
