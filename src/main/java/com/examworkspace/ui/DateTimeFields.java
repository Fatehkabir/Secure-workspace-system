package com.examworkspace.ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.stream.IntStream;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/**
 * Date (day / month / year) plus time (hour : minute), in the computer's local time zone.
 * Time uses dropdowns — no spinner arrows.
 */
final class DateTimeFields extends HBox {

    private final DatePicker date = new DatePicker();
    private final ComboBox<String> hour = combo(0, 23);
    private final ComboBox<String> minute = combo(0, 59);

    DateTimeFields() {
        date.setPrefWidth(168);
        date.setPromptText("Date");
        hour.setPrefWidth(78);
        minute.setPrefWidth(78);
        hour.setPromptText("HH");
        minute.setPromptText("MM");
        hour.setEditable(true);
        minute.setEditable(true);
        hour.getStyleClass().add("time-combo");
        minute.getStyleClass().add("time-combo");

        Label colon = new Label(":");
        colon.getStyleClass().add("time-colon");

        Label timeHint = new Label("Time");
        timeHint.getStyleClass().add("time-hint");

        HBox time = new HBox(6, hour, colon, minute);
        time.setAlignment(Pos.CENTER_LEFT);
        time.getStyleClass().add("time-fields");

        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        getStyleClass().add("datetime-fields");
        getChildren().addAll(date, timeHint, time);
    }

    void setInstant(Instant instant) {
        ZonedDateTime local = instant.atZone(ZoneId.systemDefault());
        date.setValue(local.toLocalDate());
        hour.setValue(pad(local.getHour()));
        minute.setValue(pad(local.getMinute()));
    }

    Instant toInstant() {
        LocalDate day = date.getValue();
        if (day == null) {
            return null;
        }
        LocalTime time = LocalTime.of(read(hour, 0, 23), read(minute, 0, 59));
        return ZonedDateTime.of(day, time, ZoneId.systemDefault()).toInstant();
    }

    private static ComboBox<String> combo(int min, int max) {
        ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(
                IntStream.rangeClosed(min, max).mapToObj(DateTimeFields::pad).toList()));
        box.setVisibleRowCount(8);
        return box;
    }

    private static String pad(int value) {
        return String.format("%02d", value);
    }

    private static int read(ComboBox<String> box, int min, int max) {
        String raw = box.getEditor().getText();
        if (raw == null || raw.isBlank()) {
            raw = box.getValue();
        }
        if (raw == null || raw.isBlank()) {
            return min;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return min;
        }
    }
}
