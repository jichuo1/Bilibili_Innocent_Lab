plugins {
    alias(libs.plugins.android.application) apply false
    // 仅把 KGP 放上 buildscript classpath，**不 apply**。AGP 9 的内置 Kotlin
    // (`initBuiltInKotlinSupport`) 会加载 `KotlinBaseApiPlugin` 并读它的
    // `getPluginVersion()` 来决定编译器版本；不声明这一行就退回 AGP 自带的
    // kotlin-gradle-plugin 2.2.10，而本项目依赖（Hikage 1.1.1 / KavaRef 1.1.0 /
    // BetterAndroid 1.1.6 / kotlin-stdlib 2.4.10）的 metadata 版本是 2.4.0，
    // 2.2.x 编译器最高只能读到 2.3.0，会全量报 "Incompatible classes were found"。
    // 一旦在任何模块里真的 apply 了它，AGP 就会让出内置 Kotlin 并退回旧 variant API。
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.ksp) apply false
}