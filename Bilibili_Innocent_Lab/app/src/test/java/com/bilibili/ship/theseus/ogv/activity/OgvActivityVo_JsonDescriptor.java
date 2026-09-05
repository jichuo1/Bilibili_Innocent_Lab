package com.bilibili.ship.theseus.ogv.activity;

import com.bilibili.bson.common.PojoClassDescriptor;
import com.bilibili.bson.common.PojoPropertyDescriptor;
import java.util.List;

public final class OgvActivityVo_JsonDescriptor extends PojoClassDescriptor {
    // Deliberately differs from the observed obfuscated name "a".
    public static final PojoPropertyDescriptor[] shiftedName = {
        new PojoPropertyDescriptor("activity_id", int.class, 5),
        new PojoPropertyDescriptor("invite_drawer", OgvActivityVo.InviteDrawer.class, 4),
        new PojoPropertyDescriptor("invite_win", OgvActivityVo.InviteWin.class, 4),
        new PojoPropertyDescriptor("container", List.class, 21),
        new PojoPropertyDescriptor("watch_count_down_cfg", OgvActivityVo.Countdown.class, 4),
        new PojoPropertyDescriptor("independent_win", OgvActivityVo.IndependentWin.class, 0),
        new PojoPropertyDescriptor("pp_float_layer", OgvActivityVo.FloatLayer.class, 0),
        new PojoPropertyDescriptor("play_half_container", OgvActivityHalfScreenPopup.class, 4),
        new PojoPropertyDescriptor("float_ball", OgvActivityVo.FloatBall.class, 4)
    };

    @Override public Object constructWith(Object[] values) {
        return new OgvActivityVo(values[0] == null ? 0 : (Integer) values[0],
            (OgvActivityVo.InviteDrawer) values[1], (OgvActivityVo.InviteWin) values[2],
            (List<?>) values[3], (OgvActivityVo.Countdown) values[4],
            (OgvActivityVo.IndependentWin) values[5], (OgvActivityVo.FloatLayer) values[6],
            (OgvActivityHalfScreenPopup) values[7], (OgvActivityVo.FloatBall) values[8]);
    }
}
