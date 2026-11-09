package com.bilibili.nativelibrary;

import java.util.Map;

/** Fixture signer: joins the parameters in order and appends a recognisable signature. */
public final class LibBili {
    private LibBili() {}

    public static Object signQuery(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append('&');
        }
        return out.append("sign=fixture").toString();
    }
}
