package com.zzf.codeagent.core.rag.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class ElasticsearchIndexNames {
    public static final String CODE_AGENT_V2 = "code_agent_v2";

    private ElasticsearchIndexNames() {
    }

    public static String workspaceId(String workspaceRoot) {
        String normalized = normalizeWorkspaceRoot(workspaceRoot);
        if (normalized.isEmpty()) {
            return "default";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return toHex(digest).substring(0, 12);
        } catch (Exception e) {
            return "default";
        }
    }

    public static String codeAgentV2IndexForWorkspaceRoot(String workspaceRoot) {
        return CODE_AGENT_V2 + "_" + workspaceId(workspaceRoot);
    }

    private static String normalizeWorkspaceRoot(String workspaceRoot) {
        if (workspaceRoot == null) {
            return "";
        }
        String s = workspaceRoot.trim();
        if (s.isEmpty()) {
            return "";
        }
        s = s.replace('\\', '/');
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            sb.append(Character.forDigit((v >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }
}
