package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModuleNativeLoader

/**
 * `libinnocent_danmaku_wire.so` 的加载闩锁与 JNI 入口。
 *
 * 与 DexMeguviz（`DexMeguvizAssistEngine`）同一范式：`NOT_TRIED/AVAILABLE/UNAVAILABLE` 三态、
 * 一次性 `ModuleNativeLoader.load`、失败永不重试不抛出。加载与首次探测只发生在安装期，
 * 弹幕回调热路径上只读一个 volatile 状态。ABI 版本不符同样视为不可用（新旧 .so/类
 * 混装时不猜）。所有包装方法捕获 Throwable：符号缺失（R8 keep 失效）、加载期 OOM、
 * 垫片折算的拒绝（返回 null）都折叠成"本次不可用"，由安装器回退下一层。
 */
internal object DanmakuWireNative {
    internal const val EXPECTED_ABI_VERSION = 1

    private const val NOT_TRIED = 0
    private const val AVAILABLE = 1
    private const val UNAVAILABLE = 2

    @Volatile
    private var state = NOT_TRIED

    /** 自检或运行期发现 native panic/结果不可信时置位：本进程内永久停用，回退 JVM 扫描。 */
    @Volatile
    private var untrusted = false

    val isAvailable: Boolean
        get() = !untrusted && ensureLoaded()

    /** 只允许安装期自检或运行期 panic 检出调用；外部不得强行重置为可信。 */
    fun markUntrusted() {
        untrusted = true
    }

    internal fun setAvailableForTest(available: Boolean) {
        untrusted = false
        state = if (available) AVAILABLE else UNAVAILABLE
    }

    internal fun resetForTest() {
        untrusted = false
        state = NOT_TRIED
    }

    private fun ensureLoaded(): Boolean {
        if (state == NOT_TRIED) {
            synchronized(this) {
                if (state == NOT_TRIED) {
                    state = try {
                        ModuleNativeLoader.load("innocent_danmaku_wire")
                        if (nativeAbiVersion() == EXPECTED_ABI_VERSION && !nativeHasPanicked()) {
                            AVAILABLE
                        } else {
                            UNAVAILABLE
                        }
                    } catch (_: Throwable) {
                        UNAVAILABLE
                    }
                }
            }
        }
        return state == AVAILABLE
    }

    /** 扫描结果布局见 `danmaku-wire/src/lib.rs` 的 `flatten`；拒绝/失败返回 null，若底层 panic 则置 untrusted。 */
    fun scan(reply: ByteArray, fields: DanmakuWireFields): IntArray? =
        executeNative(::nativeHasPanicked) {
            nativeScan(reply, fields.elemsField, fields.weightField, fields.colorfulField,
                fields.contentField, fields.colorfulSrcField, fields.colorfulTypeField)
        }

    fun rewrite(
        reply: ByteArray,
        fields: DanmakuWireFields,
        keepElem: BooleanArray,
        recolorElem: BooleanArray,
        keepSrc: BooleanArray,
    ): ByteArray? =
        executeNative(::nativeHasPanicked) {
            nativeRewrite(reply, fields.elemsField, fields.weightField, fields.colorfulField,
                fields.contentField, fields.colorfulSrcField, fields.colorfulTypeField,
                keepElem, recolorElem, keepSrc)
        }

    internal fun scanWith(
        reply: ByteArray,
        fields: DanmakuWireFields,
        invokeScan: (ByteArray, DanmakuWireFields) -> IntArray?,
        hasPanicked: () -> Boolean,
    ): IntArray? = executeNative(hasPanicked) { invokeScan(reply, fields) }

    internal fun rewriteWith(
        reply: ByteArray,
        fields: DanmakuWireFields,
        keepElem: BooleanArray,
        recolorElem: BooleanArray,
        keepSrc: BooleanArray,
        invokeRewrite: (ByteArray, DanmakuWireFields, BooleanArray, BooleanArray, BooleanArray) -> ByteArray?,
        hasPanicked: () -> Boolean,
    ): ByteArray? = executeNative(hasPanicked) { invokeRewrite(reply, fields, keepElem, recolorElem, keepSrc) }

    private inline fun <T> executeNative(
        hasPanicked: () -> Boolean,
        block: () -> T?
    ): T? {
        if (!isAvailable) return null
        val result = try {
            block()
        } catch (_: LinkageError) {
            markUntrusted()
            return null
        } catch (_: Throwable) {
            if (runCatching { hasPanicked() }.getOrDefault(false)) {
                markUntrusted()
            }
            return null
        }
        if (result == null) {
            val panicked = try {
                hasPanicked()
            } catch (_: LinkageError) {
                true
            } catch (_: Throwable) {
                false
            }
            if (panicked) markUntrusted()
        }
        return result
    }

    private external fun nativeAbiVersion(): Int

    private external fun nativeHasPanicked(): Boolean

    private external fun nativeScan(
        reply: ByteArray, elemsField: Int, weightField: Int, colorfulField: Int,
        contentField: Int, srcField: Int, srcTypeField: Int,
    ): IntArray?

    private external fun nativeRewrite(
        reply: ByteArray, elemsField: Int, weightField: Int, colorfulField: Int,
        contentField: Int, srcField: Int, srcTypeField: Int,
        keepElem: BooleanArray, recolorElem: BooleanArray, keepSrc: BooleanArray,
    ): ByteArray?
}
