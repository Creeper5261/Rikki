package com.zzf.rikki.idea;

import java.util.List;
import java.util.function.BiConsumer;

final class ChatSseAdapter {
    private String currentEvent;
    private final StringBuilder currentData = new StringBuilder();

    void consume(List<String> lines, BiConsumer<String, String> eventHandler) {
        if (eventHandler == null) {
            return;
        }
        reset();
        if (lines == null || lines.isEmpty()) {
            return;
        }
        for (String line : lines) {
            acceptLine(line, eventHandler);
        }
        finish(eventHandler);
    }

    void acceptLine(String line, BiConsumer<String, String> eventHandler) {
        if (line == null || eventHandler == null) {
            return;
        }
        if (line.startsWith("event:")) {
            currentEvent = line.substring(6).trim();
            return;
        }
        if (line.startsWith("data:")) {
            if (currentData.length() > 0) {
                currentData.append('\n');
            }
            currentData.append(line.substring(5).trim());
            return;
        }
        if (line.isBlank()) {
            flush(eventHandler, currentEvent, currentData);
            currentEvent = null;
            currentData.setLength(0);
        }
    }

    void finish(BiConsumer<String, String> eventHandler) {
        if (eventHandler == null) {
            reset();
            return;
        }
        flush(eventHandler, currentEvent, currentData);
        reset();
    }

    void reset() {
        currentEvent = null;
        currentData.setLength(0);
    }

    private void flush(BiConsumer<String, String> handler, String event, StringBuilder data) {
        if (data == null || data.length() == 0) {
            return;
        }
        handler.accept(event, data.toString());
    }
}
