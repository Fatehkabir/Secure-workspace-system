package com.examworkspace.ui;

import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.scene.Node;
import javafx.util.Duration;

final class Motion {

    private Motion() {
    }

    static void lift(Node node) {
        node.setOnMouseEntered(e -> scale(node, 1.018));
        node.setOnMouseExited(e -> scale(node, 1.0));
        node.setOnMousePressed(e -> scale(node, 0.985));
        node.setOnMouseReleased(e -> scale(node, node.isHover() ? 1.018 : 1.0));
    }

    static void press(Node node) {
        node.setOnMousePressed(e -> scale(node, 0.97));
        node.setOnMouseReleased(e -> scale(node, 1.0));
    }

    private static void scale(Node node, double to) {
        ScaleTransition transition = new ScaleTransition(Duration.millis(140), node);
        transition.setToX(to);
        transition.setToY(to);
        transition.setInterpolator(Interpolator.EASE_BOTH);
        transition.play();
    }
}
