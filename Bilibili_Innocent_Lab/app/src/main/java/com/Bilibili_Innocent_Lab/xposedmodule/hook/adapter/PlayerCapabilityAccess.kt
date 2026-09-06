package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCapability
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method

/** 所有反射在安装期完成；protobuf builder 复制保留无关字段，绝不修改默认实例。 */
internal class PlayerCapabilityAccess private constructor(
    val replyClass: Class<*>,
    private val defaultReply: Any,
    private val hasVideo: Method,
    private val readContainer: Method,
    private val writeContainer: Method,
    private val containerCopy: BuilderCopy,
    private val arcCopy: BuilderCopy,
    private val defaultArc: Any,
    private val disabled: Method,
    private val supported: Method,
    private val setDisabled: Method,
    private val setSupported: Method,
    private val readMap: Method?,
    private val putMap: Method?,
    private val slots: List<Slot>
) {
    val capabilityCount: Int get() = slots.size
    private data class Slot(val capability: PlayerCapability, val getter: Method?, val setter: Method?)
    private data class BuilderCopy(val newBuilder: Method, val build: Method) {
        fun copy(value: Any): Any = checkNotNull(newBuilder.invoke(null, value))
        fun finish(builder: Any): Any = checkNotNull(build.invoke(builder))
    }

    /** 返回确实改写且读回正确的能力数；0 包括默认回复、无视频信息和本就开放的回复。 */
    fun apply(reply: Any): Int {
        if (!replyClass.isInstance(reply) || reply === defaultReply || hasVideo.invoke(reply) != true) return 0
        val originalContainer = readContainer.invoke(reply) ?: return 0
        val originalMap = readMap?.invoke(originalContainer) as? Map<*, *>
        if (readMap != null && originalMap == null) return 0
        var builder: Any? = null
        val changed = ArrayList<Slot>(slots.size)
        for (slot in slots) {
            val originalArc = if (readMap != null) {
                originalMap?.get(slot.capability.wireId) ?: defaultArc
            } else {
                slot.getter?.invoke(originalContainer) ?: continue
            }
            if (disabled.invoke(originalArc) == false && supported.invoke(originalArc) == true) continue
            val arcBuilder = arcCopy.copy(originalArc)
            setDisabled.invoke(arcBuilder, false)
            setSupported.invoke(arcBuilder, true)
            val updatedArc = arcCopy.finish(arcBuilder)
            check(updatedArc !== originalArc && updatedArc !== defaultArc)
            check(disabled.invoke(updatedArc) == false && supported.invoke(updatedArc) == true)
            val targetBuilder = builder ?: containerCopy.copy(originalContainer).also { builder = it }
            if (putMap != null) putMap.invoke(targetBuilder, slot.capability.wireId, updatedArc)
            else slot.setter?.invoke(targetBuilder, updatedArc)
            changed += slot
        }
        val updated = builder?.let(containerCopy::finish) ?: return 0
        check(updated !== originalContainer)
        // 全部副本准备完成后才改顶层字段，失败不会留下半份配置；其他 reply 字段不动。
        writeContainer.invoke(reply, updated)
        val actual = readContainer.invoke(reply) ?: return 0
        val actualMap = readMap?.invoke(actual) as? Map<*, *>
        return changed.count { slot ->
            val arc = if (readMap != null) actualMap?.get(slot.capability.wireId)
                else slot.getter?.invoke(actual)
            arc != null && disabled.invoke(arc) == false && supported.invoke(arc) == true
        }
    }

    companion object {
        fun resolve(
            loader: ClassLoader,
            replyName: String,
            shared: Boolean,
            enabled: List<PlayerCapability>
        ): PlayerCapabilityAccess? = runCatching {
            val reply = KavaMemberLookup.classOrNull(loader, replyName) ?: return null
            val prefix = if (shared) "com.bapis.bilibili.playershared." else "com.bapis.bilibili.app.playurl.v1."
            val container = KavaMemberLookup.classOrNull(loader, prefix + "PlayArcConf") ?: return null
            val arc = KavaMemberLookup.classOrNull(loader, prefix + "ArcConf") ?: return null
            val containerCopy = copyPlan(container) ?: return null
            val arcCopy = copyPlan(arc) ?: return null
            val readContainer = method(reply, if (shared) "getPlayArcConf" else "getPlayArc")
                ?.takeIf { it.returnType == container } ?: return null
            val writeContainer = method(reply, if (shared) "setPlayArcConf" else "setPlayArc", container)
                ?.takeIf { it.returnType == Void.TYPE } ?: return null
            val hasVideo = method(reply, if (shared) "hasVodInfo" else "hasVideoInfo")
                ?.takeIf { it.returnType == classOf<Boolean>() } ?: return null
            val disabled = method(arc, "getDisabled")?.takeIf { it.returnType == classOf<Boolean>() } ?: return null
            val supported = method(arc, "getIsSupport")?.takeIf { it.returnType == classOf<Boolean>() } ?: return null
            val setDisabled = method(arcCopy.newBuilder.returnType, "setDisabled", classOf<Boolean>()) ?: return null
            val setSupported = method(arcCopy.newBuilder.returnType, "setIsSupport", classOf<Boolean>()) ?: return null
            val readMap = if (shared) method(container, "getArcConfsMap")
                ?.takeIf { it.returnType isSubclassOf classOf<Map<*, *>>() } ?: return null else null
            val putMap = if (shared) method(containerCopy.newBuilder.returnType, "putArcConfs", classOf<Int>(), arc)
                ?: return null else null
            val slots = enabled.mapNotNull { capability ->
                if (shared) Slot(capability, null, null) else {
                    val getter = method(container, "get${capability.legacySuffix}")
                        ?.takeIf { it.returnType == arc } ?: return@mapNotNull null
                    val setter = method(containerCopy.newBuilder.returnType, "set${capability.legacySuffix}", arc)
                        ?: return@mapNotNull null
                    Slot(capability, getter, setter)
                }
            }
            if (slots.isEmpty()) return null
            PlayerCapabilityAccess(reply, defaultInstance(reply) ?: return null, hasVideo,
                readContainer, writeContainer, containerCopy, arcCopy, defaultInstance(arc) ?: return null,
                disabled, supported, setDisabled, setSupported, readMap, putMap, slots)
        }.getOrNull()

        private fun method(owner: Class<*>, name: String, vararg parameters: Class<*>): Method? =
            KavaMemberLookup.methodOrNull(owner, name, *parameters)?.takeIf { !it.isStatic }

        private fun defaultInstance(owner: Class<*>): Any? =
            KavaMemberLookup.methodOrNull(owner, "getDefaultInstance")
                ?.takeIf { it.isStatic && it.returnType == owner }
                ?.invoke(null)

        private fun copyPlan(owner: Class<*>): BuilderCopy? {
            val creator = KavaMemberLookup.methodOrNull(owner, "newBuilder", owner)
                ?.takeIf { it.isStatic } ?: return null
            val build = KavaMemberLookup.inheritedMethodOrNull(creator.returnType, "build")
                ?.takeIf { !it.isStatic } ?: return null
            return BuilderCopy(creator, build)
        }
    }
}
