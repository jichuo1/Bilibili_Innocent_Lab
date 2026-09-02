package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiCompatibilityTest {

    @Test
    fun `accepts direct invalid XmlBlock document failure`() {
        assertTrue(SettingsUiCompatibility.isInvalidXmlDocumentFailure(invalidDocumentFailure()))
    }

    @Test
    fun `accepts invalid XmlBlock document failure nested in cause chain`() {
        val failure = IllegalStateException("layout failed", invalidDocumentFailure())

        assertTrue(SettingsUiCompatibility.isInvalidXmlDocumentFailure(failure))
    }

    @Test
    fun `rejects matching message outside XmlBlock parser`() {
        val failure = NullPointerException("Null document").apply {
            stackTrace = arrayOf(StackTraceElement("example.Parser", "getStyleAttribute", "Parser.kt", 1))
        }

        assertFalse(SettingsUiCompatibility.isInvalidXmlDocumentFailure(failure))
    }

    @Test
    fun `rejects non matching exception type or message`() {
        val wrongType = IllegalStateException("Null document").apply {
            stackTrace = invalidDocumentStack()
        }
        val wrongMessage = NullPointerException("other").apply {
            stackTrace = invalidDocumentStack()
        }

        assertFalse(SettingsUiCompatibility.isInvalidXmlDocumentFailure(wrongType))
        assertFalse(SettingsUiCompatibility.isInvalidXmlDocumentFailure(wrongMessage))
    }

    private fun invalidDocumentFailure() = NullPointerException("Null document").apply {
        stackTrace = invalidDocumentStack()
    }

    private fun invalidDocumentStack() = arrayOf(
        StackTraceElement(
            "android.content.res.XmlBlock\$Parser",
            "getStyleAttribute",
            "XmlBlock.java",
            620
        )
    )
}
