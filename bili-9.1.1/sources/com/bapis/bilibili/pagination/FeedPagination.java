package com.bapis.bilibili.pagination;

/* JADX INFO: compiled from: BL */
/* JADX INFO: loaded from: classes28.dex */
public final class FeedPagination extends com.google.protobuf.GeneratedMessageLite<com.bapis.bilibili.pagination.FeedPagination, com.bapis.bilibili.pagination.FeedPagination.b> implements F7.a {
    private static final com.bapis.bilibili.pagination.FeedPagination DEFAULT_INSTANCE;
    public static final int IS_REFRESH_FIELD_NUMBER = 3;
    public static final int OFFSET_FIELD_NUMBER = 2;
    public static final int PAGE_SIZE_FIELD_NUMBER = 1;
    private static volatile com.google.protobuf.Parser<com.bapis.bilibili.pagination.FeedPagination> PARSER;
    private boolean isRefresh_;
    private java.lang.String offset_ = "";
    private int pageSize_;

    /* JADX INFO: compiled from: BL */
    public static /* synthetic */ class a {

        /* JADX INFO: renamed from: a, reason: collision with root package name */
        public static final /* synthetic */ int[] f157872a;

        static {
            int[] iArr = new int[com.google.protobuf.GeneratedMessageLite.MethodToInvoke.values().length];
            f157872a = iArr;
            try {
                iArr[com.google.protobuf.GeneratedMessageLite.MethodToInvoke.NEW_MUTABLE_INSTANCE.ordinal()] = 1;
            } catch (java.lang.NoSuchFieldError unused) {
            }
            try {
                f157872a[com.google.protobuf.GeneratedMessageLite.MethodToInvoke.NEW_BUILDER.ordinal()] = 2;
            } catch (java.lang.NoSuchFieldError unused2) {
            }
            try {
                f157872a[com.google.protobuf.GeneratedMessageLite.MethodToInvoke.BUILD_MESSAGE_INFO.ordinal()] = 3;
            } catch (java.lang.NoSuchFieldError unused3) {
            }
            try {
                f157872a[com.google.protobuf.GeneratedMessageLite.MethodToInvoke.GET_DEFAULT_INSTANCE.ordinal()] = 4;
            } catch (java.lang.NoSuchFieldError unused4) {
            }
            try {
                f157872a[com.google.protobuf.GeneratedMessageLite.MethodToInvoke.GET_PARSER.ordinal()] = 5;
            } catch (java.lang.NoSuchFieldError unused5) {
            }
            try {
                f157872a[com.google.protobuf.GeneratedMessageLite.MethodToInvoke.GET_MEMOIZED_IS_INITIALIZED.ordinal()] = 6;
            } catch (java.lang.NoSuchFieldError unused6) {
            }
            try {
                f157872a[com.google.protobuf.GeneratedMessageLite.MethodToInvoke.SET_MEMOIZED_IS_INITIALIZED.ordinal()] = 7;
            } catch (java.lang.NoSuchFieldError unused7) {
            }
        }
    }

    /* JADX INFO: compiled from: BL */
    public static final class b extends com.google.protobuf.GeneratedMessageLite.Builder<com.bapis.bilibili.pagination.FeedPagination, com.bapis.bilibili.pagination.FeedPagination.b> implements F7.a {
        public /* synthetic */ b(int i10) {
            this();
        }

        public com.bapis.bilibili.pagination.FeedPagination.b clearIsRefresh() {
            copyOnWrite();
            ((com.bapis.bilibili.pagination.FeedPagination) this.instance).clearIsRefresh();
            return this;
        }

        public com.bapis.bilibili.pagination.FeedPagination.b clearOffset() {
            copyOnWrite();
            ((com.bapis.bilibili.pagination.FeedPagination) this.instance).clearOffset();
            return this;
        }

        public com.bapis.bilibili.pagination.FeedPagination.b clearPageSize() {
            copyOnWrite();
            ((com.bapis.bilibili.pagination.FeedPagination) this.instance).clearPageSize();
            return this;
        }

        @Override // F7.a
        public boolean getIsRefresh() {
            return ((com.bapis.bilibili.pagination.FeedPagination) this.instance).getIsRefresh();
        }

        @Override // F7.a
        public java.lang.String getOffset() {
            return ((com.bapis.bilibili.pagination.FeedPagination) this.instance).getOffset();
        }

        @Override // F7.a
        public com.google.protobuf.ByteString getOffsetBytes() {
            return ((com.bapis.bilibili.pagination.FeedPagination) this.instance).getOffsetBytes();
        }

        @Override // F7.a
        public int getPageSize() {
            return ((com.bapis.bilibili.pagination.FeedPagination) this.instance).getPageSize();
        }

        public com.bapis.bilibili.pagination.FeedPagination.b setIsRefresh(boolean z12) {
            copyOnWrite();
            ((com.bapis.bilibili.pagination.FeedPagination) this.instance).setIsRefresh(z12);
            return this;
        }

        public com.bapis.bilibili.pagination.FeedPagination.b setOffset(java.lang.String str) {
            copyOnWrite();
            ((com.bapis.bilibili.pagination.FeedPagination) this.instance).setOffset(str);
            return this;
        }

        public com.bapis.bilibili.pagination.FeedPagination.b setOffsetBytes(com.google.protobuf.ByteString byteString) {
            copyOnWrite();
            ((com.bapis.bilibili.pagination.FeedPagination) this.instance).setOffsetBytes(byteString);
            return this;
        }

        public com.bapis.bilibili.pagination.FeedPagination.b setPageSize(int i10) {
            copyOnWrite();
            ((com.bapis.bilibili.pagination.FeedPagination) this.instance).setPageSize(i10);
            return this;
        }

        private b() {
            super(com.bapis.bilibili.pagination.FeedPagination.DEFAULT_INSTANCE);
        }
    }

    static {
        com.bapis.bilibili.pagination.FeedPagination feedPagination = new com.bapis.bilibili.pagination.FeedPagination();
        DEFAULT_INSTANCE = feedPagination;
        com.google.protobuf.GeneratedMessageLite.registerDefaultInstance(com.bapis.bilibili.pagination.FeedPagination.class, feedPagination);
    }

    private FeedPagination() {
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearIsRefresh() {
        this.isRefresh_ = false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearOffset() {
        this.offset_ = getDefaultInstance().getOffset();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearPageSize() {
        this.pageSize_ = 0;
    }

    public static com.bapis.bilibili.pagination.FeedPagination getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    public static com.bapis.bilibili.pagination.FeedPagination.b newBuilder() {
        return DEFAULT_INSTANCE.createBuilder();
    }

    public static com.bapis.bilibili.pagination.FeedPagination parseDelimitedFrom(java.io.InputStream inputStream) throws java.io.IOException {
        return (com.bapis.bilibili.pagination.FeedPagination) com.google.protobuf.GeneratedMessageLite.parseDelimitedFrom(DEFAULT_INSTANCE, inputStream);
    }

    public static com.bapis.bilibili.pagination.FeedPagination parseFrom(java.nio.ByteBuffer byteBuffer) throws com.google.protobuf.InvalidProtocolBufferException {
        return (com.bapis.bilibili.pagination.FeedPagination) com.google.protobuf.GeneratedMessageLite.parseFrom(DEFAULT_INSTANCE, byteBuffer);
    }

    public static com.google.protobuf.Parser<com.bapis.bilibili.pagination.FeedPagination> parser() {
        return DEFAULT_INSTANCE.getParserForType();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setIsRefresh(boolean z12) {
        this.isRefresh_ = z12;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setOffset(java.lang.String str) {
        str.getClass();
        this.offset_ = str;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setOffsetBytes(com.google.protobuf.ByteString byteString) {
        com.google.protobuf.AbstractMessageLite.checkByteStringIsUtf8(byteString);
        this.offset_ = byteString.toStringUtf8();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setPageSize(int i10) {
        this.pageSize_ = i10;
    }

    @Override // com.google.protobuf.GeneratedMessageLite
    public final java.lang.Object dynamicMethod(com.google.protobuf.GeneratedMessageLite.MethodToInvoke methodToInvoke, java.lang.Object obj, java.lang.Object obj2) {
        switch (com.bapis.bilibili.pagination.FeedPagination.a.f157872a[methodToInvoke.ordinal()]) {
            case 1:
                return new com.bapis.bilibili.pagination.FeedPagination();
            case 2:
                return new com.bapis.bilibili.pagination.FeedPagination.b(0);
            case 3:
                return com.google.protobuf.GeneratedMessageLite.newMessageInfo(DEFAULT_INSTANCE, "\u0000\u0003\u0000\u0000\u0001\u0003\u0003\u0000\u0000\u0000\u0001\u0004\u0002Ȉ\u0003\u0007", new java.lang.Object[]{"pageSize_", "offset_", "isRefresh_"});
            case 4:
                return DEFAULT_INSTANCE;
            case 5:
                com.google.protobuf.Parser<com.bapis.bilibili.pagination.FeedPagination> defaultInstanceBasedParser = PARSER;
                if (defaultInstanceBasedParser == null) {
                    synchronized (com.bapis.bilibili.pagination.FeedPagination.class) {
                        try {
                            defaultInstanceBasedParser = PARSER;
                            if (defaultInstanceBasedParser == null) {
                                defaultInstanceBasedParser = new com.google.protobuf.GeneratedMessageLite.DefaultInstanceBasedParser<>(DEFAULT_INSTANCE);
                                PARSER = defaultInstanceBasedParser;
                            }
                        } catch (java.lang.Throwable th2) {
                            throw th2;
                        }
                        break;
                    }
                }
                return defaultInstanceBasedParser;
            case 6:
                return (byte) 1;
            case 7:
                return null;
            default:
                throw new java.lang.UnsupportedOperationException();
        }
    }

    @Override // F7.a
    public boolean getIsRefresh() {
        return this.isRefresh_;
    }

    @Override // F7.a
    public java.lang.String getOffset() {
        return this.offset_;
    }

    @Override // F7.a
    public com.google.protobuf.ByteString getOffsetBytes() {
        return com.google.protobuf.ByteString.copyFromUtf8(this.offset_);
    }

    @Override // F7.a
    public int getPageSize() {
        return this.pageSize_;
    }

    public static com.bapis.bilibili.pagination.FeedPagination.b newBuilder(com.bapis.bilibili.pagination.FeedPagination feedPagination) {
        return DEFAULT_INSTANCE.createBuilder(feedPagination);
    }

    public static com.bapis.bilibili.pagination.FeedPagination parseDelimitedFrom(java.io.InputStream inputStream, com.google.protobuf.ExtensionRegistryLite extensionRegistryLite) throws java.io.IOException {
        return (com.bapis.bilibili.pagination.FeedPagination) com.google.protobuf.GeneratedMessageLite.parseDelimitedFrom(DEFAULT_INSTANCE, inputStream, extensionRegistryLite);
    }

    public static com.bapis.bilibili.pagination.FeedPagination parseFrom(java.nio.ByteBuffer byteBuffer, com.google.protobuf.ExtensionRegistryLite extensionRegistryLite) throws com.google.protobuf.InvalidProtocolBufferException {
        return (com.bapis.bilibili.pagination.FeedPagination) com.google.protobuf.GeneratedMessageLite.parseFrom(DEFAULT_INSTANCE, byteBuffer, extensionRegistryLite);
    }

    public static com.bapis.bilibili.pagination.FeedPagination parseFrom(com.google.protobuf.ByteString byteString) throws com.google.protobuf.InvalidProtocolBufferException {
        return (com.bapis.bilibili.pagination.FeedPagination) com.google.protobuf.GeneratedMessageLite.parseFrom(DEFAULT_INSTANCE, byteString);
    }

    public static com.bapis.bilibili.pagination.FeedPagination parseFrom(com.google.protobuf.ByteString byteString, com.google.protobuf.ExtensionRegistryLite extensionRegistryLite) throws com.google.protobuf.InvalidProtocolBufferException {
        return (com.bapis.bilibili.pagination.FeedPagination) com.google.protobuf.GeneratedMessageLite.parseFrom(DEFAULT_INSTANCE, byteString, extensionRegistryLite);
    }

    public static com.bapis.bilibili.pagination.FeedPagination parseFrom(byte[] bArr) throws com.google.protobuf.InvalidProtocolBufferException {
        return (com.bapis.bilibili.pagination.FeedPagination) com.google.protobuf.GeneratedMessageLite.parseFrom(DEFAULT_INSTANCE, bArr);
    }

    public static com.bapis.bilibili.pagination.FeedPagination parseFrom(byte[] bArr, com.google.protobuf.ExtensionRegistryLite extensionRegistryLite) throws com.google.protobuf.InvalidProtocolBufferException {
        return (com.bapis.bilibili.pagination.FeedPagination) com.google.protobuf.GeneratedMessageLite.parseFrom(DEFAULT_INSTANCE, bArr, extensionRegistryLite);
    }

    public static com.bapis.bilibili.pagination.FeedPagination parseFrom(java.io.InputStream inputStream) throws java.io.IOException {
        return (com.bapis.bilibili.pagination.FeedPagination) com.google.protobuf.GeneratedMessageLite.parseFrom(DEFAULT_INSTANCE, inputStream);
    }

    public static com.bapis.bilibili.pagination.FeedPagination parseFrom(java.io.InputStream inputStream, com.google.protobuf.ExtensionRegistryLite extensionRegistryLite) throws java.io.IOException {
        return (com.bapis.bilibili.pagination.FeedPagination) com.google.protobuf.GeneratedMessageLite.parseFrom(DEFAULT_INSTANCE, inputStream, extensionRegistryLite);
    }

    public static com.bapis.bilibili.pagination.FeedPagination parseFrom(com.google.protobuf.CodedInputStream codedInputStream) throws java.io.IOException {
        return (com.bapis.bilibili.pagination.FeedPagination) com.google.protobuf.GeneratedMessageLite.parseFrom(DEFAULT_INSTANCE, codedInputStream);
    }

    public static com.bapis.bilibili.pagination.FeedPagination parseFrom(com.google.protobuf.CodedInputStream codedInputStream, com.google.protobuf.ExtensionRegistryLite extensionRegistryLite) throws java.io.IOException {
        return (com.bapis.bilibili.pagination.FeedPagination) com.google.protobuf.GeneratedMessageLite.parseFrom(DEFAULT_INSTANCE, codedInputStream, extensionRegistryLite);
    }
}
