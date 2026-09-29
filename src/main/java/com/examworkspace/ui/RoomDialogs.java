package com.examworkspace.ui;

import com.examworkspace.model.Room;
import com.examworkspace.room.RoomService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

final class RoomDialogs {

    record Schedule(Instant start, Instant end) {
    }

    private RoomDialogs() {
    }

    /**
     * Opens the create-room form. The room is saved inside this dialog, so a duplicate room ID
     * shows an alert and leaves the form open.
     */
    static Room create(Node owner, RoomService rooms) {
        TextField name = new TextField();
        name.setPromptText("Physics midterm");
        TextField roomId = new TextField();
        roomId.setPromptText("e.g. PHY-101");
        PasswordField password = new PasswordField();
        password.setPromptText("At least " + RoomService.MIN_ROOM_PASSWORD + " characters");
        DateTimeFields start = new DateTimeFields();
        DateTimeFields end = new DateTimeFields();
        Instant suggested = Instant.now().plus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
        start.setInstant(suggested);
        end.setInstant(suggested.plus(2, ChronoUnit.HOURS));

        return ask(owner, "New room", "Create room",
                "Choose a room ID that is not already used. The password is only visible to you "
                        + "and does not have to be unique.",
                new VBox(14,
                        Ui.field("Room name", name),
                        Ui.field("Room ID", roomId),
                        Ui.field("Room password", password),
                        Ui.field("Starts", start),
                        Ui.field("Ends", end)),
                () -> {
                    if (name.getText() == null || name.getText().isBlank()) {
                        throw new RoomService.RoomException("Room name cannot be empty.");
                    }
                    RoomService.checkRoomCode(roomId.getText());
                    RoomService.checkPassword(password.getText());
                    RoomService.checkSchedule(start.toInstant(), end.toInstant(), null);
                    return rooms.createRoom(name.getText(), roomId.getText(), password.getText(),
                            start.toInstant(), end.toInstant());
                });
    }

    static Schedule schedule(Node owner, Instant currentStart, Instant currentEnd) {
        DateTimeFields start = new DateTimeFields();
        DateTimeFields end = new DateTimeFields();
        start.setInstant(currentStart);
        end.setInstant(currentEnd);
        return ask(owner, "Edit schedule", "Save schedule",
                "Start and end have to be in the future. A room that has already started can keep its current start.",
                new VBox(14, Ui.field("Starts", start), Ui.field("Ends", end)),
                () -> {
                    RoomService.checkSchedule(start.toInstant(), end.toInstant(), currentStart);
                    return new Schedule(start.toInstant(), end.toInstant());
                });
    }

    static String password(Node owner) {
        PasswordField password = new PasswordField();
        password.setPromptText("At least " + RoomService.MIN_ROOM_PASSWORD + " characters");
        PasswordField confirm = new PasswordField();
        confirm.setPromptText("Repeat the password");
        return ask(owner, "Change room password", "Save password",
                "Only you can see this password. It does not have to be unique.",
                new VBox(14, Ui.field("New password", password), Ui.field("Confirm password", confirm)),
                () -> {
                    if (!password.getText().equals(confirm.getText())) {
                        throw new RoomService.RoomException("Those passwords do not match.");
                    }
                    RoomService.checkPassword(password.getText());
                    return password.getText();
                });
    }

    private static <T> T ask(Node owner, String title, String confirmText, String message, VBox fields,
                             Attempt<T> read) {
        Dialog<T> dialog = new Dialog<>();
        dialog.initOwner(Ui.windowOf(owner));
        dialog.setTitle(title);
        dialog.setResizable(false);
        Theme.applyTo(dialog.getDialogPane());
        dialog.getDialogPane().setHeaderText(null);

        Label heading = new Label(title);
        heading.getStyleClass().add("dialog-title");
        Label body = new Label(message);
        body.getStyleClass().add("dialog-message");
        body.setWrapText(true);
        body.setMaxWidth(420);

        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        error.setVisible(false);
        error.managedProperty().bind(error.visibleProperty());

        VBox content = new VBox(16, new VBox(6, heading, body), fields, error);
        content.getStyleClass().add("dialog-content");
        content.setAlignment(Pos.CENTER_LEFT);
        dialog.getDialogPane().setContent(content);

        ButtonType confirm = new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, confirm);
        Button confirmButton = (Button) dialog.getDialogPane().lookupButton(confirm);
        confirmButton.getStyleClass().add("primary");

        final Object[] value = new Object[1];
        confirmButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                value[0] = read.get();
                error.setVisible(false);
            } catch (RoomService.RoomException ex) {
                error.setText(ex.getMessage());
                error.setVisible(true);
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == confirm ? cast(value[0]) : null);
        return dialog.showAndWait().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    @FunctionalInterface
    private interface Attempt<T> {
        T get() throws RoomService.RoomException;
    }
}
