package com.bapis.bilibili.metadata;

import java.nio.charset.StandardCharsets;

/** 测试夹具：protobuf-lite 的 GeneratedMessageLite 形状——toBuilder() 在基类，生成的消息类自身不声明。 */
public abstract class FakeMessageBase<M extends FakeMessageBase<M, B>, B extends FakeBuilderBase<M, B>> {
    protected final String[] fields;

    protected FakeMessageBase(String[] fields) {
        this.fields = fields;
    }

    protected abstract B newBuilderInstance();

    public final B toBuilder() {
        B builder = newBuilderInstance();
        System.arraycopy(fields, 0, builder.fields, 0, fields.length);
        return builder;
    }

    public final byte[] toByteArray() {
        return String.join("|", fields).getBytes(StandardCharsets.UTF_8);
    }

    protected static String[] decode(byte[] bytes, int size) {
        String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\\|", -1);
        String[] out = new String[size];
        for (int i = 0; i < size; i++) out[i] = i < parts.length ? parts[i] : "";
        return out;
    }
}
