package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GuestSpaceFollowShapeTest {
    class Wrapper { @JvmField var a: String = ""; @JvmField var c: Any? = null }
    class TwoStrings { @JvmField var a: String = ""; @JvmField var b: String = "" }

    @Test fun wrapperMidIsItsSingleStringField() {
        assertEquals("a", GuestSpaceFollowSync.stringField(Wrapper::class.java)?.name)
        assertNull(GuestSpaceFollowSync.stringField(TwoStrings::class.java))  // never guessed
    }

    @Test fun guestSpaceResponseReadsAsFollowed() {
        // Trimmed from a real guest response for vmid=2.
        val guest = """{"code":0,"data":{"relation":-999,"guest_relation":-999,"card":{"mid":"2","attention":430,"relation":{"status":1}}}}"""
        val data = JSONObject(GuestSpaceFollowSync.followedSpace(guest)).getJSONObject("data")
        assertEquals(1, data.getInt("relation"))
        assertEquals(-999, data.getInt("guest_relation"))  // whether the owner follows the viewer is left alone
        assertEquals(2, data.getJSONObject("card").getJSONObject("relation").getInt("status"))
        assertEquals(1, data.getJSONObject("card").getJSONObject("relation").getInt("is_follow"))
        assertEquals(430, data.getJSONObject("card").getInt("attention"))

        val error = """{"code":-400,"message":"请求错误"}"""
        assertEquals(error, GuestSpaceFollowSync.followedSpace(error))
    }

    @Test fun ownerNameAndAvatarComeFromTheGeneratedModelText() {
        assertEquals("碧诗", GuestSpaceFollowSync.ownerName("SpaceOwnerInfo(name=碧诗, sign=We Are Star Dust)"))
        assertEquals("https://i0.hdslb.com/face.jpg",
            GuestSpaceFollowSync.ownerFace("SpaceAvatar(face=https://i0.hdslb.com/face.jpg, digits=null, pendant=null)"))
        assertNull(GuestSpaceFollowSync.ownerName("SpaceAvatar(face=x, digits=null, pendant=null)"))
        assertNull(GuestSpaceFollowSync.ownerName("SpaceOwnerInfo(name=, sign=x)"))
        assertNull(GuestSpaceFollowSync.ownerFace("SpaceAvatar(face=null, digits=null, pendant=null)"))
        assertNull(GuestSpaceFollowSync.ownerFace("SpaceAvatar(face=truncated"))
    }
}
