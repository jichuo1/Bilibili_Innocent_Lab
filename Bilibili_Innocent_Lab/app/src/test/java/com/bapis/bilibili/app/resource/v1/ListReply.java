package com.bapis.bilibili.app.resource.v1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 形状照搬真实 `ListReply`：`newBuilder(X)` 静态工厂给具体 builder，见 {@link MessageLiteStub}。 */
public final class ListReply extends MessageLiteStub {
    private final List<PoolReply> pools;

    public ListReply(List<PoolReply> pools) {
        this.pools = Collections.unmodifiableList(new ArrayList<>(pools));
    }

    public List<PoolReply> getPoolsList() {
        return pools;
    }

    /** 真实消息类带这个非泛型重载；解析器用它取元素类型，不依赖泛型签名。 */
    public PoolReply getPools(int index) {
        return pools.get(index);
    }

    public static Builder newBuilder() {
        return new Builder(new ListReply(Collections.<PoolReply>emptyList()));
    }

    public static Builder newBuilder(ListReply source) {
        return new Builder(source);
    }

    public byte[] toByteArray() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream s = new java.io.DataOutputStream(out)) {
            s.writeInt(pools.size());
            for (PoolReply pool : pools) {
                s.writeUTF(pool.getPoolName());
                s.writeInt(pool.getModulesList().size());
                for (ModuleReply module : pool.getModulesList()) {
                    s.writeUTF(module.getModuleName());
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    public static ListReply parseFrom(byte[] bytes) {
        try (java.io.DataInputStream s = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            int poolCount = s.readInt();
            List<PoolReply> parsed = new ArrayList<>(poolCount);
            for (int i = 0; i < poolCount; i++) {
                String name = s.readUTF();
                int modCount = s.readInt();
                List<ModuleReply> mods = new ArrayList<>(modCount);
                for (int j = 0; j < modCount; j++) {
                    mods.add(new ModuleReply(s.readUTF()));
                }
                parsed.add(new PoolReply(name, mods));
            }
            return new ListReply(parsed);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected BuilderStub newConcreteBuilder() {
        return new Builder(this);
    }

    public static final class Builder extends BuilderStub {
        private final List<PoolReply> pools;

        private Builder(ListReply source) {
            pools = new ArrayList<>(source.pools);
        }

        public Builder clearPools() {
            pools.clear();
            return this;
        }

        public Builder addPools(PoolReply pool) {
            pools.add(pool);
            return this;
        }

        public Builder addPools(PoolReply.Builder pool) {
            pools.add(pool.build());
            return this;
        }

        public ListReply build() {
            return new ListReply(pools);
        }
    }
}
