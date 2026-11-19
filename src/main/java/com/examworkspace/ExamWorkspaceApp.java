package com.examworkspace;

import com.examworkspace.db.Database;
import com.examworkspace.model.Room;
import com.examworkspace.model.Student;
import com.examworkspace.model.Teacher;
import com.examworkspace.room.RoomService;
import com.examworkspace.ui.DashboardView;
import com.examworkspace.ui.LoginView;
import com.examworkspace.ui.RegisterView;
import com.examworkspace.ui.RoomView;
import com.examworkspace.ui.StudentDashboardView;
import com.examworkspace.ui.StudentEntryView;
import com.examworkspace.ui.StudentLoginView;
import com.examworkspace.ui.StudentRegisterView;
import com.examworkspace.ui.StudentRoomView;
import com.examworkspace.ui.Theme;
import com.examworkspace.ui.Ui;
import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

public class ExamWorkspaceApp extends Application {

    /** Implemented by any screen that must record a student leaving when it stops being shown. */
    public interface Leavable {
        void leaveRoom();
    }

    private final StackPane content = new StackPane();
    private final VBox toastLayer = new VBox(8);
    private Parent currentView;
    private Stage primaryStage;

    @Override
    public void start(Stage stage) {
        try {
            Database.initialize();
            com.examworkspace.monitor.OfflineViolationQueue.startWorker();
        } catch (Exception ex) {
            System.err.println("Database initial connection warning: " + ex.getMessage());
        }

        // Covers the window being closed outright (the X button) while a student is in a room --
        // navigating to another screen is already handled by StudentRoomView's own scene listener.
        stage.setOnCloseRequest(e -> {
<<<<<<< HEAD
            if (currentView instanceof StudentRoomView studentRoom && !studentRoom.canExit()) {
                e.consume();
                Ui.toast(studentRoom, "The teacher has locked room exit until the exam is finished.", false);
                return;
            }
=======
>>>>>>> e7c81e45d495d8dd87bda0603cfa76e68600d125
            com.examworkspace.net.LanDiscoveryService.stopBeacon();
            if (currentView instanceof Leavable leavable) {
                leavable.leaveRoom();
            }
        });

        toastLayer.getStyleClass().add("toast-layer");
        toastLayer.setAlignment(Pos.BOTTOM_RIGHT);
        toastLayer.setFillWidth(false);
        toastLayer.setMouseTransparent(true);
        toastLayer.setPickOnBounds(false);

        StackPane root = new StackPane(content, toastLayer);
        root.getStyleClass().add("app-canvas");
        Scene scene = new Scene(root, 1180, 760);
        Theme.applyTo(scene);

        stage.setTitle("Exam Workspace");
        stage.setMinWidth(920);
        stage.setMinHeight(620);
        this.primaryStage = stage;
        stage.fullScreenProperty().addListener((obs, wasFullScreen, isFullScreen) -> {
            if (!isFullScreen && currentView instanceof StudentRoomView studentRoom && !studentRoom.canExit()) {
                javafx.application.Platform.runLater(this::enterExamLockdown);
            }
        });
        stage.iconifiedProperty().addListener((obs, wasIconified, isIconified) -> {
            if (isIconified && currentView instanceof StudentRoomView studentRoom && !studentRoom.canExit()) {
                javafx.application.Platform.runLater(() -> {
                    stage.setIconified(false);
                    enterExamLockdown();
                });
            }
        });
        stage.setScene(scene);
        showLogin();
        stage.show();
    }

    public void showLogin() {
        setContent(new LoginView(this));
    }

    public void showRegister() {
        setContent(new RegisterView(this));
    }

    public void showDashboard(Teacher teacher) {
        setContent(new DashboardView(this, teacher, new RoomService(teacher)));
    }

    public void showRoom(Teacher teacher, RoomService rooms, Room room) {
        setContent(new RoomView(this, teacher, rooms, room));
    }

    public void showStudentEntry() {
        setContent(new StudentEntryView(this));
    }

    public void showStudentLogin() {
        setContent(new StudentLoginView(this));
    }

    public void showStudentRegister() {
        setContent(new StudentRegisterView(this));
    }

    public void showStudentDashboard(Student student) {
        setContent(new StudentDashboardView(this, student));
    }

    public void showStudentRoom(Room room, Student student) {
        setContent(new StudentRoomView(this, room, student));
    }

    public void enterExamLockdown() {
        if (primaryStage != null) {
            primaryStage.setFullScreenExitHint("");
            primaryStage.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH);
            primaryStage.setAlwaysOnTop(true);
            primaryStage.setFullScreen(true);
            com.examworkspace.ui.WindowsTaskbarHelper.hideTaskbar();
        }
    }

    public void exitExamLockdown() {
        if (primaryStage != null) {
            primaryStage.setFullScreen(false);
            primaryStage.setAlwaysOnTop(false);
            com.examworkspace.ui.WindowsTaskbarHelper.showTaskbar();
        }
    }

    private void setContent(Parent view) {
        // Navigating away from a Leavable screen (e.g. the student's room) is itself a "leave" --
        // StudentRoomView's own scene listener already handles this; this covers the window
        // closing outright, when no navigation happens for that listener to react to.
        currentView = view;
        content.getChildren().setAll(view);
        FadeTransition fade = new FadeTransition(Duration.millis(220), view);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
