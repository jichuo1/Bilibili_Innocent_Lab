package com.bilibili.ship.theseus.ogv.activity;

import java.util.List;

/** Nine distinct parameter positions mirror the verified Theseus model. */
public final class OgvActivityVo {
    public final int activityId;
    public final InviteDrawer inviteDrawer;
    public final InviteWin inviteWin;
    public final List<?> container;
    public final Countdown countdown;
    public final IndependentWin independentWin;
    public final FloatLayer floatLayer;
    public final OgvActivityHalfScreenPopup half;
    public final FloatBall floatBall;

    public OgvActivityVo(int activityId, InviteDrawer inviteDrawer, InviteWin inviteWin,
                         List<?> container, Countdown countdown, IndependentWin independentWin,
                         FloatLayer floatLayer, OgvActivityHalfScreenPopup half, FloatBall floatBall) {
        this.activityId = activityId;
        this.inviteDrawer = inviteDrawer;
        this.inviteWin = inviteWin;
        this.container = container;
        this.countdown = countdown;
        this.independentWin = independentWin;
        this.floatLayer = floatLayer;
        this.half = half;
        this.floatBall = floatBall;
    }

    public static final class InviteDrawer { }
    public static final class InviteWin { }
    public static final class Countdown { }
    public static final class IndependentWin { }
    public static final class FloatLayer { }
    public static final class FloatBall { }
}
