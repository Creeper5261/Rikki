package com.zzf.codeagent.core.util;

public final class StringUtils {
    private StringUtils() {}

    public static String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }
}
