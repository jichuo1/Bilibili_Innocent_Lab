package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import java.util.concurrent.CountDownLatch

/** Debug-only real display surfaces; TextureView matches the host player's UI consumption path. */
class FrameRateValidationActivity : Activity(), SurfaceHolder.Callback {
    val ready = CountDownLatch(1)
    lateinit var view: SurfaceView
        private set
    lateinit var outputSurface: android.view.Surface
        private set
    val textureUpdates = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
    @Volatile var focused = false
        private set
    var vendorRequestResult = "not requested"
        private set
    private var vendorRelease: (() -> Unit)? = null
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused = hasFocus
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val target = intent.getIntExtra("targetFps", 120).toFloat()
        val viewVote = if (Build.VERSION.SDK_INT >= 35 && intent.getBooleanExtra("frameRateCategory", false))
            android.view.View.REQUESTED_FRAME_RATE_CATEGORY_HIGH else target
        if (intent.getBooleanExtra("frtc", false) || intent.getBooleanExtra("vrrToken", false) ||
            intent.getBooleanExtra("skipSynthetic", false)) {
            vendorRequestResult = runCatching {
                val binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java).invoke(null, "oplus_vrr_service") as android.os.IBinder
                val service = Class.forName("com.oplus.vrr.IOPlusRefreshRate\$Stub")
                    .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
                val token = android.os.Binder()
                val api = Class.forName("com.oplus.vrr.IOPlusRefreshRate")
                if (intent.getBooleanExtra("skipSynthetic", false)) {
                    val method = api.getMethod("skipEnableSyntheticVsyncWithToken", Boolean::class.javaPrimitiveType,
                        android.os.IBinder::class.java)
                    vendorRelease = { method.invoke(service, false, token); Unit }
                    method.invoke(service, true, token)
                    "requested"
                } else if (intent.getBooleanExtra("vrrToken", false)) {
                    val method = api.getMethod("setFrameRateWithToken", Float::class.javaPrimitiveType,
                        String::class.java, String::class.java, Int::class.javaPrimitiveType, android.os.IBinder::class.java)
                    val title = window.attributes.title.toString()
                    vendorRelease = { method.invoke(service, 0f, title, packageName, android.os.Process.myPid(), token); Unit }
                    method.invoke(service, target, title, packageName, android.os.Process.myPid(), token).toString()
                } else {
                    val method = api.getMethod("setFrameRateTargetControlWithToken", Float::class.javaPrimitiveType,
                        String::class.java, Boolean::class.javaPrimitiveType, String::class.java, android.os.IBinder::class.java)
                    val strict = intent.getBooleanExtra("frtcStrict", false)
                    val key = "frame-interpolation-validation"
                    // Explicit opt-in diagnostic only. A zero request unregisters the same key/token.
                    vendorRelease = { method.invoke(service, 0f, packageName, strict, key, token); Unit }
                    method.invoke(service, target, packageName, strict, key, token).toString()
                }
            }.fold({ it }, { it.cause?.toString() ?: it.toString() })
        }
        val display = windowManager.defaultDisplay
        val chosen = display.supportedModes.filter { kotlin.math.abs(it.refreshRate - target) < 0.5f }
            .maxByOrNull { it.physicalWidth * it.physicalHeight }
        window.attributes = window.attributes.apply {
            preferredRefreshRate = target
            if (chosen != null) preferredDisplayModeId = chosen.modeId
        }
        if (intent.getBooleanExtra("texture", false)) {
            val texture = android.view.TextureView(this)
            if (Build.VERSION.SDK_INT >= 35) texture.requestedFrameRate = viewVote
            texture.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                    outputSurface = android.view.Surface(st)
                    if (Build.VERSION.SDK_INT >= 31) outputSurface.setFrameRate(target,
                        android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, android.view.Surface.CHANGE_FRAME_RATE_ALWAYS)
                    ready.countDown()
                }
                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {
                    textureUpdates += System.nanoTime() to st.timestamp
                }
                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                    outputSurface.release()
                    return true
                }
            }
            setContentView(texture)
            return
        }
        view = SurfaceView(this)
        val bufferWidth = intent.getIntExtra("surfaceWidth", 0)
        val bufferHeight = intent.getIntExtra("surfaceHeight", 0)
        if (bufferWidth in 1..4096 && bufferHeight in 1..4096 && bufferWidth.toLong() * bufferHeight <= 4096L * 2160L) {
            view.holder.setFixedSize(bufferWidth, bufferHeight)
        }
        if (Build.VERSION.SDK_INT >= 35) view.requestedFrameRate = viewVote
        view.holder.addCallback(this)
        setContentView(view)
    }

    @Suppress("DEPRECATION")
    override fun surfaceCreated(holder: SurfaceHolder) {
        outputSurface = holder.surface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            holder.surface.setFrameRate(intent.getIntExtra("targetFps", 120).toFloat(), android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                android.view.Surface.CHANGE_FRAME_RATE_ALWAYS)
        ready.countDown()
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

    override fun onDestroy() {
        runCatching { vendorRelease?.invoke() }
        vendorRelease = null
        super.onDestroy()
    }
}
