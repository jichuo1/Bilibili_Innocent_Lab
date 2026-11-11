package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Replace only the brand page's first painter; logos and every other resource consumer stay native. */
internal class BrandSplashKntrRenderer(
    private val runtime: BrandSplashSelectionRuntime,
    private val environment: HookEnvironment
) {
    private class PaintFrame(val photo: BrandSplashImageCache.Photo?, var mainConsumed: Boolean = false)
    private class CachedPage(val photo: BrandSplashImageCache.Photo, val model: Any)
    private class ModelBinding(val model: WeakReference<Any>, val photo: WeakReference<BrandSplashImageCache.Photo>)
    private val paints = BrandSplashScopes<PaintFrame>()
    private val bindings = WeakHashMap<Any, CachedPage>()
    private val modelBindings = ArrayList<ModelBinding>()
    private val loader = requireNotNull(environment.classLoader)
    private val active = AtomicBoolean(false)
    private var painterBitmap: Bitmap? = null
    private var painter: Any? = null
    private val painterFactories = HashMap<Class<*>, Constructor<*>>()
    private val nativeDrawPhotos = WeakHashMap<Any, WeakReference<BrandSplashImageCache.Photo>>()
    private var wrappedBitmap: Bitmap? = null
    private var wrappedImage: Any? = null

    fun install(): Boolean {
        val composer = KavaMemberLookup.classOrNull(loader, "androidx.compose.runtime.Composer") ?: return false
        val modifier = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.Modifier") ?: return false
        val function0 = KavaMemberLookup.classOrNull(loader, "kotlin.jvm.functions.Function0") ?: return false
        val scopeGetter = KavaMemberLookup.methodOrNull(composer, "getRecomposeScope") ?: return false
        val invalidate = KavaMemberLookup.inheritedMethodOrNull(scopeGetter.returnType, "invalidate")
            ?.takeIf { it.returnType == Void.TYPE } ?: return false
        val owners = (('a'..'z').map { PACKAGE + it } + PACKAGE + "BrandSplashPageKt")
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
        val page = owners.flatMap(KavaMemberLookup::declaredMethods).singleOrNull { method ->
            Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                brandSplashPageAbi(method.parameterTypes.map { it.name })?.let { abi ->
                    method.parameterTypes[1] == function0 && method.parameterTypes[2] == modifier &&
                        method.parameterTypes[abi.composerIndex] == composer &&
                        (method.parameterTypes[3] != function0 ||
                            method.declaringClass.name == COMPACT_PAGE && method.name == "a")
                } == true &&
                brandSplashKntrModelConstructor(method.parameterTypes[0]) != null
        } ?: return false
        val abi = requireNotNull(brandSplashPageAbi(page.parameterTypes.map { it.name }))
        val composerIndex = abi.composerIndex
        val flagsIndex = abi.flagsIndex
        val constructor = brandSplashKntrModelConstructor(page.parameterTypes[0]) ?: return false
        val exitPoint = BrandSplashKntrAccess.exit(loader) ?: return false
        val exitOwner = exitPoint.owner
        val exitMethod = exitPoint.resume
        // 9.19's compact brand page constructs the exact repackaged BoxWithConstraints draw lambda.
        val drawOwners = if (page.parameterTypes[3] == function0)
            listOfNotNull(KavaMemberLookup.classOrNull(loader, COMPACT_DRAW)) else owners
        val draw = drawOwners.singleOrNull { owner ->
            KavaMemberLookup.declaredFields(owner).count { it.type == page.parameterTypes[0] } == 1 &&
                KavaMemberLookup.declaredMethods(owner).count { it.name == "invoke" && it.parameterCount == 3 } == 1
        } ?: return false
        val invokeDraw = KavaMemberLookup.declaredMethods(draw).single { it.name == "invoke" && it.parameterCount == 3 }
        val drawModel = KavaMemberLookup.declaredFields(draw, makeAccessible = true).single { it.type == page.parameterTypes[0] }
        val resourceClass = KavaMemberLookup.classOrNull(loader, "org.jetbrains.compose.resources.DrawableResource") ?: return false
        val resources = KavaMemberLookup.classOrNull(loader, "org.jetbrains.compose.resources.ImageResourcesKt") ?: return false
        val resource = KavaMemberLookup.methodOrNull(resources, "painterResource", resourceClass, composer,
            Int::class.javaPrimitiveType!!) ?: return false
        val nativeImages = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.graphics.AndroidImageBitmap_androidKt") ?: return false
        val wrapImage = KavaMemberLookup.methodOrNull(nativeImages, "asImageBitmap", classOf<Bitmap>()) ?: return false
        val imageBitmap = wrapImage.returnType
        val bitmapFields = KavaMemberLookup.declaredFields(draw, makeAccessible = true).filter { it.type == imageBitmap }
        if (bitmapFields.size > 1) return false
        val bitmapField = bitmapFields.singleOrNull()
        val bitmapConstructor = if (bitmapField != null) KavaMemberLookup.declaredConstructors(draw, true).singleOrNull {
            brandSplashDrawConstructorAbi(it.parameterTypes.map(Class<*>::getName),
                imageBitmap.name, page.parameterTypes[0].name, resourceClass.name)
        } ?: return false else null
        if (bitmapConstructor != null) {
            // This page owns the constructor; pass a bitmap without changing its immutable
            // captures after construction or intercepting app-wide image rendering functions.
            environment.registrar.constructor("brand.custom.kntr.bitmap", bitmapConstructor) {
                before {
                    if (!active.get()) return@before
                    val model = argOrNull(1) ?: return@before
                    val photo = modelBindings.firstOrNull { it.model.get() === model }?.photo?.get()
                        ?.takeIf { runtime.isSelectionCurrent(it.signature) } ?: return@before
                    val value = if (wrappedBitmap === photo.bitmap && wrappedImage != null) requireNotNull(wrappedImage)
                        else requireNotNull(wrapImage.invoke(null, photo.bitmap)).also {
                            wrappedBitmap = photo.bitmap; wrappedImage = it
                        }
                    args[0] = value
                    setObjectExtra("brand.custom.bitmap.photo", photo)
                    setObjectExtra("brand.custom.bitmap.value", value)
                }
                after {
                    if (hasThrowable) return@after
                    val photo = getObjectExtra("brand.custom.bitmap.photo") as? BrandSplashImageCache.Photo ?: return@after
                    val target = instance ?: return@after
                    if (bitmapField?.get(target) !== getObjectExtra("brand.custom.bitmap.value")) return@after
                    if (nativeDrawPhotos.size >= 16) nativeDrawPhotos.keys.firstOrNull()?.let(nativeDrawPhotos::remove)
                    nativeDrawPhotos[target] = WeakReference(photo)
                }
            }
        }
        environment.registrar.exact("brand.custom.kntr.painter", resources, resource.name, *resource.parameterTypes) {
            after {
                if (!active.get() || hasThrowable) return@after
                val frame = paints.current() ?: return@after
                if (frame.mainConsumed) return@after
                frame.mainConsumed = true
                val photo = frame.photo ?: return@after
                val original = result ?: return@after
                runCatching {
                    val replacement = nativePainter(photo.bitmap, original.javaClass, wrapImage, imageBitmap)
                    result = replacement
                    runtime.shown(photo)
                    environment.reportRuntimeEvidence(BrandSplashCustomFeatureInstaller.ID, FeatureRuntimeStage.APPLIED)
                }.onFailure {
                    environment.logError("brand_custom_painter", "[BIL] 自选开屏绘制结构发生变化，保留原画面")
                }
            }
        }
        environment.registrar.exact("brand.custom.kntr.draw", draw, invokeDraw.name, *invokeDraw.parameterTypes) {
            before {
                // BoxWithConstraints can subcompose during measurement, after the outer page returned.
                // Resolve its captured immutable model rather than relying on an outer thread-local lifetime.
                val photo = if (active.get()) runCatching {
                    val value = instance?.let(drawModel::get)
                    modelBindings.removeAll { it.model.get() == null || it.photo.get() == null }
                    val stored = modelBindings.firstOrNull { it.model.get() === value }?.photo?.get()
                    val composition = argOrNull(1)?.takeIf(composer::isInstance)
                    composition?.let(scopeGetter::invoke)?.let { runtime.observeScope(it, invalidate) }
                    stored?.takeIf { runtime.isSelectionCurrent(it.signature) } ?: runtime.photo()
                }.getOrNull() else null
                // The modern page draws its background bitmap first and its Logo later.
                // Resource painters in that branch must always stay native.
                paints.enter(PaintFrame(photo, mainConsumed = bitmapConstructor != null))
                val owned = instance?.let { nativeDrawPhotos[it]?.get() }
                if (owned != null && photo === owned && runtime.isSelectionCurrent(owned.signature)) {
                    setObjectExtra("brand.custom.draw.photo", owned)
                }
            }
            after {
                try {
                    val photo = getObjectExtra("brand.custom.draw.photo") as? BrandSplashImageCache.Photo
                    if (!hasThrowable && photo != null && runtime.isSelectionCurrent(photo.signature)) {
                        runtime.shown(photo)
                        environment.reportRuntimeEvidence(BrandSplashCustomFeatureInstaller.ID, FeatureRuntimeStage.APPLIED)
                    }
                } finally { paints.leave() }
            }
        }
        environment.registrar.exact("brand.custom.kntr.page", page.declaringClass, page.name, *page.parameterTypes) {
            before {
                if (active.get()) runCatching {
                    val composition = argOrNull(composerIndex)?.takeIf(composer::isInstance) ?: return@runCatching
                    val scope = scopeGetter.invoke(composition)
                    if (scope != null) runtime.observeScope(scope, invalidate)
                    val key = scope ?: composition
                    val existing = bindings[key]?.takeIf { runtime.isSelectionCurrent(it.photo.signature) }
                    val cached = existing ?: runtime.photo()?.let { bind(key, it, constructor) } ?: return@runCatching
                    args[0] = cached.model
                    // The callee uses 4 for a changed model (2 means unchanged). Keep the callback's bits intact.
                    args[flagsIndex] = brandSplashChangedModelFlags(argOrNull(flagsIndex) as Int)
                }.onFailure {
                    environment.logError("brand_custom_page", "[BIL] 自选开屏页面绑定不可用，保留原画面")
                }
            }
        }
        environment.registrar.exact("brand.custom.kntr.completed", exitOwner, exitMethod.name, classOf<Any>()) {
            after {
                if (!active.get() || hasThrowable || !exitPoint.exited(instance, result)) return@after
                runtime.photo()?.let(runtime::exited)
            }
        }
        active.set(true)
        return true
    }

    private fun bind(key: Any, photo: BrandSplashImageCache.Photo, constructor: Constructor<*>): CachedPage {
        val image = photo.image
        val offset = brandSplashSerializationOffset(constructor.parameterCount)
        val source = enumValue(constructor.parameterTypes[offset], image.source ?: "brand")
        val mode = enumValue(constructor.parameterTypes[8 + offset], if (image.mode == "full") "full" else "half")
        val values = arrayOf<Any?>(source, image.id, image.url, image.hash, null, null, null, image.showLogo, mode)
        // R8 retains the serialization constructor with a leading presence mask on these APKs.
        val arguments = if (offset == 1) arrayOf<Any?>(0x1ff, *values) else values
        val model = constructor.newInstance(*arguments)
        if (bindings.size >= 4) bindings.keys.firstOrNull()?.let(bindings::remove)
        modelBindings.removeAll { it.model.get() == null || it.photo.get() == null }
        if (modelBindings.size >= 16) modelBindings.removeAt(0)
        modelBindings.add(ModelBinding(WeakReference(model), WeakReference(photo)))
        return CachedPage(photo, model).also { bindings[key] = it }
    }

    private fun enumValue(type: Class<*>, value: String): Any = type.enumConstants?.singleOrNull {
        (it as? Enum<*>)?.name?.equals(value, ignoreCase = true) == true
    } ?: error("unknown-brand-enum")

    private fun nativePainter(bitmap: Bitmap, owner: Class<*>, wrapImage: Method, imageBitmap: Class<*>): Any {
        if (painterBitmap === bitmap && painter != null && owner.isInstance(painter)) return requireNotNull(painter)
        val constructor = painterFactories[owner] ?: KavaMemberLookup.declaredConstructors(owner).filter { c ->
            val p = c.parameterTypes
            p.isNotEmpty() && p[0] == imageBitmap && (p.size == 1 ||
                (p.size in 3..5 && p[1] == Long::class.javaPrimitiveType && p[2] == Long::class.javaPrimitiveType &&
                    (p.size == 3 || (p.size == 4 && p[3].name == "kotlin.jvm.internal.DefaultConstructorMarker") ||
                        (p.size == 5 && p[3] == Int::class.javaPrimitiveType && p[4].name == "kotlin.jvm.internal.DefaultConstructorMarker"))))
        }.minByOrNull { it.parameterCount }?.also { painterFactories[owner] = it }
            ?: error("missing-host-bitmap-painter")
        val nativeBitmap = wrapImage.invoke(null, bitmap)
        val size = (bitmap.width.toLong() shl 32) or (bitmap.height.toLong() and 0xffffffffL)
        val args: Array<Any?> = when (constructor.parameterCount) {
            1 -> arrayOf(nativeBitmap)
            3 -> arrayOf(nativeBitmap, 0L, size)
            4 -> arrayOf(nativeBitmap, 0L, size, null)
            5 -> arrayOf(nativeBitmap, 0L, size, 6, null)
            else -> error("unsupported-painter-constructor")
        }
        return constructor.newInstance(*args).also { painterBitmap = bitmap; painter = it }
    }

    companion object {
        const val PAGE = "kntr.srcs.app.splash.brand.startup.c"
        private const val PACKAGE = "kntr.srcs.app.splash.brand.startup."
        private const val COMPACT_PAGE = PACKAGE + "a"
        private const val COMPACT_DRAW = "ckk"
    }
}

internal fun brandSplashDrawConstructorAbi(parameters: List<String>, image: String, model: String, resource: String): Boolean {
    val state = "androidx.compose.runtime.MutableState"
    val prefix = listOf(image, model, "androidx.compose.ui.unit.Density", resource, state)
    val oldCaptures = listOf(state, state)
    // 9.18/9.19 capture the selected image's native transition state between readiness captures.
    return parameters == prefix + oldCaptures ||
        parameters == prefix + "androidx.compose.runtime.MutableIntState" + oldCaptures
}

internal fun brandSplashChangedModelFlags(flags: Int): Int = (flags and 0b1110.inv()) or 0b0100

internal fun brandSplashSerializationOffset(parameterCount: Int): Int = when (parameterCount) {
    9 -> 0
    10 -> 1
    else -> error("unsupported-brand-model-constructor")
}

internal fun brandSplashKntrModelConstructor(type: Class<*>): Constructor<*>? =
    KavaMemberLookup.declaredConstructors(type).filter { candidate ->
        val p = candidate.parameterTypes
        val offset = if (p.size == 10 && p[0] == Int::class.javaPrimitiveType) 1 else 0
        p.size == 9 + offset && p[offset].name == "kntr.srcs.app.splash.brand.model.BrandSplashSource" &&
            p[1 + offset] == Long::class.javaPrimitiveType && (2..6).all { p[it + offset] == classOf<String>() } &&
            p[7 + offset] == Boolean::class.javaPrimitiveType &&
            p[8 + offset].name == "kntr.srcs.app.splash.brand.model.BrandSplashMode"
    }.minByOrNull { it.parameterCount }
