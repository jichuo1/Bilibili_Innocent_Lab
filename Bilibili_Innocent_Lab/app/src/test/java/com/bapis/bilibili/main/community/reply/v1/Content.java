package com.bapis.bilibili.main.community.reply.v1;

import java.util.Collections;
import java.util.Map;

public final class Content {
    public Map<String, Object> getUrls() {
        return getUrlsMap();
    }

    public Map<String, Object> getUrlsMap() {
        return Collections.emptyMap();
    }

    public String getMessage() {
        return "unchanged";
    }
}
