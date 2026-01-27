package com.zzf.codeagent.core.rag.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ElasticsearchIndexNamesTest {

    @Test
    public void testWorkspaceIdNormalizationStable() {
        String id1 = ElasticsearchIndexNames.workspaceId("C:\\Repo\\");
        String id2 = ElasticsearchIndexNames.workspaceId("c:/repo");
        String id3 = ElasticsearchIndexNames.workspaceId("C:/Repo///");

        assertEquals(id1, id2);
        assertEquals(id1, id3);
        assertEquals(12, id1.length());
    }

    @Test
    public void testWorkspaceIdDefaultOnEmpty() {
        assertEquals("default", ElasticsearchIndexNames.workspaceId(""));
        assertEquals("default", ElasticsearchIndexNames.workspaceId("   "));
        assertEquals("default", ElasticsearchIndexNames.workspaceId(null));
    }

    @Test
    public void testIndexNameUsesWorkspaceId() {
        String index = ElasticsearchIndexNames.codeAgentV2IndexForWorkspaceRoot("D:/Work/Project");
        assertTrue(index.startsWith(ElasticsearchIndexNames.CODE_AGENT_V2 + "_"));
        assertNotEquals(ElasticsearchIndexNames.CODE_AGENT_V2, index);
    }
}
