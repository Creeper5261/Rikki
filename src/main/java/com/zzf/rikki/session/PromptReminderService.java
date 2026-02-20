package com.zzf.rikki.session;

import com.zzf.rikki.agent.AgentInfo;
import com.zzf.rikki.session.model.MessageV2;
import com.zzf.rikki.session.model.PromptPart;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 消息提醒服务 (对齐 OpenCode insertReminders)
 */
@Service
public class PromptReminderService {

    public List<MessageV2.WithParts> insertReminders(List<MessageV2.WithParts> messages, AgentInfo agent, SessionInfo session) {
        if (messages.isEmpty()) return messages;

        MessageV2.WithParts userMessage = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getInfo().getRole())) {
                userMessage = messages.get(i);
                break;
            }
        }

        if (userMessage == null) return messages;

        
        
        
        return messages;
    }

    /**
     * 为 mid-loop 中的用户消息添加包装 (对齐 prompt.ts:579)
     */
    public void wrapMidLoopUserMessages(List<MessageV2.WithParts> messages, String lastFinishedId) {
        for (MessageV2.WithParts msg : messages) {
            if (!"user".equals(msg.getInfo().getRole()) || (lastFinishedId != null && msg.getInfo().getId().compareTo(lastFinishedId) <= 0)) {
                continue;
            }

            for (PromptPart part : msg.getParts()) {
                if (part instanceof MessageV2.TextPart) {
                    MessageV2.TextPart textPart = (MessageV2.TextPart) part;
                    if (Boolean.TRUE.equals(textPart.getIgnored()) || Boolean.TRUE.equals(textPart.getSynthetic())) continue;
                    
                    String text = textPart.getText();
                    if (text == null || text.trim().isEmpty()) continue;

                    textPart.setText(String.join("\n",
                        "<system-reminder>",
                        "The user sent the following message:",
                        text,
                        "",
                        "Please address this message and continue with your tasks.",
                        "</system-reminder>"
                    ));
                }
            }
        }
    }
}
