package com.bapis.bilibili.app.view.v1;

/**
 * JVM 替身：模仿 protobuf-lite 生成的 {@code view.v1.Tag}。
 *
 * <p>真机核对（9.11.0）字段集：{@code name_} / {@code tagType_} / {@code uri_} / {@code id_}
 * / {@code likes_} / {@code hates_} / {@code liked_} / {@code hated_}。
 * 这里只保留判别需要的 {@code uri}，外加一个 {@code name} 便于测试可读。
 */
public final class Tag {

    private final String name;
    private final String uri;

    public Tag(String name, String uri) {
        this.name = name;
        this.uri = uri;
    }

    public String getName() {
        return name;
    }

    public String getUri() {
        return uri;
    }

    @Override
    public String toString() {
        return "Tag(" + name + ")";
    }
}
