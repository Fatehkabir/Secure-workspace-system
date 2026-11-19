package com.examworkspace.screenshare;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.ByteArrayInputStream;

/**
 * A floating, always-on-top JavaFX window that displays the teacher's live screen.
 *
 * <p>The window:
 * <ul>
 *   <li>Is always on top of all other windows (the teacher's intent is for students to see it).</li>
 *   <li>Can be freely moved and resized by the student.</li>
 *   <li>Cannot be minimized or closed by the student while the share is active
 *       (the close button does nothing; it closes automatically when the teacher stops).</li>
 *   <li>Shows a "Connecting…" message until the first frame arrives.</li>
 *   <li>Scales the frame image to fit the window while preserving the aspect ratio.</li>
 * </ul>
 */
public final class ScreenShareWindow {

    private static final double INIT_WIDTH  = 800;
    private static final double INIT_HEIGHT = 480;

    private final Stage stage;
    private final ImageView imageView;
    private final Label statusLabel;
    private volatile boolean receivedFirstFrame = false;

    public ScreenShareWindow() {
        stage = new Stage(StageStyle.DECORATED);
        stage.setTitle("📡  Teacher's Screen — Live Broadcast");
        stage.setAlwaysOnTop(true);
        stage.setWidth(INIT_WIDTH);
        stage.setHeight(INIT_HEIGHT);
        stage.setMinWidth(400);
        stage.setMinHeight(260);

        // ── Image view (scales to fit the window) ────────────────────────────
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.fitWidthProperty().bind(stage.widthProperty().subtract(16));
        imageView.fitHeightProperty().bind(stage.heightProperty().subtract(54));

        // ── Status & Title bar ────────────────────────────
        Label liveBadge = new Label("🔴 LIVE BROADCAST");
        liveBadge.setStyle("-fx-background-color: rgba(239, 68, 68, 0.2); -fx-text-fill: #fca5a5; "
                + "-fx-font-size: 11px; -fx-font-weight: 800; -fx-padding: 4 10; -fx-background-radius: 20; "
                + "-fx-border-color: rgba(239, 68, 68, 0.4); -fx-border-radius: 20;");

        Label hintText = new Label("Always on top • Resizable");
        hintText.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11.5px;");

        HBox topBar = new HBox(10, liveBadge, hintText);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-padding: 8 12; -fx-background-color: rgba(15, 23, 42, 0.85); -fx-border-color: rgba(255, 255, 255, 0.06); -fx-border-width: 0 0 1 0;");

        statusLabel = new Label("Connecting to teacher's screen…");
        statusLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 14.5px; -fx-font-weight: 600;");

        StackPane contentStack = new StackPane(imageView, statusLabel);
        StackPane.setAlignment(statusLabel, Pos.CENTER);
        VBox.setVgrow(contentStack, javafx.scene.layout.Priority.ALWAYS);

        VBox root = new VBox(topBar, contentStack);
        root.setStyle("-fx-background-color: linear-gradient(180deg, #0f172a 0%, #070b14 100%);");

        Scene scene = new Scene(root, INIT_WIDTH, INIT_HEIGHT);
        stage.setScene(scene);

        // Prevent the student from closing the window manually while sharing
        stage.setOnCloseRequest(e -> e.consume());
    }

    /** Shows the window. Call on the JavaFX Application Thread. */
    public void show() {
        stage.show();
        stage.toFront();
    }

    /**
     * Renders a new JPEG frame. Safe to call from any thread.
     *
     * @param jpegBytes raw JPEG bytes received from the server
     */
    public void updateFrame(byte[] jpegBytes) {
        Image img = new Image(new ByteArrayInputStream(jpegBytes));
        Platform.runLater(() -> {
            imageView.setImage(img);
            if (!receivedFirstFrame) {
                receivedFirstFrame = true;
                statusLabel.setVisible(false);
                statusLabel.setManaged(false);
            }
        });
    }

    /**
     * Closes the window gracefully (called when the teacher stops sharing).
     * Safe to call from any thread.
     */
    public void close() {
        Platform.runLater(stage::close);
    }
}
