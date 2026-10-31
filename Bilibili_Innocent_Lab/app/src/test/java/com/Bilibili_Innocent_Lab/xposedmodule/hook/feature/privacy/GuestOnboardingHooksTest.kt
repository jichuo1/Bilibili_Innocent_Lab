package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.bilibili.pegasus.PegasusFragment
import com.bilibili.pegasus.components.interest.LowActiveInterestChoosePlugin
import com.bilibili.pegasus.components.interest.NewUserInterestChoosePlugin
import com.bilibili.pegasus.data.interestchoose.InterestChoose
import com.bilibili.pegasus.request.d as FirstStartGate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GuestOnboardingHooksTest {
    private val statuses = linkedMapOf<String, String>()

    private fun environment(registrar: PlayerPortTestRegistrar, loader: ClassLoader = javaClass.classLoader!!) =
        HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })

    private fun PlayerPortTestRegistrar.callGate(): Any? =
        invoke("guest.onboarding.gate", null, emptyArray()) { FirstStartGate.h() }

    @Before fun resetFixture() {
        FirstStartGate.firstStart = true
        FirstStartGate.a = true
        statuses.clear()
    }

    @Test fun bothPluginsAreFinishedForGuestsByShapeWithoutSubmittingChoicesAndPassThroughAfterLogin() {
        var guest = true
        val registrar = PlayerPortTestRegistrar()
        // The first-start gate, the wizard data source, the V2 entry rejection, and both legacy plugins.
        assertEquals(5, GuestOnboardingHooks { guest }.install(environment(registrar)))

        val plugin = NewUserInterestChoosePlugin()
        registrar.invoke("guest.onboarding.new.show", plugin, arrayOf(InterestChoose())) { plugin.F(InterestChoose()); null }
        assertEquals(0, plugin.dialogs)      // wizard not shown
        assertEquals(1, plugin.completed)    // finished instead, releasing the loading gate
        assertFalse(plugin.loading)
        assertFalse(plugin.submittedChoice)  // finish(false, null) submits nothing

        val low = LowActiveInterestChoosePlugin()
        registrar.invoke("guest.onboarding.low.show", low, arrayOf(InterestChoose())) { low.C(InterestChoose()); null }
        assertEquals(0, low.dialogs)
        assertFalse(low.loading)
        assertFalse(low.submittedChoice)     // finish(false, null, false) submits nothing

        // Once logged in, the real show runs untouched.
        guest = false
        registrar.invoke("guest.onboarding.new.show", plugin, arrayOf(InterestChoose())) { plugin.F(InterestChoose()); null }
        assertEquals(1, plugin.dialogs)
        registrar.invoke("guest.onboarding.low.show", low, arrayOf(InterestChoose())) { low.C(InterestChoose()); null }
        assertEquals(1, low.dialogs)
    }

    @Test fun guestOnFirstStartTakesTheHostsNonFirstStartPathInsteadOfWaitingForTheWizard() {
        var guest = true
        val registrar = PlayerPortTestRegistrar()
        GuestOnboardingHooks { guest }.install(environment(registrar))

        // Host default on a first start: the feed would wait for the wizard.
        val untouched = PegasusFragment().apply { onCreate(null) }
        assertEquals(1, untouched.waitedForWizard)
        assertEquals(0, untouched.immediateLoads)

        // Hooked predicate for a guest is false, which is exactly what the fragment's onCreate branches on.
        assertEquals(false, registrar.callGate())
        assertEquals("seen", statuses["guest_onboarding_gate_applied"])
        assertEquals("installed", statuses["guest_onboarding_gate"]) // the install report is never overwritten

        // After login the original predicate runs untouched, so a real new user still gets the wizard.
        guest = false
        assertEquals(true, registrar.callGate())
    }

    @Test fun installingTheGateAloneDoesNotClaimItWasApplied() {
        val registrar = PlayerPortTestRegistrar()
        GuestOnboardingHooks { false }.install(environment(registrar))
        assertEquals("installed", statuses["guest_onboarding_gate"])
        registrar.callGate()
        assertNull(statuses["guest_onboarding_gate_applied"]) // a logged-in call never reports applied
    }

    @Test fun guestEntryIsRejectedSoTheHostMountsNoWizardAndNothingNeedsUnwinding() {
        var guest = true
        val registrar = PlayerPortTestRegistrar()
        GuestOnboardingHooks { guest }.install(environment(registrar))
        val entry = tv.danmaku.bili.interest.v2.entry.o()

        // Guest: the hooked decision answers false and the original start logic never runs.
        assertEquals(false, registrar.invoke("guest.onboarding.entry", entry,
            arrayOf(0, "view_created", null, false, null)) { entry.a(0, "view_created", null, false, null) })
        assertEquals(0, entry.started)
        assertEquals("seen", statuses["guest_onboarding_entry_rejected"])
        assertEquals("installed", statuses["guest_onboarding_entry"])

        // After login the real decision runs, so a genuine new user still gets the wizard.
        guest = false
        assertEquals(true, registrar.invoke("guest.onboarding.entry", entry,
            arrayOf(0, "view_created", null, false, null)) { entry.a(0, "view_created", null, false, null) })
        assertEquals(1, entry.started)
    }

    @Test fun guestGetsNoWizardDataButALoggedInUserStillDoes() {
        var guest = true
        val registrar = PlayerPortTestRegistrar()
        GuestOnboardingHooks { guest }.install(environment(registrar))
        val response = com.bilibili.pegasus.data.base.PegasusResponse().apply { interestChoose = InterestChoose() }

        // Guest: the host receives no InterestChoose, and its own null guard ends the wizard flow.
        assertNull(registrar.invoke("guest.onboarding.data", response, emptyArray()) { response.getInterestChoose() })
        assertEquals("seen", statuses["guest_onboarding_data_suppressed"])
        assertEquals("installed", statuses["guest_onboarding_data"])

        // After login the real object flows through, so a genuine new user still sees the wizard.
        guest = false
        assertSame(response.interestChoose,
            registrar.invoke("guest.onboarding.data", response, emptyArray()) { response.getInterestChoose() })
    }

    @Test fun dataAndEntryHooksAreNeverInstalledWithoutTheFirstStartGate() {
        val registrar = PlayerPortTestRegistrar()
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String?, resolve: Boolean): Class<*> =
                if (name == "com.bilibili.pegasus.request.d") NoStaticBoolean::class.java else super.loadClass(name, resolve)
        }
        GuestOnboardingHooks { true }.install(environment(registrar, loader))
        assertEquals("skipped-no-gate", statuses["guest_onboarding_data"])
        assertEquals("skipped-no-gate", statuses["guest_onboarding_entry"])
        assertNull(registrar.hooks["guest.onboarding.data"])
        assertNull(registrar.hooks["guest.onboarding.entry"])
    }

    @Test fun entryIsNeverRejectedWithoutTheFirstStartGate() {
        // No pegasus request class on this build: the gate is absent, so rejecting the entry would strand the feed.
        val registrar = PlayerPortTestRegistrar()
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String?, resolve: Boolean): Class<*> =
                if (name == "com.bilibili.pegasus.request.d") NoStaticBoolean::class.java else super.loadClass(name, resolve)
        }
        GuestOnboardingHooks { true }.install(environment(registrar, loader))
        assertEquals("skipped-no-gate", statuses["guest_onboarding_entry"])
        assertNull(registrar.hooks["guest.onboarding.entry"])
    }

    @Test fun entryLocatorFollowsSessionToRuntimeToEntryAndAcceptsBothSignatures() {
        // 9.15.2+: (int, String, ref, boolean, ref).
        val five = GuestOnboardingHooks.locateEntryDecision(
            com.bilibili.pegasus.components.interestv2.host.AndroidInterestChooseSession::class.java)
        assertEquals("a", five?.name)
        assertEquals(5, five?.parameterCount)
        // 8.97-9.15.1: the same decision without the trailing step session.
        val four = GuestOnboardingHooks.locateEntryDecision(FourParamSession::class.java)
        assertEquals(4, four?.parameterCount)
    }

    @Test fun entryLocatorFailsClosedOnAmbiguityOrAMissingController() {
        assertNull(GuestOnboardingHooks.locateEntryDecision(TwoEntriesSession::class.java))
        assertNull(GuestOnboardingHooks.locateEntryDecision(NoEntrySession::class.java))
        // Platform-typed fields are never walked into.
        assertNull(GuestOnboardingHooks.locateEntryDecision(PlatformFieldsSession::class.java))
    }

    @Test fun gateLocatorFailsClosedUnlessExactlyOneStaticBooleanExists() {
        assertEquals("h", GuestOnboardingHooks.locateGate(FirstStartGate::class.java)?.name)
        assertEquals("only", GuestOnboardingHooks.locateGate(OneStaticBoolean::class.java)?.name)
        assertNull(GuestOnboardingHooks.locateGate(NoStaticBoolean::class.java))
        assertNull(GuestOnboardingHooks.locateGate(TwoStaticBooleans::class.java)) // ambiguity is never guessed
        assertNull(GuestOnboardingHooks.locateGate(StaticBooleanWithArgument::class.java))
    }

    @Test fun gateOnRequestBIsUsedWhenRequestDHasNone() {
        // 8.99-9.01 and 9.08-9.15: request.d is the Retrofit interface, the predicate is request.b.g()/f()/h().
        val registrar = PlayerPortTestRegistrar()
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String?, resolve: Boolean): Class<*> = when (name) {
                "com.bilibili.pegasus.request.d" -> NoStaticBoolean::class.java
                "com.bilibili.pegasus.request.b" -> OneStaticBoolean::class.java
                else -> super.loadClass(name, resolve)
            }
        }
        GuestOnboardingHooks { true }.install(environment(registrar, loader))
        assertEquals("installed", statuses["guest_onboarding_gate"])
        assertEquals(OneStaticBoolean::class.java.getMethod("only"), registrar.hooks["guest.onboarding.gate"]?.member)
        // With the gate in place, the wizard data and entry suppressions install as on 9.16.
        assertEquals("installed", statuses["guest_onboarding_data"])
        assertEquals("installed", statuses["guest_onboarding_entry"])
    }

    @Test fun gateLocatorNeverPicksBetweenPredicatesInBothRequestClasses() {
        assertEquals("only", GuestOnboardingHooks.locateGate(NoStaticBoolean::class.java, OneStaticBoolean::class.java)?.name)
        assertNull(GuestOnboardingHooks.locateGate(FirstStartGate::class.java, OneStaticBoolean::class.java))
        assertNull(GuestOnboardingHooks.locateGate(NoStaticBoolean::class.java, NoStaticBoolean::class.java))
    }

    @Test fun reportsGateAbsentAndRegistersNothingWhenTheBuildHasNoSuchPredicate() {
        val registrar = PlayerPortTestRegistrar()
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String?, resolve: Boolean): Class<*> =
                if (name == "com.bilibili.pegasus.request.d") NoStaticBoolean::class.java else super.loadClass(name, resolve)
        }
        GuestOnboardingHooks { true }.install(environment(registrar, loader))
        assertEquals("absent", statuses["guest_onboarding_gate"])
        assertNull(registrar.hooks["guest.onboarding.gate"])
    }

    class NoStaticBoolean { fun instance() = true }
    class OneStaticBoolean { companion object { @JvmStatic fun only() = true } }
    class TwoStaticBooleans { companion object { @JvmStatic fun a() = true; @JvmStatic fun b() = false } }
    class StaticBooleanWithArgument { companion object { @JvmStatic fun withArg(a: Int) = a > 0 } }

    // Entry-locator shapes: session -> runtime -> entry.
    class FourEntry { fun a(i: Int, s: String?, p: Any?, z: Boolean): Boolean = true }
    class FourRuntime { @JvmField val entry = FourEntry() }
    class FourParamSession { @JvmField val runtime = FourRuntime() }

    class AmbiguousEntry { fun a(i: Int, s: String?, p: Any?, z: Boolean) = true; fun b(i: Int, s: String?, p: Any?, z: Boolean) = false }
    class AmbiguousRuntime { @JvmField val entry = AmbiguousEntry() }
    class TwoEntriesSession { @JvmField val runtime = AmbiguousRuntime() }

    class NoEntryRuntime { @JvmField val value = 1 }
    class NoEntrySession { @JvmField val runtime = NoEntryRuntime() }

    class PlatformFieldsSession { @JvmField val list: java.util.List<*>? = null; @JvmField val text: String = "" }
}
