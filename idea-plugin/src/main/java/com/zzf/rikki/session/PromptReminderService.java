package com.zzf.rikki.session;

import com.zzf.rikki.session.model.MessageV2;
import com.zzf.rikki.session.model.PromptPart;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PromptReminderService {
    public List<String> reminders(SessionInfo session, List<MessageV2.WithParts> messages) {
        return List.of();
    }

    public List<MessageV2.WithParts> insertReminders(List<MessageV2.WithParts> messages, SessionInfo session) {
        return messages;
    }

    public void wrapMidLoopUserMessages(List<MessageV2.WithParts> messages, String lastFinishedId) {
        if (messages == null || messages.isEmpty() || lastFinishedId == null || lastFinishedId.isBlank()) {
            return;
        }

        boolean afterBoundary = false;
        for (MessageV2.WithParts message : messages) {
            if (message == null || message.info == null) {
                continue;
            }
            if (!afterBoundary && lastFinishedId.equals(message.info.id)) {
                afterBoundary = true;
                continue;
            }
            if (!afterBoundary || !"user".equals(message.info.role) || message.parts == null) {
                continue;
            }
            for (PromptPart part : message.parts) {
                if (!(part instanceof MessageV2.TextPart textPart)) {
                    continue;
                }
                if (Boolean.TRUE.equals(textPart.ignored) || Boolean.TRUE.equals(textPart.synthetic)) {
                    continue;
                }
                String text = textPart.text == null ? "" : textPart.text.trim();
                if (text.isEmpty()) {
                    continue;
                }
                Map<String, Object> variables = new LinkedHashMap<>();
                variables.put("userMessage", text);
                String template = PromptTextLoader.loadRuntimePrompt("mid-loop-user-reminder");
                textPart.text = PromptTextLoader.renderTemplate(template, variables).trim();
                textPart.delta = textPart.text;
            }
        }
    }
}
