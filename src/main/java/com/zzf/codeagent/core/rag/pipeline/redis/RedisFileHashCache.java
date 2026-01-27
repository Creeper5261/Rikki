package com.zzf.codeagent.core.rag.pipeline.redis;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class RedisFileHashCache implements FileHashCache {
    private final String host;
    private final int port;
    private final int timeoutMs;
    private final String keyPrefix;

    public RedisFileHashCache(String host, int port, int timeoutMs) {
        this(host, port, timeoutMs, "codeagent:filehash:");
    }

    public RedisFileHashCache(String host, int port, int timeoutMs, String keyPrefix) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.keyPrefix = keyPrefix == null ? "codeagent:filehash:" : keyPrefix;
    }

    @Override
    public boolean isUnchanged(String repoRoot, String relativePath, String sha256) {
        String key = key(repoRoot, relativePath);
        String old = get(key);
        return old != null && old.equals(sha256);
    }

    @Override
    public void update(String repoRoot, String relativePath, String sha256) {
        set(key(repoRoot, relativePath), sha256);
    }

    private String key(String repoRoot, String relativePath) {
        return keyPrefix + repoRoot + "|" + relativePath;
    }

    private String get(String key) {
        byte[] req = respArray(new String[]{"GET", key});
        byte[] resp = roundtrip(req);
        if (resp.length == 0) {
            return null;
        }
        if (resp[0] == '$') {
            int crlf = indexOf(resp, 0, (byte) '\n');
            if (crlf < 0) {
                return null;
            }
            String header = new String(resp, 0, crlf + 1, StandardCharsets.UTF_8);
            if (header.startsWith("$-1")) {
                return null;
            }
            int len = Integer.parseInt(header.substring(1, header.indexOf("\r\n")));
            int start = crlf + 1;
            if (start + len > resp.length) {
                return null;
            }
            return new String(resp, start, len, StandardCharsets.UTF_8);
        }
        if (resp[0] == '+') {
            return new String(resp, 1, resp.length - 3, StandardCharsets.UTF_8);
        }
        return null;
    }

    private void set(String key, String value) {
        byte[] req = respArray(new String[]{"SET", key, value});
        byte[] resp = roundtrip(req);
        if (resp.length == 0 || resp[0] == '-') {
            throw new RuntimeException("redis set failed: " + new String(resp, StandardCharsets.UTF_8));
        }
    }

    private byte[] roundtrip(byte[] req) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(req);
            out.flush();
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            if (n <= 0) {
                return new byte[0];
            }
            byte[] data = new byte[n];
            System.arraycopy(buf, 0, data, 0, n);
            return data;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] respArray(String[] parts) {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(parts.length).append("\r\n");
        for (int i = 0; i < parts.length; i++) {
            byte[] b = parts[i].getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(b.length).append("\r\n");
            sb.append(parts[i]).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] data, int start, byte target) {
        for (int i = start; i < data.length; i++) {
            if (data[i] == target) {
                return i;
            }
        }
        return -1;
    }
}
