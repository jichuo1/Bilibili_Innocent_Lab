package com.bapis.bilibili.pagination;

/** 回复脉络定位测试桩：模拟 protobuf lite 的 newBuilder/Builder 边界。 */
public final class FeedPagination {
    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        public Builder setOffset(String offset) {
            return this;
        }

        public FeedPagination build() {
            return new FeedPagination();
        }
    }
}
