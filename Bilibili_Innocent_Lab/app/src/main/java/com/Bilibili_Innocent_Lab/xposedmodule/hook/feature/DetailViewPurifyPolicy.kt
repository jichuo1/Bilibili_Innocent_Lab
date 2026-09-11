package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 详细页里**只能在 View 层处理**的那几个组件。
 *
 * ### 为什么有这一层
 *
 * 两类东西协议层动不了：
 *
 * 1. **客户端按状态渲染的控件**（创作团队的关注按钮）。2026-09-11 抓包判定：
 *    `Staff.attention` 在未关注时根本不被序列化（默认 0），而按钮此时是显示的 ⇒
 *    `setAttention(0)` 是无操作；写"已关注值"会谎报关注关系，且「已关注」按钮的点击
 *    语义是**取关**，误触会真的改动账号关系。详见长期文档 2026-09-11（三）。
 * 2. **协议面走的不是我们 Hook 的那一条**。实测 9.11.0：详细页由
 *    `viewunite.v1.View/View` 供数（deeplink `bilibili://united_video/…`，
 *    Activity `UnitedBizDetailsActivity`），而 `view.v1` 那套顶层字段改写**不影响本页**。
 *    热搜横条就属于这一类——在 View 层拿掉是当下唯一可验证的做法。
 *
 * ### 定位依据：资源名 + 结构特征，不是类名、不是文字
 *
 * - **不能拿 uiautomator 的 `class` 当类名**：那是无障碍泛化名（自定义 item 会报
 *   `android.view.ViewGroup` 这种抽象类）。
 * - **不按文字判别**：热搜横条的文案是「热搜第N名 · …」，N 和后面的词都会变。
 *   这里用的是**一组资源名同时存在**这个结构特征。
 */
internal object DetailViewPurifyPolicy {

    /** androidx 的类，`setAdapter` 只有一个重载。类名已回 dex 核实（classes13.dex）。 */
    const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
    const val CHILD_ATTACH_LISTENER_CLASS =
        "androidx.recyclerview.widget.RecyclerView\$OnChildAttachStateChangeListener"
    const val ADD_LISTENER_METHOD = "addOnChildAttachStateChangeListener"
    const val SET_ADAPTER_METHOD = "setAdapter"
    const val ATTACHED_CALLBACK = "onChildViewAttachedToWindow"

    /**
     * 一条屏蔽规则。
     *
     * @param itemIdName item 根自身的资源名。**非 null 时是最便宜的前置判据**——
     *   子项挂载时只做一次 int 比较就能把绝大多数列表挡掉。
     *   为 null 表示该组件的 item 根没有资源名，只能靠 [requiredIdNames] 认。
     * @param requiredIdNames 必须**同时存在**的后代资源名，即这个组件的结构指纹。
     * @param hideIdName 要隐藏的目标资源名；null = 隐藏 item 根自身（整条去掉）。
     */
    data class Rule(
        val capabilityId: String,
        val preferenceKey: String,
        val itemIdName: String?,
        val requiredIdNames: List<String>,
        val hideIdName: String?
    ) {
        /** 解析资源 id 时要用到的全部名字。 */
        val idNames: List<String>
            get() = (listOfNotNull(itemIdName) + requiredIdNames + listOfNotNull(hideIdName))
                .distinct()

        /**
         * 隐藏对象是不是 RecyclerView 的**item 根**。
         *
         * 这两种要分开处理：
         * - 隐藏 item **内部**的某个 View（关注按钮）：普通 `GONE` 就够了，
         *   它的父容器是正常 ViewGroup，会跳过 GONE 子视图重新布局（实测列表确实重排了）。
         * - 隐藏 **item 根**（热搜横条）：**光 `GONE` 不行**。`LinearLayoutManager`
         *   自己测量/摆放子项，**不像 `LinearLayout` 那样跳过 GONE 的孩子**，
         *   所以那一格的高度照样被占住——实测留下 175px 空白，下面所有内容坐标不变。
         *   必须同时把 `layoutParams.height` 与上下 margin 归零。
         */
        val hidesItemRoot: Boolean get() = hideIdName == null
    }

    /**
     * 创作团队的关注按钮。
     *
     * 层级：`RecyclerView → #vfl_avatar → { #avatar, #name_layout, #fl_follow_container → #follow }`。
     * **隐容器不隐图标**：实测容器 [380,1234]-[597,1311]，图标只有 [380,1234]-[485,1311]，
     * 容器宽一倍，隐容器才能把整块触区去掉（"易误触"要治的正是触区）。
     * 两个资源名在 8.84.0 / 9.1.0 / 9.7.0 / 9.11.0 都存在（aapt2 核对）。
     */
    val STAFF_FOLLOW = Rule(
        capabilityId = "detail_staff_follow_hidden",
        preferenceKey = FeaturePreferences.REMOVE_DETAIL_STAFF_FOLLOW,
        itemIdName = "vfl_avatar",
        requiredIdNames = listOf("fl_follow_container"),
        hideIdName = "fl_follow_container"
    )

    /**
     * 「热搜第N名 · …」通栏横条。
     *
     * 层级（9.11.0 实测）：item 根是**没有资源名**的可点击 ViewGroup，里面是
     * `#card_view_background` + `#ivIcon` + `#tvTitle` + `#tvSubtitle`
     * + `#endIconContainer → #ivEndIcon`（还有一个 `#tvConfirm`）。
     * 全是 camelCase（全页其余 id 是 snake_case），说明它是单独一个组件。
     * 命中后隐藏 **item 根**，整条横条消失。
     *
     * ### 判据余量：为什么是六个 id 而不是四个
     *
     * 2026-09-11 把 24 个存档宿主（8.84.0 → 9.11.0）的 layout 逐版扫了一遍：
     * 这组 id 全部同时出现的 layout **每版都只有一个**，就是
     * `theseus_detail_guide_strip`（对应协议里的 `ActivityGuidanceBar`）。
     *
     * 但原来只要求四个 id 时，**余量只有一个**：
     * `theseus_ogv_live_reserve_bar`（OGV 直播预约条）在 24 版里都是
     * `card_view_background` + `ivIcon` + `tvConfirm` + `tvTitle`，只差
     * `endIconContainer` 一个就会被误命中——而那是**另一个组件**，
     * 用户打开「隐藏热搜横条」不该把它一起拿走。
     *
     * 实测这三个 id 是横条每版都有、OGV 预约条每版都没有的：
     * `endIconContainer` / `ivEndIcon` / `tvSubtitle`。**全都要求上**，
     * 余量从 1 变 3。反方向的否决判据不存在（OGV 预约条的 id 集是横条的子集），
     * 所以只能靠多要求横条自己的 id。
     *
     * 8.84.0–8.98.0 还有个 `mall_theseus_detail_guide_strip`（5 个 id，
     * 多了 `tvSubtitle` 但没有 `endIconContainer` / `ivEndIcon`），同样被排除。
     *
     * 代价是**失效方向安全**：将来宿主从横条里去掉其中任一个 id，
     * 规则就不再命中（功能失效，而不是误删别的组件），
     * 而且协议层 [DetailUnitedModulePurifyPolicy] 那一层还在兜。
     */
    val HOT_BANNER = Rule(
        capabilityId = "detail_hot_banner_hidden",
        preferenceKey = FeaturePreferences.REMOVE_DETAIL_HOT_BANNER,
        itemIdName = null,
        requiredIdNames = listOf(
            "tvTitle", "ivIcon", "endIconContainer", "card_view_background",
            "ivEndIcon", "tvSubtitle"
        ),
        hideIdName = null
    )

    /**
     * 只用于测试与文档：横条判据必须把这几个组件排除在外。
     *
     * 每一项都是"在 24 个存档宿主里凑齐了横条部分 id 的另一个组件"，
     * 后面有人想精简 [HOT_BANNER] 的 requiredIdNames 时，
     * 这张表说明每一个 id 都在挡着谁。
     */
    val HOT_BANNER_MUST_NOT_MATCH = mapOf(
        // OGV 直播预约条：只差 endIconContainer / ivEndIcon / tvSubtitle
        "theseus_ogv_live_reserve_bar" to
            listOf("endIconContainer", "ivEndIcon", "tvSubtitle"),
        // 会场版引导条（8.84.0–8.98.0）：只差 endIconContainer / ivEndIcon
        "mall_theseus_detail_guide_strip" to listOf("endIconContainer", "ivEndIcon")
    )

    val rules = listOf(STAFF_FOLLOW, HOT_BANNER)

    val preferenceKeys: List<String> = rules.map { it.preferenceKey }

    fun rulesFor(enabledKeys: Set<String>): List<Rule> =
        rules.filter { it.preferenceKey in enabledKeys }

    /** 规则要用到的资源名必须**全部**解析成功；缺一个就不装这条，避免白跑查找。 */
    fun usable(rule: Rule, resolved: Map<String, Int>): Boolean =
        rule.idNames.all { (resolved[it] ?: 0) != 0 }
}
