package com.google.protobuf;

/** Exact repeated-list ABI verified in the local Bilibili 8.90.2 APK. */
public final class Internal {
    private Internal() { }
    public interface ProtobufList<E> extends java.util.List<E> {
        boolean isModifiable();
        void makeImmutable();
        ProtobufList<E> mutableCopyWithCapacity(int capacity);
    }
}
