# No release shrinking is enabled for the doc-derived verification APK.

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
-keep,includedescriptorclasses class com.example.llmhost.**$$serializer { *; }
-keepclassmembers class com.example.llmhost.** { *** Companion; }
-keepclasseswithmembers class com.example.llmhost.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.example.llmhost.NativeLlmBridge { *; }
-keep class com.example.llmhost.NativeDrainResult { *; }

# Keep data classes used with Gson/Moshi/JSON (chat transcripts, benchmarks)
-keepclassmembers class com.example.llmhost.TranscriptMessage { *; }
-keepclassmembers class com.example.llmhost.BenchmarkRun { *; }
-keepclassmembers class com.example.llmhost.BenchmarkPreset { *; }
-keepclassmembers class com.example.llmhost.GenerationSettings { *; }
-keepclassmembers class com.example.llmhost.ChatSession { *; }
-keepclassmembers class com.example.llmhost.MemoryFact { *; }
-keepclassmembers class com.example.llmhost.GenerationPerformance { *; }
-keepclassmembers class com.example.llmhost.PendingAgentToolAction { *; }
-keepclassmembers class com.example.llmhost.AgentToolCall { *; }
-keepclassmembers class com.example.llmhost.AgentToolResult { *; }

# AndroidX WorkManager
-keep class * extends androidx.work.Worker { *; }

# General Android
-keepattributes Signature
-keepattributes Exceptions
-dontwarn javax.annotation.**
