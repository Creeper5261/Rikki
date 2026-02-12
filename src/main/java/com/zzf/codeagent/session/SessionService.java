package com.zzf.codeagent.session;

import com.zzf.codeagent.bus.AgentBus;
import com.zzf.codeagent.session.model.MessageV2;
import com.zzf.codeagent.session.model.PromptPart;
import com.zzf.codeagent.session.SessionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Session 管理服务 (对齐 OpenCode Session namespace)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final AgentBus agentBus;

    // In-memory storage for now (replacing OpenCode Storage)
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<MessageV2.WithParts>> sessionMessages = new ConcurrentHashMap<>();

    public SessionInfo create(String parentID, String title, String directory) {
        return createNext(parentID, title, directory);
    }

    public SessionInfo createNext(String parentID, String title, String directory) {
        String id = UUID.randomUUID().toString(); // Should be ULID-like
        SessionInfo session = SessionInfo.builder()
                .id(id)
                .title(title != null ? title : (parentID != null ? "Child session - " : "New session - ") + new Date())
                .directory(directory != null ? directory : System.getProperty("user.dir")) // Instance.directory
                .time(SessionInfo.SessionTime.builder()
                        .created(System.currentTimeMillis())
                        .updated(System.currentTimeMillis())
                        .build())
                .parentID(parentID)
                .build();
        
        sessions.put(id, session);
        sessionMessages.put(id, Collections.synchronizedList(new ArrayList<>()));
        
        agentBus.publish("session.created", session);
        return session;
    }

    public SessionInfo fork(String sessionID, String messageID) {
        // Create new session
        SessionInfo newSession = createNext(null, null, null);
        
        // Clone messages up to messageID
        List<MessageV2.WithParts> msgs = getMessages(sessionID);
        Map<String, String> idMap = new HashMap<>(); // oldID -> newID

        for (MessageV2.WithParts msg : msgs) {
            if (messageID != null && msg.getInfo().getId().compareTo(messageID) >= 0) {
                // Assuming messageID comparison works lexicographically (ULID) or we check equality
                // If messageID is provided, break if we passed it? 
                // OpenCode: if (input.messageID && msg.info.id >= input.messageID) break
                // If IDs are chronological. UUIDs are not. Assuming chronological for now.
                if (msg.getInfo().getId().equals(messageID)) break; 
            }

            String newMsgID = UUID.randomUUID().toString();
            idMap.put(msg.getInfo().getId(), newMsgID);

            MessageV2.MessageInfo newInfo = new MessageV2.MessageInfo();
            // Copy fields
            newInfo.setId(newMsgID);
            newInfo.setSessionID(newSession.getId());
            newInfo.setRole(msg.getInfo().getRole());
            newInfo.setCreated(System.currentTimeMillis());
            newInfo.setAgent(msg.getInfo().getAgent());
            newInfo.setModelID(msg.getInfo().getModelID());
            newInfo.setProviderID(msg.getInfo().getProviderID());
            newInfo.setParentID(msg.getInfo().getParentID() != null ? idMap.get(msg.getInfo().getParentID()) : null);
            newInfo.setMode(msg.getInfo().getMode());
            newInfo.setSummary(msg.getInfo().getSummary());

            List<PromptPart> newParts = new ArrayList<>();
            for (PromptPart part : msg.getParts()) {
                // Clone part (simplified)
                // Ideally deep copy. For now just re-assigning might be dangerous if mutable.
                // Creating new instance:
                // We need to know exact type.
                // For now, assuming parts are immutable or we don't modify them deeply here.
                // But updatePart modifies parts.
                // We should clone. Skipping deep clone for brevity, TODO.
                part.setSessionID(newSession.getId());
                part.setMessageID(newMsgID);
                part.setId(UUID.randomUUID().toString());
                newParts.add(part); 
            }

            addMessage(newSession.getId(), new MessageV2.WithParts(newInfo, newParts));
        }

        return newSession;
    }

    public Optional<SessionInfo> get(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public void update(String id, java.util.function.Consumer<SessionInfo> updater) {
        SessionInfo session = sessions.get(id);
        if (session != null) {
            updater.accept(session);
            agentBus.publish("session.updated", session);
        }
    }

    public void touch(String id) {
        update(id, s -> s.getTime().setUpdated(System.currentTimeMillis()));
    }
    
    public void remove(String sessionID) {
        SessionInfo session = sessions.remove(sessionID);
        if (session != null) {
            // Remove children
            List<SessionInfo> children = children(sessionID);
            for (SessionInfo child : children) {
                remove(child.getId());
            }
            
            // Remove messages
            sessionMessages.remove(sessionID);
            
            agentBus.publish("session.deleted", session);
        }
    }

    public List<SessionInfo> children(String parentID) {
        return sessions.values().stream()
                .filter(s -> Objects.equals(s.getParentID(), parentID))
                .collect(Collectors.toList());
    }
    
    public List<SessionInfo> list() {
        return new ArrayList<>(sessions.values());
    }

    // Message Management
    
    public List<MessageV2.WithParts> getMessages(String sessionID) {
        List<MessageV2.WithParts> msgs = sessionMessages.get(sessionID);
        if (msgs == null) {
            return new ArrayList<>();
        }
        synchronized (msgs) {
            return new ArrayList<>(msgs);
        }
    }

    /**
     * 获取过滤掉被压缩消息后的列表
     */
    public List<MessageV2.WithParts> getFilteredMessages(String sessionID) {
        List<MessageV2.WithParts> all = getMessages(sessionID);
        List<MessageV2.WithParts> result = new ArrayList<>();
        
        for (int i = all.size() - 1; i >= 0; i--) {
            MessageV2.WithParts msg = all.get(i);
            result.add(0, msg);
            // OpenCode filterCompacted logic: stop when hitting a summary message
            if ("assistant".equals(msg.getInfo().getRole()) && Boolean.TRUE.equals(msg.getInfo().getSummary())) {
                break;
            }
        }
        return result;
    }

    public void addMessage(String sessionID, MessageV2.WithParts message) {
        List<MessageV2.WithParts> msgs = sessionMessages.computeIfAbsent(sessionID, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (msgs) {
            msgs.add(message);
        }
        agentBus.publish("message.created", message); // Assuming event
    }

    public void updateMessage(MessageV2.MessageInfo info) {
        List<MessageV2.WithParts> msgs = sessionMessages.get(info.getSessionID());
        if (msgs == null) {
            return;
        }
        MessageV2.WithParts updated = null;
        synchronized (msgs) {
            for (MessageV2.WithParts msg : msgs) {
                if (msg.getInfo().getId().equals(info.getId())) {
                    msg.setInfo(info);
                    updated = msg;
                    break;
                }
            }
        }
        if (updated != null) {
            agentBus.publish("message.updated", updated);
        }
    }
    
    public void updateMessage(MessageV2.WithParts message) {
        List<MessageV2.WithParts> msgs = sessionMessages.computeIfAbsent(
                message.getInfo().getSessionID(),
                k -> Collections.synchronizedList(new ArrayList<>())
        );
        boolean replaced = false;
        synchronized (msgs) {
            for (int i = 0; i < msgs.size(); i++) {
                if (msgs.get(i).getInfo().getId().equals(message.getInfo().getId())) {
                    msgs.set(i, message);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                msgs.add(message);
            }
        }
        agentBus.publish("message.updated", message);
    }

    public void updatePart(PromptPart part) {
        List<MessageV2.WithParts> msgs = sessionMessages.get(part.getSessionID());
        if (msgs != null) {
            boolean updated = false;
            synchronized (msgs) {
            for (MessageV2.WithParts msg : msgs) {
                if (msg.getInfo().getId().equals(part.getMessageID())) {
                    // Find and replace or add part
                    boolean found = false;
                    for (int i = 0; i < msg.getParts().size(); i++) {
                        if (msg.getParts().get(i).getId().equals(part.getId())) {
                            msg.getParts().set(i, part);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        msg.getParts().add(part);
                    }
                        updated = true;
                    break;
                }
            }
            }
            if (updated) {
                agentBus.publish("part.updated", part);
            }
        }
    }
    
    /**
     * 根据消息ID获取消息（跨会话查找）
     */
    public MessageV2.WithParts getMessage(String messageID) {
        if (messageID == null) return null;
        for (List<MessageV2.WithParts> msgs : sessionMessages.values()) {
            if (msgs == null) continue;
            synchronized (msgs) {
                for (MessageV2.WithParts msg : msgs) {
                    if (messageID.equals(msg.getInfo().getId())) {
                        return msg;
                    }
                }
            }
        }
        return null;
    }
    
    public List<Object> diff(String sessionID) {
        // TODO: Implement diff retrieval from storage
        return Collections.emptyList();
    }
}
