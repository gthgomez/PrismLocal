# Prism Local (com.prismai.llmhost) — R8/ProGuard rules for minify-enabled builds
# (release, benchmark, profile, adreno).

# Compose — keep Composable functions and their metadata
-keep class androidx.compose.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Keep Kotlin serialization (used by chat transcripts JSON)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.prismai.llmhost.**$$serializer { *; }
-keepclassmembers class com.prismai.llmhost.** { *** Companion; }
-keepclasseswithmembers class com.prismai.llmhost.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep JNI entry points and drain carrier (names must match llmhost_jni.cpp)
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.prismai.llmhost.bridge.NativeLlmBridge { *; }
-keep class com.prismai.llmhost.bridge.NativeDrainResult { *; }

# Keep data classes used with JSON (chat transcripts, benchmarks)
-keepclassmembers class com.prismai.llmhost.TranscriptMessage { *; }
-keepclassmembers class com.prismai.llmhost.BenchmarkRun { *; }
-keepclassmembers class com.prismai.llmhost.BenchmarkPreset { *; }
-keepclassmembers class com.prismai.llmhost.GenerationSettings { *; }
-keepclassmembers class com.prismai.llmhost.ChatSession { *; }
-keepclassmembers class com.prismai.llmhost.MemoryFact { *; }
-keepclassmembers class com.prismai.llmhost.GenerationPerformance { *; }
-keepclassmembers class com.prismai.llmhost.PendingAgentToolAction { *; }
-keepclassmembers class com.prismai.llmhost.AgentToolCall { *; }
-keepclassmembers class com.prismai.llmhost.AgentToolResult { *; }

# AndroidX WorkManager
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# General Android
-keepattributes Signature
-keepattributes Exceptions
-dontwarn javax.annotation.**
