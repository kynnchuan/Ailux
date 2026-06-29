# Consumer ProGuard rules for ailux-runtime-llamacpp.
#
# LlamaCppEngine binds to a native `.so` (libailux_llama.so) via JNI. R8 in
# release builds must not strip the class declaring the `external` methods, nor
# rename the native method names, or the JNI symbol lookup fails at runtime.

# Keep the engine class and its native method signatures intact.
-keep class com.ailux.runtime.llamacpp.LlamaCppEngine { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# The native code calls back into this callback interface by name; keep it.
-keep interface com.ailux.runtime.llamacpp.LlamaNativeBridge$TokenSink { *; }
-keep class com.ailux.runtime.llamacpp.LlamaNativeBridge { *; }
