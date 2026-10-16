package com.examworkspace.ui;

import com.examworkspace.ExamWorkspaceApp;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Student entry after "Join as a student": guest, sign in, or create account. */
public class StudentEntryView extends HBox {

    public StudentEntryView(ExamWorkspaceApp app) {
        getStyleClass().add("auth-root");

        Label kicker = new Label("STUDENT");
        kicker.getStyleClass().add("dash-kicker");
        Label title = new Label("Join an exam");
        title.getStyleClass().add("form-title");
        Label subtitle = new Label(
                "Continue as a guest, or sign in with a student account. You will enter your roll number when joining a room.");
        subtitle.getStyleClass().add("form-subtitle");
        subtitle.setWrapText(true);

        Button guest = new Button("Continue as guest");
        guest.setMaxWidth(Double.MAX_VALUE);
        guest.getStyleClass().addAll("pink", "lg");
        Motion.press(guest);
        guest.setOnAction(e -> app.showStudentDashboard(null));

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

        Button signIn = new Button("Sign in");
        signIn.setMaxWidth(Double.MAX_VALUE);
        signIn.getStyleClass().addAll("primary", "lg");
        Motion.press(signIn);
        signIn.setOnAction(e -> app.showStudentLogin());

        Button create = new Button("Create account");
        create.setMaxWidth(Double.MAX_VALUE);
        create.getStyleClass().addAll("quiet", "lg");
        Motion.press(create);
        create.setOnAction(e -> app.showStudentRegister());

        Hyperlink back = new Hyperlink("Back to teacher sign in");
        back.setOnAction(e -> app.showLogin());
        HBox footer = new HBox(back);
        footer.setAlignment(Pos.CENTER);
        footer.getStyleClass().add("form-footer");

        VBox form = new VBox(18,
                new VBox(6, kicker, title, subtitle),
                guest,
                divider,
                signIn,
                create,
                footer);
        AuthLayout.fill(this, form);
    }
}
