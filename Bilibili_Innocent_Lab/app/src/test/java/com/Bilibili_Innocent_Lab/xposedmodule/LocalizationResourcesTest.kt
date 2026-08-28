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
    }
}
