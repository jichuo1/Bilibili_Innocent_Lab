package com.bapis.bilibili.metadata.device;

import com.bapis.bilibili.metadata.FakeBuilderBase;
import com.bapis.bilibili.metadata.FakeMessageBase;

/** 测试夹具：com.bapis.bilibili.metadata.device.Device（字段：buvid|fp|fpLocal|fpRemote|guestId|fts）。 */
public final class Device extends FakeMessageBase<Device, Device.Builder> {
    private Device(String[] fields) {
        super(fields);
    }

    public static Device parseFrom(byte[] bytes) {
        return new Device(decode(bytes, 6));
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public String field(int index) {
        return fields[index];
    }

    @Override
    protected Builder newBuilderInstance() {
        return new Builder();
    }

    public static final class Builder extends FakeBuilderBase<Device, Builder> {
        Builder() {
            super(6);
        }

        public Builder setBuvid(String value) {
            fields[0] = value;
            return this;
        }

        public Builder setFp(String value) {
            fields[1] = value;
            return this;
        }

        public Builder setFpLocal(String value) {
            fields[2] = value;
            return this;
        }

        public Builder setFpRemote(String value) {
            fields[3] = value;
            return this;
        }

        public Builder setGuestId(String value) {
            fields[4] = value;
            return this;
        }

        public Builder setFts(long value) {
            fields[5] = Long.toString(value);
            return this;
        }

        @Override
        protected Device create(String[] fields) {
            return new Device(fields);
        }
    }
}
