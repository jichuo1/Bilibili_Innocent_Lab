package com.bilibili.pegasus.data.base;

import com.bilibili.app.comm.list.common.api.model.PlayerArgs;

public class BasePegasusData {
    public String getBizType() { return "UGC"; }
    public String getCardType() { return "CARD_TYPE_AV"; }
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
