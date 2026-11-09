package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart;

import android.os.Bundle;

// 只提供固定宿主操作；不接受包名、组件、用户编号或 shell 文本。
interface IHostRestartService {
    Bundle stopHost() = 0;
    Bundle startHost(String token) = 1;
    void destroy() = 16777114;
}
