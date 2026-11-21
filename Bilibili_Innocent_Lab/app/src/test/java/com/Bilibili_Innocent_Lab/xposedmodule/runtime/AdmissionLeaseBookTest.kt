package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdmissionLeaseBookTest {
    private var now = 10L
    private val book = AdmissionLeaseBook { now }
    private val identity = PublicationIdentity("0123456789abcdef", 1, 1, 1, "a".repeat(64))
    private fun lease(challenge: String = "c") = AdmissionLeaseBook.Lease(10001, 20, "nonce", challenge, "module_direct", identity, 100)
    fun confirmationIsSingleUseAndBoundToProcessAndNonce() {
        assertTrue(book.prepare(lease()))
        assertNull(book.consume(10002,20,"nonce","c",identity))
        assertNull(book.consume(10001,21,"nonce","c",identity))
        assertNull(book.consume(10001,20,"forged","c",identity))
        assertNotNull(book.consume(10001,20,"nonce","c",identity))
        assertNull(book.consume(10001,20,"nonce","c",identity))
    }
    fun elapsedDeadlineRejectsLateCompletion() {
        book.prepare(lease()); now = 101
        assertNull(book.consume(10001,20,"nonce","c",identity))
        assertFalse(book.prepare(lease()))
    }
    fun anyChangedConsentPolicySnapshotOrPublisherInvalidatesThePreparedIdentity() {
        listOf(identity.copy(consentRevision=2), identity.copy(policyEpoch=2), identity.copy(snapshotRevision=2),
            identity.copy(incarnation="fedcba9876543210"), identity.copy(fingerprint="b".repeat(64))).forEachIndexed { i, changed ->
            val c = "c$i"; assertTrue(book.prepare(lease(c)))
            assertNull(book.consume(10001,20,"nonce",c,changed))
            assertNull(book.consume(10001,20,"nonce",c,identity))
        }
    }
    fun capacityIsBoundedAndExpiredRequestsAreReclaimed() {
        repeat(64) { assertTrue(book.prepare(lease("c$it"))) }
        assertFalse(book.prepare(lease("overflow"))); now = 101
        assertTrue(book.prepare(lease("fresh").copy(deadline=200)))
    }
    fun duplicateChallengesCannotReplaceAnotherProcess() {
        book.prepare(lease()); assertFalse(book.prepare(lease().copy(pid=22)))
        assertNotNull(book.consume(10001,20,"nonce","c",identity))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AdmissionLeaseBookTest) -> Unit) {
            val s = AdmissionLeaseBookTest()
            block(s)
        }
        runIsolated { s -> s.confirmationIsSingleUseAndBoundToProcessAndNonce() }
        runIsolated { s -> s.elapsedDeadlineRejectsLateCompletion() }
        runIsolated { s -> s.anyChangedConsentPolicySnapshotOrPublisherInvalidatesThePreparedIdentity() }
        runIsolated { s -> s.capacityIsBoundedAndExpiredRequestsAreReclaimed() }
        runIsolated { s -> s.duplicateChallengesCannotReplaceAnotherProcess() }
    
        AdmissionReplayBookCases().verifyAllCases()
        AdmissionResponseCoordinatorCases().verifyAllCases()
        AndroidUserSpaceCases().verifyAllCases()
        CanaryBuildCheckerCases().verifyAllCases()
        ColdStartUpdateStateCases().verifyAllCases()
    }
}

private class AdmissionReplayBookCases {
    fun sameKeyComputesOnceWhenFourTransportsRace() {
        var now = 0L
        val book = AdmissionReplayBook(clock = { now })
        val computes = AtomicInteger()
        val entered = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        val task = Callable {
            book.getOrPut("nonce", "prepare_admission") {
                computes.incrementAndGet()
                entered.countDown()
                Thread.sleep(40)
                Bundle()
            }
        }
        val futures = List(4) { pool.submit(task) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue(futures.all { it.get(2, TimeUnit.SECONDS) != null })
        pool.shutdownNow()
        assertEquals(1, computes.get())
        now = AdmissionReplayBook.TTL_MS + 1
        val secondWave = AtomicInteger()
        assertNotNull(
            book.getOrPut("nonce", "prepare_admission") {
                secondWave.incrementAndGet()
                Bundle()
            }
        )
        assertEquals(1, secondWave.get())
    }

    fun differentKeysDoNotHoldEachOtherOutOfCompute() {
        val book = AdmissionReplayBook(clock = { 0L })
        val aStarted = CountDownLatch(1)
        val bStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val a = pool.submit(Callable {
            book.getOrPut("a", "prepare_admission") {
                aStarted.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                Bundle()
            }
        })
        val b = pool.submit(Callable {
            book.getOrPut("b", "prepare_admission") {
                bStarted.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                Bundle()
            }
        })
        assertTrue(aStarted.await(2, TimeUnit.SECONDS))
        assertTrue(bStarted.await(2, TimeUnit.SECONDS))
        release.countDown()
        assertNotNull(a.get(2, TimeUnit.SECONDS))
        assertNotNull(b.get(2, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    fun nullComputeIsNotCached() {
        val book = AdmissionReplayBook(clock = { 0L })
        val computes = AtomicInteger()
        assertNull(book.getOrPut("n", "prepare_admission") {
            computes.incrementAndGet()
            null
        })
        assertNotNull(book.getOrPut("n", "prepare_admission") {
            computes.incrementAndGet()
            Bundle()
        })
        assertEquals(2, computes.get())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AdmissionReplayBookCases) -> Unit) {
            val s = AdmissionReplayBookCases()
            block(s)
        }
        runIsolated { s -> s.sameKeyComputesOnceWhenFourTransportsRace() }
        runIsolated { s -> s.differentKeysDoNotHoldEachOtherOutOfCompute() }
        runIsolated { s -> s.nullComputeIsNotCached() }
    }
}

private class AdmissionResponseCoordinatorCases {
    fun `unhandled delivery only ends stage with validated fallback`() {
        val coordinator = AdmissionResponseCoordinator()
        val prepare = coordinator.begin("prepare")
        assertFalse(coordinator.completeUnhandled("prepare", false))
        assertFalse(prepare.response.isDone)
        assertTrue(coordinator.completeUnhandled("prepare", true))
        assertTrue(prepare.response.isDone)
        coordinator.clear(prepare)
        val confirm = coordinator.begin("confirm")
        assertFalse(coordinator.completeUnhandled("prepare", true))
        assertFalse(confirm.response.isDone)
    }

    fun `confirm response cannot consume a completed prepare future`() {
        val coordinator = AdmissionResponseCoordinator()
        val prepare = coordinator.begin("prepare")
        val prepareResponse = Bundle()

        assertTrue(coordinator.complete("prepare", prepareResponse))
        assertSame(prepareResponse, prepare.response.get(1, TimeUnit.SECONDS))

        coordinator.clear(prepare)
        val confirm = coordinator.begin("confirm")
        assertFalse(coordinator.complete("prepare", Bundle()))
        val confirmResponse = Bundle()
        assertTrue(coordinator.complete("confirm", confirmResponse))
        assertSame(confirmResponse, confirm.response.get(1, TimeUnit.SECONDS))
    }

    fun `empty transport reply does not finish active stage`() {
        val coordinator = AdmissionResponseCoordinator()
        val awaiter = coordinator.begin("prepare")

        assertFalse(coordinator.complete("prepare", null))
        assertFalse(awaiter.response.isDone)
        val response = Bundle()
        assertTrue(coordinator.complete("prepare", response))
        assertSame(response, awaiter.response.get(1, TimeUnit.SECONDS))
    }

    fun `stale action cannot complete a newer stage`() {
        val coordinator = AdmissionResponseCoordinator()
        val prepare = coordinator.begin("prepare")
        coordinator.clear(prepare)
        val confirm = coordinator.begin("confirm")

        assertFalse(coordinator.complete("prepare", Bundle()))
        assertFalse(confirm.response.isDone)
    }

    fun `malformed callback payload is isolated from the waiting stage`() {
        val coordinator = AdmissionResponseCoordinator()
        val awaiter = coordinator.begin("prepare")

        assertFalse(coordinator.completeSafely("prepare") { error("malformed bundle") })
        assertFalse(awaiter.response.isDone)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AdmissionResponseCoordinatorCases) -> Unit) {
            val s = AdmissionResponseCoordinatorCases()
            block(s)
        }
        runIsolated { s -> s.`unhandled delivery only ends stage with validated fallback`() }
        runIsolated { s -> s.`confirm response cannot consume a completed prepare future`() }
        runIsolated { s -> s.`empty transport reply does not finish active stage`() }
        runIsolated { s -> s.`stale action cannot complete a newer stage`() }
        runIsolated { s -> s.`malformed callback payload is isolated from the waiting stage`() }
    }
}

private class AndroidUserSpaceCases {

    fun `uid encoding resolves primary secondary and clone users`() {
        assertEquals(0, AndroidUserSpace.userIdFromUid(10_234))
        assertEquals(10, AndroidUserSpace.userIdFromUid(1_010_234))
        // MIUI 应用双开使用 userId 999。
        assertEquals(999, AndroidUserSpace.userIdFromUid(99_910_234))
    }

    fun `negative uid never fabricates a clone profile`() {
        assertEquals(0, AndroidUserSpace.userIdFromUid(-1))
        assertEquals(0, AndroidUserSpace.userIdFromUid(Int.MIN_VALUE))
        assertFalse(
            AndroidUserSpace.isSecondaryOrCloneProfile(AndroidUserSpace.userIdFromUid(-1))
        )
    }

    fun `only non-primary users are treated as possible clone profiles`() {
        assertFalse(AndroidUserSpace.isSecondaryOrCloneProfile(0))
        assertTrue(AndroidUserSpace.isSecondaryOrCloneProfile(10))
        assertTrue(AndroidUserSpace.isSecondaryOrCloneProfile(999))
    }

    fun `same-user comparison stays unknown while the target is invisible`() {
        assertNull(AndroidUserSpace.resolveSameUser(moduleUserId = 999, targetUserId = null))
        assertEquals(true, AndroidUserSpace.resolveSameUser(999, 999))
        assertEquals(false, AndroidUserSpace.resolveSameUser(999, 0))
        assertEquals(false, AndroidUserSpace.resolveSameUser(0, 999))
    }

    fun `snapshot derives clone flag from the module user only`() {
        val clone = AndroidUserSpaceSnapshot(
            moduleUserId = 999,
            targetUserId = 999,
            sameUser = true
        )
        assertTrue(clone.possibleSecondaryOrCloneProfile)

        val primaryModuleWithCloneTarget = AndroidUserSpaceSnapshot(
            moduleUserId = 0,
            targetUserId = 999,
            sameUser = false
        )
        assertFalse(primaryModuleWithCloneTarget.possibleSecondaryOrCloneProfile)
    }

    fun `neutral snapshot produces no multi-user hint`() {
        val neutral = AndroidUserSpaceSnapshot.PRIMARY
        assertEquals(AndroidUserSpace.PRIMARY_USER_ID, neutral.moduleUserId)
        assertNull(neutral.targetUserId)
        assertNull(neutral.sameUser)
        assertFalse(neutral.possibleSecondaryOrCloneProfile)
    }

    fun `target cache path helper agrees with the shared user resolution`() {
        assertEquals(
            AndroidUserSpace.userIdFromUid(99_910_234),
            TargetAppStorage.userIdFromUid(99_910_234)
        )
        assertEquals(
            "/data/user/999/tv.danmaku.bili/cache/innocent_lab_adapt.json",
            TargetAppStorage.cachePath(
                "innocent_lab_adapt.json",
                AndroidUserSpace.userIdFromUid(99_910_234)
            )
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AndroidUserSpaceCases) -> Unit) {
            val s = AndroidUserSpaceCases()
            block(s)
        }
        runIsolated { s -> s.`uid encoding resolves primary secondary and clone users`() }
        runIsolated { s -> s.`negative uid never fabricates a clone profile`() }
        runIsolated { s -> s.`only non-primary users are treated as possible clone profiles`() }
        runIsolated { s -> s.`same-user comparison stays unknown while the target is invisible`() }
        runIsolated { s -> s.`snapshot derives clone flag from the module user only`() }
        runIsolated { s -> s.`neutral snapshot produces no multi-user hint`() }
        runIsolated { s -> s.`target cache path helper agrees with the shared user resolution`() }
    }
}

private class CanaryBuildCheckerCases {
    private val sha = "a".repeat(40)
    private fun run(id: Long = 7, number: Long = 3) = JSONObject().apply {
        put("id",id);put("run_number",number);put("head_sha",sha);put("head_branch","main")
        put("event","push");put("status","completed");put("conclusion","success")
        put("path",".github/workflows/canary-build.yml")
        put("head_repository",JSONObject().put("full_name","jichuo1/Bilibili_Innocent_Lab"))
        put("html_url","https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/$id")
    }
    private fun artifact(tag: String = "v1.2.1-canary.3") = JSONObject().apply {
        put("name","Bilibili_Innocent_Lab-$tag-aaaaaaaa");put("expired",false);put("size_in_bytes",100)
        put("workflow_run",JSONObject().put("id",7).put("head_sha",sha))
    }
    private fun runs(vararg values: JSONObject) = JSONObject().put("workflow_runs",JSONArray(values.toList())).toString()
    private fun artifacts(vararg values: JSONObject) = JSONObject().put("artifacts",JSONArray(values.toList())).toString()
    private fun currentRun() = CanaryBuildChecker.parseRuns(runs(run())).single()

    fun validSuccessfulMainPushHasAChannelSpecificVersionAndActionsDestination() {
        val result=CanaryBuildChecker.parseArtifacts(artifacts(artifact()),currentRun())!!
        assertEquals("v1.2.1-canary.3",result.tagName)
        assertTrue(result.actionsBuild);assertTrue(result.prerelease);assertNull(result.apkDownloadUrl)
        assertEquals("https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/7",result.htmlUrl)
    }

    fun untrustedOrIncompleteRunsNeverBecomeCanaryUpdates() {
        for ((key,value) in listOf("head_branch" to "other","event" to "pull_request","status" to "in_progress",
            "conclusion" to "failure","path" to ".github/workflows/alpha-release.yml","head_sha" to "not-a-sha")) {
            assertTrue(key,CanaryBuildChecker.parseRuns(runs(run().put(key,value))).isEmpty())
        }
        assertTrue(CanaryBuildChecker.parseRuns(runs(run().put("head_repository",JSONObject().put("full_name","attacker/repo")))).isEmpty())
    }

    fun expiredMismatchedAndUnsignedArtifactNamesAreRejected() {
        for (value in listOf(artifact().put("expired",true),artifact("v1.2.1-alpha.3"),artifact("v1.2.1-canary.4"),
            artifact().put("name","debug.apk"),artifact().put("size_in_bytes",0),
            artifact().put("workflow_run",JSONObject().put("id",8).put("head_sha",sha)))) {
            assertNull(CanaryBuildChecker.parseArtifacts(artifacts(value),currentRun()))
        }
    }

    fun runAndArtifactInspectionRemainBounded() {
        assertEquals(3,CanaryBuildChecker.parseRuns(runs(*Array(20){run((it+1).toLong())})).size)
        val values=Array(21){artifact().put("expired",true)};values[20]=artifact()
        assertNull(CanaryBuildChecker.parseArtifacts(artifacts(*values),currentRun()))
    }

    fun actionUrlsCannotEscapeTheRepositoryOrEmbedCredentials() {
        for (url in listOf("https://evil.invalid/jichuo1/Bilibili_Innocent_Lab/actions/runs/7",
            "https://github.com/other/repo/actions/runs/7","https://user@github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/7",
            "https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/7?next=evil","http://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/7")) {
            assertThrows(IOException::class.java){CanaryBuildChecker.validateRunUrl(url,7)}
        }
    }

    fun versionAndStorageExtendCanaryWithoutChangingTheAlphaChannel() {
        assertEquals(GitHubReleaseChecker.UpdateChannel.CANARY,GitHubReleaseChecker.UpdateChannel.fromStorageValue("canary"))
        assertEquals(GitHubReleaseChecker.UpdateChannel.PREVIEW,GitHubReleaseChecker.UpdateChannel.fromStorageValue("preview"))
        assertEquals(GitHubReleaseChecker.UpdateChannel.STABLE,GitHubReleaseChecker.UpdateChannel.fromStorageValue("unknown"))
        val early=GitHubReleaseChecker.ReleaseVersion.parse("v1.2.1-canary.2")!!
        val later=GitHubReleaseChecker.ReleaseVersion.parse("v1.2.1-canary.3")!!
        val alpha=GitHubReleaseChecker.ReleaseVersion.parse("v1.2.1-alpha.1")!!
        val stable=GitHubReleaseChecker.ReleaseVersion.parse("v1.2.1")!!
        assertTrue(early<later);assertTrue(later<alpha);assertTrue(alpha<stable)
        assertEquals(GitHubReleaseChecker.VersionRelation.REMOTE_NEWER,GitHubReleaseChecker.compareVersions("v1.2.1-canary.3","1.2.1-alpha.1",GitHubReleaseChecker.UpdateChannel.CANARY))
        assertEquals(GitHubReleaseChecker.VersionRelation.LOCAL_NEWER,GitHubReleaseChecker.compareVersions("v1.2.1-canary.3","1.2.1"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CanaryBuildCheckerCases) -> Unit) {
            val s = CanaryBuildCheckerCases()
            block(s)
        }
        runIsolated { s -> s.validSuccessfulMainPushHasAChannelSpecificVersionAndActionsDestination() }
        runIsolated { s -> s.untrustedOrIncompleteRunsNeverBecomeCanaryUpdates() }
        runIsolated { s -> s.expiredMismatchedAndUnsignedArtifactNamesAreRejected() }
        runIsolated { s -> s.runAndArtifactInspectionRemainBounded() }
        runIsolated { s -> s.actionUrlsCannotEscapeTheRepositoryOrEmbedCredentials() }
        runIsolated { s -> s.versionAndStorageExtendCanaryWithoutChangingTheAlphaChannel() }
    }
}

private class ColdStartUpdateStateCases {
    private val stable = GitHubReleaseChecker.UpdateChannel.STABLE
    private val preview = GitHubReleaseChecker.UpdateChannel.PREVIEW
    private fun release(tag: String) = GitHubReleaseChecker.ReleaseInfo(
        tag, tag, "notes", GitHubReleaseChecker.REPOSITORY_URL + "/releases/tag/" + tag, null, tag.contains("alpha")
    )

    fun coldStartWaitsTenSecondsAndOnlyClaimsOncePerProcess() {
        val state = ColdStartUpdateState()
        state.resume(1, 1000)
        assertFalse(state.claimAutomatic(1, 10999))
        assertTrue(state.claimAutomatic(1, 11000))
        assertFalse(state.claimAutomatic(1, 20000))
        state.pause(1)
        state.resume(2, 30000)
        assertNull(state.remainingMs(2, 50000))
        assertFalse(state.claimAutomatic(2, 50000))
        val anotherProcess = ColdStartUpdateState()
        anotherProcess.resume(1, 0)
        assertTrue(anotherProcess.claimAutomatic(1, 10000))
    }

    fun foregroundDwellRestartsAfterPauseAndOldActivityCannotClaim() {
        val state = ColdStartUpdateState()
        state.resume(1, 0)
        state.resume(1, 7000)
        assertEquals(3000L, state.remainingMs(1, 7000))
        state.pause(1)
        assertFalse(state.claimAutomatic(1, 20000))
        state.resume(2, 21000)
        state.pause(1)
        assertFalse(state.claimAutomatic(1, 40000))
        assertFalse(state.claimAutomatic(2, 30999))
        assertTrue(state.claimAutomatic(2, 31000))
    }

    fun onlyCurrentChannelAndNewestRequestCanReplaceNotice() {
        val state = ColdStartUpdateState()
        val first = state.beginRequest()
        val second = state.beginRequest()
        assertFalse(state.accept(first, stable, stable, release("v1.2.0"), "1.1.4"))
        assertFalse(state.accept(second, preview, stable, release("v1.3.0-alpha.1"), "1.1.4"))
        assertTrue(state.accept(second, stable, stable, release("v1.2.0"), "1.1.4"))
        assertNotNull(state.noticeFor(stable))
        assertNull(state.noticeFor(preview))
        state.channelChanged()
        assertNull(state.noticeFor(stable))
    }

    fun equalOlderAndInvalidVersionsNeverShowNewAndResultsSurviveActivityRecreation() {
        val state = ColdStartUpdateState()
        val request = state.beginRequest()
        state.resume(1, 0)
        state.pause(1)
        state.resume(2, 1000)
        assertTrue(state.accept(request, stable, stable, release("v1.2.0"), "1.1.4"))
        assertEquals("v1.2.0", state.noticeFor(stable)?.release?.tagName)
        for (tag in listOf("1.1.4", "1.1.3", "bad")) {
            state.accept(state.beginRequest(), stable, stable, release(tag), "1.1.4")
            assertNull(state.noticeFor(stable))
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ColdStartUpdateStateCases) -> Unit) {
            val s = ColdStartUpdateStateCases()
            block(s)
        }
        runIsolated { s -> s.coldStartWaitsTenSecondsAndOnlyClaimsOncePerProcess() }
        runIsolated { s -> s.foregroundDwellRestartsAfterPauseAndOldActivityCannotClaim() }
        runIsolated { s -> s.onlyCurrentChannelAndNewestRequestCanReplaceNotice() }
        runIsolated { s -> s.equalOlderAndInvalidVersionsNeverShowNewAndResultsSurviveActivityRecreation() }
    }
}
