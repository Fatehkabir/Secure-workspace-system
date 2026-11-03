package com.examworkspace.alerts;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Holds received alerts in memory and notifies a listener (the UI) whenever
 * a new one arrives. In-memory only -- fine for a testing tool; the real
 * system would persist these, same as the existing Room/AppEntry data.
 */
public class AlertStore {

    private final LinkedList<AlertRecord> alerts = new LinkedList<>();
    private volatile Consumer<AlertRecord> listener;

    public synchronized void add(AlertRecord record) {
        alerts.addFirst(record);
        Consumer<AlertRecord> currentListener = listener;
        if (currentListener != null) {
            currentListener.accept(record);
        }
    }

    public synchronized List<AlertRecord> getAll() {
        return Collections.unmodifiableList(new LinkedList<>(alerts));
    }

    public void setListener(Consumer<AlertRecord> listener) {
        this.listener = listener;
    }
}
