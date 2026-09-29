package com.examworkspace.ui;

import com.examworkspace.ExamWorkspaceApp;
import com.examworkspace.model.Teacher;
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

/** Frame shared by the signed-in screens: a fixed sidebar on the left and a page on the right. */
abstract class AppShell extends BorderPane {

    protected AppShell(ExamWorkspaceApp app, Teacher teacher, String activeRoom) {
        getStyleClass().add("dash-shell");
        StackPane frame = new StackPane(sidebar(app, teacher, activeRoom));
        frame.getStyleClass().add("dash-sidebar-frame");
        setLeft(frame);
    }

    /** Places the page header and its scrolling body in the content area. */
    protected void setPage(Node header, Node body) {
        VBox page = new VBox(18, header, body);
        page.getStyleClass().addAll("page", "dash-main");
        VBox.setVgrow(body, Priority.ALWAYS);
        setCenter(page);
    }

    private static VBox sidebar(ExamWorkspaceApp app, Teacher teacher, String activeRoom) {
        StackPane mark = new StackPane(Icons.of(Icons.LOGO, 20, "icon-on-accent"));
        mark.getStyleClass().add("brand-mark");
        Label brandName = new Label("Exam Workspace");
        brandName.getStyleClass().add("brand-title");
        Label brandRole = new Label("TEACHER CONSOLE");
        brandRole.getStyleClass().add("brand-kicker");
        HBox brand = new HBox(11, mark, new VBox(1, brandName, brandRole));
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("brand");

        Label section = new Label("WORKSPACE");
        section.getStyleClass().add("side-section");
        Button allRooms = navItem(Icons.DASHBOARD, "Exam Rooms", activeRoom == null);
        allRooms.setOnAction(e -> app.showDashboard(teacher));

        VBox nav = new VBox(4, section, allRooms);
        if (activeRoom != null) {
            Button current = navItem(Icons.APPS, activeRoom, true);
            current.setMouseTransparent(true);
            nav.getChildren().add(current);
        }

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);

        Ui.Avatar avatar = new Ui.Avatar(36);
        avatar.setName(teacher.fullName());
        Label name = new Label(teacher.fullName());
        name.getStyleClass().add("user-name");
        Label handle = new Label("@" + teacher.username());
        handle.getStyleClass().add("user-handle");
        VBox identity = new VBox(1, name, handle);
        identity.setMinWidth(0);
        HBox.setHgrow(identity, Priority.ALWAYS);

        Button logout = Ui.iconButton(Icons.LOGOUT, "Log out");
        logout.getStyleClass().add("icon-button-on-dark");
        logout.setOnAction(e -> app.showLogin());

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
