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

/** Asks for username/name, optional roll number, and room password so a student can join an exam room. */
final class StudentJoinDialog {

    public record JoinResult(Room room, Student student) {}

    private StudentJoinDialog() {
    }

    static JoinResult ask(Node owner, StudentRoomService rooms, JoinableRoom selected, Student student) {
        TextField nameField = new TextField();
        nameField.setPromptText("Your name or username");
        boolean isRegisteredAccount = student != null && student.id() > 0;
        if (student != null && !student.fullName().isBlank()) {
            nameField.setText(student.fullName());
            if (isRegisteredAccount) {
                nameField.setDisable(true);
            }
        }

        TextField roll = new TextField();
        roll.setPromptText("Optional (defaults to your name)");
        PasswordField password = new PasswordField();
        password.setPromptText("Room password from your teacher");

        Dialog<JoinResult> dialog = new Dialog<>();
        dialog.initOwner(Ui.windowOf(owner));
        dialog.setTitle("Join room");
        dialog.setResizable(false);
        Theme.applyTo(dialog.getDialogPane());
        dialog.getDialogPane().setHeaderText(null);

        Label heading = new Label("Join " + selected.name());
        heading.getStyleClass().add("dialog-title");
        Label body = new Label("Enter your name/username and the password for room "
                + selected.roomCode() + ".");
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
                new VBox(14,
                        Ui.field("Your Name / Username", nameField),
                        Ui.field("Roll number (optional)", roll),
                        Ui.field("Room password", password)),
                error);
        content.getStyleClass().add("dialog-content");
        content.setAlignment(Pos.CENTER_LEFT);
        dialog.getDialogPane().setContent(content);

        ButtonType confirm = new ButtonType("Join room", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, confirm);
        Button confirmButton = (Button) dialog.getDialogPane().lookupButton(confirm);
        confirmButton.getStyleClass().add("primary");

        final JoinResult[] value = new JoinResult[1];
        confirmButton.addEventFilter(ActionEvent.ACTION, event -> {
            String enteredName = nameField.getText() == null ? "" : nameField.getText().strip();
            if (enteredName.isEmpty()) {
                error.setText("Enter your name / username.");
                error.setVisible(true);
                nameField.requestFocus();
                event.consume();
                return;
            }
            String enteredRoll = roll.getText() == null ? "" : roll.getText().strip();
            if (enteredRoll.isEmpty()) {
                enteredRoll = enteredName;
            }
            try {
                Student activeStudent = isRegisteredAccount
                        ? student
                        : new Student(0L, enteredName, enteredName);
                Room joined = rooms.join(selected.id(), password.getText(), enteredRoll, enteredName, activeStudent);
                value[0] = new JoinResult(joined, activeStudent);
                error.setVisible(false);
            } catch (RoomService.RoomException ex) {
                error.setText(ex.getMessage());
                error.setVisible(true);
                password.clear();
                if (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("name")) {
                    nameField.requestFocus();
                } else if (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("roll")) {
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
