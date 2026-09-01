package com.bilibili.app.comm.list.common.api.model;

import com.google.gson.annotations.SerializedName;

public class PlayerArgs {
    public int unannotatedDuration;

    @SerializedName("duration_ms")
    public int durationMs;

    @SerializedName("duration")
    public int fakeDuration;

    public long getDuration() { return fakeDuration; }
}
