package com.Bilibili_Innocent_Lab.xposedmodule

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LocalizationResourcesTest {

    private val resRoot: File by lazy {
        sequenceOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull(File::isDirectory)
            ?: error("Unable to locate app/src/main/res from ${File(".").absolutePath}")
    }

    @Test
    fun `all released locales have the same string keys and placeholders`() {
        val base = readStrings(File(resRoot, "values/strings.xml"))
        val translations = listOf(
            "zh-CN" to File(resRoot, "values-zh-rCN/strings.xml"),
            "zh-Hant" to File(resRoot, "values-b+zh+Hant/strings.xml")
        )

        translations.forEach { (locale, file) ->
            assertTrue("Missing released locale resource: $file", file.isFile)
            val translated = readStrings(file)
            assertEquals(keyDifferenceMessage(locale, base.keys, translated.keys), base.keys, translated.keys)
            base.keys.forEach { key ->
                assertEquals(
                    "$locale placeholder mismatch for '$key'",
                    placeholders(base.getValue(key)),
                    placeholders(translated.getValue(key))
                )
            }
        }
    }

    @Test
    fun `default English resources do not contain Han characters`() {
        val base = readStrings(File(resRoot, "values/strings.xml"))
        val unexpected = base.filterValues { HAN_REGEX.containsMatchIn(it) }.keys
        assertTrue("Default English strings contain Han characters: $unexpected", unexpected.isEmpty())
    }

    @Test
    fun `locale config exposes only complete released locales`() {
        val file = File(resRoot, "xml/locales_config.xml")
        assertTrue("Missing locale config: $file", file.isFile)
        val document = parse(file)
        val locales = buildSet {
            val nodes = document.getElementsByTagName("locale")
            repeat(nodes.length) { index ->
                val element = nodes.item(index) as Element
                add(element.getAttributeNS(ANDROID_NAMESPACE, "name"))
            }
        }
        assertFalse("Locale config contains an empty locale tag", "" in locales)
        assertEquals(setOf("en", "zh-CN", "zh-Hant"), locales)
    }

    @Test
    fun `user terms ui and body are complete in every released locale`() {
        val locales = mapOf(
            "en" to File(resRoot, "values/strings.xml"),
            "zh-CN" to File(resRoot, "values-zh-rCN/strings.xml"),
            "zh-Hant" to File(resRoot, "values-b+zh+Hant/strings.xml")
        )

        locales.forEach { (locale, file) ->
            val strings = readStrings(file)
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
    }

    @Test
    fun `simplified Chinese terms preserve maintainer supplied core notice`() {
        val strings = readStrings(File(resRoot, "values-zh-rCN/strings.xml"))
        val body = strings.getValue(USER_TERMS_BODY).replace("\\n", "\n")
        assertEquals(SIMPLIFIED_TERMS_BODY, body)
        assertEquals("不同意并退出", strings.getValue("user_terms_decline"))
        assertEquals("我已阅读完毕并知情同意", strings.getValue("user_terms_accept"))
    }

    private fun readStrings(file: File): Map<String, String> {
        assertTrue("Missing strings resource: $file", file.isFile)
        val document = parse(file)
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

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(ACCESS_EXTERNAL_DTD, "")
        setAttribute(ACCESS_EXTERNAL_SCHEMA, "")
    }.newDocumentBuilder().parse(file)

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
        val USER_TERMS_KEYS = setOf(
            "user_terms_dialog_title",
            USER_TERMS_BODY,
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
            "附加免责声明与风险提示详见：$PROJECT_URL",
            "请确保阅读完毕摘要内容和完整附加内容，而后决定。"
        ).joinToString("\n\n")
    }
}
