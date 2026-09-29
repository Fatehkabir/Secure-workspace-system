package com.examworkspace.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Split screen used by sign in and sign up: a brand panel on the left, the form on the right. */
final class AuthLayout {

    private AuthLayout() {
    }

    static void fill(HBox root, VBox form) {
        root.getStyleClass().add("auth-root");
        form.getStyleClass().add("auth-card");
        root.setSpacing(20);
        form.setMaxWidth(400);
        form.setMaxHeight(Region.USE_PREF_SIZE);

        StackPane formArea = new StackPane(form);
        formArea.getStyleClass().add("auth-form-area");
        HBox.setHgrow(formArea, Priority.ALWAYS);

        root.getChildren().setAll(hero(), formArea);
    }

    private static VBox hero() {
        StackPane mark = new StackPane(Icons.of(Icons.LOGO, 22, "icon-on-accent"));
        mark.getStyleClass().add("brand-mark");
        Label name = new Label("Exam Workspace");
        name.getStyleClass().add("brand-title");
        Label role = new Label("Teacher console");
        role.getStyleClass().add("brand-subtitle");
        HBox brand = new HBox(12, mark, new VBox(1, name, role));
        brand.setAlignment(Pos.CENTER_LEFT);

        Label headline = new Label("Set up the apps\nfor every exam.");
        headline.getStyleClass().add("hero-headline");
        Label blurb = new Label(
                "Create a room, give it a schedule, then choose exactly which programs on this computer students may open.");
        blurb.getStyleClass().add("hero-blurb");
        blurb.setWrapText(true);
        blurb.setMaxWidth(320);

        VBox features = new VBox(15,
                feature(Icons.ROOM, "Exam rooms", "One room per exam, renamed or removed any time."),
                feature(Icons.MONITOR, "Apps from this PC", "Pick from everything installed on this computer."),
                feature(Icons.LOCK, "Private to you", "Each teacher only ever sees their own rooms."));

        Label footnote = new Label("Everything stays on this computer.");
        footnote.getStyleClass().add("hero-footnote");

        Region topGap = new Region();
        VBox.setVgrow(topGap, Priority.ALWAYS);
        Region bottomGap = new Region();
        VBox.setVgrow(bottomGap, Priority.ALWAYS);

        VBox hero = new VBox(14, brand, topGap, headline, blurb, features, bottomGap, footnote);
        hero.getStyleClass().add("auth-hero");
        hero.setMaxHeight(Double.MAX_VALUE);
        return hero;
    }

    private static HBox feature(String svgPath, String title, String description) {
        StackPane tile = new StackPane(Icons.of(svgPath, 17, "icon-on-hero"));
        tile.getStyleClass().add("hero-feature-icon");

        Label heading = new Label(title);
        heading.getStyleClass().add("hero-feature-title");
        Label body = new Label(description);
        body.getStyleClass().add("hero-feature-text");
        body.setWrapText(true);

        VBox text = new VBox(2, heading, body);
        text.setMaxWidth(250);
        HBox row = new HBox(12, tile, text);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }
}
