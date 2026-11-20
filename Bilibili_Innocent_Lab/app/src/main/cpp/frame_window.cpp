#include <android/native_window_jni.h>
#include <jni.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameWindowGeometry_extent(
        JNIEnv* env, jobject, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return 0;
    const int width = ANativeWindow_getWidth(window);
    const int height = ANativeWindow_getHeight(window);
    ANativeWindow_release(window);
    if (width <= 0 || height <= 0) return 0;
    return (static_cast<jlong>(width) << 32) | static_cast<unsigned>(height);
}
