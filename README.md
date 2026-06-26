# LLMHostAndroid (Prism Local)

Android app for local GGUF inference using a JNI C++ bridge to llama.cpp. Provides a chat interface, model management, Hugging Face downloads, and agent tool dispatch.

**Tech stack:** Kotlin, Jetpack Compose Material3, C++/CMake/NDK, vendored llama.cpp, GGUF model storage, Oboe (audio), WorkManager.

**Build:** `.\gradlew.bat --no-daemon assembleDebug`

**Detailed docs:** [AGENTS.md](AGENTS.md) | [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md)
