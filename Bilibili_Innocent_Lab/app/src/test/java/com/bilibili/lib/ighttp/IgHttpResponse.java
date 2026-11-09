package com.bilibili.lib.ighttp;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Fixture response with the reader methods the Ktor engine calls; by default it carries the server's anonymous error reply. */
public class IgHttpResponse {
    public static final String SERVER_BODY = "{\"code\":-101,\"message\":\"not logged in\"}";
    private final byte[] body = SERVER_BODY.getBytes(StandardCharsets.UTF_8);
    private int offset;
    public boolean closed;

    public int httpCode() { return 200; }

    public String httpMessage() { return "server"; }

    public Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Encoding", "br");
        headers.put("Content-Length", "999");
        return headers;
    }

    public int readDataV2(byte[] target, int max) {
        int count = Math.min(Math.min(max, target.length), body.length - offset);
        if (count <= 0) return 0;
        System.arraycopy(body, offset, target, 0, count);
        offset += count;
        return count;
    }

    public void close() { closed = true; }
}
