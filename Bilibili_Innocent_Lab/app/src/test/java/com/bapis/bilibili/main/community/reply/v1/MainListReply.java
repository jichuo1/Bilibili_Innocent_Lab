package com.bapis.bilibili.main.community.reply.v1;

public final class MainListReply {
    public boolean hasQoe() {
        return true;
    }

    public QoeInfo getQoe() {
        return new QoeInfo();
    }

    public boolean hasOperation() {
        return true;
    }

    public Operation getOperation() {
        return new Operation();
    }

    public boolean hasOperationV2() {
        return true;
    }

    public OperationV2 getOperationV2() {
        return new OperationV2();
    }
}
