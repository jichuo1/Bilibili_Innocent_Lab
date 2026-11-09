package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestLottieFollowButtonShapeTest {
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

    @Test fun anchorsResolveOnTheHostShape() {
        val anchors = requireNotNull(GuestLottieFollowButton.anchors(HostButton::class.java, listener))
        assertEquals("getAuthorId", anchors.authorId.name)
        assertEquals("D", anchors.callback.name)
        assertEquals("E", anchors.changed?.name)
        assertEquals("onFollowSuccess", anchors.onSuccess?.name)
        assertEquals("onFollowError", anchors.onError?.name)
        assertEquals("playAnimation", anchors.play?.name)
    }

    @Test fun missingAuthorIdGetterSkipsTheButton() {
        assertNull(GuestLottieFollowButton.anchors(MissingGetter::class.java, listener))
    }

    @Test fun ambiguousCallbackFieldsNeverResolve() {
        assertNull(GuestLottieFollowButton.callbackField(AmbiguousCallback::class.java))
        assertNull(GuestLottieFollowButton.anchors(AmbiguousCallback::class.java, listener))
    }

    @Test fun missingCallbackFieldSkipsTheButton() {
        assertNull(GuestLottieFollowButton.anchors(NoCallback::class.java, listener))
    }

    @Test fun ambiguousListenerFieldStillResolvesWithoutBroadcast() {
        val anchors = requireNotNull(GuestLottieFollowButton.anchors(AmbiguousListener::class.java, listener))
        assertNull(anchors.changed)
    }
}
