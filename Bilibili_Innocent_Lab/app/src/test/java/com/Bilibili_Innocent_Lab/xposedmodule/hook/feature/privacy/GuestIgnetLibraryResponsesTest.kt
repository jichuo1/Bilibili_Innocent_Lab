package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.bilibili.lib.ighttp.IgHttpRequest
import com.bilibili.lib.ighttp.IgHttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class GuestIgnetLibraryResponsesTest {
    private val statuses = linkedMapOf<String, String>()
    private val asked = mutableListOf<Pair<String, Map<String, String>>>()
    private var guest = true
    private var reply: (String) -> String? = { path -> """{"code":0,"data":{"path":"$path"}}""" }

    @Before fun reset() {
        statuses.clear()
        asked.clear()
        guest = true
        reply = { path -> """{"code":0,"data":{"path":"$path"}}""" }
    }

    private fun environment(registrar: PlayerPortTestRegistrar) =
        HookEnvironment("tv.danmaku.bili", javaClass.classLoader!!, HookPointRegistry(javaClass.classLoader!!), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })

    private fun installed(requestClass: String = GuestIgnetLibraryResponses.REQUEST): PlayerPortTestRegistrar {
        val registrar = PlayerPortTestRegistrar()
        GuestIgnetLibraryResponses({ guest }, { path, params -> asked += path to params; reply(path) }, requestClass)
            .install(environment(registrar))
        return registrar
    }

    private fun request(url: String, method: String = "GET") = IgHttpRequest.Builder().url(url).method(method, null).build()

    /** Runs execute() through the hook; the fixture's own execute() stands for the network call. */
    private fun PlayerPortTestRegistrar.execute(request: IgHttpRequest) =
        invoke("guest.ignet.library.execute", request) { request.execute() } as IgHttpResponse

    /** Reads the body the way the Ktor engine does: 32 KiB buffer until readDataV2 is not positive. */
    private fun PlayerPortTestRegistrar.body(response: IgHttpResponse, buffer: Int = 32768): String {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(buffer)
        while (true) {
            val count = invoke("guest.ignet.library.read", response, arrayOf(chunk, chunk.size)) { response.readDataV2(chunk, chunk.size) } as Int
            if (count <= 0) break
            out.write(chunk, 0, count)
        }
        return out.toString("UTF-8")
    }

    private fun PlayerPortTestRegistrar.code(response: IgHttpResponse) = invoke("guest.ignet.library.code", response) { response.httpCode() }
    private fun PlayerPortTestRegistrar.message(response: IgHttpResponse) = invoke("guest.ignet.library.message", response) { response.httpMessage() }
    private fun PlayerPortTestRegistrar.headers(response: IgHttpResponse) = invoke("guest.ignet.library.headers", response) { response.headers() }

    @Test fun aGuestListReadIsAnsweredFromTheLocalLibrary() {
        val registrar = installed()
        val response = registrar.execute(request("https://api.bilibili.com/x/v3/fav/tab/fav?tab_id=2&start_oid=0&page_type=1"))

        assertEquals(200, registrar.code(response))
        assertEquals("OK", registrar.message(response))
        assertEquals(mapOf("Content-Type" to "application/json; charset=utf-8"), registrar.headers(response))
        assertEquals("""{"code":0,"data":{"path":"/x/v3/fav/tab/fav"}}""", registrar.body(response))
        assertEquals(listOf("/x/v3/fav/tab/fav" to mapOf("tab_id" to "2", "start_oid" to "0", "page_type" to "1")), asked)
        assertEquals("/x/v3/fav/tab/fav", statuses["guest_ignet_library_answered"])
        assertEquals("installed", statuses["guest_ignet_library"])
    }

    @Test fun aBodyLargerThanTheReadBufferIsServedInOrderAndThenEnds() {
        val big = "x".repeat(100_000)
        reply = { """{"code":0,"data":"$big"}""" }
        val registrar = installed()
        val response = registrar.execute(request("https://api.bilibili.com/x/v2/history/toview/v2/list?asc=false"))
        assertEquals("""{"code":0,"data":"$big"}""", registrar.body(response, buffer = 4096))
    }

    @Test fun loggedInUsersGetTheServerReply() {
        guest = false
        val registrar = installed()
        val response = registrar.execute(request("https://api.bilibili.com/x/v3/fav/tab"))
        assertEquals(IgHttpResponse.SERVER_BODY, registrar.body(response))
        assertEquals("server", registrar.message(response))
        assertTrue(asked.isEmpty())
        assertNull(statuses["guest_ignet_library_answered"])
    }

    @Test fun writesOtherHostsAndOtherPathsAreNeverAnswered() {
        val registrar = installed()
        val cases = listOf(
            request("https://api.bilibili.com/x/v3/fav/tab", method = "POST"),
            request("https://api.bilibili.com/x/v3/fav/resource/batch-deal"),
            request("https://api.bilibili.com/x/v3/fav/tab/dels"),
            request("https://evil.example.com/x/v3/fav/tab"),
            request("http://api.bilibili.com.evil.example/x/v3/fav/tab"),
            request("bilibili://x/v3/fav/tab"),
            request("not a url at all")
        )
        for (case in cases) assertEquals(IgHttpResponse.SERVER_BODY, registrar.body(registrar.execute(case)))
        assertTrue(asked.isEmpty())
        assertNull(statuses["guest_ignet_library_answered"])
    }

    @Test fun aPathTheLibraryCannotAnswerKeepsTheServerReply() {
        reply = { null }
        val registrar = installed()
        val response = registrar.execute(request("https://api.bilibili.com/x/v3/fav/tab"))
        assertEquals(IgHttpResponse.SERVER_BODY, registrar.body(response))
        assertNull(statuses["guest_ignet_library_answered"])
    }

    @Test fun aFailedLocalAnswerKeepsTheServerReplyAndIsReported() {
        reply = { throw IllegalStateException("Unknown local folder") }
        val registrar = installed()
        val response = registrar.execute(request("https://api.bilibili.com/x/v3/fav/folder/resources?media_id=9"))
        assertEquals(IgHttpResponse.SERVER_BODY, registrar.body(response))
        assertEquals("server", registrar.message(response))
        assertEquals("/x/v3/fav/folder/resources", statuses["guest_ignet_library_failed"])
        assertNull(statuses["guest_ignet_library_answered"])
    }

    @Test fun aRequestThatFailedOnTheNetworkIsLeftFailed() {
        val registrar = installed()
        val request = request("https://api.bilibili.com/x/v3/fav/tab")
        try {
            registrar.invoke("guest.ignet.library.execute", request) { throw IOException("Request withheld by the incognito session") }
            throw AssertionError("expected the host's IOException")
        } catch (expected: IOException) {
            assertEquals("Request withheld by the incognito session", expected.message)
        }
        assertTrue(asked.isEmpty())
    }

    @Test fun responsesOfOtherRequestsAreUntouchedWhileAnotherIsAnswered() {
        val registrar = installed()
        val local = registrar.execute(request("https://api.bilibili.com/x/v3/fav/tab"))
        val other = registrar.execute(request("https://api.bilibili.com/x/web-interface/nav"))
        assertEquals("server", registrar.message(other))
        assertEquals(mapOf("Content-Encoding" to "br", "Content-Length" to "999"), registrar.headers(other))
        assertEquals(IgHttpResponse.SERVER_BODY, registrar.body(other))
        assertEquals("OK", registrar.message(local))
    }

    /** Runs Builder.build() through the write hook, as the Ktor engine does after filling the builder. */
    private fun PlayerPortTestRegistrar.build(builder: IgHttpRequest.Builder) =
        invoke("guest.ignet.library.write", builder) { builder.build() } as IgHttpRequest

    private fun post(url: String, form: String?) = IgHttpRequest.Builder().url(url).method("POST", form?.toByteArray())

    @Test fun aGuestListWriteNeverLeavesAsAWriteAndIsAppliedLocally() {
        val registrar = installed()
        val built = registrar.build(post("https://api.bilibili.com/x/v3/fav/tab/dels", "resources=100%3A2&media_id=0"))

        assertEquals(GuestIgnetLibraryResponses.HARMLESS_READ, built.url())   // what goes out is the harmless read
        assertEquals("GET", built.method())
        assertNull(built.body())
        assertTrue(asked.isEmpty())                                            // nothing applied before the request runs

        val response = registrar.execute(built)
        assertEquals(listOf("/x/v3/fav/tab/dels" to mapOf("resources" to "100:2", "media_id" to "0")), asked)
        assertEquals("""{"code":0,"data":{"path":"/x/v3/fav/tab/dels"}}""", registrar.body(response))
        assertEquals("OK", registrar.message(response))
        assertEquals("/x/v3/fav/tab/dels", statuses["guest_ignet_library_wrote"])
        assertNull(statuses["guest_ignet_library_answered"])
    }

    @Test fun aFailedLocalWriteIsAnsweredAsAFailureNeverAsTheReadReply() {
        reply = { throw IllegalArgumentException("Unknown local folder") }
        val registrar = installed()
        val response = registrar.execute(registrar.build(post("https://api.bilibili.com/x/v3/fav/folder/del", "media_ids=9")))
        assertEquals(GuestIgnetLibraryResponses.WRITE_FAILED, registrar.body(response))
        assertEquals("/x/v3/fav/folder/del", statuses["guest_ignet_library_failed"])
        assertNull(statuses["guest_ignet_library_wrote"])
    }

    @Test fun aWriteWhoseHarmlessReadFailsIsNeverAppliedAfterwards() {
        val registrar = installed()
        val built = registrar.build(post("https://api.bilibili.com/x/v2/history/toview/v2/dels", "resources=5"))
        try {
            registrar.invoke("guest.ignet.library.execute", built) { throw IOException("offline") }
        } catch (_: IOException) {
        }
        // A second run of the same request object does not replay the dropped write either.
        registrar.execute(built)
        assertTrue(asked.isEmpty())
        assertNull(statuses["guest_ignet_library_wrote"])
    }

    @Test fun loggedInWritesAreSentAsTheHostBuiltThem() {
        guest = false
        val registrar = installed()
        val built = registrar.build(post("https://api.bilibili.com/x/v3/fav/tab/dels", "resources=100%3A2"))
        assertEquals("https://api.bilibili.com/x/v3/fav/tab/dels", built.url())
        assertEquals("POST", built.method())
        assertEquals("resources=100%3A2", String(built.body()))
    }

    @Test fun writesTheLocalLibraryCannotApplyAreLeftToTheHost() {
        val registrar = installed()
        for (url in listOf("https://api.bilibili.com/x/v3/fav/resource/batch-deal", "https://api.bilibili.com/x/v3/fav/folder/fav",
                "https://evil.example.com/x/v3/fav/tab/dels")) {
            val built = registrar.build(post(url, "a=1"))
            assertEquals(url, built.url())
            assertEquals("POST", built.method())
        }
        assertTrue(asked.isEmpty())
    }

    @Test fun aStreamedFormIsDetachedFromTheRequestAndReadOnlyAfterTheHarmlessRead() {
        val chunks = ArrayDeque(listOf("resources=100%3A2", "&media_id=0"))
        var produced = 0
        val provider = IgHttpRequest.StreamDataProvider { produced++; chunks.removeFirstOrNull()?.toByteArray() }
        val registrar = installed()
        val built = registrar.build(post("https://api.bilibili.com/x/v3/fav/tab/dels", null).streamDataProvider(provider))

        assertEquals(GuestIgnetLibraryResponses.HARMLESS_READ, built.url())
        assertEquals("GET", built.method())
        assertNull(built.streamDataProvider())                 // native code has nothing to send
        assertEquals(0, produced)                               // nothing read before the request runs

        val response = registrar.execute(built)
        assertEquals(listOf("/x/v3/fav/tab/dels" to mapOf("resources" to "100:2", "media_id" to "0")), asked)
        assertEquals("""{"code":0,"data":{"path":"/x/v3/fav/tab/dels"}}""", registrar.body(response))
        assertEquals("/x/v3/fav/tab/dels", statuses["guest_ignet_library_wrote"])
    }

    @Test fun aStreamThatNeverEndsFailsTheWriteInsteadOfHangingTheHost() {
        val provider = IgHttpRequest.StreamDataProvider { Thread.sleep(60_000); null }
        val registrar = installed()
        val response = registrar.execute(registrar.build(post("https://api.bilibili.com/x/v3/fav/tab/dels", null).streamDataProvider(provider)))
        assertEquals(GuestIgnetLibraryResponses.WRITE_FAILED, registrar.body(response))
        assertTrue(asked.isEmpty())
        assertEquals("/x/v3/fav/tab/dels", statuses["guest_ignet_library_failed"])
    }

    @Test fun listWriteMergesTheQueryAndTheFormBody() {
        val write = GuestIgnetLibraryResponses::listWrite
        assertEquals("/x/v3/fav/folder/add" to mapOf("csrf" to "", "title" to "新 建", "intro" to ""),
            write("POST", "https://api.bilibili.com/x/v3/fav/folder/add?csrf=", "title=%E6%96%B0+%E5%BB%BA&intro=".toByteArray()))
        assertEquals("/x/v3/fav/toview/adds" to mapOf("resources" to "1,2"), write("post", "https://app.bilibili.com/x/v3/fav/toview/adds/", "resources=1%2C2".toByteArray()))
        assertNull(write("GET", "https://api.bilibili.com/x/v3/fav/tab/dels", null))
        assertNull(write("POST", "https://api.bilibili.com/x/v3/fav/tab", null))
        assertNull(write("POST", null, null))
    }

    @Test fun missingIgnetClassesInstallNothingAndReportAbsent() {
        val registrar = installed(requestClass = "com.bilibili.lib.ighttp.NoSuchRequest")
        assertEquals("absent", statuses["guest_ignet_library"])
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test fun aRequestWithoutTheExpectedMembersInstallsNothing() {
        val registrar = installed(requestClass = "fixtures.privacy.videofavorite.IncompleteProxy")
        assertEquals("absent", statuses["guest_ignet_library"])
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test fun listReadParsesOnlyGetReadsOnApiHosts() {
        val read = GuestIgnetLibraryResponses::listRead
        assertEquals("/x/v3/fav/tab" to emptyMap<String, String>(), read("GET", "https://api.bilibili.com/x/v3/fav/tab"))
        assertEquals("/x/v3/fav/tab" to emptyMap<String, String>(), read("get", "https://API.bilibili.com/x/v3/fav/tab/"))
        assertEquals("/x/v3/fav/tab/fav/search" to mapOf("keyword" to "a b", "pn" to "1", "flag" to ""),
            read("GET", "https://app.bilibili.com/x/v3/fav/tab/fav/search?keyword=a%20b&pn=1&flag"))
        assertNull(read("POST", "https://api.bilibili.com/x/v3/fav/tab"))
        assertEquals("/x/v3/fav/folder/whitelist" to emptyMap<String, String>(), read("GET", "https://api.bilibili.com/x/v3/fav/folder/whitelist"))
        assertNull(read(null, "https://api.bilibili.com/x/v3/fav/tab"))
        assertNull(read("GET", null))
        assertNull(read("GET", "https://api.bilibili.com/x/v3/fav/tab/clean_invalid"))
        assertNull(read("GET", "ftp://api.bilibili.com/x/v3/fav/tab"))
    }
}
