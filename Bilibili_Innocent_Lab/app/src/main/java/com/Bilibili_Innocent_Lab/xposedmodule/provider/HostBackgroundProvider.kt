package com.Bilibili_Innocent_Lab.xposedmodule.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Binder
import android.os.Process
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundImages
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostCallerIdentityVerifier
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import java.io.FileNotFoundException

/** 冷启动宿主会撤销临时 URI grant；每次打开以 Binder UID 校验固定宿主，绝不按 URI 授权绕过。 */
class HostBackgroundProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = context ?: throw FileNotFoundException()
        val caller = Binder.getCallingUid()
        val trusted = caller == Process.myUid() || HostCallerIdentityVerifier.verifyProviderCaller(
            context = app,
            callerUid = caller,
            expectedPackage = "tv.danmaku.bili",
            allowSelf = true,
            allowPrivilegedShell = false,
            allowPackageListFallbackWhenPrimaryMissing = false,
            auditTag = "HostBackgroundProvider"
        ).allowed
        if (!trusted) throw SecurityException("Host background image caller is not trusted")
        if (mode != "r" || uri.authority != HostBackgroundImages.AUTHORITY || uri.pathSegments.size != 2 ||
            uri.pathSegments[0] != "image" || !HostBackgroundConfig.validAsset(uri.pathSegments[1]) ||
            !UserTermsConsentStore.readOrInitialize(app).isAuthorized) throw FileNotFoundException()
        return ParcelFileDescriptor.open(HostBackgroundImages.file(app, uri.pathSegments[1]), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
