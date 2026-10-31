package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudKeyFreezeRulesTest {
    private fun parse(raw: String?) = CloudKeyFreezeRules.parse(raw)

    @Test fun `blank input has no rules and nothing rejected`() {
        for (raw in listOf(null, "", "   ", "\n\n", "# only a comment")) {
            assertEquals(CloudKeyRuleSet.EMPTY, parse(raw))
        }
    }

    @Test fun `each value form parses to its rule`() {
        val set = parse(
            """
            ff_a=true
            ff_b = FALSE
            ff_c=default
            live.text=s:hello world
            live.empty=s:
            """.trimIndent()
        )
        assertEquals(
            mapOf(
                "ff_a" to CloudKeyRule.Bool(true),
                "ff_b" to CloudKeyRule.Bool(false),
                "ff_c" to CloudKeyRule.UseDefault,
                "live.text" to CloudKeyRule.Text("hello world"),
                "live.empty" to CloudKeyRule.Text("")
            ),
            set.rules
        )
        assertEquals(0, set.rejected)
        assertEquals(0, set.protectedRejected)
    }

    @Test fun `semicolons and full width semicolons separate entries and comments are skipped`() {
        val set = parse("ff_a=true; ff_b=false；ff_c=default\n# ff_d=true\n\n  ff_e=true  ")
        assertEquals(listOf("ff_a", "ff_b", "ff_c", "ff_e"), set.rules.keys.toList())
    }

    @Test fun `only the first equals sign splits so text values may contain one`() {
        assertEquals(CloudKeyRule.Text("a=b"), parse("k.v=s:a=b").rules.getValue("k.v"))
    }

    @Test fun `malformed entries are counted and dropped`() {
        val set = parse(
            listOf(
                "no_equals",
                "=true",
                "ff a=true",
                "ff_ok=maybe",
                "ff_ok2=1",
                "a".repeat(CloudKeyFreezeRules.MAX_KEY_LENGTH + 1) + "=true",
                "ff_text=s:" + "x".repeat(CloudKeyFreezeRules.MAX_TEXT_LENGTH + 1),
                "kéy=true",
                "ff_good=true"
            ).joinToString("\n")
        )
        assertEquals(listOf("ff_good"), set.rules.keys.toList())
        assertEquals(8, set.rejected)
    }

    @Test fun `keys are exact and case sensitive and a text value of the longest allowed size is accepted`() {
        val set = parse("Live.A=true\nlive.a=false\nk=s:" + "x".repeat(CloudKeyFreezeRules.MAX_TEXT_LENGTH))
        assertEquals(CloudKeyRule.Bool(true), set.rules["Live.A"])
        assertEquals(CloudKeyRule.Bool(false), set.rules["live.a"])
        assertEquals(CloudKeyFreezeRules.MAX_TEXT_LENGTH, (set.rules.getValue("k") as CloudKeyRule.Text).value.length)
    }

    @Test fun `a repeated key keeps the last value`() {
        val set = parse("ff_a=true\nff_b=true\nff_a=false")
        assertEquals(CloudKeyRule.Bool(false), set.rules["ff_a"])
        assertEquals(listOf("ff_b", "ff_a"), set.rules.keys.toList())
    }

    @Test fun `protected categories are refused whatever the case`() {
        val set = parse(
            "infra.blconfig.new.init=true\nGRPC.x=false\nnet.timeout=default\nlogin.mode=true\n" +
                "risk.check=false\nflowcontrol.q=true\nblrouter.pureurl=s:x\nsecurity.a=true\nff_login_page=true"
        )
        assertEquals(listOf("ff_login_page"), set.rules.keys.toList())
        assertEquals(8, set.protectedRejected)
        assertEquals(0, set.rejected)
    }

    @Test fun `a prefix is protected only at a segment boundary`() {
        assertFalse(CloudKeyFreezeRules.isProtected("network_state_card"))
        assertFalse(CloudKeyFreezeRules.isProtected("login_banner"))
        assertFalse(CloudKeyFreezeRules.isProtected("dd.use_new_home_frame"))
        assertTrue(CloudKeyFreezeRules.isProtected("net.anything"))
        assertTrue(CloudKeyFreezeRules.isProtected("Infra.AnyThing"))
    }

    @Test fun `the rule count is capped and later entries are counted as rejected`() {
        val raw = (1..CloudKeyFreezeRules.MAX_RULES + 5).joinToString("\n") { "ff_$it=true" }
        val set = parse(raw)
        assertEquals(CloudKeyFreezeRules.MAX_RULES, set.rules.size)
        assertEquals(5, set.rejected)
        // 超限之后再次出现的已有键仍可更新，不算新增条目。
        val updated = parse(raw + "\nff_1=false")
        assertEquals(CloudKeyRule.Bool(false), updated.rules["ff_1"])
        assertEquals(5, updated.rejected)
    }

    // region resolver

    private val unchanged = CloudKeyRuleResolver.UNCHANGED

    @Test fun `a boolean rule only rewrites boolean reads`() {
        val rule = CloudKeyRule.Bool(false)
        assertEquals(false, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.BOOL, true, true, true))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.TEXT, "x", "d", true))
        assertEquals(false, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, true, null, true))
        assertEquals(false, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, null, true, true))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, "x", "d", true))
    }

    @Test fun `a text rule only rewrites string reads`() {
        val rule = CloudKeyRule.Text("fixed")
        assertEquals("fixed", CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.TEXT, "x", "d", true))
        assertEquals("fixed", CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.TEXT, null, null, true))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.BOOL, true, true, true))
        assertEquals("fixed", CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, "x", null, true))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, true, true, true))
    }

    @Test fun `use default hands back what the host call site passed`() {
        val rule = CloudKeyRule.UseDefault
        assertEquals(true, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.BOOL, false, true, true))
        assertEquals("d", CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.TEXT, "cloud", "d", true))
        assertNull(CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.TEXT, "cloud", null, true))
        assertEquals(true, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, false, true, true))
    }

    @Test fun `use default never invents a value it cannot return safely`() {
        val rule = CloudKeyRule.UseDefault
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.BOOL, false, null, false))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.BOOL, false, "not a boolean", true))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, true, null, true))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.TEXT, "x", 5, true))
    }

    // endregion
}
