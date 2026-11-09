package com.bilibili.pegasus.data.base;

import com.bilibili.pegasus.PegasusHolderData;
import com.bilibili.pegasus.data.interestchoose.InterestChoose;
import java.util.Collections;
import java.util.List;

public class PegasusResponse {
    public InterestChoose interestChoose = null;

    public List<PegasusHolderData> getItems() {
        return Collections.emptyList();
    }

    /** The wizard's only data source on every supported build (single caller: PegasusResponseWrapper). */
    public InterestChoose getInterestChoose() {
        return interestChoose;
    }
}
