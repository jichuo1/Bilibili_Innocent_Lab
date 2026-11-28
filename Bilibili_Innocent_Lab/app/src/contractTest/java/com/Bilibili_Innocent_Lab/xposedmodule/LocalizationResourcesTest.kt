package com.Bilibili_Innocent_Lab.xposedmodule

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LocalizationResourcesTest {

    @Test
    fun `released locales and guest browsing copy remain consistent`() {
        val translations = listOf(
            "zh-CN" to zhCnStrings,
            "zh-Hant" to zhHantStrings
        )

        translations.forEach { (locale, translated) ->
            assertEquals(
                keyDifferenceMessage(locale, enStrings.keys, translated.keys),
                enStrings.keys,
                translated.keys
            )
            enStrings.keys.forEach { key ->
                assertEquals(
                    "$locale placeholder mismatch for '$key'",
                    placeholders(enStrings.getValue(key)),
                    placeholders(translated.getValue(key))
                )
            }
        }

        val unexpectedHan = enStrings.filterValues { HAN_REGEX.containsMatchIn(it) }.keys
        assertTrue("Default English strings contain Han characters: $unexpectedHan", unexpectedHan.isEmpty())

        assertFalse("Locale config contains an empty locale tag", "" in localeConfigLocales)
        assertEquals(setOf("en", "zh-CN", "zh-Hant"), localeConfigLocales)

        val restart = listOf(
            Triple("values", enStrings, "Restart Bilibili to apply."),
            Triple("values-zh-rCN", zhCnStrings, "重启哔哩哔哩后生效。"),
            Triple("values-b+zh+Hant", zhHantStrings, "重新啟動嗶哩嗶哩後生效。")
        )
        restart.forEach { (locale, strings, phrase) ->
            listOf("no_login_enabled", "incognito_enabled", "skip_guest_onboarding").forEach { key ->
                assertTrue(strings.getValue(key).isNotBlank())
                assertTrue("$locale $key missing $phrase", strings.getValue("${key}_tip").endsWith(phrase))
            }
        }
        val browsing = zhCnStrings.getValue("no_login_enabled_tip")
        listOf("保留主动登录入口", "只存于本机，不上传", "画质以游客可用为限", "以无痕为准")
            .forEach { assertTrue(it, browsing.contains(it)) }
        val incognito = zhCnStrings.getValue("incognito_enabled_tip")
        listOf("临时游客会话", "关闭并重启后恢复账号", "会话结束即丢弃", "服务端仍可能记录访问")
            .forEach { assertTrue(it, incognito.contains(it)) }
    }

    @Test
    fun `user terms privacy disclosure and telemetry formatting match policy`() {
        val locales = listOf(
            "en" to enStrings,
            "zh-CN" to zhCnStrings,
            "zh-Hant" to zhHantStrings
        )

        locales.forEach { (locale, strings) ->
            assertTrue(
                "$locale user-terms keys missing: ${USER_TERMS_KEYS - strings.keys}",
                strings.keys.containsAll(USER_TERMS_KEYS)
            )
            USER_TERMS_KEYS.forEach { key ->
                assertTrue("$locale user-terms string '$key' is blank", strings.getValue(key).isNotBlank())
            }
            assertTrue(
                "$locale user-terms body lost its paragraph structure",
                strings.getValue(USER_TERMS_BODY).contains("\\n\\n")
            )
            assertTrue(
                "$locale user-terms body lost the canonical project URL",
                strings.getValue(USER_TERMS_BODY).contains(PROJECT_URL)
            )
        }

        val body = zhCnStrings.getValue(USER_TERMS_BODY).replace("\\n", "\n")
        assertEquals(SIMPLIFIED_TERMS_BODY, body)
        assertTrue(body.contains("拒绝或关闭遥测不会限制任何模块功能"))
        assertTrue(body.contains("Cloudflare Workers/D1 服务"))
        assertFalse(body.contains("境外"))
        assertEquals("不同意并退出", zhCnStrings.getValue("user_terms_decline"))
        assertEquals("我已阅读完毕并知情同意", zhCnStrings.getValue("user_terms_accept"))

        listOf(USER_TERMS_BODY, "telemetry_info_body").forEach { key ->
            val copy = zhCnStrings.getValue(key)
            assertTrue(copy.contains("更快了解各项功能的适配情况、定位并修复问题"))
            assertTrue(copy.contains("缩短修复与更新周期"))
            assertTrue(copy.contains("是否开启由您决定，关闭不会影响任何模块功能"))
        }
        assertTrue(
            zhCnStrings.getValue(USER_TERMS_BODY).contains(
                "项目与更新 → 匿名适配遥测 → 遥测说明与用户控制"
            )
        )

        for ((locale, strings) in listOf("values-zh-rCN" to zhCnStrings, "values-b+zh+Hant" to zhHantStrings)) {
            val label = strings.getValue("telemetry_enabled_summary")
            assertTrue("$locale telemetry summary missing full-width parentheses", label.contains("（Per 24h）"))
            assertFalse("$locale telemetry summary unexpectedly uses Latin parentheses", label.contains("(Per 24h)"))
        }
        assertTrue(enStrings.getValue("telemetry_enabled_summary").contains("(Per 24h)"))
    }

    private fun placeholders(value: String): List<String> =
        FORMAT_PLACEHOLDER.findAll(value).map { it.value }.sorted().toList()

    private fun keyDifferenceMessage(
        locale: String,
        expected: Set<String>,
        actual: Set<String>
    ): String = "$locale string keys differ; missing=${expected - actual}, extra=${actual - expected}"

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
        const val ACCESS_EXTERNAL_SCHEMA =
            "http://javax.xml.XMLConstants/property/accessExternalSchema"
        val FORMAT_PLACEHOLDER = Regex("%(?:\\d+\\$)?[#+ 0,(<\\-]*\\d*(?:\\.\\d+)?[a-zA-Z]")
        val HAN_REGEX = Regex("\\p{IsHan}")
        const val USER_TERMS_BODY = "user_terms_body"
        const val PROJECT_URL = "https://github.com/jichuo1/Bilibili_Innocent_Lab"

        val documentBuilderFactory: DocumentBuilderFactory by lazy {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(ACCESS_EXTERNAL_DTD, "")
                setAttribute(ACCESS_EXTERNAL_SCHEMA, "")
            }
        }

        val enStrings: Map<String, String> by lazy { readStrings("src/main/res/values/strings.xml") }
        val zhCnStrings: Map<String, String> by lazy { readStrings("src/main/res/values-zh-rCN/strings.xml") }
        val zhHantStrings: Map<String, String> by lazy { readStrings("src/main/res/values-b+zh+Hant/strings.xml") }

        val localeConfigLocales: Set<String> by lazy {
            val file = SourceContract.file("src/main/res/xml/locales_config.xml")
            assertTrue("Missing locale config: $file", file.isFile)
            val document = documentBuilderFactory.newDocumentBuilder().parse(file)
            buildSet {
                val nodes = document.getElementsByTagName("locale")
                repeat(nodes.length) { index ->
                    val element = nodes.item(index) as Element
                    add(element.getAttributeNS(ANDROID_NAMESPACE, "name"))
                }
            }
        }

        fun readStrings(relativePath: String): Map<String, String> {
            val file = SourceContract.file(relativePath)
            assertTrue("Missing strings resource: $file", file.isFile)
            val document = documentBuilderFactory.newDocumentBuilder().parse(file)
            val result = linkedMapOf<String, String>()
            val nodes = document.getElementsByTagName("string")
            repeat(nodes.length) { index ->
                val element = nodes.item(index) as Element
                val name = element.getAttribute("name")
                require(name.isNotBlank()) { "Unnamed string in $file" }
                check(result.put(name, element.textContent) == null) {
                    "Duplicate string '$name' in $file"
                }
            }
            return result
        }

        val USER_TERMS_KEYS = setOf(
            "user_terms_dialog_title",
            USER_TERMS_BODY,
            "telemetry_terms_choice",
            "telemetry_terms_choice_summary",
            "user_terms_accept",
            "user_terms_decline",
            "user_terms_declined_title",
            "user_terms_declined_message",
            "user_terms_read_again",
            "user_terms_exit",
            "user_terms_save_failed",
            "user_terms_need_framework_enable",
            "user_terms_need_api102",
            "user_terms_need_api102_named",
            "user_terms_publish_failed",
            "user_terms_open_framework_manager",
            "user_terms_pending_title",
            "user_terms_pending_message",
            "user_terms_pending_waiting",
            "user_terms_pending_syncing",
            "user_terms_pending_unsupported",
            "user_terms_pending_failed",
            "user_terms_pending_local_failed",
            "user_terms_retry_sync",
            "user_terms_diagnostics_title",
            "user_terms_diagnostics_module_identity",
            "user_terms_diagnostics_profile_primary",
            "user_terms_diagnostics_profile_secondary",
            "user_terms_diagnostics_framework_connected",
            "user_terms_diagnostics_framework_disconnected",
            "user_terms_diagnostics_remote_available",
            "user_terms_diagnostics_remote_unavailable",
            "user_terms_diagnostics_remote_unknown",
            "user_terms_diagnostics_target_visible",
            "user_terms_diagnostics_target_not_visible",
            "user_terms_diagnostics_same_user_yes",
            "user_terms_diagnostics_same_user_no",
            "user_terms_diagnostics_same_user_unknown",
            "user_terms_diagnostics_failure",
            "user_terms_diagnostics_failure_none"
        )
        val SIMPLIFIED_TERMS_BODY = listOf(
            "用户须知：",
            "本项目是非官方、非商业性质的个人学习与研究项目，与哔哩哔哩及其关联公司不存在隶属、授权、合作或背书关系。",
            "使用 Xposed/LSPosed 模块可能改变目标应用运行行为，并可能受到客户端更新、系统安全策略、厂商 ROM、账号实验分组或其他模块的影响。使用者应自行评估风险，并对安装、启用、数据备份和设备环境负责。",
            "本项目不提供任何内容资源，不参与账号交易，不提供访问凭证，也不保证第三方扩展服务的可用性。请在遵守所在地法律法规、目标平台规则和开源项目许可的前提下使用。",
            "为保证项目稳定维护，请勿将本项目相关内容以包括但不限于以文件，文本，图标，链接，图片，视频，代码等形式在公开的社交媒体进行不定向传播。本项目为非盈利性项目，不会以任何方式收取软件服务费用，不会以任何方式在未经同意的前提下主动获取并传输您的个人信息，若您怀疑从其他渠道获取的文件与以上宗旨相违背，请务必对照对应版本Release HASH，若您发现文件HASH不一致，则该文件可能经过第三方篡改，本项目无法保证其安全性，若您已知并继续使用，由此带来的一切风险与后果请自行承担。",
            "为尽早发现哔哩哔哩版本更新造成的适配失效，本项目提供可选的隐私保护遥测。匿名适配遥测有助于开发者更快了解各项功能的适配情况、定位并修复问题，缩短修复与更新周期。是否开启由您决定，关闭不会影响任何模块功能。开启遥测时，模块或宿主数值版本变化后，首次取得有效且安装链已完成的宿主回执会触发一次独立的版本变化上传；首次使用此规则时的版本组合也计入一次。此上传由模块进程在后台执行，无需打开模块界面。每滚动 24 小时最多尝试 3 次，开始网络发送即计次，失败也占用一次；不占用手动或常规自动上传额度。同一版本组合成功上传后不再重复。后台限制或回执不可用时，可能延后至后续启动宿主时再尝试。常规自动上传由模块 App 在前台低频发送，自动上传每 24 小时至多一次；手动上传独立计次，每滚动 24 小时最多尝试 3 次；内容限于模块与宿主数值版本、Android SDK 与受支持 ABI 类别、厂商、机型与 ROM 类别（无法识别时记为未知）、框架类别及框架服务自报版本、适配规则代次，以及实际参与安装的细分能力 ID、有界安装结果和运行异常标志。细分能力状态可能间接反映已启用的功能，但不发送具体规则或设置值。它不会收集哔哩哔哩账号、UID、昵称、Cookie、访问令牌、硬件设备标识、内容、评论、弹幕、搜索词、位置、日志原文、异常消息或具体设置值。轮换随机标识在服务端只保存为不可直接还原的 HMAC；原始报告最多保留 30 天，少于 10 个安装样本的单元不会进入长期聚合。数据由 Cloudflare Workers/D1 服务处理。为防滥用，服务端短期使用每日变化的来源摘要限流，并保留最多 48 小时的去标识化额度记录；删除报告不会重置额度。接受本版须知时，下方遥测选择默认开启；您可在接受前关闭，也可随时在“项目与更新 → 匿名适配遥测 → 遥测说明与用户控制”中关闭、查看实际 JSON 或请求删除原始报告。拒绝或关闭遥测不会限制任何模块功能；已混入至少 10 个安装样本且不含个体标识的聚合计数无法按个体回滚。",
            "附加免责声明与风险提示详见：$PROJECT_URL",
            "请确保阅读完毕摘要内容和完整附加内容，而后决定。"
        ).joinToString("\n\n")
    }
}
