package com.bapis.bilibili.metadata;

/** 测试夹具：生成消息的 Builder 基类。 */
public abstract class FakeBuilderBase<M extends FakeMessageBase<M, B>, B extends FakeBuilderBase<M, B>> {
    protected final String[] fields;

    protected FakeBuilderBase(int size) {
        fields = new String[size];
        java.util.Arrays.fill(fields, "");
    }

    protected abstract M create(String[] fields);

    public final M build() {
        return create(fields.clone());
    }
}
