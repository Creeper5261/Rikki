package com.zzf.rikki.session;

import com.zzf.rikki.session.model.MessageV2;
import com.zzf.rikki.session.model.PromptPart;

import java.util.List;

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
                textPart.text = String.join("\n",
                        "<system-reminder>",
                        "The user sent the following message:",
                        text,
                        "",
                        "Please address this message and continue with your tasks.",
                        "</system-reminder>");
                textPart.delta = textPart.text;
            }
        }
    }
}