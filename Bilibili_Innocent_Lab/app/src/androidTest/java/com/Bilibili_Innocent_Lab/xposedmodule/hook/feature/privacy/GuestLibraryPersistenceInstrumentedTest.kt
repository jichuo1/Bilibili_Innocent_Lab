package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Runs against Android's real AtomicFile/Os.rename and executor/main-loop boundary.
 * Uses a test-only directory in the module app; never reads or changes the host account. */
@RunWith(AndroidJUnit4::class)
class GuestLibraryPersistenceInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val video = LocalVirtualLibrary.Video(170001, 279786, "Persistence fixture", 199000, 0, 1)

    private class StorageContext(base: Context, private val directory: File, private val forbidDisk: Boolean) : ContextWrapper(base) {
        val accesses = AtomicInteger()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File {
            accesses.incrementAndGet()
            check(!forbidDisk) { "Incognito touched persistent storage" }
            return directory
        }
    }

    private fun position(runtime: GuestLibraryRuntime): Long? {
        val completed = CountDownLatch(1)
        val value = AtomicReference<Long?>()
        runtime.resumePosition(video.aid, video.cid) { value.set(it); completed.countDown() }
        assertTrue("Resume callback timed out", completed.await(5, TimeUnit.SECONDS))
        return value.get()
    }

    @Test fun guestProgressSurvivesRuntimeRecreationAtMillisecondPrecision() {
        val directory = File(app.cacheDir, "guest-library-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = StorageContext(app, directory, false)
        val errors = AtomicInteger()
        try {
            val first = GuestLibraryRuntime(context, false, { true }, { errors.incrementAndGet() })
            first.recordVideo(video)
            first.recordProgress(video.aid, video.cid, 123456, video.durationMs)
            assertEquals(123456L, position(first))
            val persisted = File(directory, "bil_guest_library.json")
            assertTrue(persisted.isFile)
            assertFalse(File(directory, "bil_guest_library.json.pending").exists())
            val second = GuestLibraryRuntime(context, false, { true }, { errors.incrementAndGet() })
            assertEquals(123456L, position(second))
            assertEquals(0, errors.get())
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun incognitoProgressIsMemoryOnlyAndNewSessionStartsEmpty() {
        val context = StorageContext(app, File(app.cacheDir, "must-not-be-opened"), true)
        val errors = AtomicInteger()
        val first = GuestLibraryRuntime(context, true, { true }, { errors.incrementAndGet() })
        first.recordVideo(video)
        first.recordProgress(video.aid, video.cid, 123456, video.durationMs)
        assertEquals(123456L, position(first))
        val second = GuestLibraryRuntime(context, true, { true }, { errors.incrementAndGet() })
        assertNull(position(second))
        assertEquals(0, context.accesses.get())
        assertEquals(0, errors.get())
    }

    @Test fun refreshingIdentityDiscardsProgressWithoutOpeningTheGuestStore() {
        val context = StorageContext(app, File(app.cacheDir, "must-not-be-opened"), true)
        val errors = AtomicInteger()
        val runtime = GuestLibraryRuntime(context, true, { true }, { errors.incrementAndGet() })
        runtime.recordVideo(video)
        runtime.recordProgress(video.aid, video.cid, 65432, video.durationMs)
        assertEquals(65432L, position(runtime))
        val before = runtime.session.identity()
        runtime.refreshIdentity()
        assertNotEquals(before, runtime.session.identity())
        // Queries are deliberately dropped while the reset barrier is closed. Poll only until
        // the first post-reset callback, without assuming executor scheduling on this device.
        val done = CountDownLatch(1)
        val value = AtomicReference<Long?>()
        val deadline = SystemClock.uptimeMillis() + 5000
        while (done.count > 0 && SystemClock.uptimeMillis() < deadline) {
            runtime.resumePosition(video.aid, video.cid) { value.set(it); done.countDown() }
            done.await(50, TimeUnit.MILLISECONDS)
        }
        assertEquals(0L, done.count)
        assertNull(value.get())
        assertEquals(0, context.accesses.get())
        assertEquals(0, errors.get())
    }
    @Test fun nativeFavoriteCommitPersistsBeforeFeedbackAndObserverNotification() {
        val directory = File(app.cacheDir, "guest-library-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = StorageContext(app, directory, false)
        val errors = AtomicInteger()
        val runtime = GuestLibraryRuntime(context, false, { true }, { errors.incrementAndGet() })
        val feedback = AtomicInteger()
        val notified = CountDownLatch(1)
        val observed = AtomicReference<LocalVirtualLibrary.Collections>()
        runtime.observeCollections { state ->
            if (feedback.get() == 1) { observed.set(state); notified.countDown() }
        }
        try {
            runtime.editCollections({ store ->
                store.createFavoriteFolder("native", LocalVirtualLibrary.FolderInfo(42, "description"))
                store.setFavoriteFolders(video, setOf("native"))
            }, { feedback.incrementAndGet() })
            assertTrue("Native observer did not follow committed feedback", notified.await(5, TimeUnit.SECONDS))
            val reloaded = GuestLibraryRuntime(context, false, { true }, { errors.incrementAndGet() })
            val loaded = CountDownLatch(1)
            val restored = AtomicReference<LocalVirtualLibrary.Collections>()
            reloaded.readCollections { restored.set(it); loaded.countDown() }
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
            assertEquals(observed.get(), restored.get())
            assertEquals(42L, restored.get().folderInfo.getValue("native").id)
            assertEquals(listOf(video), restored.get().favorites.getValue("native"))
            assertEquals(0, errors.get())
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun identityRefreshPublishesEmptyNativeFavoriteStateWithoutDiskAccess() {
        val context = StorageContext(app, File(app.cacheDir, "must-not-be-opened"), true)
        val errors = AtomicInteger()
        val runtime = GuestLibraryRuntime(context, true, { true }, { errors.incrementAndGet() })
        val committed = CountDownLatch(1)
        runtime.editCollections({ store ->
            store.createFavoriteFolder("temporary", LocalVirtualLibrary.FolderInfo(42, "temporary"))
            store.setFavoriteFolders(video, setOf("temporary"))
        }, { committed.countDown() })
        assertTrue(committed.await(5, TimeUnit.SECONDS))
        val cleared = CountDownLatch(1)
        runtime.observeCollections { if (it == LocalVirtualLibrary.Collections()) cleared.countDown() }
        runtime.refreshIdentity()
        assertTrue("Native selection was not cleared", cleared.await(5, TimeUnit.SECONDS))
        assertEquals(0, context.accesses.get())
        assertEquals(0, errors.get())
    }
    @Test fun nativeRequestCompletesWithFailureWhenIdentityChangesDuringExecution() {
        val context = StorageContext(app, File(app.cacheDir, "must-not-be-opened"), true)
        val runtime = GuestLibraryRuntime(context, true, { true }, {})
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failed = AtomicReference<Boolean>()
        runtime.nativeRequest({
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            "stale result"
        }) { outcome -> failed.set(outcome.isFailure); finished.countDown() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        runtime.refreshIdentity()
        release.countDown()
        assertTrue("Native RPC was left pending", finished.await(5, TimeUnit.SECONDS))
        assertEquals(true, failed.get())
        assertEquals(0, context.accesses.get())
    }

    @Test fun nativeDeleteInvalidatesPreciseResumeCacheAfterCommit() {
        val context = StorageContext(app, File(app.cacheDir, "must-not-be-opened"), true)
        val runtime = GuestLibraryRuntime(context, true, { true }, {})
        runtime.recordVideo(video)
        runtime.recordProgress(video.aid, video.cid, 65432, video.durationMs)
        assertEquals(65432L, position(runtime))
        val finished = CountDownLatch(1)
        runtime.nativeRequest({ it.clearHistory() }) { finished.countDown() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertNull(runtime.cachedResumePosition(video.cid))
        assertNull(position(runtime))
        assertEquals(0, context.accesses.get())
    }

}
