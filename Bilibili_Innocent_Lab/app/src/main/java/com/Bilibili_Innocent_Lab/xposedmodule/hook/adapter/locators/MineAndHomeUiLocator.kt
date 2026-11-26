package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.BannerPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.BottomBarPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.DynamicTabsPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.FullNumberPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeComponentPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeTabPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeTopBarPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineAccountMinePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineComponentPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineEntryPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineVipPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.StoryFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeTabResourceAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.hasSuperclassNamed
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.implementsInterfaceNamed
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.rawClassOrNull
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.LocatorReflectUtils.toHookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isAbstract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isFinal
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isPublic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

/**
 * 我的页、首页顶栏/Tab/底栏/组件/Banner、动态页签、完整数字与 Story 竖屏流定位器。
 */
internal object MineAndHomeUiLocator {

    internal const val HOME_MENU_ITEM_CLASS = "com.bilibili.lib.homepage.startdust.menu.a"

    /**
     * 新 Compose 顶栏右上角项列表的收集 lambda（9.11.0(9110400) 实测存在）。
     *
     * **这是少见的"可以按名写死"的例外**：R8 把外层组件混淆成了
     * `tv.danmaku.bili.home.components.topbar.k`（由本类 `this$0` 字段类型确认），
     * 但 Kotlin 合成 lambda 的类描述符保留了原始外层类名，所以这个名字本身是稳定的。
     * 即便如此，方法仍按签名过滤而不按名字，缺失时整层降级、不影响老路径。
     */
    internal const val HOME_TOP_RIGHT_LAMBDA_CLASS =
        "tv.danmaku.bili.home.components.topbar.TopRightComponent\$initTopRight\$1\$1"
    internal val HOME_BASE_FRAGMENT_CANDIDATES = listOf(
        "tv.danmaku.bili.ui.main2.basic.BaseMainFrameFragment"
    )
    internal val HOME_MAIN_FRAGMENT_CANDIDATES = listOf(
        "tv.danmaku.bili.ui.main2.MainFragment"
    )
    internal val HOME_DEFAULT_WORD_CLASS_CANDIDATES = setOf(
        // 8.90.2
        "com.bilibili.app.comm.list.common.api.d",
        // 9.1.0–9.9.0
        "com.bilibili.app.comm.list.common.api.b"
    )
    internal val HOME_TOP_BAR_CANDIDATES = listOf(HOME_MENU_ITEM_CLASS, HOME_TOP_RIGHT_LAMBDA_CLASS) +
        HOME_BASE_FRAGMENT_CANDIDATES + HOME_MAIN_FRAGMENT_CANDIDATES
    internal const val MINE_FRAGMENT_CLASS =
        "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment"
    internal const val MINE_VIP_MANAGER_CLASS =
        "tv.danmaku.bili.ui.main2.mine.modularvip.MineVipModuleManager"
    internal const val DYNAMIC_MEDIATOR_FRAGMENT_CLASS =
        "com.bilibili.bplus.followinglist.home.mediator.MediatorFragment"
    internal const val DYNAMIC_MEDIATOR_TAB_CLASS =
        "com.bilibili.bplus.followinglist.home.mediator.MediatorTabLayout"
    internal const val KNTR_NUMBER_FORMAT_CLASS =
        "kntr.base.localization.NumberFormat_androidKt"
    internal val FULL_NUMBER_CLASS_CANDIDATES = listOf(
        "com.bilibili.base.util.NumberFormat",
        "com.bilibili.n9.util.NumberFormat",
        "com.bilibili.lib.utils.NumberFormat",
        KNTR_NUMBER_FORMAT_CLASS
    )
    internal val HOME_FRAGMENT_V2_CLASS_CANDIDATES = listOf(
        "tv.danmaku.bili.ui.main2.HomeFragmentV2"
    )

    // 主路径取 HomeFragmentV2 构建方法的泛型实参；这里只是泛型不可读时的兜底。
    // 9.17.0(9170300) 的 Tab 模型由 resource.z 重排为 resource.y，字段布局不变；z 变成无字符串字段的 lambda，
    // 会被 locateHomeTabs 的字符串字段数量检查拒绝。
    internal val HOME_TAB_RESOURCE_CLASS_CANDIDATES = listOf(
        "tv.danmaku.bili.ui.main2.resource.y",
        "tv.danmaku.bili.ui.main2.resource.z"
    )
    internal const val HOST_FRAGMENT_CLASS = "androidx.fragment.app.Fragment"
    internal val MINE_MENU_GROUP_CLASS_CANDIDATES = listOf(
        "com.bilibili.lib.homepage.mine.MenuGroupV2",
        "com.bilibili.lib.homepage.mine.MenuGroup"
    )
    internal val MINE_MENU_ITEM_CLASS_CANDIDATES = listOf(
        "com.bilibili.lib.homepage.mine.MenuGroupV2\$Item",
        "com.bilibili.lib.homepage.mine.MenuGroup\$Item"
    )
    internal const val STORY_FEED_RESPONSE_CLASS =
        "com.bilibili.video.story.api.StoryFeedResponse"
    internal const val STORY_DETAIL_CLASS = "com.bilibili.video.story.StoryDetail"
    internal const val STORY_PAGER_PLAYER_CLASS =
        "com.bilibili.video.story.player.StoryPagerPlayer"
    internal val BOTTOM_TAB_HOST_CLASS_CANDIDATES = listOf(
        "com.bilibili.lib.homepage.widget.TabHost"
    )

    /**
     * 结构化定位“我的”页入口。已确认的字段漂移：
     * 8.90.2/9.8.0 由运行时结构匹配；9.1.1=of+n1/m1、9.2.0=pf+m1/l1、
     * 9.3.0=nf+u0/t0。只依赖方法参数、List 和 RecyclerView.Adapter 类型。
     */
    internal fun locateMineEntry(loader: ClassLoader, allowModuleInstance: Boolean = false): MineEntryPoint? = runCatching {
        val fragmentName = MINE_FRAGMENT_CLASS
        val accountMineName = "tv.danmaku.bili.ui.main2.api.AccountMine"
        val itemName = "com.bilibili.lib.homepage.mine.MenuGroup\$Item"
        val fragment = KavaMemberLookup.classOrNull(loader, fragmentName)
            ?: return@runCatching null
        val accountMine = KavaMemberLookup.classOrNull(loader, accountMineName)
            ?: return@runCatching null
        val itemClass = KavaMemberLookup.classOrNull(loader, itemName)
            ?: return@runCatching null

        val staticBuilds = KavaMemberLookup.declaredMethods(fragment, makeAccessible = true) {
            it.isStatic && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(fragment, accountMine))
        }
        val moduleInstance = staticBuilds.isEmpty()
        val builds = if (moduleInstance) {
            if (!allowModuleInstance) return@runCatching null
            // 9.15/9.19/9.20: 先找唯一的非 static (AccountMine)->void 公开终局方法；不唯一或缺失时再匹配唯一的 (AccountMine, boolean)->void。
            val instanceOneArg = KavaMemberLookup.declaredMethods(fragment, makeAccessible = true) {
                it.isPublic && it.isFinal && !it.isStatic && !it.isSynthetic && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(accountMine))
            }.singleOrNull()
            val build = instanceOneArg ?: KavaMemberLookup.declaredMethods(fragment, makeAccessible = true) {
                it.isPublic && it.isFinal && !it.isStatic && !it.isSynthetic && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(accountMine, classOf<Boolean>()))
            }.singleOrNull() ?: return@runCatching null
            listOf(build)
        } else staticBuilds

        val listFields = KavaMemberLookup.declaredFields(fragment, makeAccessible = true) {
            !it.isStatic && it.type isSubclassOf classOf<List<*>>()
        }
        val groups = listFields.singleOrNull() ?: return@runCatching null

        val adapter = KavaMemberLookup.declaredFields(fragment, makeAccessible = true) {
            !it.isStatic &&
                it.type.hasSuperclassNamed("androidx.recyclerview.widget.RecyclerView\$Adapter")
        }.singleOrNull()

        val clickOwners = (listOf("$fragmentName\$e", "$fragmentName\$i") + fragment.declaredClasses.map { it.name }).distinct()
        val click = clickOwners
            .asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .mapNotNull { clickClass ->
                KavaMemberLookup.declaredMethods(clickClass, makeAccessible = true) {
                    !it.isStatic && !it.isSynthetic && it.returnType == Void.TYPE &&
                        it.parameterTypes.contentEquals(arrayOf(itemClass)) &&
                        (!moduleInstance || (it.isPublic && it.isFinal))
                }.singleOrNull()
            }
            .firstOrNull() ?: return@runCatching null

        MineEntryPoint(builds.map { it.toHookPoint() }, groups.name, adapter?.name, click.toHookPoint())
    }.getOrNull()

    /**
     * 结构化定位“我的”页 VIP 卡片。8.90.2、9.1.0 与 9.9.0 的混淆字段名不同，
     * 但 Fragment 持有唯一 MineVipModuleManager、管理器持有唯一 ViewBinding，且
     * ViewBinding 暴露无参 getRoot。只在适配期扫描并缓存精确成员描述。
     */
    internal fun locateMineVip(loader: ClassLoader): MineVipPoint? = runCatching {
        val fragment = KavaMemberLookup.classOrNull(loader, MINE_FRAGMENT_CLASS)
            ?: return@runCatching null
        val manager = KavaMemberLookup.classOrNull(loader, MINE_VIP_MANAGER_CLASS)
            ?: return@runCatching null
        val onResume = KavaMemberLookup.declaredMethods(fragment, makeAccessible = true) {
            !it.isStatic && it.name == "onResume" && it.parameterCount == 0 &&
                it.returnType == Void.TYPE
        }.singleOrNull() ?: return@runCatching null
        val managerField = KavaMemberLookup.declaredFields(fragment, makeAccessible = true) {
            !it.isStatic && it.type isSubclassOf manager
        }.singleOrNull() ?: return@runCatching null
        val bindingField = KavaMemberLookup.declaredFields(manager, makeAccessible = true) {
            !it.isStatic &&
                it.type.implementsInterfaceNamed("androidx.viewbinding.ViewBinding")
        }.singleOrNull() ?: return@runCatching null
        val rootGetter = KavaMemberLookup.declaredMethods(
            bindingField.type,
            makeAccessible = true
        ) {
            !it.isStatic && !it.isBridge && it.name == "getRoot" &&
                it.parameterCount == 0 && it.returnType isSubclassOf classOf<View>()
        }.singleOrNull() ?: return@runCatching null

        MineVipPoint(
            onResume = onResume.toHookPoint().copy(viewField = managerField.name),
            bindingField = bindingField.name,
            rootGetter = rootGetter.toHookPoint()
        )
    }.getOrNull()

    /**
     * “我的”页入口的数据层剪枝定位：以 AccountMine 为锚，在 Fragment 构建菜单前剪枝。
     * 旧构建方法接收 (Fragment, AccountMine)，9.19.0 的实例构建方法只接收 AccountMine。
     * 实例形状复用 locateMineEntry 的菜单结构复核，这里另采集数据剪枝所需的字段名。
     * 只有 sections、group.itemList 和 item.title 是剪枝必需字段；附件字段缺失时保留对应组件。
     */
    internal fun locateMineAccountMinePoints(loader: ClassLoader): MineAccountMinePoints? = runCatching {
        val fragmentName = MINE_FRAGMENT_CLASS
        val accountMineName = "tv.danmaku.bili.ui.main2.api.AccountMine"
        val groupName = "com.bilibili.lib.homepage.mine.MenuGroup"
        val itemName = "com.bilibili.lib.homepage.mine.MenuGroup\$Item"
        val fragment = KavaMemberLookup.classOrNull(loader, fragmentName)
            ?: return@runCatching null
        val accountMine = KavaMemberLookup.classOrNull(loader, accountMineName)
            ?: return@runCatching null
        val group = KavaMemberLookup.classOrNull(loader, groupName)
            ?: return@runCatching null
        val item = KavaMemberLookup.classOrNull(loader, itemName)
            ?: return@runCatching null

        val builds = KavaMemberLookup.declaredMethods(fragment, makeAccessible = true) {
            it.isStatic && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(fragment, accountMine))
        }.map { it.toHookPoint() }.ifEmpty {
            locateMineEntry(loader, allowModuleInstance = true)?.buildMethods.orEmpty()
        }
        if (builds.isEmpty()) return@runCatching null

        fun fieldName(owner: Class<*>, predicate: (Field) -> Boolean): String? =
            KavaMemberLookup.firstDeclaredFieldOrNull(owner, makeAccessible = true) { predicate(it) }
                ?.name

        val sectionsField = fieldName(accountMine) {
            it.name == "sectionListV2" && it.type isSubclassOf classOf<List<*>>()
        } ?: fieldName(accountMine) {
            it.name.startsWith("sectionListV2") && it.type isSubclassOf classOf<List<*>>()
        } ?: return@runCatching null
        val groupTitleField = fieldName(group) {
            it.name == "title" && it.type == classOf<String>()
        }
        val groupItemsField = fieldName(group) {
            !it.isStatic && !it.isFinal &&
                it.type isSubclassOf classOf<List<*>>() &&
                (it.genericType as? ParameterizedType)
                    ?.actualTypeArguments?.singleOrNull()?.rawClassOrNull()?.name == itemName
        } ?: return@runCatching null
        val itemTitleField = fieldName(item) {
            it.name == "title" && it.type == classOf<String>()
        } ?: return@runCatching null
        val itemIdField = fieldName(item) { it.name == "id" }
        val itemUriField = fieldName(item) {
            it.name == "uri" && it.type == classOf<String>()
        }
        val itemVisibleField = fieldName(item) { it.name == "visible" }
        val itemLocalShowField = fieldName(item) { it.name == "localShow" }
        val liveTipField = fieldName(accountMine) { it.name == "liveTip" }
        val vipSectionRightField = fieldName(accountMine) { it.name == "vipSectionRight" }
        val sectionButtonField = fieldName(group) { it.name == "button" }

        MineAccountMinePoints(
            buildMethods = builds,
            accountMineClass = accountMineName,
            sectionListV2Field = sectionsField,
            groupClass = groupName,
            groupTitleField = groupTitleField,
            groupItemListField = groupItemsField,
            itemClass = itemName,
            itemTitleField = itemTitleField,
            itemIdField = itemIdField,
            itemUriField = itemUriField,
            itemVisibleField = itemVisibleField,
            itemLocalShowField = itemLocalShowField,
            liveTipField = liveTipField,
            vipSectionRightField = vipSectionRightField,
            sectionButtonField = sectionButtonField
        )
    }.getOrNull()

    /**
     * “我的”页保留 MenuGroup(V2) 公开 getter 边界，同时保留可验证的旧菜单构建字段链。
     * 新旧模型可并存，不能因闲置 getter 存在而丢弃实际消费路径。
     */
    internal fun locateMineComponents(
        loader: ClassLoader,
        mineEntry: MineEntryPoint? = null
    ): MineComponentPoints? = runCatching {
        val lists = MINE_MENU_GROUP_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getItemList" && method.parameterCount == 0 &&
                        (method.returnType isSubclassOf classOf<List<*>>()) && !method.isStatic
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        val titles = MINE_MENU_ITEM_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getTitle" && method.parameterCount == 0 &&
                        method.returnType == classOf<String>() && !method.isStatic
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        fun itemGetter(name: String, requireString: Boolean): List<HookPoint> =
            MINE_MENU_ITEM_CLASS_CANDIDATES.asSequence()
                .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
                .flatMap { owner ->
                    KavaMemberLookup.methods(
                        owner,
                        includeSuperclasses = true,
                        makeAccessible = true
                    ) { method ->
                        method.name == name && method.parameterCount == 0 && !method.isStatic &&
                            method.returnType != Void.TYPE &&
                            (!requireString || method.returnType == classOf<String>())
                    }.asSequence()
                }
                .distinctBy(Method::toGenericString)
                .map { it.toHookPoint() }
                .toList()
        val ids = itemGetter("getId", requireString = false)
        val uris = itemGetter("getUri", requireString = true)
        val groupTitles = MINE_MENU_GROUP_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.methods(
                    owner,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getTitle" && method.parameterCount == 0 && !method.isStatic &&
                        method.returnType == classOf<String>()
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        val legacy = locateLegacyMineComponents(loader, mineEntry ?: locateMineEntry(loader))
        if (lists.isNotEmpty() && titles.isNotEmpty()) {
            return@runCatching (legacy ?: MineComponentPoints(emptyList(), emptyList())).copy(
                itemListGetters = lists,
                itemTitleGetters = titles,
                itemIdGetters = ids,
                itemUriGetters = uris,
                groupTitleGetters = groupTitles
            )
        }
        legacy
    }.getOrNull()

    /** 仅供旧公开字段模型与单元测试使用；字段链在适配期确定，运行期直接 Field#get/set。 */
    internal fun locateLegacyMineComponents(
        loader: ClassLoader,
        mineEntry: MineEntryPoint?
    ): MineComponentPoints? = runCatching {
        val entry = mineEntry ?: return@runCatching null
        for (groupName in MINE_MENU_GROUP_CLASS_CANDIDATES) {
            val group = KavaMemberLookup.classOrNull(loader, groupName) ?: continue
            val itemListFields = KavaMemberLookup.declaredFields(
                group,
                makeAccessible = true
            ) { field ->
                !field.isStatic &&
                    !field.isFinal &&
                    field.type isSubclassOf classOf<List<*>>() &&
                    ((field.genericType as? ParameterizedType)
                        ?.actualTypeArguments
                        ?.singleOrNull()
                        ?.rawClassOrNull()
                        ?.name in MINE_MENU_ITEM_CLASS_CANDIDATES)
            }
            val itemListField = itemListFields.singleOrNull() ?: continue
            val itemClass = (itemListField.genericType as? ParameterizedType)
                ?.actualTypeArguments
                ?.singleOrNull()
                ?.rawClassOrNull() ?: continue
            val titleField = KavaMemberLookup.declaredFields(
                itemClass,
                makeAccessible = true
            ) { field ->
                !field.isStatic && field.name == "title" && field.type == classOf<String>()
            }.singleOrNull() ?: continue
            return@runCatching MineComponentPoints(
                itemListGetters = emptyList(),
                itemTitleGetters = emptyList(),
                legacyBuildMethods = entry.buildMethods,
                legacyGroupListField = entry.groupListField,
                legacyAdapterField = entry.adapterField,
                legacyGroupClassName = group.name,
                legacyItemListField = itemListField.name,
                legacyItemClassName = itemClass.name,
                legacyItemTitleField = titleField.name
            )
        }
        null
    }.getOrNull()

    /**
     * 首页顶部栏结构化定位：
     * - 游戏菜单构建方法在 8.90.2 为 c(Menu, MenuInflater)，9.1.0–9.9.0 为 b(...)；
     * - 默认搜索词模型在 8.90.2 为 api.d，9.1.0–9.9.0 为 api.b。
     * 因此只依赖参数/返回类型与 SwitchTextView 字段特征，不缓存宿主实例。
     */
    internal fun locateHomeTopBar(loader: ClassLoader): HomeTopBarPoints? = runCatching {
        val menuOwner = KavaMemberLookup.classOrNull(loader, HOME_MENU_ITEM_CLASS)
        val gameMenu = menuOwner?.let { owner ->
            val method = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                !it.isStatic && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(
                        arrayOf(classOf<Menu>(), classOf<MenuInflater>())
                    )
            }.singleOrNull() ?: return@let null
            val configField = KavaMemberLookup.declaredFields(
                owner,
                makeAccessible = true
            ) { field ->
                !field.isStatic &&
                    field.type.enclosingClass == owner &&
                    KavaMemberLookup.declaredFields(field.type) {
                        it.type == classOf<String>()
                    }.isNotEmpty()
            }.singleOrNull() ?: return@let null
            method.toHookPoint().copy(viewField = configField.name)
        }

        // 新 Compose 路径：只认签名 `(List, Continuation) -> Object`。
        // 同名的桥接方法是 `(Object, Object) -> Object`，靠参数 0 的类型把它排除掉；
        // 命中不到就整层缺失，由安装器单独降级，绝不影响上面的老路径。
        val composeGameMenu = KavaMemberLookup.classOrNull(loader, HOME_TOP_RIGHT_LAMBDA_CLASS)
            ?.let { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                    !it.isStatic && it.name == "invoke" && it.parameterCount == 2 &&
                        it.parameterTypes[0] == classOf<List<*>>() &&
                        it.returnType == classOf<Any>()
                }.singleOrNull()?.toHookPoint()
            }

        val baseOnViewCreated = HOME_BASE_FRAGMENT_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .mapNotNull { owner ->
                val searchFields = KavaMemberLookup.declaredFields(
                    owner,
                    makeAccessible = true
                ) {
                    (it.type isSubclassOf classOf<TextView>()) &&
                        it.type.simpleName == "SwitchTextView"
                }
                val searchField = searchFields.singleOrNull() ?: return@mapNotNull null
                val method = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                    !it.isStatic && it.returnType == Void.TYPE &&
                        it.name == "onViewCreated" &&
                        it.parameterTypes.contentEquals(
                            arrayOf(classOf<View>(), classOf<Bundle>())
                        )
                }.singleOrNull() ?: return@mapNotNull null
                method.toHookPoint().copy(viewField = searchField.name)
            }
            .firstOrNull()

        val defaultWordMethods = if (baseOnViewCreated == null) {
            emptyList()
        } else {
            HOME_MAIN_FRAGMENT_CANDIDATES.asSequence()
                .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
                .map { owner ->
                    KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                        !it.isStatic && !it.isAbstract && it.returnType == Void.TYPE &&
                            it.parameterCount == 1 && isHomeDefaultWordEntry(it)
                    }.distinctBy(Method::toGenericString)
                }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
                .map { it.toHookPoint() }
        }

        val kotlinWords = KotlinDefaultWordsLocator.direct(loader)?.let(KotlinDefaultWordsLocator::point)
        if (gameMenu == null && composeGameMenu == null &&
            baseOnViewCreated == null && defaultWordMethods.isEmpty() && kotlinWords == null
        ) {
            null
        } else {
            HomeTopBarPoints(gameMenu, composeGameMenu, baseOnViewCreated, defaultWordMethods, kotlinWords)
        }
    }.getOrNull()

    private fun isHomeDefaultWordEntry(method: Method): Boolean {
        val model = method.parameterTypes[0]
        if (model.name in HOME_DEFAULT_WORD_CLASS_CANDIDATES) return true
        // 9.15+: P0/yc 及 R8 重命名接口均只声明四个无混淆 getter。
        if (method.declaringClass.name !in HOME_MAIN_FRAGMENT_CANDIDATES || !model.isInterface) return false
        fun returns(name: String, type: Class<*>): Boolean =
            KavaMemberLookup.methodOrNull(model, name)?.let { !it.isStatic && it.returnType == type } == true
        return returns("getShow", String::class.java) &&
            returns("getAnimationTimeMilli", Long::class.javaPrimitiveType!!) &&
            returns("enableAnimation", Boolean::class.javaPrimitiveType!!) &&
            returns("isShowFront", Boolean::class.javaPrimitiveType!!)
    }

    /** 首页 Tab 构建方法：单个 List 参数、List 返回值，且参数泛型为 main2.resource。 */
    internal fun locateHomeTabs(loader: ClassLoader): HomeTabPoints? = runCatching {
        val candidates = HOME_FRAGMENT_V2_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    !method.isStatic && !method.isAbstract && method.parameterCount == 1 &&
                        (method.parameterTypes[0] isSubclassOf classOf<List<*>>()) &&
                        (method.returnType isSubclassOf classOf<List<*>>()) &&
                        method.genericParameterTypes[0].toString().contains("main2.resource.")
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .toList()
        val build = candidates.singleOrNull() ?: return@runCatching null
        val resource = (build.genericParameterTypes[0] as? ParameterizedType)
            ?.actualTypeArguments?.singleOrNull() as? Class<*>
            ?: HOME_TAB_RESOURCE_CLASS_CANDIDATES.firstNotNullOfOrNull {
                KavaMemberLookup.classOrNull(loader, it)
            }
            ?: return@runCatching null
        HomeTabResourceAdapter.buildPoints(build, resource)
    }.getOrNull()

    /** 首页子组件仅使用宿主 AndroidX Fragment 的公开生命周期和父级读取方法。 */
    internal fun locateHomeComponents(loader: ClassLoader): HomeComponentPoints? = runCatching {
        val fragment = KavaMemberLookup.classOrNull(loader, HOST_FRAGMENT_CLASS)
            ?: return@runCatching null
        val onViewCreated = KavaMemberLookup.methodOrNull(
            fragment,
            "onViewCreated",
            classOf<View>(),
            classOf<Bundle>()
        )?.takeIf { !it.isStatic && it.returnType == Void.TYPE }
            ?: return@runCatching null
        val parent = KavaMemberLookup.methodOrNull(fragment, "getParentFragment")
            ?.takeIf { !it.isStatic && it.returnType.name == HOST_FRAGMENT_CLASS }
            ?: return@runCatching null
        HomeComponentPoints(onViewCreated.toHookPoint(), parent.toHookPoint())
    }.getOrNull()

    /**
     * V8Banner 从 8.90.2 到 9.9.0 均继承 SwiperBanner；父类的 attach、setAdapter 与
     * visibility 回调是稳定低频入口。Hook 时仍按 V8Banner 实例过滤，不影响其它轮播控件。
     */
    internal fun locateBanner(loader: ClassLoader): BannerPoint? = runCatching {
        val bannerName = "com.bilibili.pegasus.holders.bannerv8.V8Banner"
        val banner = KavaMemberLookup.classOrNull(loader, bannerName)
            ?: return@runCatching null
        var currentOwner: Class<*>? = banner.superclass
        while (
            currentOwner != null &&
            currentOwner.name != "com.bilibili.app.comm.list.widget.swiper.SwiperBanner"
        ) {
            currentOwner = currentOwner.superclass
        }
        val owner = currentOwner ?: return@runCatching null
        val hooks = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            when (it.name) {
                "onAttachedToWindow" -> it.parameterCount == 0
                "setAdapter" -> it.parameterCount == 1 &&
                    it.parameterTypes[0].name.endsWith(".SwiperBannerAdapter")
                "onVisibilityChanged" -> it.parameterTypes.contentEquals(
                    arrayOf(View::class.java, Integer.TYPE)
                )
                else -> false
            }
        }.map { it.toHookPoint() }
        if (hooks.none { it.methodName == "onAttachedToWindow" }) return@runCatching null
        BannerPoint(bannerName, hooks)
    }.getOrNull()

    /** 底栏按 TabHost 的公开 tabs 列表和唯一 (int, View) 绑定入口定位。 */
    internal fun locateBottomBar(loader: ClassLoader): BottomBarPoints? = runCatching {
        for (ownerName in BOTTOM_TAB_HOST_CLASS_CANDIDATES) {
            val owner = KavaMemberLookup.classOrNull(loader, ownerName) ?: continue
            val tabsGetter = KavaMemberLookup.methods(
                owner,
                includeSuperclasses = true,
                makeAccessible = true
            ) { method ->
                method.name == "getTabs" && !method.isStatic && method.parameterCount == 0 &&
                    (method.returnType isSubclassOf classOf<List<*>>())
            }.distinctBy(Method::toGenericString).singleOrNull() ?: continue
            val itemClass = (tabsGetter.genericReturnType as? ParameterizedType)
                ?.actualTypeArguments?.singleOrNull() as? Class<*> ?: continue
            val bind = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                !method.isStatic && method.returnType == Void.TYPE && method.parameterCount == 2 &&
                    method.parameterTypes[0] == classOf<Int>() &&
                    (method.parameterTypes[1] isSubclassOf classOf<View>())
            }.singleOrNull() ?: continue
            val strings = KavaMemberLookup.declaredFields(itemClass, makeAccessible = true) {
                !it.isStatic && it.type == classOf<String>()
            }.map { it.name }
            if (strings.isEmpty()) continue
            return@runCatching BottomBarPoints(
                tabsGetter = tabsGetter.toHookPoint(),
                bindTabMethod = bind.toHookPoint(),
                itemClassName = itemClass.name,
                itemStringFields = strings
            )
        }
        null
    }.getOrNull()

    /**
     * 动态页页签定位。8.90.2、9.1.x 与 9.9.0 的渲染方法可能被 R8 内联，不能依赖
     * 某个混淆方法名；位置映射始终通过 MediatorFragment 唯一的无参 List getter，
     * 页签添加始终落到 TabLayout.addTab(Tab, boolean)。页签模型由 getter 泛型签名取得，
     * 再验证 title/name 两个 String 字段；任一结构不唯一即不安装，避免位置错配。
     */
    internal fun locateDynamicTabs(loader: ClassLoader): DynamicTabsPoint? = runCatching {
        val fragment = KavaMemberLookup.classOrNull(loader, DYNAMIC_MEDIATOR_FRAGMENT_CLASS)
            ?: return@runCatching null
        val mediatorTab = KavaMemberLookup.classOrNull(loader, DYNAMIC_MEDIATOR_TAB_CLASS)
            ?: return@runCatching null
        if (!mediatorTab.hasSuperclassNamed("com.google.android.material.tabs.TabLayout")) {
            return@runCatching null
        }
        val listGetter = KavaMemberLookup.declaredMethods(fragment, makeAccessible = true) {
            !it.isStatic && it.parameterCount == 0 && classOf<List<*>>() == it.returnType
        }.singleOrNull() ?: return@runCatching null
        val itemClass = (listGetter.genericReturnType as? ParameterizedType)
            ?.actualTypeArguments
            ?.singleOrNull() as? Class<*>
            ?: return@runCatching null
        val stringFields = KavaMemberLookup.declaredFields(itemClass, makeAccessible = true) {
            !it.isStatic && !it.isSynthetic && it.type == classOf<String>()
        }
        val namedTitle = stringFields.singleOrNull { it.name == "a" }
        val namedName = stringFields.singleOrNull { it.name == "b" }
        val (titleField, nameField) = when {
            namedTitle != null && namedName != null -> namedTitle to namedName
            stringFields.size == 2 -> stringFields[0] to stringFields[1]
            else -> return@runCatching null
        }
        val tabLayout = mediatorTab.superclass?.let { current ->
            generateSequence(current) { it.superclass }
                .firstOrNull { it.name == "com.google.android.material.tabs.TabLayout" }
        } ?: return@runCatching null
        val tabClass = KavaMemberLookup.classOrNull(
            loader,
            "com.google.android.material.tabs.TabLayout\$Tab"
        ) ?: return@runCatching null
        val addTab = KavaMemberLookup.methodOrNull(
            tabLayout,
            "addTab",
            tabClass,
            classOf<Boolean>()
        ) ?: return@runCatching null
        val customViewGetter = KavaMemberLookup.methodOrNull(
            tabClass,
            "getCustomView"
        )?.takeIf { it.returnType isSubclassOf classOf<View>() }
            ?: return@runCatching null

        DynamicTabsPoint(
            listGetter = listGetter.toHookPoint(),
            addTab = addTab.toHookPoint(),
            tabCustomViewGetter = customViewGetter.toHookPoint(),
            mediatorTabClassName = mediatorTab.name,
            itemClassName = itemClass.name,
            itemTitleField = titleField.name,
            itemNameField = nameField.name
        )
    }.getOrNull()

    /**
     * 定位数字缩写格式化器。8.90.2、9.1.0 与 9.9.0 均同时保留 Java NumberFormat；
     * “我的”页则直接调用 kntr 的 Kotlin 顶层函数。只接受 static、String 返回值且首参
     * 为 int/long（含装箱）或 kntr 的数字字符串重载，避免碰触时间/小数格式化方法。
     */
    internal fun locateFullNumbers(loader: ClassLoader): FullNumberPoints? = runCatching {
        val acceptedNames = setOf(
            "format",
            "formatWithComma",
            "formatNumber",
            "format\$default",
            "formatNumber\$default"
        )
        val numericTypes = setOf(
            classOf<Int>(),
            classOf<Long>(),
            classOf<Int>(primitiveType = false),
            classOf<Long>(primitiveType = false)
        )
        val methods = FULL_NUMBER_CLASS_CANDIDATES.asSequence()
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    val firstType = method.parameterTypes.firstOrNull()
                    method.isStatic && method.returnType == classOf<String>() &&
                        method.name in acceptedNames && method.parameterCount in 1..5 &&
                        (firstType in numericTypes ||
                            (firstType == classOf<String>() &&
                                owner.name == KNTR_NUMBER_FORMAT_CLASS))
                }.asSequence()
            }
            .distinctBy(Method::toGenericString)
            .map { it.toHookPoint() }
            .toList()
        methods.takeIf { it.isNotEmpty() }?.let(::FullNumberPoints)
    }.getOrNull()

    /** Story 只采用 StoryDetail 自身公开类型判断，不按标题、URI 或字段内容猜测。 */
    internal fun locateStoryFeed(loader: ClassLoader): StoryFeedPoints? = runCatching {
        val detail = KavaMemberLookup.classOrNull(loader, STORY_DETAIL_CLASS)
            ?: return@runCatching null
        fun booleanGetter(name: String): HookPoint? = KavaMemberLookup.methodOrNull(detail, name)
            ?.takeIf { method ->
                !method.isStatic && method.parameterCount == 0 &&
                    method.returnType == classOf<Boolean>()
            }
            ?.toHookPoint()

        fun objectGetter(name: String): Method? = KavaMemberLookup.methodOrNull(detail, name)
            ?.takeIf { method ->
                !method.isStatic && method.parameterCount == 0 &&
                    !method.returnType.isPrimitive && method.returnType != Void.TYPE
            }

        val responses = KavaMemberLookup.classOrNull(loader, STORY_FEED_RESPONSE_CLASS)
            ?.let { response ->
                KavaMemberLookup.methods(
                    response,
                    includeSuperclasses = true,
                    makeAccessible = true
                ) { method ->
                    method.name == "getItems" && !method.isStatic &&
                        method.parameterCount == 0 &&
                        (method.returnType isSubclassOf classOf<List<*>>())
                }.distinctBy(Method::toGenericString).map { it.toHookPoint() }
            }.orEmpty()

        val pager = KavaMemberLookup.classOrNull(loader, STORY_PAGER_PLAYER_CLASS)
            ?.let { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    !method.isStatic && !method.isAbstract && method.returnType == Void.TYPE &&
                        method.parameterCount == 1 &&
                        (method.parameterTypes[0] isSubclassOf classOf<List<*>>()) &&
                        method.genericParameterTypes[0].toString().contains(STORY_DETAIL_CLASS)
                }.distinctBy(Method::toGenericString).map { it.toHookPoint() }
            }.orEmpty()

        val ad = booleanGetter("isAd")
        val live = booleanGetter("isLive")
        val game = booleanGetter("isGame")
        val bangumi = booleanGetter("isBangumi")
        val course = booleanGetter("isCheese")
        val music = booleanGetter("isMusic")
        val cartInfo = objectGetter("getCartIconInfo")
        val dramaPrompt = objectGetter("getDramaPromptBar")
        val seasonInfo = objectGetter("getSeasonInfo")
        val seasonType = seasonInfo?.returnType?.let { owner ->
            KavaMemberLookup.methodOrNull(owner, "getSeasonType")
                ?.takeIf { method ->
                    !method.isStatic && method.parameterCount == 0 &&
                        method.returnType == classOf<Int>()
                }
        }
        if ((responses.isEmpty() && pager.isEmpty()) ||
            (ad == null && live == null && game == null && bangumi == null && course == null &&
                music == null && cartInfo == null && dramaPrompt == null && seasonType == null)
        ) {
            return@runCatching null
        }
        StoryFeedPoints(
            responses,
            pager,
            ad,
            live,
            game,
            bangumi,
            course,
            music,
            cartInfo?.toHookPoint(),
            dramaPrompt?.toHookPoint(),
            seasonInfo?.takeIf { seasonType != null }?.toHookPoint(),
            seasonType?.toHookPoint()
        )
    }.getOrNull()
}
