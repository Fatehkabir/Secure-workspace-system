package com.examworkspace.ui;

import com.examworkspace.model.AppEntry;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

/** App icon when one can be extracted, otherwise the app's initials. */
final class AppGlyph extends StackPane {

    private final ImageView image = new ImageView();
    private final Label initials = new Label();
    private AppEntry app;

    AppGlyph(double size) {
        image.setFitWidth(size);
        image.setFitHeight(size);
        image.setPreserveRatio(true);
        image.setSmooth(true);
        Rectangle clip = new Rectangle(size, size);
        clip.setArcWidth(size * 0.35);
        clip.setArcHeight(size * 0.35);
        image.setClip(clip);

        initials.getStyleClass().add("glyph-initials");
        initials.setStyle("-fx-font-size: " + Math.max(10, Math.round(size * 0.34)) + "px;");
        setAlignment(Pos.CENTER);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        getStyleClass().add("app-glyph");
        getChildren().addAll(initials, image);
        image.setVisible(false);
        AppIconCache.shared().subscribe(() -> {
            if (app != null) {
                show(AppIconCache.shared().get(app.launchTarget()));
            }
        });
    }

    void setApp(AppEntry entry) {
        app = entry;
        String name = entry == null ? "" : entry.name();
        initials.setText(initialsOf(name));
        Image icon = entry == null ? null : AppIconCache.shared().get(entry.launchTarget());
        show(icon);
        if (entry != null && icon == null) {
            AppIconCache.shared().request(entry);
        }
    }

    private void show(Image icon) {
        boolean found = icon != null && !icon.isError();
        image.setImage(found ? icon : null);
        image.setVisible(found);
        initials.setVisible(!found);
    }

    private static String initialsOf(String name) {
        StringBuilder initials = new StringBuilder();
        for (String word : name.split("[\\s._-]+")) {
            if (!word.isBlank() && initials.length() < 2) {
                initials.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        return initials.isEmpty() ? "?" : initials.toString();
    }
}
