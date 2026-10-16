package com.examworkspace.ui;

import com.examworkspace.ExamWorkspaceApp;
import com.examworkspace.model.Student;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Student counterpart to {@link AppShell}: same layout tokens, student branding and navigation. */
abstract class StudentShell extends BorderPane {

    protected StudentShell(ExamWorkspaceApp app, Student student, String activeRoom) {
        getStyleClass().add("dash-shell");
        StackPane frame = new StackPane(sidebar(app, student, activeRoom));
        frame.getStyleClass().add("dash-sidebar-frame");
        setLeft(frame);
    }

    protected void setPage(Node header, Node body) {
        VBox page = new VBox(18, header, body);
        page.getStyleClass().addAll("page", "dash-main");
        VBox.setVgrow(body, Priority.ALWAYS);
        setCenter(page);
    }

    private static VBox sidebar(ExamWorkspaceApp app, Student student, String activeRoom) {
        StackPane mark = new StackPane(Icons.of(Icons.LOGO, 20, "icon-on-accent"));
        mark.getStyleClass().add("brand-mark");
        Label brandName = new Label("Exam Workspace");
        brandName.getStyleClass().add("brand-title");
        Label brandRole = new Label("STUDENT");
        brandRole.getStyleClass().add("brand-kicker");
        HBox brand = new HBox(11, mark, new VBox(1, brandName, brandRole));
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("brand");

        Label section = new Label("WORKSPACE");
        section.getStyleClass().add("side-section");
        Button available = navItem(Icons.DASHBOARD, "Available rooms", activeRoom == null);
        available.setOnAction(e -> app.showStudentDashboard(student));

        VBox nav = new VBox(4, section, available);
        if (activeRoom != null) {
            Button current = navItem(Icons.APPS, activeRoom, true);
            current.setMouseTransparent(true);
            nav.getChildren().add(current);
        }

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);

        String displayName = student != null ? student.fullName() : "Guest student";
        String handleText = student != null ? "@" + student.username() : "Guest session";
        Ui.Avatar avatar = new Ui.Avatar(36);
        avatar.setName(displayName);
        Label name = new Label(displayName);
        name.getStyleClass().add("user-name");
        Label handle = new Label(handleText);
        handle.getStyleClass().add("user-handle");
        VBox identity = new VBox(1, name, handle);
        identity.setMinWidth(0);
        HBox.setHgrow(identity, Priority.ALWAYS);

        Button logout = Ui.iconButton(Icons.LOGOUT, "Log out");
        logout.getStyleClass().add("icon-button-on-dark");
        logout.setOnAction(e -> app.showStudentEntry());

        HBox userCard = new HBox(10, avatar, identity, logout);
        userCard.setAlignment(Pos.CENTER_LEFT);
        userCard.getStyleClass().add("side-user");

        VBox sidebar = new VBox(16, brand, nav, grow, userCard);
        sidebar.getStyleClass().add("dash-sidebar");
        return sidebar;
    }

    private static Button navItem(String svgPath, String text, boolean active) {
        Button item = new Button(text, Icons.of(svgPath, 17));
        item.getStyleClass().add(active ? "side-nav-active" : "quiet");
        item.setMaxWidth(Double.MAX_VALUE);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setMinWidth(0);
        return item;
    }
}
