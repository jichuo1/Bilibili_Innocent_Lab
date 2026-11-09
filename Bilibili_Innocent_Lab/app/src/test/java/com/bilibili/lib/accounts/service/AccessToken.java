package com.bilibili.lib.accounts.service;

/** Public return contract used by BiliAccounts in the inspected 8.90.2 APK. */
public interface AccessToken {
    String getAccessKey();
    boolean isValid();
    boolean isExpired();
}
