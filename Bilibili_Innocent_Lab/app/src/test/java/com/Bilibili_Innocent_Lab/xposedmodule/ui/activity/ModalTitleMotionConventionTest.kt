package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住"锚点型选择面板的标题必须复用来源行的 string"这条约定。
 *
 * `ModalTitleMotion.create` 拿弹窗容器的第一个 TextView 当 target，再在被点击的来源行里
 * 找首行文字与它**相等**的 TextView 当 source（`ModalTitleMotionSpec.matches` 比的是
 * 渲染文本，不是资源 id）。所以只要给弹窗单独写一条标题文案，文字平移就**静默退化**成
 * 只有容器形变——界面看着还在动，少了的那一半没有任何报错。
 *
 * 2026-09-12 实测有五个面板踩了这条：默认播放画质、按用户等级过滤评论、按权重过滤弹幕、
 * 应用语言、按视频时长过滤推荐，它们的标题都用了独立的 `*_dialog_title`。
 *
 * 这里用命名约定当判据：`*_dialog_title` 这个后缀的存在意义就是"只给弹窗用的标题"，
 * 出现在带 anchor 的面板里必然配不上。判据是代理而非充要条件，但方向只会误报、不会漏报，
 * 且失败信息直接告诉你该怎么改。
 */
class ModalTitleMotionConventionTest {

    private val dialogSources: List<File> by lazy {
        val root = sequenceOf(
            File("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity"),
            File("app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity")
        ).firstOrNull(File::isDirectory) ?: error("找不到 ui/activity 源码目录")
        root.listFiles { file -> file.name.endsWith("Dialogs.kt") }?.sorted().orEmpty()
    }

    /** 以 `internal fun MainActivity.showXxx(` 为界把文件切成函数块。 */
    private fun functions(file: File): List<Pair<String, String>> {
        val text = file.readText()
        val header = Regex("""(?m)^internal fun MainActivity\.(\w+)\(""")
        val starts = header.findAll(text).toList()
        return starts.mapIndexed { index, match ->
            val end = starts.getOrNull(index + 1)?.range?.first ?: text.length
            match.groupValues[1] to text.substring(match.range.first, end)
        }
    }

    @Test fun theScanActuallySeesTheDialogSources() {
        assertTrue("没扫到任何 *Dialogs.kt，护栏会静默通过", dialogSources.size >= 4)
        val names = dialogSources.flatMap { functions(it) }.map { it.first }
        assertTrue("没解析出弹窗函数：${dialogSources.map(File::getName)}", names.size >= 10)
        assertTrue("showPlayerQualityDialog 应当在扫描范围内", "showPlayerQualityDialog" in names)
    }

    @Test fun anchoredPanelsReuseTheSourceRowTitleInsteadOfADialogOnlyString() {
        val offenders = dialogSources.flatMap { file ->
            functions(file).filter { (_, body) ->
                // 只管带锚点的面板：没有 anchor 就没有形变，也就无从谈文字平移。
                body.contains("anchor: View?") &&
                    Regex("""R\.string\.\w*_dialog_title""").containsMatchIn(body)
            }.map { (name, body) ->
                val key = Regex("""R\.string\.(\w*_dialog_title)""").find(body)?.groupValues?.get(1)
                "${file.name}#$name 用了 R.string.$key"
            }
        }
        assertEquals(
            "带 anchor 的选择面板不能用独立的 *_dialog_title 当标题——那会让 ModalTitleMotion " +
                "配不上、文字平移静默退化成只有容器形变。改成来源行那一行用的同一个 string。",
            emptyList<String>(),
            offenders
        )
    }

    /**
     * 通用编辑器的标题是**参数**，所以"面板函数体里不许出现 `*_dialog_title`"这条
     * 拦不到它——违规发生在调用方。2026-09-12 实测 `showRuleEditorDialog` 的 7 个调用点
     * 全都传了独立的 `*_dialog_title`（其中 6 个文案是"编辑 X"、与来源行不同）。
     */
    @Test fun ruleEditorCallersPassTheRowTitleNotADialogOnlyString() {
        val activity = sequenceOf(
            File("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/MainActivity.kt"),
            File("app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/MainActivity.kt")
        ).firstOrNull(File::isFile) ?: error("找不到 MainActivity.kt")
        val lines = activity.readLines()
        val callSites = lines.withIndex().filter { it.value.contains("showRuleEditorDialog(") }
        assertTrue("没扫到 showRuleEditorDialog 调用点，护栏会静默通过", callSites.size >= 5)
        val offenders = callSites.mapNotNull { (index, _) ->
            // 标题是第一个实参，紧跟在调用行后面（允许中间夹注释行）。
            val titleRef = lines.drop(index + 1).take(4)
                .firstNotNullOfOrNull { Regex("""R\.string\.(\w+)""").find(it)?.groupValues?.get(1) }
            titleRef?.takeIf { it.endsWith("_dialog_title") }?.let { "MainActivity.kt:${index + 1} → $it" }
        }
        assertEquals(
            "showRuleEditorDialog 的标题实参要传来源行那一行用的 string，不能另写一条 " +
                "*_dialog_title——弹窗标题与来源行文案不同就配不上文字平移。",
            emptyList<String>(),
            offenders
        )
    }

    @Test fun theFivePanelsFixedInThisChangeStayFixed() {
        // 逐个钉死，避免将来有人"顺手"又给它们写一条独立标题。
        val expected = mapOf(
            "showPlayerQualityDialog" to "R.string.player_default_quality",
            "showCommentMinLevelDialog" to "R.string.comment_min_level_filter",
            "showDanmakuWeightDialog" to "R.string.danmaku_weight_filter",
            "showAppLanguageDialog" to "R.string.app_language",
            "showRecommendVideoDurationRangeDialog" to "R.string.recommend_video_duration_range"
        )
        val bodies = dialogSources.flatMap { functions(it) }.toMap()
        expected.forEach { (name, titleRef) ->
            val body = bodies[name] ?: error("找不到 $name，面板被改名或搬走了")
            assertTrue(
                "$name 的标题必须是 $titleRef（来源行同一个 string），否则文字平移配不上",
                body.contains("$titleRef)")
            )
        }
    }
}
