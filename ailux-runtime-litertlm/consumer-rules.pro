# Consumer ProGuard rules for ailux-runtime-litertlm.
#
# LiteRT-LM uses reflection on @Tool / @ToolParam annotations, JNI symbol
# lookups and Kotlin metadata; without explicit keep rules R8 in release
# builds will strip required classes and the engine will crash with
# `LiteRtLmJniException` or `IllegalStateException`.

# Public surface — keep everything under the LiteRT-LM package and any
# nested classes / native-bound members.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }

# Native methods discovered via JNI must keep their signatures intact.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve Kotlin metadata so reflection on data classes / sealed types
# (Backend, SamplerConfig, Message, Content, …) works after R8.
-keep class kotlin.Metadata { *; }
