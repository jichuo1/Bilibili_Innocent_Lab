package tv.danmaku.bili.ui.main2.mineV2;

/**
 * Shape fixture for the 9.16.1 Compose mine page host. Vf(isLogin, fromPageSelected) is the single non-static
 * (boolean, boolean):void method; it records a call instead of creating the force-login fragment. The decoys have
 * a different return type, a different arity, and a different static-ness, so only the real toggle matches the shape.
 */
public final class HomeUserCenterFragmentV2 {
    public int overlayCalls;
    public boolean lastIsLogin = true;

    public void Vf(boolean isLogin, boolean fromPageSelected) {
        overlayCalls++;
        lastIsLogin = isLogin;
    }

    public void Wf() {
    }

    public boolean Xf(boolean a, boolean b) {
        return a && b;
    }

    public void onPageSelected(java.util.Map<String, Object> extras) {
    }

    public static void Yf(boolean a, boolean b) {
    }
}
