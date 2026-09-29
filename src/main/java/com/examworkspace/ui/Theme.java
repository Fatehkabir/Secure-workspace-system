package com.examworkspace.ui;

import java.util.Objects;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;

public final class Theme {

    private static final String STYLESHEET = Objects
            .requireNonNull(Theme.class.getResource("/com/examworkspace/app.css"), "app.css is missing")
            .toExternalForm();

    private Theme() {
    }

    public static void applyTo(Scene scene) {
        if (!scene.getStylesheets().contains(STYLESHEET)) {
            scene.getStylesheets().add(STYLESHEET);
        }
    }

    /** Dialogs live in their own scene, so they need the stylesheet applied separately. */
    static void applyTo(DialogPane pane) {
        if (!pane.getStylesheets().contains(STYLESHEET)) {
            pane.getStylesheets().add(STYLESHEET);
        }
        pane.getStyleClass().add("app-dialog");
    }
}
