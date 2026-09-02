package com.bilibili.app.comm.list.common.api.model;

import com.google.gson.annotations.SerializedName;

public class PlayerArgs {
    public int unannotatedDuration;

    @SerializedName("aid")
    public long aid;

    @SerializedName("is_live")
    public int isLive;

    @SerializedName("room_id")
    public long roomId;

    @SerializedName("epid")
    public long epid;

    @SerializedName("pgc_season_id")
    public long pgcSeasonId;

    @SerializedName("duration_ms")
    public int durationMs;

    @SerializedName("duration")
    public int fakeDuration;

    public long getDuration() { return fakeDuration; }
}
