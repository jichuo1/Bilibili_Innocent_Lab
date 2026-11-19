package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets

/** Immutable identities shared by the downloader, publisher and host. No remote manifest can change them. */
internal data class AudioAsset(val name: String, val size: Long, val sha256: String, val entry: String = name) {
    init { require(name.matches(Regex("[a-zA-Z0-9._-]+"))); require(size > 0); require(sha256.matches(Regex("[0-9a-f]{64}"))) }
    val remoteName get() = "audio-v1-${sha256.take(16)}-$name"
}

internal object AudioAssetSpec {
    const val ID = "gtcrn-dns3-ort-1.31.0-v1"
    val model = AudioAsset("gtcrn-dns3.onnx", 535190, "b4718df6228e7bdf1a8a435cf98f838636eb2fd331acabf86ba87c5192ebcb87")
    val runtime = AudioAsset("onnxruntime-android-1.31.0.aar", 55138854, "72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7")
    private const val COMMIT = "502ebfab64da7c4a9af78dcb9c6ceef1ebb01c73"
    val sources = mapOf(
        model to listOf("https://raw.githubusercontent.com/Xiaobin-Rong/gtcrn/$COMMIT/stream/onnx_models/gtcrn_simple.onnx",
            "https://cdn.jsdelivr.net/gh/Xiaobin-Rong/gtcrn@$COMMIT/stream/onnx_models/gtcrn_simple.onnx"),
        runtime to listOf("https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.0/${runtime.name}",
            "https://maven.aliyun.com/repository/central/com/microsoft/onnxruntime/onnxruntime-android/1.31.0/${runtime.name}")
    )
    val native = mapOf(
        "arm64-v8a" to listOf(
            AudioAsset("libonnxruntime.so", 34208680, "8005a741e57117b4d7b476417706c0e9af13cf1b89db47e60f0cbc56e1af91e0", "jni/arm64-v8a/libonnxruntime.so"),
            AudioAsset("libonnxruntime4j_jni.so", 122576, "5f2f1a99fd9f65fd6271eed825632f4ae5ada95f5cc13d66605888ccb80cdc29", "jni/arm64-v8a/libonnxruntime4j_jni.so")),
        "armeabi-v7a" to listOf(
            AudioAsset("libonnxruntime.so", 24182012, "2a63f9a56b211511209dbf4b5bc143f18d0b365977dd7a0afd57d95c85479af6", "jni/armeabi-v7a/libonnxruntime.so"),
            AudioAsset("libonnxruntime4j_jni.so", 89900, "6cc9816ea72fa290c6b13627412efad178eca031421ac9c367ebf4523b971b3d", "jni/armeabi-v7a/libonnxruntime4j_jni.so"))
    )
    fun parts(abi: String) = listOf(model) + requireNotNull(native[abi]) { "unsupported_abi" }
    val allParts get() = listOf(model) + native.values.flatten()
}

/** Codes are stable and safe to export; never include source URLs, private paths or raw exception messages. */
internal class AudioAssetFailure(val code: String, val part: String = "", val detail: String = "", cause: Throwable? = null) :
    java.io.IOException("$code:$part:$detail", cause)
