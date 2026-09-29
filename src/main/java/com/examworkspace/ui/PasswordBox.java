package com.examworkspace.ui;

import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/** Password entry with a reveal toggle. The two fields share one text property. */
final class PasswordBox extends StackPane {

    private final PasswordField masked = new PasswordField();
    private final TextField revealed = new TextField();

    PasswordBox(String prompt) {
        masked.setPromptText(prompt);
        revealed.setPromptText(prompt);
        revealed.textProperty().bindBidirectional(masked.textProperty());
        masked.getStyleClass().add("with-affix");
        revealed.getStyleClass().add("with-affix");
        revealed.setVisible(false);
        revealed.setManaged(false);

        Region icon = Icons.of(Icons.EYE, 16);
        ToggleButton toggle = new ToggleButton();
        toggle.setGraphic(icon);
        toggle.getStyleClass().add("field-toggle");
        toggle.setFocusTraversable(false);
        toggle.setTooltip(new Tooltip("Show password"));
        toggle.selectedProperty().addListener((obs, was, showing) -> {
            icon.setStyle(Icons.shapeStyle(showing ? Icons.EYE_OFF : Icons.EYE));
            toggle.setTooltip(new Tooltip(showing ? "Hide password" : "Show password"));
            revealed.setVisible(showing);
            revealed.setManaged(showing);
            masked.setVisible(!showing);
            masked.setManaged(!showing);
            TextField focused = showing ? revealed : masked;
            focused.requestFocus();
            focused.end();
        });

        getChildren().addAll(masked, revealed, toggle);
        StackPane.setAlignment(toggle, Pos.CENTER_RIGHT);
        StackPane.setMargin(toggle, new Insets(0, 6, 0, 0));
    }

    String getText() {
        return masked.getText();
    }

    StringProperty textProperty() {
        return masked.textProperty();
    }

    void clear() {
        masked.clear();
    }

    void focusField() {
        (masked.isVisible() ? masked : revealed).requestFocus();
    }
}
