package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 验证系统令牌的身份与取消语义，不发送任何仍然有效的启动令牌。 */
@RunWith(AndroidJUnit4::class)
class RoamingSettingsLaunchInstrumentedTest {
    @Test fun moduleLaunchTokenHasAFixedImmutableTargetIndependentOfRoaming() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requestCode = 0x42494c01
        val token = RoamingSettingsLauncher.createModuleLaunchToken(context, requestCode)
        try {
            assertEquals(context.packageName, token.creatorPackage)
            assertEquals(context.applicationInfo.uid, token.creatorUid)
            if (Build.VERSION.SDK_INT >= 31) Api31.assertActivityImmutable(token)
            val registered = PendingIntent.getActivity(context, requestCode,
                Intent().setComponent(ComponentName(context.packageName,
                    "${context.packageName}.ui.activity.MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            assertEquals(token, registered)
            token.cancel()
            assertCancelled(token)
        } finally {
            token.cancel()
        }
    }

    @Test fun launchTokenBelongsToTargetApplicationAndUsesImmutableActivityShape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = RoamingSettingsLauncher.createLaunchToken(context, 0x524c0001)
        try {
            assertEquals(context.packageName, token.creatorPackage)
            assertEquals(context.applicationInfo.uid, token.creatorUid)
            if (Build.VERSION.SDK_INT >= 31) Api31.assertActivityImmutable(token)
        } finally {
            token.cancel()
        }
    }

    @Test fun cancelledLaunchTokenCannotBeSent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = RoamingSettingsLauncher.createLaunchToken(context, 0x524c0002)
        try {
            token.cancel()
            assertCancelled(token)
        } finally {
            token.cancel()
        }
    }

    @Test fun replacementCancelsPreviousTokenAndRegistersNewToken() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requestCode = 0x524c0003
        val previous = RoamingSettingsLauncher.createLaunchToken(context, requestCode)
        var replacement: PendingIntent? = null
        try {
            val current = RoamingSettingsLauncher.createLaunchToken(context, requestCode)
            replacement = current
            assertNotEquals(previous, current)
            assertCancelled(previous)
            val registered = findLaunchToken(context, requestCode)
            assertNotNull(registered)
            assertEquals(current, registered)
        } finally {
            previous.cancel()
            replacement?.cancel()
        }
    }

    private fun findLaunchToken(context: Context, requestCode: Int): PendingIntent? =
        PendingIntent.getActivity(context, requestCode,
            Intent().setComponent(ComponentName("me.iacn.biliroaming", "me.iacn.biliroaming.MainActivityAlias"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)

    private fun assertCancelled(token: PendingIntent) {
        try {
            token.send()
            fail("A cancelled launch token must throw CanceledException")
        } catch (_: PendingIntent.CanceledException) {
            // 系统确认令牌已失效，不会启动目标页面。
        }
    }

    @RequiresApi(31)
    private object Api31 {
        fun assertActivityImmutable(token: PendingIntent) {
            assertTrue(token.isActivity)
            assertTrue(token.isImmutable)
        }
    }
}
