package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

/** 候选的校验所有权独立于当前服务；旧服务死亡不能作废另一个新候选。 */
internal class ServiceCandidateGate<T : Any> {
    private var candidate: T? = null
    private var epoch = 0L
    @Synchronized fun arrive(value: T): Long { candidate = value; return ++epoch }
    @Synchronized fun remove(value: T) { if (candidate === value) { candidate = null; epoch++ } }
    @Synchronized fun matches(value: T, token: Long): Boolean = candidate === value && epoch == token
}
