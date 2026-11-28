package com.Bilibili_Innocent_Lab.xposedmodule.contract

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** 语义护栏读取与正式依赖同版本的官方 sources JAR；先核对模块确实委托到该类型。 */
object LceMotionSource {
    private val archiveEntriesCache = ConcurrentHashMap<String, Map<String, String>>()
    private val engineCache = ConcurrentHashMap<String, String>()
    private val readCache = ConcurrentHashMap<String, String>()

    internal val delegates = mapOf(
        "BubbleIconProxy.kt" to ("motion/modal/BubbleIconProxy.kt" to "com.lumen.coacervation.engine.motion.modal.BubbleIconProxy"),
        "BubbleLayerMotionSpec.kt" to ("motion/modal/BubbleLayerMotionSpec.kt" to "com.lumen.coacervation.engine.motion.modal.BubbleLayerMotionSpec"),
        "BubbleMotionController.kt" to ("motion/modal/BubbleMotionController.kt" to "com.lumen.coacervation.engine.motion.modal.BubbleMotionController"),
        "BubblePanelLayer.kt" to ("motion/modal/BubblePanelLayer.kt" to "com.lumen.coacervation.engine.motion.modal.BubblePanelLayer"),
        "BubblePlacementSpec.kt" to ("motion/modal/BubblePlacementSpec.kt" to "com.lumen.coacervation.engine.motion.modal.BubbleTailEdge"),
        "BubbleSkinSurfaceView.kt" to ("motion/modal/BubbleSkinSurfaceView.kt" to "com.lumen.coacervation.engine.motion.modal.BubbleSkinSurfaceView"),
        "BubbleSurfaceDrawable.kt" to ("motion/modal/BubbleSurfaceDrawable.kt" to "com.lumen.coacervation.engine.motion.modal.BubbleSurfaceDrawable"),
        "IconAnchoredMotionController.kt" to ("motion/modal/IconAnchoredMotionController.kt" to "com.lumen.coacervation.engine.motion.modal.IconAnchoredMotionController"),
        "IconAnchoredMotionLayer.kt" to ("motion/modal/IconAnchoredMotionLayer.kt" to "com.lumen.coacervation.engine.motion.modal.IconAnchoredMotionLayer"),
        "IconAnchoredMotionSpec.kt" to ("motion/modal/IconAnchoredMotionSpec.kt" to "com.lumen.coacervation.engine.motion.modal.IconAnchoredContentTiming"),
        "ModalBackdropBlur.kt" to ("motion/modal/ModalBackdropBlur.kt" to "com.lumen.coacervation.engine.motion.modal.ModalBackdropBlur"),
        "ModalBackdropBlurSpec.kt" to ("motion/modal/ModalBackdropBlurSpec.kt" to "com.lumen.coacervation.engine.motion.modal.ModalBackdropBlurSpec"),
        "ModalCardRoot.kt" to ("motion/modal/ModalCardRoot.kt" to "com.lumen.coacervation.engine.motion.modal.ModalCardRoot"),
        "ModalTitleColorOwners.kt" to ("motion/modal/ModalTitleColorOwners.kt" to "com.lumen.coacervation.engine.motion.modal.ModalTitleColorOwners"),
        "ModalTitleDescriptions.kt" to ("motion/modal/ModalTitleDescriptions.kt" to "com.lumen.coacervation.engine.motion.modal.ModalTitleDescriptions"),
        "ModalTitleMotion.kt" to ("motion/modal/ModalTitleMotion.kt" to "com.lumen.coacervation.engine.motion.modal.ModalTitleMotionSpec"),
        "ExpansionMotionPolicy.kt" to ("motion/expansion/ExpansionMotionPolicy.kt" to "com.lumen.coacervation.engine.motion.expansion.ExpansionMotionPolicy"),
        "NestedExpansionPolicy.kt" to ("motion/expansion/NestedExpansionPolicy.kt" to "com.lumen.coacervation.engine.motion.expansion.NestedExpansionPolicy"),
        "SectionExpansionController.kt" to ("motion/expansion/SectionExpansionController.kt" to "com.lumen.coacervation.engine.motion.expansion.SectionExpansionController"),
        "SettingsPageMotionPolicy.kt" to ("motion/pager/PageMotionPolicy.kt" to "com.lumen.coacervation.engine.motion.pager.SwitchTouchBounds"),
        "SettingsPageTextChain.kt" to ("motion/pager/PageTextChain.kt" to "com.lumen.coacervation.engine.motion.pager.PageTextChain"),
        "SettingsRevealScrollMotion.kt" to ("motion/reveal/RevealScrollMotion.kt" to "com.lumen.coacervation.engine.motion.reveal.RevealScrollMotion"),
        "SettingsRevealRequest.kt" to ("motion/reveal/RevealRequest.kt" to "com.lumen.coacervation.engine.motion.reveal.RevealRequest"),
        "NavigationMotionPolicy.kt" to ("motion/InterruptibleMotion.kt" to "com.lumen.coacervation.engine.motion.InterruptibleMotionPhase"),
        "SettingsBackupMotionSpec.kt" to ("motion/morph/ContainerMorphSpec.kt" to "com.lumen.coacervation.engine.motion.MotionRect"),
        "SettingsBackupMotionHost.kt" to ("motion/morph/ContainerMorphHost.kt" to "com.lumen.coacervation.engine.motion.morph.ContainerMorphGeometry"),
        "ElasticMotionGroupPolicy.kt" to ("interaction/ElasticMotionGroupPolicy.kt" to "com.lumen.coacervation.engine.interaction.ElasticGroupNode"),
        "ElasticMotionPolicy.kt" to ("interaction/ElasticMotionPolicy.kt" to "com.lumen.coacervation.engine.interaction.ElasticGestureDecision"),
        "ElasticInteractionController.kt" to ("interaction/ElasticInteractionController.kt" to "com.lumen.coacervation.engine.interaction.ElasticInteractionController"),
        "SettingsPagePager.kt" to ("motion/pager/LumenPagePager.kt" to "com.lumen.coacervation.engine.motion.pager.LumenPagePager"),
        "ModernNavigationMotion.kt" to ("widget/BoundedNavigationMotion.kt" to "com.lumen.coacervation.engine.widget.BoundedNavigationMotion"),
        "CoverableRippleDrawable.kt" to ("widget/CoverableRippleDrawable.kt" to "com.lumen.coacervation.engine.widget.CoverableRippleDrawable"),
    )

    fun engine(path: String): String = engineCache.computeIfAbsent(path) { key ->
        val archives = System.getProperty("innocentLab.lumenSources")
            ?: throw AssertionError("Missing published LCE sources JARs")
        val module = if (key.startsWith("core/")) "lumen-engine" else "lumen-motion"
        val relative = key.removePrefix("core/")
        val archive = archives.split(File.pathSeparator).map(::File)
            .single { it.name.startsWith("$module-") && it.name.endsWith("-sources.jar") }
        val entries = archiveEntriesCache.computeIfAbsent(archive.absolutePath) {
            ZipFile(archive).use { zip ->
                buildMap {
                    val enumeration = zip.entries()
                    while (enumeration.hasMoreElements()) {
                        val entry = enumeration.nextElement()
                        if (!entry.isDirectory && entry.name.startsWith("com/lumen/coacervation/engine/")) {
                            put(
                                entry.name.removePrefix("com/lumen/coacervation/engine/"),
                                SourceContract.normalize(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                            )
                        }
                    }
                }
            }
        }
        entries[relative] ?: throw AssertionError("Published LCE source not found: $module/$relative")
    }

    fun read(path: String): String = readCache.computeIfAbsent(path) { key ->
        val host = SourceContract.read(key)
        // Only the final filename segment is needed to match the delegate key.
        val target = delegates[key.substringAfterLast('/')] ?: return@computeIfAbsent host
        val forwarded = if (key.endsWith("SettingsPagePager.kt")) host.contains(": LumenPagePager(")
            else if (key.endsWith("ModernNavigationMotion.kt"))
                host.contains("object ModernNavigationMotion : ${target.second}(8)")
            else host.contains("typealias ") && host.contains(target.second)
        if (!forwarded) throw AssertionError("Host no longer delegates $key to ${target.second}")
        engine(target.first)
    }
}
