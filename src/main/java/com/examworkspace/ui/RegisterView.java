package com.examworkspace.ui;

import com.examworkspace.ExamWorkspaceApp;
import com.examworkspace.auth.AuthService;
import com.examworkspace.model.Teacher;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class RegisterView extends HBox {

    private final AuthService auth = new AuthService();

    public RegisterView(ExamWorkspaceApp app) {
        getStyleClass().add("auth-root");

        Label kicker = new Label("TEACHER CONSOLE");
        kicker.getStyleClass().add("dash-kicker");
        Label title = new Label("Create account");
        title.getStyleClass().add("form-title");
        Label subtitle = new Label("Your rooms, IDs and passwords stay private to this account.");
        subtitle.getStyleClass().add("form-subtitle");
        subtitle.setWrapText(true);

        TextField fullName = new TextField();
        fullName.setPromptText("Jane Smith");
        TextField username = new TextField();
        username.setPromptText("teacher.name");
        PasswordBox password = new PasswordBox("At least " + AuthService.MIN_PASSWORD_LENGTH + " characters");
        PasswordBox confirm = new PasswordBox("Repeat your password");

        Label errorText = new Label();
        HBox banner = Ui.banner(errorText);

        Button create = new Button("Create account");
        create.setDefaultButton(true);
        create.setMaxWidth(Double.MAX_VALUE);
        create.getStyleClass().addAll("primary", "lg");
        Motion.press(create);

        Hyperlink back = new Hyperlink("Sign in");
        back.setOnAction(e -> app.showLogin());
        HBox footer = new HBox(2, new Label("Already registered?"), back);
        footer.setAlignment(Pos.CENTER);
        footer.getStyleClass().add("form-footer");

        create.setOnAction(e -> {
            banner.setVisible(false);
            if (!password.getText().equals(confirm.getText())) {
                Ui.showBanner(banner, errorText, "Those passwords do not match.");
                return;
            }
            create.setDisable(true);
            create.setText("Creating account\u2026");
            String name = fullName.getText();
            String user = username.getText();
            char[] secret = password.getText().toCharArray();
            Ui.<Teacher>runAsync(() -> auth.register(user, name, secret), app::showDashboard, ex -> {
                create.setDisable(false);
                create.setText("Create account");
                Ui.showBanner(banner, errorText, ex instanceof AuthService.AuthException
                        ? ex.getMessage()
                        : "Registration failed: " + ex.getMessage());
            });
        });

        VBox form = new VBox(18,
                new VBox(6, kicker, title, subtitle),
                new VBox(14,
                        Ui.field("Full name", fullName),
                        Ui.field("Username", username),
                        Ui.field("Password", password),
                        Ui.field("Confirm password", confirm)),
                banner,
                create,
                footer);
        AuthLayout.fill(this, form);
        Platform.runLater(fullName::requestFocus);
    }
}
