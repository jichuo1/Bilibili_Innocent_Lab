package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import fixtures.privacy.nativelist.AmbiguousCall
import fixtures.privacy.nativelist.FakeCache
import fixtures.privacy.nativelist.NewCall
import fixtures.privacy.nativelist.OldCall
import fixtures.privacy.nativelist.Resp
import fixtures.privacy.nativelist.Result
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Type
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestLibraryRoutesTest {
    fun nativeMineMenuAndFinalProviderRoutesReachTheSameLocalSections() {
        assertEquals("history", GuestLibraryHostHooks.sectionForUri("bilibili://main/drawer/history"))
        listOf("bilibili://main/drawer/favorites", "bilibili://main/favorite",
            "bilibili://user_center/favourite", "bilibili://user_center/favourite2",
            "bilibili://user_center/favorite_v2/favorite", "bilibili://user_center/favorite_v2/favorite/folders").forEach {
            assertEquals("favorites", GuestLibraryHostHooks.sectionForUri(it))
        }
        listOf("bilibili://main/drawer/watch-later", "bilibili://user_center/watch_later_v2",
            "bilibili://compose/watch_later/kntr").forEach {
            assertEquals("watchLater", GuestLibraryHostHooks.sectionForUri(it))
        }
    }

    fun optionalQueryAndTrailingSlashDoNotChangeTheMenuIdentity() {
        assertEquals("favorites", GuestLibraryHostHooks.sectionForUri(
            "bilibili://main/drawer/favorites/?prompt_scene=mine"))
    }

    fun unrelatedRoutesAndActiveLoginAreNotRedirectedToTheLocalLibrary() {
        listOf(null, "", "bilibili://main/login", "activity://main/login/",
            "https://example.test/main/drawer/favorites", "bilibili://main/drawer/offline",
            "bilibili://main/favorite/cheese").forEach { assertNull(GuestLibraryHostHooks.sectionForUri(it)) }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (GuestLibraryRoutesTest) -> Unit) {
            val s = GuestLibraryRoutesTest()
            block(s)
        }
        runIsolated { s -> s.nativeMineMenuAndFinalProviderRoutesReachTheSameLocalSections() }
        runIsolated { s -> s.optionalQueryAndTrailingSlashDoNotChangeTheMenuIdentity() }
        runIsolated { s -> s.unrelatedRoutesAndActiveLoginAreNotRedirectedToTheLocalLibrary() }
    
        GuestLottieFollowButtonShapeCases().verifyAllCases()
        GuestNativeFavoriteFeedbackCases().fallsBackOnlyForTheMissingHostFavoriteRepository()
        GuestNativeListHttpHooksCases().verifyAllCases()
        GuestPlaybackItemIdCases().verifyAllCases()
        GuestStoryStarCountCases().verifyAllCases()
    }
}

private class GuestLottieFollowButtonShapeCases {
    interface Callback {
        fun a(z: Boolean)
        fun isCancel(): Boolean
        fun onFollowError(t: Throwable?)
        fun onFollowSuccess()
    }
    interface OtherCallback {
        fun onFollowSuccess()
        fun onSomethingElse()
    }
    interface FollowChangeListener {
        fun onFollowChange(z: Boolean)
    }
    class ChangeRelay : FollowChangeListener {
        override fun onFollowChange(z: Boolean) = Unit
    }
    interface NotAListener

    // Mirrors the host button: authorId getter, one callback field, one change-listener field.
    open class HostButton {
        @Suppress("unused") private var D: Callback? = null
        @Suppress("unused") private val E: ChangeRelay = ChangeRelay()
        fun getAuthorId(): Long = 42
        fun playAnimation() = Unit
    }

    open class MissingGetter {
        @Suppress("unused") private var D: Callback? = null
        fun playAnimation() = Unit
    }

    open class AmbiguousCallback {
        @Suppress("unused") private var D: Callback? = null
        @Suppress("unused") private var D2: OtherCallback? = null
        fun getAuthorId(): Long = 42
        fun playAnimation() = Unit
    }

    open class NoCallback {
        @Suppress("unused") private var D: NotAListener? = null
        fun getAuthorId(): Long = 42
        fun playAnimation() = Unit
    }

    open class AmbiguousListener {
        @Suppress("unused") private var D: Callback? = null
        @Suppress("unused") private val E: ChangeRelay = ChangeRelay()
        @Suppress("unused") private val E2: ChangeRelay = ChangeRelay()
        fun getAuthorId(): Long = 42
        fun playAnimation() = Unit
    }

    private val listener = FollowChangeListener::class.java

    fun anchorsResolveOnTheHostShape() {
        val anchors = requireNotNull(GuestLottieFollowButton.anchors(HostButton::class.java, listener))
        assertEquals("getAuthorId", anchors.authorId.name)
        assertEquals("D", anchors.callback.name)
        assertEquals("E", anchors.changed?.name)
        assertEquals("onFollowSuccess", anchors.onSuccess?.name)
        assertEquals("onFollowError", anchors.onError?.name)
        assertEquals("playAnimation", anchors.play?.name)
    }

    fun missingAuthorIdGetterSkipsTheButton() {
        assertNull(GuestLottieFollowButton.anchors(MissingGetter::class.java, listener))
    }

    fun ambiguousCallbackFieldsNeverResolve() {
        assertNull(GuestLottieFollowButton.callbackField(AmbiguousCallback::class.java))
        assertNull(GuestLottieFollowButton.anchors(AmbiguousCallback::class.java, listener))
    }

    fun missingCallbackFieldSkipsTheButton() {
        assertNull(GuestLottieFollowButton.anchors(NoCallback::class.java, listener))
    }

    fun ambiguousListenerFieldStillResolvesWithoutBroadcast() {
        val anchors = requireNotNull(GuestLottieFollowButton.anchors(AmbiguousListener::class.java, listener))
        assertNull(anchors.changed)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (GuestLottieFollowButtonShapeCases) -> Unit) {
            val s = GuestLottieFollowButtonShapeCases()
            block(s)
        }
        runIsolated { s -> s.anchorsResolveOnTheHostShape() }
        runIsolated { s -> s.missingAuthorIdGetterSkipsTheButton() }
        runIsolated { s -> s.ambiguousCallbackFieldsNeverResolve() }
        runIsolated { s -> s.missingCallbackFieldSkipsTheButton() }
        runIsolated { s -> s.ambiguousListenerFieldStillResolvesWithoutBroadcast() }
    }
}

private class GuestNativeFavoriteFeedbackCases {
    fun fallsBackOnlyForTheMissingHostFavoriteRepository() {
        var calls = 0
        GuestNativeFavoriteHooks.withMissingRepositoryFallback({}, { calls++; true })
        assertEquals(0, calls)
        val expected = InvocationTargetException(NoClassDefFoundError("com.bilibili.community.favorite.FavorRepository"))
        GuestNativeFavoriteHooks.withMissingRepositoryFallback({ throw expected }, { calls++; true })
        assertEquals(1, calls)
        try {
            GuestNativeFavoriteHooks.withMissingRepositoryFallback({ throw expected }, { false })
            fail("A failed fallback must preserve the native error")
        } catch (error: InvocationTargetException) { assertSame(expected, error) }
        val unrelated = InvocationTargetException(NoClassDefFoundError("other.HostType"))
        try {
            GuestNativeFavoriteHooks.withMissingRepositoryFallback({ throw unrelated }, { calls++; true })
            fail("Unrelated host failures must not be hidden")
        } catch (error: InvocationTargetException) { assertSame(unrelated, error) }
        assertEquals(1, calls)
    }
}

private class GuestNativeListHttpHooksCases {
    private companion object {
        const val PKG = "fixtures.privacy.nativelist."
    }

    private class NetworkCall { fun execute(): String = "network" }

    fun failingLocalResponsesDeliverAnIoExceptionWithoutExecutingTheHostRequest() {
        val port = PlayerPortTestRegistrar()
        var policy: HookExceptionPolicy? = null
        val registrar = object : HookRegistrar by port {
            override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy,
                                 block: ModernMemberHookCreator.() -> Unit) {
                policy = exceptionPolicy
                val method = NetworkCall::class.java.getDeclaredMethod(point.methodName)
                port.hooks[id] = PlayerPortTestRegistrar.Entry(method, ModernMemberHookCreator(method).apply(block))
            }
        }
        var localFailure = true
        GuestNativeListHttpHooks.registerExecute(registrar, NetworkCall::class.java) {
            before { if (localFailure) throwable = java.io.IOException("local response unavailable") }
        }
        var networkCalls = 0
        val error = assertThrows(java.io.IOException::class.java) {
            port.invoke("guest.native.lists.http", NetworkCall()) { networkCalls++; "network" }
        }
        assertEquals("local response unavailable", error.message)
        assertEquals(HookExceptionPolicy.DELIVER_TO_HOST, policy)
        assertEquals(0, networkCalls)
        localFailure = false
        assertEquals("network", port.invoke("guest.native.lists.http", NetworkCall()) { networkCalls++; "network" })
        assertEquals(1, networkCalls)
    }
    fun theConverterKeepsItsStableNameWhereTheBuildStillHasIt() {
        // 8.84 to 8.96: `p` and a same-shaped private `q`; the name decides, the shape is never consulted.
        val method = GuestNativeListHttpHooks.locateConverter(OldCall::class.java, Resp::class.java, Result::class.java)
        assertEquals("p", method?.name)
    }

    fun theConverterIsFoundByShapeWhenTheNameIsObfuscated() {
        // 8.97+: the single public instance (Response) -> retrofit2.Response method, whatever it is called.
        val method = GuestNativeListHttpHooks.locateConverter(NewCall::class.java, Resp::class.java, Result::class.java)
        assertEquals("a", method?.name)
    }

    fun theConverterIsNotGuessedWhenTwoPublicMethodsShareTheShape() {
        assertNull(GuestNativeListHttpHooks.locateConverter(AmbiguousCall::class.java, Resp::class.java, Result::class.java))
    }

    fun theConverterIsAbsentWhenNoMethodHasTheShape() {
        assertNull(GuestNativeListHttpHooks.locateConverter(String::class.java, Resp::class.java, Result::class.java))
    }

    fun theResponseTypeFieldIsTheOnlyFieldOfThatType() {
        val field = GuestNativeListHttpHooks.locateResponseType(WithType::class.java)
        assertEquals("c", field?.name)
    }

    fun theResponseTypeFieldIsNotGuessedWhenTwoFieldsShareTheType() {
        assertNull(GuestNativeListHttpHooks.locateResponseType(WithTwoTypes::class.java))
        assertNull(GuestNativeListHttpHooks.locateResponseType(WithoutType::class.java))
    }

    fun onlyTheLazyHoldingTheFallbackCacheIsReplaced() {
        val vm = CacheVm()
        assertEquals(1, GuestNativeListHttpHooks.dropCacheLazies(vm, PKG))
        assertNull(vm.cache.value)            // the ViewModel's `if (cache != null)` now skips both read and write
        assertTrue(vm.cache.isInitialized())
        assertEquals("key", vm.key.value)     // the key and the switch keep their own values
        assertEquals(true, vm.switch.value)
    }

    fun aViewModelWithoutACacheLazyIsLeftAlone() {
        val vm = NoCacheVm()
        assertEquals(0, GuestNativeListHttpHooks.dropCacheLazies(vm, PKG))
        assertEquals("key", vm.key.value)
    }

    fun aLazyThatThrowsOrYieldsNothingIsSkippedNotFatal() {
        val vm = BrokenVm()
        assertEquals(0, GuestNativeListHttpHooks.dropCacheLazies(vm, PKG))
        assertEquals("key", vm.key.value)
    }

    fun theDefaultPackageIsTheHostFallbackCache() {
        assertEquals("com.bilibili.common.fallbackcache.", GuestNativeListHttpHooks.CACHE_PACKAGE)
        // A fixture cache outside that package is not touched when the default is used.
        assertEquals(0, GuestNativeListHttpHooks.dropCacheLazies(CacheVm()))
    }

    @Suppress("unused")
    private class CacheVm {
        val cache: Lazy<Any?> = lazy { FakeCache() }
        val key: Lazy<Any?> = lazy { "key" }
        val switch: Lazy<Any?> = lazy { true }
    }

    @Suppress("unused")
    private class NoCacheVm {
        val key: Lazy<Any?> = lazy { "key" }
    }

    @Suppress("unused")
    private class BrokenVm {
        val boom: Lazy<Any?> = lazy { error("not ready") }
        val empty: Lazy<Any?> = lazy { null }
        val key: Lazy<Any?> = lazy { "key" }
    }

    @Suppress("unused")
    private class WithType {
        // 9.10+ renamed the field and moved the boolean flags: the type field is found by type, not by `b`.
        val m = false
        val c: Type = String::class.java
    }

    @Suppress("unused")
    private class WithTwoTypes {
        val b: Type = String::class.java
        val c: Type = Int::class.java
    }

    @Suppress("unused")
    private class WithoutType {
        val b = 1
    }

    fun verifyAllCases() {
        fun runIsolated(block: (GuestNativeListHttpHooksCases) -> Unit) {
            val s = GuestNativeListHttpHooksCases()
            block(s)
        }
        runIsolated { s -> s.failingLocalResponsesDeliverAnIoExceptionWithoutExecutingTheHostRequest() }
        runIsolated { s -> s.theConverterKeepsItsStableNameWhereTheBuildStillHasIt() }
        runIsolated { s -> s.theConverterIsFoundByShapeWhenTheNameIsObfuscated() }
        runIsolated { s -> s.theConverterIsNotGuessedWhenTwoPublicMethodsShareTheShape() }
        runIsolated { s -> s.theConverterIsAbsentWhenNoMethodHasTheShape() }
        runIsolated { s -> s.theResponseTypeFieldIsTheOnlyFieldOfThatType() }
        runIsolated { s -> s.theResponseTypeFieldIsNotGuessedWhenTwoFieldsShareTheType() }
        runIsolated { s -> s.onlyTheLazyHoldingTheFallbackCacheIsReplaced() }
        runIsolated { s -> s.aViewModelWithoutACacheLazyIsLeftAlone() }
        runIsolated { s -> s.aLazyThatThrowsOrYieldsNothingIsSkippedNotFatal() }
        runIsolated { s -> s.theDefaultPackageIsTheHostFallbackCache() }
    }
}

private class GuestPlaybackItemIdCases {
    fun aidPlusCidIdIsSplit() {
        assertEquals(170001L to 279786L, GuestPlaybackHooks.parseItemId("170001+279786"))
        assertEquals(1L to 2L, GuestPlaybackHooks.parseItemId("1+2"))
    }

    fun bareOrMalformedIdsAreNotGuessed() {
        assertNull(GuestPlaybackHooks.parseItemId("279786"))
        assertNull(GuestPlaybackHooks.parseItemId("170001+"))
        assertNull(GuestPlaybackHooks.parseItemId("+279786"))
        assertNull(GuestPlaybackHooks.parseItemId("170001+279786+3"))
        assertNull(GuestPlaybackHooks.parseItemId("av170001+279786"))
        assertNull(GuestPlaybackHooks.parseItemId(""))
    }

    fun zeroAndOverflowingIdsAreRejected() {
        assertNull(GuestPlaybackHooks.parseItemId("0+279786"))
        assertNull(GuestPlaybackHooks.parseItemId("170001+0"))
        assertNull(GuestPlaybackHooks.parseItemId("99999999999999999999+1"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (GuestPlaybackItemIdCases) -> Unit) {
            val s = GuestPlaybackItemIdCases()
            block(s)
        }
        runIsolated { s -> s.aidPlusCidIdIsSplit() }
        runIsolated { s -> s.bareOrMalformedIdsAreNotGuessed() }
        runIsolated { s -> s.zeroAndOverflowingIdsAreRejected() }
    }
}

private class GuestStoryStarCountCases {
    fun aWholeNumberCountMovesByOne() {
        assertEquals("9029", GuestStoryStar.nudged("9028", 1))
        assertEquals("9027", GuestStoryStar.nudged("9028", -1))
    }

    fun surroundingWhitespaceIsIgnored() {
        assertEquals("10", GuestStoryStar.nudged("  9 ", 1))
    }

    fun anAbbreviatedCountIsLeftAloneBecauseTheRoundingCannotBeGuessed() {
        assertNull(GuestStoryStar.nudged("1.2万", 1))
        assertNull(GuestStoryStar.nudged("3.9万", -1))
        assertNull(GuestStoryStar.nudged("1,234", 1))
    }

    fun emptyOrMissingTextIsLeftAlone() {
        assertNull(GuestStoryStar.nudged(null, 1))
        assertNull(GuestStoryStar.nudged("", 1))
        assertNull(GuestStoryStar.nudged("   ", -1))
    }

    fun aCountNeverGoesBelowZero() {
        assertEquals("0", GuestStoryStar.nudged("0", -1))
        assertEquals(0, GuestStoryStar.shifted(0, -1))
        assertEquals(0, GuestStoryStar.shifted(1, -5))
    }

    fun theNumericCountMovesByTheDelta() {
        assertEquals(10, GuestStoryStar.shifted(9, 1))
        assertEquals(8, GuestStoryStar.shifted(9, -1))
    }

    fun textThatLooksNumericButIsNotAPlainDigitRunIsNotTouched() {
        assertNull(GuestStoryStar.nudged("-5", 1))
        assertNull(GuestStoryStar.nudged("+5", 1))
        assertNull(GuestStoryStar.nudged("5.0", 1))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (GuestStoryStarCountCases) -> Unit) {
            val s = GuestStoryStarCountCases()
            block(s)
        }
        runIsolated { s -> s.aWholeNumberCountMovesByOne() }
        runIsolated { s -> s.surroundingWhitespaceIsIgnored() }
        runIsolated { s -> s.anAbbreviatedCountIsLeftAloneBecauseTheRoundingCannotBeGuessed() }
        runIsolated { s -> s.emptyOrMissingTextIsLeftAlone() }
        runIsolated { s -> s.aCountNeverGoesBelowZero() }
        runIsolated { s -> s.theNumericCountMovesByTheDelta() }
        runIsolated { s -> s.textThatLooksNumericButIsNotAPlainDigitRunIsNotTouched() }
    }
}
