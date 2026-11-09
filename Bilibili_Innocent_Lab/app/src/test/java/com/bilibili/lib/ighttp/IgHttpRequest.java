package com.bilibili.lib.ighttp;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/** Fixture with the 9.16.1 shape of the host's native HTTP request; execute() never does I/O. */
public final class IgHttpRequest {
    final String mUrl;
    final String mMethod;
    final byte[] mBody;
    final StreamDataProvider mStreamDataProvider;
    final Map<String, String> mHeaders;
    public boolean closed;
    public int executed;

    public interface StreamDataProvider {
        byte[] onProduceData(int size);
    }

    private IgHttpRequest(Builder builder) {
        mUrl = builder.mUrl;
        mMethod = builder.mMethod;
        mBody = builder.mBody;
        mStreamDataProvider = builder.mStreamDataProvider;
        mHeaders = builder.mHeaders;
    }

    public StreamDataProvider streamDataProvider() { return mStreamDataProvider; }

    public byte[] body() { return mBody; }

    public IgHttpResponse execute() throws IOException {
        executed++;
        return new IgHttpResponse();
    }

    public String url() { return mUrl; }

    public String method() { return mMethod; }

    public Map<String, String> headers() { return mHeaders; }

    public void close() { closed = true; }

    public static class Builder {
        private String mUrl;
        private String mMethod = "GET";
        private byte[] mBody;
        private StreamDataProvider mStreamDataProvider;
        private final Map<String, String> mHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        public Builder streamDataProvider(StreamDataProvider provider) {
            mStreamDataProvider = provider;
            return this;
        }

        public Builder url(String url) {
            mUrl = url;
            return this;
        }

        public Builder addHeaders(String name, String value) {
            mHeaders.put(name, value);
            return this;
        }

        public Builder get() { return method("GET", null); }

        public Builder method(String method, byte[] body) {
            mMethod = method;
            mBody = body;
            return this;
        }

        public IgHttpRequest build() { return new IgHttpRequest(this); }
    }
}
