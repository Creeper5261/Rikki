package com.zzf.codeagent.session;

import com.zzf.codeagent.bus.AgentBus;
import com.zzf.codeagent.session.model.MessageV2;
import com.zzf.codeagent.session.model.PromptPart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionServiceForkTest {

    @Test
    void forkShouldNotMutateSourceParts() {
        SessionService service = new SessionService(new AgentBus());
        SessionInfo parent = service.create(null, "parent", System.getProperty("user.dir"));

        MessageV2.MessageInfo info = new MessageV2.MessageInfo();
        info.setId("msg-parent-1");
        info.setSessionID(parent.getId());
        info.setRole("assistant");
        info.setCreated(System.currentTimeMillis());

        MessageV2.TextPart textPart = new MessageV2.TextPart();
        textPart.setId("part-parent-1");
        textPart.setType("text");
        textPart.setSessionID(parent.getId());
        textPart.setMessageID(info.getId());
        textPart.setText("hello");
        textPart.setMetadata(new HashMap<>(java.util.Map.of("k", "v")));

        service.addMessage(parent.getId(), new MessageV2.WithParts(info, new ArrayList<>(List.of(textPart))));
        SessionInfo forked = service.fork(parent.getId(), null);

        List<MessageV2.WithParts> parentMessages = service.getMessages(parent.getId());
        PromptPart sourcePart = parentMessages.get(0).getParts().get(0);
        assertEquals(parent.getId(), sourcePart.getSessionID());
        assertEquals("msg-parent-1", sourcePart.getMessageID());
        assertEquals("part-parent-1", sourcePart.getId());

        List<MessageV2.WithParts> forkedMessages = service.getMessages(forked.getId());
        assertTrue(!forkedMessages.isEmpty());
        PromptPart forkedPart = forkedMessages.get(0).getParts().get(0);
        assertEquals(forked.getId(), forkedPart.getSessionID());
        assertNotEquals(sourcePart.getId(), forkedPart.getId());
        assertNotEquals(sourcePart.getMessageID(), forkedPart.getMessageID());
    }
}

