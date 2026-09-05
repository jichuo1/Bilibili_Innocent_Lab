# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# FreeReflection
-keep class me.weishu.reflection.** {*;}

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static *** throwUninitializedProperty(...);
    public static *** throwUninitializedPropertyAccessException(...);
}

-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    *** inflate(android.view.LayoutInflater);
}

-keep class * extends android.app.Activity
-keep class * implements androidx.viewbinding.ViewBinding {
    <init>();
    *** inflate(android.view.LayoutInflater);
}

-dontwarn io.github.libxposed.annotation.**
# API 101 的 HookBuilder 没有 setId；该版本桥不能被内联或合并到兼容路径。
-keep,allowobfuscation class com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookIdsApi102 {
    *;
}
# 只允许改写 java_init.list 的**内容**（把入口类名换成混淆后的名字）。
#
# 曾经这里还有一条**无过滤器**的 `-adaptresourcefilenames`：它会把 Java 资源的路径
# 按混淆后的包名改写，而 R8 full mode 把 `kotlin` 等包压平后，
# `kotlin/collections/collections.kotlin_builtins` 变成了 `/collections.kotlin_builtins`
# ——**以 `/` 开头的绝对路径 zip 条目**（release APK 里共 9 条，含 commonmark 的
# `org/commonmark/internal/util/entities.properties`）。后果：
#   1. NPatch 用 `java.util.zip.ZipFile` 打开模块 APK 做模块识别，Android 的 zip
#      路径穿越加固会拒绝这类条目，异常被 NPatch 静默吞掉 → 模块列表恒为 0；
#   2. Kotlin 内建元数据与 commonmark 实体表按资源路径查找，release 下也一并失效。
# 详见长期文档 2026-09-04 条目。不要再加回不带过滤器的 -adaptresourcefilenames。
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
