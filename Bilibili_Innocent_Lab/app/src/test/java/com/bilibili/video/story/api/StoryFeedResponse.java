package com.bilibili.video.story.api;

import com.bilibili.video.story.StoryDetail;
import java.util.List;

public final class StoryFeedResponse {
    private List<StoryDetail> items;

    public List<StoryDetail> getItems() {
        return items;
    }
}
