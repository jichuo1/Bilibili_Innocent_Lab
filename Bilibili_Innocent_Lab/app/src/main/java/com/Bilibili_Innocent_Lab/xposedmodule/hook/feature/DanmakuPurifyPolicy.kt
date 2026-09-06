package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 弹幕净化的纯判定层：只做列表筛选，不接触 protobuf、反射和 Hook 边界。
 *
 * 权重是服务端下发的"优质度"（1–10，越高越优质）。宿主不下发该字段时整段会全是 0，
 * 直接按阈值删就等于清空弹幕——[hasUsableWeight] 就是为这种情况准备的熔断判据。
 */
internal object DanmakuPurifyPolicy {

    /** 弹幕权重取值区间；与设置页的可选档位一致。 */
    const val MIN_WEIGHT = 1
    const val MAX_WEIGHT = 10

    /** 权重过滤默认阈值：低于 3 的弹幕多为刷屏与复读。 */
    const val DEFAULT_MINIMUM_WEIGHT = 3

    /**
     * `DmColorfulType.VipGradualColor` 的兜底枚举值。
     *
     * 运行期优先读宿主自己的 `VipGradualColor_VALUE` 常量；只有读不到时才用这个数字。
     */
    const val VIP_GRADUAL_COLOR_VALUE = 60001

    /**
     * 按 [keep] 保留元素；全部保留时返回 null，调用方据此完全跳过 protobuf 写回。
     *
     * 判定本体在 [ProtobufListRetention]，动态页与搜索结果用的是同一条原语。
     */
    fun retain(source: List<*>, keep: (Any) -> Boolean): List<Any>? =
        ProtobufListRetention.retainOrNull(source, keep)

    /**
     * 本段是否存在可用的权重信号。
     *
     * 只要有一条弹幕的权重为正就认为字段有效；整段全 0 或全部读取失败时返回 false，
     * 调用方必须整段放行。
     */
    fun hasUsableWeight(source: List<*>, weightOf: (Any) -> Int?): Boolean =
        source.any { item -> item != null && (weightOf(item) ?: 0) > 0 }

    /** 设置页与宿主侧共用的阈值归一化，避免两边各写一份 coerce。 */
    fun normalizeWeight(value: Int): Int = value.coerceIn(MIN_WEIGHT, MAX_WEIGHT)
}
