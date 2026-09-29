package com.examworkspace.ui;

import com.examworkspace.ExamWorkspaceApp;
import com.examworkspace.auth.AuthService;
import com.examworkspace.auth.GoogleSignIn;
import com.examworkspace.model.Teacher;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class LoginView extends HBox {

    private final AuthService auth = new AuthService();

    public LoginView(ExamWorkspaceApp app) {
        getStyleClass().add("auth-root");

        Label kicker = new Label("TEACHER CONSOLE");
        kicker.getStyleClass().add("dash-kicker");
        Label title = new Label("Sign in");
        title.getStyleClass().add("form-title");
        Label subtitle = new Label("Welcome back. Continue with your teacher account or Google.");
        subtitle.getStyleClass().add("form-subtitle");
        subtitle.setWrapText(true);

        TextField username = new TextField();
        username.setPromptText("teacher.name");
        PasswordBox password = new PasswordBox("Your password");

        Label errorText = new Label();
        HBox banner = Ui.banner(errorText);

        Button signIn = new Button("Sign in");
        signIn.setDefaultButton(true);
        signIn.setMaxWidth(Double.MAX_VALUE);
        signIn.getStyleClass().addAll("primary", "lg");
        Motion.press(signIn);

        Label or = new Label("OR");
        or.getStyleClass().add("or-label");
        Region lineLeft = new Region();
        Region lineRight = new Region();
        lineLeft.getStyleClass().add("or-line");
        lineRight.getStyleClass().add("or-line");
        HBox.setHgrow(lineLeft, Priority.ALWAYS);
        HBox.setHgrow(lineRight, Priority.ALWAYS);
        HBox divider = new HBox(10, lineLeft, or, lineRight);
        divider.setAlignment(Pos.CENTER);

        Label googleMark = new Label("G");
        googleMark.getStyleClass().add("google-mark");
        Button google = new Button("Sign in with Google", googleMark);
        google.setMaxWidth(Double.MAX_VALUE);
        google.getStyleClass().addAll("google-button", "lg");
        Motion.press(google);
        google.setOnAction(e -> startGoogle(app, banner, errorText, signIn, google));

        Hyperlink register = new Hyperlink("Create one");
        register.setOnAction(e -> app.showRegister());
        HBox footer = new HBox(2, new Label("No account yet?"), register);
        footer.setAlignment(Pos.CENTER);
        footer.getStyleClass().add("form-footer");

        signIn.setOnAction(e -> {
            banner.setVisible(false);
            signIn.setDisable(true);
            signIn.setText("Signing in\u2026");
            String user = username.getText();
            char[] secret = password.getText().toCharArray();
            Ui.<Teacher>runAsync(() -> auth.login(user, secret), app::showDashboard, ex -> {
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
                divider,
                google,
                footer);
        AuthLayout.fill(this, form);
        Platform.runLater(username::requestFocus);
    }

    private void startGoogle(ExamWorkspaceApp app, HBox banner, Label errorText, Button... busy) {
        banner.setVisible(false);
        String clientId = GoogleSignIn.savedClientId();
        if (clientId != null) {
            runGoogle(app, banner, errorText, () -> {
                GoogleSignIn.Profile profile = GoogleSignIn.signIn(clientId);
                return auth.loginWithGoogle(profile.subject(), profile.email(), profile.name());
            }, busy);
            return;
        }
        GoogleConnectDialog.Account account = new GoogleConnectDialog(this).showAndWait().orElse(null);
        if (account == null) {
            return;
        }
        runGoogle(app, banner, errorText,
                () -> auth.loginWithGoogle(account.email(), account.email(), account.name()),
                busy);
    }

    private void runGoogle(ExamWorkspaceApp app, HBox banner, Label errorText,
                           java.util.concurrent.Callable<Teacher> work, Button... busy) {
        for (Button button : busy) {
            button.setDisable(true);
        }
        Ui.<Teacher>runAsync(work, app::showDashboard, ex -> {
            for (Button button : busy) {
                button.setDisable(false);
            }
            String message = ex instanceof AuthService.AuthException || ex instanceof GoogleSignIn.GoogleSignInException
                    ? ex.getMessage()
                    : "Google sign-in failed: " + ex.getMessage();
            Ui.showBanner(banner, errorText, message);
        });
    }
}
