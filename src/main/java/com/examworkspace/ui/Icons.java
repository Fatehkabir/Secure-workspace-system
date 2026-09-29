package com.examworkspace.ui;

import javafx.scene.layout.Region;

/**
 * Vector icons drawn as {@link Region}s through the CSS {@code -fx-shape} property, so their
 * colour follows the stylesheet ({@code .icon} and its variants) instead of being baked in.
 */
final class Icons {

    static final String LOGO = "M5 13.18v4L12 21l7-3.82v-4L12 17l-7-3.82zM12 3L1 9l11 6 9-4.91V17h2V9L12 3z";
    static final String DASHBOARD = "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z";
    static final String APPS = "M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4z"
            + "m6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z";
    static final String ROOM = "M10 4H4c-1.1 0-2 0.9-2 2v12c0 1.1 0.9 2 2 2h16c1.1 0 2-0.9 2-2V8"
            + "c0-1.1-0.9-2-2-2h-8l-2-2z";
    static final String PLUS = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z";
    static final String SEARCH = "M10 2C5.58 2 2 5.58 2 10s3.58 8 8 8c1.85 0 3.55-0.63 4.9-1.69l5.4 5.39"
            + "L21.71 20.29l-5.39-5.4C17.37 13.55 18 11.85 18 10c0-4.42-3.58-8-8-8zm0 2c3.31 0 6 2.69 6 6"
            + "s-2.69 6-6 6-6-2.69-6-6 2.69-6 6-6z";
    static final String EDIT = "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04"
            + "c0.39-0.39 0.39-1.02 0-1.41l-2.34-2.34c-0.39-0.39-1.02-0.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z";
    static final String TRASH = "M6 19c0 1.1 0.9 2 2 2h8c1.1 0 2-0.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z";
    static final String PLAY = "M8 5v14l11-7z";
    static final String LOGOUT = "M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4"
            + "c-1.1 0-2 0.9-2 2v14c0 1.1 0.9 2 2 2h8v-2H4V5z";
    static final String CHEVRON_RIGHT = "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z";
    static final String CHECK = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z"
            + "m-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z";
    static final String WARNING = "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z";
    static final String LOCK = "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 0.9-2 2v10"
            + "c0 1.1 0.9 2 2 2h12c1.1 0 2-0.9 2-2V10c0-1.1-0.9-2-2-2zM12 17c-1.1 0-2-0.9-2-2s0.9-2 2-2"
            + " 2 0.9 2 2-0.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1s3.1 1.39 3.1 3.1v2z";
    static final String REFRESH = "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8"
            + "c3.73 0 6.84-2.55 7.73-6h-2.08C16.73 16.09 14.54 18 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6"
            + "c1.66 0 3.14 0.69 4.22 1.78L13 11h7V4l-2.35 2.35z";
    static final String FILE = "M14 2H6c-1.1 0-2 0.9-2 2v16c0 1.1 0.9 2 2 2h12c1.1 0 2-0.9 2-2V8l-6-6z"
            + "m2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z";
    static final String EYE = "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5"
            + "c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z"
            + "m0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z";
    static final String EYE_OFF = "M12 7c2.76 0 5 2.24 5 5 0 0.65-0.13 1.26-0.36 1.83l2.92 2.92"
            + "c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74 0.25-3.98 0.7l2.16 2.16"
            + "C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28 0.46 0.46C3.08 8.3 1.78 10.02 1 12"
            + "c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-0.3 4.38-0.84l0.42 0.42L19.73 22 21 20.73 3.27 3 2 4.27z"
            + "M7.53 9.8l1.55 1.55c-0.05 0.21-0.08 0.43-0.08 0.65 0 1.66 1.34 3 3 3 0.22 0 0.44-0.03 0.65-0.08"
            + "l1.55 1.55c-0.67 0.33-1.41 0.53-2.2 0.53-2.76 0-5-2.24-5-5 0-0.79 0.2-1.53 0.53-2.2z";
    static final String MONITOR = "M20 18c1.1 0 2-0.9 2-2V6c0-1.1-0.9-2-2-2H4c-1.1 0-2 0.9-2 2v10"
            + "c0 1.1 0.9 2 2 2H0v2h24v-2h-4zM4 6h16v10H4V6z";

    private Icons() {
    }

    static Region of(String svgPath, double size, String... extraStyleClasses) {
        Region icon = new Region();
        icon.getStyleClass().add("icon");
        icon.getStyleClass().addAll(extraStyleClasses);
        icon.setStyle(shapeStyle(svgPath));
        icon.setMinSize(size, size);
        icon.setPrefSize(size, size);
        icon.setMaxSize(size, size);
        icon.setMouseTransparent(true);
        return icon;
    }

    static String shapeStyle(String svgPath) {
        return "-fx-shape: \"" + svgPath + "\";";
    }
}
