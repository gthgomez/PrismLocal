# LLMHostAndroid (Prism Local)

Android app for local GGUF inference using a JNI C++ bridge to llama.cpp. Provides a chat interface, model management, Hugging Face downloads, and agent tool dispatch.

**Tech stack:** Kotlin, Jetpack Compose Material3, C++/CMake/NDK, vendored llama.cpp, GGUF model storage, Oboe (audio), WorkManager.

**Build** (always from this directory, not the `Project_Android` composite root):

```powershell
cd C:\Workspace\Project_Android\PrismLocal
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon assembleDebug
```

`PrismLocal` is also linked in the workspace composite via `includeBuild("PrismLocal")` for Android Studio multi-project opens. App tasks still run with this project’s wrapper (`gradle-9.4.1`, AGP `9.2.0`).

**Detailed docs:** [AGENTS.md](AGENTS.md) | [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) | [SIGNING.md](SIGNING.md)
