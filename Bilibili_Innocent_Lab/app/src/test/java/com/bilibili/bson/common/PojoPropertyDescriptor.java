package com.bilibili.bson.common;

import java.lang.reflect.Type;

/** Host contract fixture: accessors and nullable flag verified in the 8.90.2 DEX. */
public final class PojoPropertyDescriptor {
    private final String key;
    private final Type type;
    private final int flags;

    public PojoPropertyDescriptor(String key, Type type, int flags) {
        this.key = key;
        this.type = type;
        this.flags = flags;
    }

    public String getKeyName() { return key; }
    public Type getType() { return type; }
    public boolean getNonNull() { return (flags & 1) != 0; }
}
