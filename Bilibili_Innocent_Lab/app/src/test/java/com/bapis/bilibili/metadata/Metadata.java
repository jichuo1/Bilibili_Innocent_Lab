package com.bapis.bilibili.metadata;

/** 测试夹具：com.bapis.bilibili.metadata.Metadata（字段：buvid | accessKey）。 */
public final class Metadata extends FakeMessageBase<Metadata, Metadata.Builder> {
    private Metadata(String[] fields) {
        super(fields);
    }

    public static Metadata parseFrom(byte[] bytes) {
        return new Metadata(decode(bytes, 2));
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public String getBuvid() {
        return fields[0];
    }

    public String getAccessKey() {
        return fields[1];
    }

    @Override
    protected Builder newBuilderInstance() {
        return new Builder();
    }

    public static final class Builder extends FakeBuilderBase<Metadata, Builder> {
        Builder() {
            super(2);
        }

        public Builder setBuvid(String value) {
            fields[0] = value;
            return this;
        }

        public Builder setAccessKey(String value) {
            fields[1] = value;
            return this;
        }

        @Override
        protected Metadata create(String[] fields) {
            return new Metadata(fields);
        }
    }
}
