package com.examworkspace.ui;

import com.examworkspace.ExamWorkspaceApp;
import com.examworkspace.auth.AuthService;
import com.examworkspace.auth.StudentAuthService;
import com.examworkspace.model.Student;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class StudentLoginView extends HBox {

    private final StudentAuthService auth = new StudentAuthService();

    public StudentLoginView(ExamWorkspaceApp app) {
        getStyleClass().add("auth-root");

        Label kicker = new Label("STUDENT");
        kicker.getStyleClass().add("dash-kicker");
        Label title = new Label("Sign in");
        title.getStyleClass().add("form-title");
        Label subtitle = new Label("Sign in with your student username and password.");
        subtitle.getStyleClass().add("form-subtitle");
        subtitle.setWrapText(true);

        TextField username = new TextField();
        username.setPromptText("student.name");
        PasswordBox password = new PasswordBox("Your password");

        Label errorText = new Label();
        HBox banner = Ui.banner(errorText);

        Button signIn = new Button("Sign in");
        signIn.setDefaultButton(true);
        signIn.setMaxWidth(Double.MAX_VALUE);
        signIn.getStyleClass().addAll("primary", "lg");
        Motion.press(signIn);

        Hyperlink register = new Hyperlink("Create one");
        register.setOnAction(e -> app.showStudentRegister());
        HBox footer = new HBox(2, new Label("No account yet?"), register);
        footer.setAlignment(Pos.CENTER);
        footer.getStyleClass().add("form-footer");

        Hyperlink back = new Hyperlink("Back");
        back.setOnAction(e -> app.showStudentEntry());
        HBox backRow = new HBox(back);
        backRow.setAlignment(Pos.CENTER);
        backRow.getStyleClass().add("form-footer");

        signIn.setOnAction(e -> {
            banner.setVisible(false);
            signIn.setDisable(true);
            signIn.setText("Signing in\u2026");
            String user = username.getText();
            char[] secret = password.getText().toCharArray();
            Ui.<Student>runAsync(() -> auth.login(user, secret), app::showStudentDashboard, ex -> {
                signIn.setDisable(false);
                signIn.setText("Sign in");
                password.clear();
                password.focusField();
                Ui.showBanner(banner, errorText, ex instanceof AuthService.AuthException
                        ? ex.getMessage()
                        : "Sign in failed: " + ex.getMessage());
            });
        });

        VBox form = new VBox(18,
                new VBox(6, kicker, title, subtitle),
                new VBox(14, Ui.field("Username", username), Ui.field("Password", password)),
                banner,
                signIn,
                footer,
                backRow);
        AuthLayout.fill(this, form);
        Platform.runLater(username::requestFocus);
    }
}
