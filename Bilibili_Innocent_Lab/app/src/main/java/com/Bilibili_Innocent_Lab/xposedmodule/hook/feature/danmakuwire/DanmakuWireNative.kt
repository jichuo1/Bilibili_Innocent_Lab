package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire

/**
 * `libinnocent_danmaku_wire.so` 的加载闩锁与 JNI 入口。
 *
 * 与 DexMeguviz（`DexMeguvizAssistEngine`）同一范式：`NOT_TRIED/AVAILABLE/UNAVAILABLE` 三态、
 * 一次性 `System.loadLibrary`、失败永不重试不抛出。加载与首次探测只发生在安装期，
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

    /** 自检发现 native 结果与 JVM 基准不一致时置位：本进程内永久停用，回退 JVM 扫描。 */
    @Volatile
    private var untrusted = false

    val isAvailable: Boolean
        get() = !untrusted && ensureLoaded()

    /** 只允许安装期自检调用；运行期一律通过 [DanmakuWireEngine]。 */
    fun markUntrusted() {
        untrusted = true
    }

    private fun ensureLoaded(): Boolean {
        if (state == NOT_TRIED) {
            synchronized(this) {
                if (state == NOT_TRIED) {
                    state = try {
                        System.loadLibrary("innocent_danmaku_wire")
                        if (nativeAbiVersion() == EXPECTED_ABI_VERSION) AVAILABLE else UNAVAILABLE
                    } catch (_: Throwable) {
                        UNAVAILABLE
                    }
                }
            }
        }
        return state == AVAILABLE
    }

    /** 扫描结果布局见 `danmaku-wire/src/lib.rs` 的 `flatten`；拒绝/失败返回 null。 */
    fun scan(reply: ByteArray, fields: DanmakuWireFields): IntArray? {
        if (!isAvailable) return null
        return runCatching {
            nativeScan(reply, fields.elemsField, fields.weightField, fields.colorfulField,
                fields.contentField, fields.colorfulSrcField, fields.colorfulTypeField)
        }.getOrNull()
    }

    fun rewrite(
        reply: ByteArray,
        fields: DanmakuWireFields,
        keepElem: BooleanArray,
        recolorElem: BooleanArray,
        keepSrc: BooleanArray,
    ): ByteArray? {
        if (!isAvailable) return null
        return runCatching {
            nativeRewrite(reply, fields.elemsField, fields.weightField, fields.colorfulField,
                fields.contentField, fields.colorfulSrcField, fields.colorfulTypeField,
                keepElem, recolorElem, keepSrc)
        }.getOrNull()
    }

    private external fun nativeAbiVersion(): Int

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
