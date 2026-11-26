// 弹幕净化 wire 内核的 JNI 垫片：只做参数搬运，不含任何业务判定。
// JNI 调用在当前线程完成。Rust 入口用 catch_unwind 捕获 panic，并向 Java 返回 null。
// NewIntArray/NewByteArray 分配失败时 JVM 会保留 OOM，调用方用 runCatching 处理。
#include <jni.h>
#include <cstdint>
#include <cstddef>

extern "C" {
typedef struct {
    uint32_t elems_field;
    uint32_t weight_field;
    uint32_t colorful_field;
    uint32_t content_field;
    uint32_t colorful_src_field;
    uint32_t colorful_type_field;
} DwFields;

uint32_t dw_abi_version(void);
int32_t dw_scan(const uint8_t* buf, size_t buf_len, const DwFields* fields,
                int32_t** out_ptr, size_t* out_len, size_t* out_cap);
int32_t dw_rewrite(const uint8_t* buf, size_t buf_len, const DwFields* fields,
                   const uint8_t* keep_elem, size_t keep_elem_len,
                   const uint8_t* recolor_elem, size_t recolor_elem_len,
                   const uint8_t* keep_src, size_t keep_src_len,
                   uint8_t** out_ptr, size_t* out_len, size_t* out_cap);
void dw_free_i32(int32_t* ptr, size_t len, size_t cap);
void dw_free_u8(uint8_t* ptr, size_t len, size_t cap);
}

namespace {
// 与 Rust 侧 MAX_REPLY_BYTES 对齐的前置上限：先挡掉超大数组，避免无谓的元素拷贝。
constexpr jsize kMaxReplyBytes = 16 * 1024 * 1024;

// protobuf 字段号合法区间（1..2^29-1）；越界直接拒绝，不进 Rust。
bool validField(jint field) {
    return field >= 1 && field <= 536870911;
}

bool validFields(jint a, jint b, jint c, jint d, jint e, jint f) {
    return validField(a) && validField(b) && validField(c) && validField(d) && validField(e) && validField(f);
}

// 带挂起异常继续调 JNI 属未定义行为：失败路径先清异常再走释放/返回，
// 把失败折叠成"返回 null（本次拒绝）"而不是把 OOM 泄给调用方的下一段 JNI。
void clearPending(JNIEnv* env) {
    if (env->ExceptionCheck()) env->ExceptionClear();
}

DwFields makeFields(jint elems, jint weight, jint colorful, jint content, jint src, jint srcType) {
    return DwFields{static_cast<uint32_t>(elems), static_cast<uint32_t>(weight),
                    static_cast<uint32_t>(colorful), static_cast<uint32_t>(content),
                    static_cast<uint32_t>(src), static_cast<uint32_t>(srcType)};
}
} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_danmakuwire_DanmakuWireNative_nativeAbiVersion(
    JNIEnv*, jobject) {
    return static_cast<jint>(dw_abi_version());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_danmakuwire_DanmakuWireNative_nativeScan(
    JNIEnv* env, jobject, jbyteArray reply, jint elemsField, jint weightField, jint colorfulField,
    jint contentField, jint srcField, jint srcTypeField) {
    if (reply == nullptr || !validFields(elemsField, weightField, colorfulField, contentField, srcField, srcTypeField)) {
        return nullptr;
    }
    const jsize replyLen = env->GetArrayLength(reply);
    if (replyLen < 0 || replyLen > kMaxReplyBytes) return nullptr;
    jbyte* bytes = env->GetByteArrayElements(reply, nullptr);
    if (bytes == nullptr) {
        clearPending(env);
        return nullptr;
    }
    const DwFields fields = makeFields(elemsField, weightField, colorfulField, contentField, srcField, srcTypeField);
    int32_t* scanOut = nullptr;
    size_t scanLen = 0;
    size_t scanCap = 0;
    const int32_t code = dw_scan(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(replyLen),
                                 &fields, &scanOut, &scanLen, &scanCap);
    env->ReleaseByteArrayElements(reply, bytes, JNI_ABORT);
    if (code != 0 || scanLen == 0 || scanLen > static_cast<size_t>(INT32_MAX)) {
        if (scanOut != nullptr) dw_free_i32(scanOut, scanLen, scanCap);
        return nullptr;
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(scanLen));
    if (result == nullptr) {
        clearPending(env);
        dw_free_i32(scanOut, scanLen, scanCap);
        return nullptr;
    }
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(scanLen), scanOut);
    dw_free_i32(scanOut, scanLen, scanCap);
    if (env->ExceptionCheck()) {
        clearPending(env);
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_danmakuwire_DanmakuWireNative_nativeRewrite(
    JNIEnv* env, jobject, jbyteArray reply, jint elemsField, jint weightField, jint colorfulField,
    jint contentField, jint srcField, jint srcTypeField, jbooleanArray keepElem, jbooleanArray recolorElem,
    jbooleanArray keepSrc) {
    if (reply == nullptr || keepElem == nullptr || recolorElem == nullptr || keepSrc == nullptr ||
        !validFields(elemsField, weightField, colorfulField, contentField, srcField, srcTypeField)) {
        return nullptr;
    }
    const jsize replyLen = env->GetArrayLength(reply);
    const jsize keepLen = env->GetArrayLength(keepElem);
    const jsize recolorLen = env->GetArrayLength(recolorElem);
    const jsize srcLen = env->GetArrayLength(keepSrc);
    if (replyLen < 0 || replyLen > kMaxReplyBytes || keepLen < 0 || recolorLen < 0 || srcLen < 0) {
        return nullptr;
    }
    jbyte* bytes = env->GetByteArrayElements(reply, nullptr);
    if (bytes == nullptr) {
        clearPending(env);
        return nullptr;
    }
    // jboolean 是单字节 0/1，可直接当 u8 位图交给 Rust。
    jboolean* keepRaw = env->GetBooleanArrayElements(keepElem, nullptr);
    if (keepRaw == nullptr) {
        clearPending(env);
        env->ReleaseByteArrayElements(reply, bytes, JNI_ABORT);
        return nullptr;
    }
    jboolean* recolorRaw = env->GetBooleanArrayElements(recolorElem, nullptr);
    if (recolorRaw == nullptr) {
        clearPending(env);
        env->ReleaseBooleanArrayElements(keepElem, keepRaw, JNI_ABORT);
        env->ReleaseByteArrayElements(reply, bytes, JNI_ABORT);
        return nullptr;
    }
    jboolean* keepSrcRaw = env->GetBooleanArrayElements(keepSrc, nullptr);
    if (keepSrcRaw == nullptr) {
        clearPending(env);
        env->ReleaseBooleanArrayElements(recolorElem, recolorRaw, JNI_ABORT);
        env->ReleaseBooleanArrayElements(keepElem, keepRaw, JNI_ABORT);
        env->ReleaseByteArrayElements(reply, bytes, JNI_ABORT);
        return nullptr;
    }
    const auto* keep = reinterpret_cast<const uint8_t*>(keepRaw);
    const auto* recolor = reinterpret_cast<const uint8_t*>(recolorRaw);
    const auto* keepSrcBytes = reinterpret_cast<const uint8_t*>(keepSrcRaw);
    jbyteArray result = nullptr;
    // 六字段统一传全：Rust 侧 rewrite 只用其中三个，但签名与 scan 对齐，
    // 避免将来扩展时留下"0 号字段静默失效"的陷阱。
    const DwFields fields = makeFields(elemsField, weightField, colorfulField, contentField, srcField, srcTypeField);
    uint8_t* rewriteOut = nullptr;
    size_t rewriteLen = 0;
    size_t rewriteCap = 0;
    const int32_t code = dw_rewrite(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(replyLen),
                                    &fields,
                                    keep, static_cast<size_t>(keepLen),
                                    recolor, static_cast<size_t>(recolorLen),
                                    keepSrcBytes, static_cast<size_t>(srcLen),
                                    &rewriteOut, &rewriteLen, &rewriteCap);
    env->ReleaseByteArrayElements(reply, bytes, JNI_ABORT);
    env->ReleaseBooleanArrayElements(keepElem, keepRaw, JNI_ABORT);
    env->ReleaseBooleanArrayElements(recolorElem, recolorRaw, JNI_ABORT);
    env->ReleaseBooleanArrayElements(keepSrc, keepSrcRaw, JNI_ABORT);
    if (code == 0 && rewriteLen <= static_cast<size_t>(INT32_MAX)) {
        // 空输出（rewriteOut==null && len==0）对空输入是合法结果。
        result = env->NewByteArray(static_cast<jsize>(rewriteLen));
        if (result == nullptr) {
            clearPending(env);
        } else if (rewriteOut != nullptr) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(rewriteLen),
                                    reinterpret_cast<const jbyte*>(rewriteOut));
            if (env->ExceptionCheck()) {
                clearPending(env);
                result = nullptr;
            }
        }
    }
    if (rewriteOut != nullptr) dw_free_u8(rewriteOut, rewriteLen, rewriteCap);
    return result;
}
