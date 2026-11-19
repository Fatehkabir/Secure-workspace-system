package com.examworkspace.ui;

import com.examworkspace.db.Database;
import com.examworkspace.net.LanDiscoveryService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Dialog to view or configure the database server connection.
 * Supports auto-detecting the Teacher PC on the Wi-Fi/LAN,
 * or entering a Device Name / IP manually.
 */
public final class ServerConfigDialog {

    private ServerConfigDialog() {}

    public static void show(Node owner, Runnable onConnected) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initOwner(Ui.windowOf(owner));
        dialog.setTitle("Server Connection Settings");
        dialog.setResizable(false);
        Theme.applyTo(dialog.getDialogPane());

        Label title = new Label("Exam Server Connection");
        title.getStyleClass().add("dialog-title");
        Label subtitle = new Label("Connect to the Teacher's PC over your local network (Wi-Fi or Ethernet).");
        subtitle.getStyleClass().add("dialog-message");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(460);

        Label currentLabel = new Label("Current: " + Database.getServerDisplayName());
        currentLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #0ea5e9;");

        TextField hostField = new TextField();
        hostField.setPromptText("Teacher PC Device Name (e.g. FATEH) or IP");
        hostField.setText(LanDiscoveryService.getLocalDeviceName());

        TextField portField = new TextField("5432");
        portField.setPromptText("5432");
        portField.setPrefWidth(90);

        TextField userField = new TextField("examuser");
        PasswordField passField = new PasswordField();
        passField.setText("StrongPassword123!");

        Label statusMsg = new Label();
        statusMsg.setWrapText(true);
        statusMsg.setMaxWidth(460);

        Button autoDetectBtn = new Button("🔍 Auto-Detect on Wi-Fi");
        autoDetectBtn.getStyleClass().addAll("quiet", "sm");

        autoDetectBtn.setOnAction(e -> {
            autoDetectBtn.setDisable(true);
            statusMsg.setText("Scanning local network for Teacher PC...");
            statusMsg.setStyle("-fx-text-fill: #94a3b8;");

            Ui.runAsync(
                () -> LanDiscoveryService.discover(2000),
                discovered -> {
                    autoDetectBtn.setDisable(false);
                    if (discovered != null) {
                        hostField.setText(discovered.hostName());
                        portField.setText(String.valueOf(discovered.port()));
                        statusMsg.setText("Found Teacher PC: " + discovered.hostName() + " (" + discovered.ipAddress() + ")!");
                        statusMsg.setStyle("-fx-text-fill: #10b981; -fx-font-weight: bold;");
                    } else {
                        statusMsg.setText("No teacher broadcast found. Ensure Teacher app is open on the same Wi-Fi.");
                        statusMsg.setStyle("-fx-text-fill: #f59e0b;");
                    }
                },
                err -> {
                    autoDetectBtn.setDisable(false);
                    statusMsg.setText("Auto-detect failed: " + err.getMessage());
                    statusMsg.setStyle("-fx-text-fill: #ef4444;");
                }
            );
        });

        HBox hostPortBox = new HBox(8, hostField, new Label(":"), portField);
        HBox.setHgrow(hostField, Priority.ALWAYS);
        hostPortBox.setAlignment(Pos.CENTER_LEFT);

        VBox form = new VBox(12,
                title,
                subtitle,
                currentLabel,
                new HBox(autoDetectBtn),
                Ui.field("Teacher PC Name or IP", hostPortBox),
                Ui.field("Database User", userField),
                Ui.field("Database Password", passField),
                statusMsg
        );
        form.setPrefWidth(460);

        dialog.getDialogPane().setContent(form);

        ButtonType connectType = new ButtonType("Connect & Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(connectType, cancelType);

        Button connectBtn = (Button) dialog.getDialogPane().lookupButton(connectType);
        connectBtn.getStyleClass().addAll("primary");

        connectBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume(); // prevent dialog close until verified
            String host = hostField.getText() == null ? "" : hostField.getText().trim();
            if (host.isEmpty()) {
                statusMsg.setText("Please enter a device name or IP.");
                statusMsg.setStyle("-fx-text-fill: #ef4444;");
                return;
            }
            int parsedPort;
            try {
                parsedPort = Integer.parseInt(portField.getText().trim());
            } catch (Exception ex) {
                parsedPort = 5432;
            }
            final int port = parsedPort;
            String user = userField.getText().trim();
            String pass = passField.getText();
            String url = "jdbc:postgresql://" + host + ":" + port + "/examworkspace";

            connectBtn.setDisable(true);
            statusMsg.setText("Testing connection to " + host + "...");
            statusMsg.setStyle("-fx-text-fill: #94a3b8;");

            Ui.runAsync(
                () -> Database.testConnection(url, user, pass),
                success -> {
                    connectBtn.setDisable(false);
                    if (Boolean.TRUE.equals(success)) {
                        Database.saveConfig(url, user, pass);
                        dialog.setResult(Boolean.TRUE);
                        dialog.close();
                        if (onConnected != null) {
                            onConnected.run();
                        }
                    } else {
                        statusMsg.setText("Could not connect to " + host + ":" + port + ". Check device name and Wi-Fi.");
                        statusMsg.setStyle("-fx-text-fill: #ef4444;");
                    }
                },
                err -> {
                    connectBtn.setDisable(false);
                    statusMsg.setText("Connection failed: " + err.getMessage());
                    statusMsg.setStyle("-fx-text-fill: #ef4444;");
                }
            );
        });

        dialog.showAndWait();
    }
}
