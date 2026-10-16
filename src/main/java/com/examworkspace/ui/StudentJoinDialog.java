package com.examworkspace.ui;

import com.examworkspace.model.JoinableRoom;
import com.examworkspace.model.Room;
import com.examworkspace.model.Student;
import com.examworkspace.room.RoomService;
import com.examworkspace.room.StudentRoomService;
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

/** Asks for roll number and room password so a student can join a selected exam room. */
final class StudentJoinDialog {

    private StudentJoinDialog() {
    }

    static Room ask(Node owner, StudentRoomService rooms, JoinableRoom selected, Student student) {
        TextField roll = new TextField();
        roll.setPromptText("e.g. 2024-CS-042");
        PasswordField password = new PasswordField();
        password.setPromptText("Room password from your teacher");

        Dialog<Room> dialog = new Dialog<>();
        dialog.initOwner(Ui.windowOf(owner));
        dialog.setTitle("Join room");
        dialog.setResizable(false);
        Theme.applyTo(dialog.getDialogPane());
        dialog.getDialogPane().setHeaderText(null);

        Label heading = new Label("Join " + selected.name());
        heading.getStyleClass().add("dialog-title");
        Label body = new Label("Enter your roll number and the password for room "
                + selected.roomCode() + ". Your teacher will see this roll on the roster.");
        body.getStyleClass().add("dialog-message");
        body.setWrapText(true);
        body.setMaxWidth(420);

        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        error.setVisible(false);
        error.managedProperty().bind(error.visibleProperty());

        VBox content = new VBox(16,
                new VBox(6, heading, body),
                new VBox(14, Ui.field("Roll number", roll), Ui.field("Room password", password)),
                error);
        content.getStyleClass().add("dialog-content");
        content.setAlignment(Pos.CENTER_LEFT);
        dialog.getDialogPane().setContent(content);

        ButtonType confirm = new ButtonType("Join room", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, confirm);
        Button confirmButton = (Button) dialog.getDialogPane().lookupButton(confirm);
        confirmButton.getStyleClass().add("primary");

        final Room[] value = new Room[1];
        confirmButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                value[0] = rooms.join(selected.id(), password.getText(), roll.getText(), student);
                error.setVisible(false);
            } catch (RoomService.RoomException ex) {
                error.setText(ex.getMessage());
                error.setVisible(true);
                password.clear();
                if (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("roll")) {
                    roll.requestFocus();
                } else {
                    password.requestFocus();
                }
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == confirm ? value[0] : null);
        return dialog.showAndWait().orElse(null);
    }
}
