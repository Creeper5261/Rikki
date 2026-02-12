package com.zzf.codeagent.id;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 标识符生成器 (对齐 OpenCode Identifier)
 * 使用 ULID 风格或递增 ID
 */
public class Identifier {

    private static final AtomicLong counter = new AtomicLong(System.currentTimeMillis());

    /**
     * 生成递增 ID
     */
    public static String ascending(String prefix) {
        return prefix + "_" + counter.incrementAndGet();
    }

    /**
     * 生成随机 ID
     */
    public static String random(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
