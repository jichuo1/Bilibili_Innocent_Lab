package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.bapis.bilibili.metadata.Metadata
import com.bapis.bilibili.metadata.device.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class KmpMossBoundaryIdentityTest {
    private val identity = IncognitoSession.Identity(1, "session-buvid", "session-id")

    private fun sanitizer() = KmpMossBoundary.identitySanitizer(javaClass.classLoader!!)

    private fun headers(name: String, value: ByteArray) =
        listOf(LinkedHashMap<Any?, Any?>(), LinkedHashMap<Any?, Any?>(mapOf(name to value)))

    @Test fun resolvesWhenToBuilderIsInheritedFromTheProtobufBaseClass() {
        // Regression: toBuilder() is not declared on the generated message, only on its base class.
        assertNotNull(sanitizer())
    }

    @Test fun metadataKeepsNothingOfTheRealAccount() {
        val real = Metadata.newBuilder().setBuvid("real-buvid").setAccessKey("real-secret").build().toByteArray()
        val maps = headers("x-bili-metadata-bin", real)
        sanitizer()!!.sanitize(maps, identity)
        val bytes = maps[1]["x-bili-metadata-bin"] as ByteArray
        val sealed = Metadata.parseFrom(bytes)
        assertEquals("session-buvid", sealed.buvid)
        assertEquals("", sealed.accessKey)
        assertFalse(String(bytes).contains("real"))
    }

    @Test fun everyDeviceIdentifierIsReplacedAndTheTimestampReset() {
        val real = Device.newBuilder().setBuvid("real-buvid").setFp("real-fp").setFpLocal("real-local")
            .setFpRemote("real-remote").setGuestId("real-guest").setFts(1234L).build().toByteArray()
        val maps = headers("x-bili-device-bin", real)
        sanitizer()!!.sanitize(maps, identity)
        val sealed = Device.parseFrom(maps[1]["x-bili-device-bin"] as ByteArray)
        assertEquals((0..4).map { "session-buvid" }, (0..4).map { sealed.field(it) })
        assertEquals("0", sealed.field(5))
    }
}
