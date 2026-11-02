package com.bilibili.lib.accounts.model;

public final class AccessToken implements com.bilibili.lib.accounts.service.AccessToken {
    private final String key;
    private final boolean valid;
    private final boolean expired;

    public AccessToken(String key, boolean valid, boolean expired) {
        this.key = key;
        this.valid = valid;
        this.expired = expired;
    }
    public String getAccessKey() { return key; }
    public boolean isValid() { return valid; }
    public boolean isExpired() { return expired; }
}
