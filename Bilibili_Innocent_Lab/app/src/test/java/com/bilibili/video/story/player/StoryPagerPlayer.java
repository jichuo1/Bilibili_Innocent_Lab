package com.bilibili.video.story.player;

import com.bilibili.video.story.StoryDetail;
import java.util.List;

public final class StoryPagerPlayer {
    private StoryPlayer player;
    private StoryDetail current;

    public StoryPagerPlayer(String tag) {
    }

    /** 测试用：和真实分页器一样持有唯一的 StoryPlayer 引用与当前页条目。 */
    public void bind(StoryPlayer player, StoryDetail current) {
        this.player = player;
        this.current = current;
    }

    public StoryDetail getCurrentItem() {
        return current;
    }

    public void append(List<StoryDetail> items) {
    }

    public void insert(List<StoryDetail> items) {
    }

    public void unrelated(List<String> values, int position) {
    }
}
