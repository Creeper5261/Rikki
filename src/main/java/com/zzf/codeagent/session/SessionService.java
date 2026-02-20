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

    
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<MessageV2.WithParts>> sessionMessages = new ConcurrentHashMap<>();

    public SessionInfo create(String parentID, String title, String directory) {
        return createNext(parentID, title, directory);
    }

    public SessionInfo createNext(String parentID, String title, String directory) {
        String id = UUID.randomUUID().toString(); 
        SessionInfo session = SessionInfo.builder()
                .id(id)
                .title(title != null ? title : (parentID != null ? "Child session - " : "New session - ") + new Date())
                .directory(directory != null ? directory : System.getProperty("user.dir")) 
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
        
        SessionInfo newSession = createNext(null, null, null);
        
        
        List<MessageV2.WithParts> msgs = getMessages(sessionID);
        Map<String, String> idMap = new HashMap<>(); 

        for (MessageV2.WithParts msg : msgs) {
            if (messageID != null && msg.getInfo().getId().compareTo(messageID) >= 0) {
                
                
                
                
                if (msg.getInfo().getId().equals(messageID)) break; 
            }

            String newMsgID = UUID.randomUUID().toString();
            idMap.put(msg.getInfo().getId(), newMsgID);

            MessageV2.MessageInfo newInfo = new MessageV2.MessageInfo();
            
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
                PromptPart copied = clonePartForFork(part, newSession.getId(), newMsgID);
                if (copied != null) {
                    newParts.add(copied);
                }
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
            
            List<SessionInfo> children = children(sessionID);
            for (SessionInfo child : children) {
                remove(child.getId());
            }
            
            
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
        agentBus.publish("message.created", message); 
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
        
        return Collections.emptyList();
    }

    private PromptPart clonePartForFork(PromptPart source, String sessionId, String messageId) {
        if (source == null) {
            return null;
        }
        PromptPart target;
        if (source instanceof MessageV2.TextPart text) {
            MessageV2.TextPart copy = new MessageV2.TextPart();
            copy.setText(text.getText());
            copy.setDelta(text.getDelta());
            copy.setSynthetic(text.getSynthetic());
            copy.setIgnored(text.getIgnored());
            copy.setTime(copyPartTime(text.getTime()));
            target = copy;
        } else if (source instanceof MessageV2.ReasoningPart reasoning) {
            MessageV2.ReasoningPart copy = new MessageV2.ReasoningPart();
            copy.setText(reasoning.getText());
            copy.setDelta(reasoning.getDelta());
            copy.setTime(copyPartTime(reasoning.getTime()));
            copy.setCollapsed(reasoning.getCollapsed());
            target = copy;
        } else if (source instanceof MessageV2.FilePart file) {
            MessageV2.FilePart copy = new MessageV2.FilePart();
            copy.setMime(file.getMime());
            copy.setFilename(file.getFilename());
            copy.setUrl(file.getUrl());
            copy.setContent(file.getContent());
            target = copy;
        } else if (source instanceof MessageV2.CompactionPart compaction) {
            MessageV2.CompactionPart copy = new MessageV2.CompactionPart();
            copy.setAuto(compaction.isAuto());
            copy.setSummary(compaction.getSummary());
            target = copy;
        } else if (source instanceof MessageV2.SubtaskPart subtask) {
            MessageV2.SubtaskPart copy = new MessageV2.SubtaskPart();
            copy.setPrompt(subtask.getPrompt());
            copy.setDescription(subtask.getDescription());
            copy.setAgent(subtask.getAgent());
            target = copy;
        } else if (source instanceof MessageV2.ToolPart tool) {
            MessageV2.ToolPart copy = new MessageV2.ToolPart();
            copy.setCallID(tool.getCallID());
            copy.setTool(tool.getTool());
            copy.setArgs(copyMap(tool.getArgs()));
            copy.setState(copyToolState(tool.getState()));
            target = copy;
        } else if (source instanceof MessageV2.StepStartPart stepStart) {
            MessageV2.StepStartPart copy = new MessageV2.StepStartPart();
            copy.setSnapshot(stepStart.getSnapshot());
            target = copy;
        } else if (source instanceof MessageV2.StepFinishPart stepFinish) {
            MessageV2.StepFinishPart copy = new MessageV2.StepFinishPart();
            copy.setReason(stepFinish.getReason());
            copy.setSnapshot(stepFinish.getSnapshot());
            copy.setTokens(copyTokenUsage(stepFinish.getTokens()));
            copy.setCost(stepFinish.getCost());
            target = copy;
        } else if (source instanceof MessageV2.AgentPart agentPart) {
            MessageV2.AgentPart copy = new MessageV2.AgentPart();
            copy.setName(agentPart.getName());
            target = copy;
        } else {
            return null;
        }

        target.setId(UUID.randomUUID().toString());
        target.setType(source.getType());
        target.setSessionID(sessionId);
        target.setMessageID(messageId);
        target.setMetadata(copyMap(source.getMetadata()));
        return target;
    }

    private MessageV2.PartTime copyPartTime(MessageV2.PartTime source) {
        if (source == null) {
            return null;
        }
        MessageV2.PartTime copy = new MessageV2.PartTime();
        copy.setStart(source.getStart());
        copy.setEnd(source.getEnd());
        copy.setCompacted(source.getCompacted());
        return copy;
    }

    private MessageV2.ToolState copyToolState(MessageV2.ToolState source) {
        if (source == null) {
            return null;
        }
        MessageV2.ToolState copy = new MessageV2.ToolState();
        copy.setStatus(source.getStatus());
        copy.setInput(copyMap(source.getInput()));
        copy.setOutput(source.getOutput());
        copy.setTitle(source.getTitle());
        copy.setError(source.getError());
        copy.setMetadata(copyMap(source.getMetadata()));
        if (source.getTime() != null) {
            MessageV2.ToolState.TimeInfo timeCopy = new MessageV2.ToolState.TimeInfo();
            timeCopy.setStart(source.getTime().getStart());
            timeCopy.setEnd(source.getTime().getEnd());
            timeCopy.setCompacted(source.getTime().getCompacted());
            copy.setTime(timeCopy);
        }
        return copy;
    }

    private MessageV2.TokenUsage copyTokenUsage(MessageV2.TokenUsage source) {
        if (source == null) {
            return null;
        }
        MessageV2.TokenUsage copy = new MessageV2.TokenUsage();
        copy.setInput(source.getInput());
        copy.setOutput(source.getOutput());
        copy.setReasoning(source.getReasoning());
        if (source.getCache() != null) {
            MessageV2.CacheUsage cacheCopy = new MessageV2.CacheUsage();
            cacheCopy.setRead(source.getCache().getRead());
            cacheCopy.setWrite(source.getCache().getWrite());
            copy.setCache(cacheCopy);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> copied = new HashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copied.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return copied;
    }

    @SuppressWarnings("unchecked")
    private Object deepCopyValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return value;
    }
}
