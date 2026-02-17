package com.zzf.codeagent.idea;

import java.util.List;
import java.util.function.BiConsumer;

final class ChatSseAdapter {
    void consume(List<String> lines, BiConsumer<String, String> eventHandler) {
        if (lines == null || lines.isEmpty() || eventHandler == null) {
            return;
        }
        String currentEvent = null;
        StringBuilder currentData = new StringBuilder();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            if (line.startsWith("event:")) {
                currentEvent = line.substring(6).trim();
                continue;
            }
            if (line.startsWith("data:")) {
                if (currentData.length() > 0) {
                    currentData.append('\n');
                }
                currentData.append(line.substring(5).trim());
                continue;
            }
            if (line.isBlank()) {
                flush(eventHandler, currentEvent, currentData);
                currentEvent = null;
                currentData.setLength(0);
            }
        }
        flush(eventHandler, currentEvent, currentData);
    }

    private void flush(BiConsumer<String, String> handler, String event, StringBuilder data) {
        if (data == null || data.length() == 0) {
            return;
        }
        handler.accept(event, data.toString());
    }
}
