package com.zzf.rikki.session;

import lombok.extern.slf4j.Slf4j;

/**
 * 会话重试逻辑 (对齐 OpenCode SessionRetry)
 */
@Slf4j
public class SessionRetry {

    public static final long RETRY_INITIAL_DELAY = 2000;
    public static final double RETRY_BACKOFF_FACTOR = 2.0;
    public static final long RETRY_MAX_DELAY_NO_HEADERS = 30000;

    public static long getDelay(int attempt, Throwable error) {
        
        return (long) Math.min(RETRY_INITIAL_DELAY * Math.pow(RETRY_BACKOFF_FACTOR, attempt - 1), RETRY_MAX_DELAY_NO_HEADERS);
    }

    public static String getRetryableMessage(Throwable error) {
        String msg = error.getMessage();
        if (msg == null) return null;
        
        if (msg.contains("Rate limit") || msg.contains("429")) {
            return "Rate Limited";
        }
        if (msg.contains("Overloaded") || msg.contains("503")) {
            return "Provider is overloaded";
        }
        if (msg.contains("timeout") || msg.contains("Timeout")) {
            return "Request Timeout";
        }
        
        return null;
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
