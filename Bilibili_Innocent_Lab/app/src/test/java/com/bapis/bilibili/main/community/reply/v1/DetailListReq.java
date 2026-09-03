package com.bapis.bilibili.main.community.reply.v1;

import com.bapis.bilibili.pagination.FeedPagination;

/** 回复脉络定位测试桩：DetailList 请求与其 Builder 的公开写入边界。 */
public final class DetailListReq {
    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        public Builder setOid(long oid) {
            return this;
        }

        public Builder setType(long type) {
            return this;
        }

        public Builder setModeValue(int mode) {
            return this;
        }

        public Builder setPagination(FeedPagination pagination) {
            return this;
        }

        public Builder setRoot(long root) {
            return this;
        }

        public Builder setRpid(long rpid) {
            return this;
        }

        public DetailListReq build() {
            return new DetailListReq();
        }
    }
}
