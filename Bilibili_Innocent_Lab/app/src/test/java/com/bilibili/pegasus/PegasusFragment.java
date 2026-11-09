package com.bilibili.pegasus;

/** Test fixture: the home feed fragment whose onCreate decides whether to wait for the interest wizard. */
public class PegasusFragment {
    public int immediateLoads = 0;
    public int waitedForWizard = 0;

    public void onCreate(Object bundle) {
        if (!com.bilibili.pegasus.request.d.h()) {
            immediateLoads++;
        } else {
            waitedForWizard++;
        }
    }
}
