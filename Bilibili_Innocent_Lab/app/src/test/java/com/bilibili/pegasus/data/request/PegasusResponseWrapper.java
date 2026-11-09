package com.bilibili.pegasus.data.request;

import com.bilibili.pegasus.data.base.PegasusResponse;

/**
 * 宿主 PegasusResponseWrapper 的最小同形状替身：凑组入口只读 getResponse() 与 getRequestPram()。
 * 请求参数在宿主里是逐版混淆的 data class，这里只复刻它 toString() 的标签格式。
 */
public final class PegasusResponseWrapper {
    private final PegasusResponse response;
    private final RequestParam requestParam;

    public PegasusResponseWrapper(PegasusResponse response, int windowSizeType) {
        this.response = response;
        this.requestParam = new RequestParam(windowSizeType);
    }

    public PegasusResponse getResponse() {
        return response;
    }

    public RequestParam getRequestPram() {
        return requestParam;
    }

    public static final class RequestParam {
        private final int windowSizeType;

        RequestParam(int windowSizeType) {
            this.windowSizeType = windowSizeType;
        }

        @Override
        public String toString() {
            // 与 9.1–9.17.0 宿主同序：rawWindowSizeType 紧随其后，解析不能把它当成窗口档位。
            return "RequestParam(idx=0, refresh=false, flush=NORMAL, cnyInfo=null, windowSizeType="
                + windowSizeType + ", rawWindowSizeType=7)";
        }
    }
}
