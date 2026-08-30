package com.bilibili.pegasus.data.base;

import com.bilibili.app.comm.list.common.api.model.PlayerArgs;

public class BasePegasusData {
    public String getBizType() { return "UGC"; }
    public Object getAdInfo() { return null; }
    public String getCardGoto() { return "av"; }
    public String getGoTo() { return "av"; }
    public String getUri() { return "bilibili://video/test"; }
    public String getParam() { return "BV1TEST"; }
    public String getTitle() { return "title"; }
    public String getSubtitle() { return "subtitle"; }
    public String getDesc() { return "desc"; }
    public PlayerArgs getPlayerArgs() { return new PlayerArgs(); }
}
