package com.bilibili.pegasus;

import com.bilibili.pegasus.data.base.BasePegasusData;

public class PegasusHolderData extends BasePegasusData {
    public String getHolderType() { return "small_cover_v2"; }

    /** 宿主 8.84–9.17.0 同名同签名；默认大卡，未覆写的夹具卡片从不参与小卡凑组。 */
    public HolderStyle getHolderStyle() { return HolderStyle.DOUBLE_COLUMN_LARGE_CARD; }
}
