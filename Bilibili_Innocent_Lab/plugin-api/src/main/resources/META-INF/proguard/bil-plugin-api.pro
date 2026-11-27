# Preserve public plugin API classes, interfaces, and enums across R8 shrinking/obfuscation.
-keep class com.bilab.plugin.api.** { *; }
-keep interface com.bilab.plugin.api.** { *; }
-keep enum com.bilab.plugin.api.** { *; }
