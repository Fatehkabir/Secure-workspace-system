package com.examworkspace.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * In-app Google account chooser. Opens like other apps' "Continue with Google" sheet.
 * If a Desktop OAuth client ID is configured, {@link LoginView} uses the browser instead.
 */
final class GoogleConnectDialog extends Dialog<GoogleConnectDialog.Account> {

    record Account(String email, String name) {
    }

    GoogleConnectDialog(Node owner) {
        initOwner(Ui.windowOf(owner));
        setTitle("Continue with Google");
        setResizable(false);

        Theme.applyTo(getDialogPane());
        getDialogPane().setHeaderText(null);

        StackPane mark = new StackPane(new Label("G"));
        mark.getStyleClass().add("google-sheet-mark");

        Label title = new Label("Continue with Google");
        title.getStyleClass().add("dialog-title");
        Label body = new Label("Choose the Google account you teach with. "
                + "The next time you use this address, you come straight back into Exam Workspace.");
        body.getStyleClass().add("dialog-message");
        body.setWrapText(true);
        body.setMaxWidth(420);

        TextField email = new TextField();
        email.setPromptText("name@gmail.com");
        TextField name = new TextField();
        name.setPromptText("Your name");

        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        error.setVisible(false);
        error.managedProperty().bind(error.visibleProperty());

        VBox content = new VBox(16, mark, new VBox(6, title, body),
                Ui.field("Google email", email),
                Ui.field("Name", name),
                error);
        content.getStyleClass().add("dialog-content");
        content.setAlignment(Pos.CENTER_LEFT);
        getDialogPane().setContent(content);

        ButtonType continueType = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, continueType);
        Button confirm = (Button) getDialogPane().lookupButton(continueType);
        confirm.getStyleClass().add("pink");

        confirm.addEventFilter(ActionEvent.ACTION, event -> {
            String address = email.getText() == null ? "" : email.getText().strip().toLowerCase();
            if (!address.contains("@") || address.startsWith("@") || address.endsWith("@") || address.contains(" ")) {
                error.setText("Enter a Google email address, for example name@gmail.com.");
                error.setVisible(true);
                event.consume();
                return;
            }
            error.setVisible(false);
        });
        setResultConverter(button -> {
            if (button != continueType) {
                return null;
            }
            String address = email.getText().strip().toLowerCase();
            String display = name.getText() == null ? "" : name.getText().strip();
            if (display.isEmpty()) {
                display = address.substring(0, address.indexOf('@')).replace('.', ' ');
            }
            return new Account(address, display);
        });
    }
}
