package com.zzf.codeagent.core.rag.search;

import java.util.ArrayList;
import java.util.List;

public final class CodeTokenizer {
    public List<String> tokenize(String code) {
        if (code == null || code.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '$') {
                sb.append(ch);
            } else {
                if (sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
            }
        }
        if (sb.length() > 0) {
            out.add(sb.toString());
        }
        return out;
    }
}
