# Runtime Limits

The current native runtime exposes only the generation settings that are validated end-to-end through Kotlin, JNI, and native code.

- Context length: fixed in native code at `n_ctx = 512`.
- Prompt batch sizes: fixed in native code at `n_batch = 512` and `n_ubatch = 512`.
- Prompt formatting: if a GGUF exposes `tokenizer.chat_template`, native generation formats the user prompt as a single chat turn before tokenization. ChatML/Qwen-style templates fall back to `<|im_start|>user ... <|im_start|>assistant` formatting if the lightweight llama.cpp template helper cannot render the stored Jinja template.
- Sampling: fixed native sampler chain with repetition penalty, top-k, top-p, temperature, and random distribution sampling. The UI does not expose these controls yet.
- Maximum generated tokens: UI-configurable from 1 to 512 in 32-token steps. Kotlin and native code both clamp the value before generation starts.
- Threads: UI-configurable from 1 to 8. Native load still chooses a safe default from available hardware, and each generation can override it with a clamped value.
- Generation telemetry: native code logs one `generation_summary` line with token count, elapsed milliseconds, and approximate tokens/sec. The service also publishes prompt latency, decode latency, total milliseconds, generated token count, terminal reason, and approximate decode tokens/sec to the Compose UI. Per-token native logging is intentionally avoided because it can materially slow Android inference.
- Performance testing APK: use `assembleBenchmark` for installable local performance tests. It is release-like and debug-signed with the `.benchmark` application ID suffix; it is not a production signing path.
- KleidiAI ARM kernels: disabled by default so offline builds remain stable. Enable only for arm64 experiments with `LLMHOST_ENABLE_KLEIDIAI=true`; this may require dependency download during CMake configure depending on the vendored llama.cpp state.
- Model memory mapping: real GGUF loads try `mmap` first and fall back to non-mmap loading if model initialization fails. This reduces RAM pressure for 2-3 GiB models, but the mapped file must remain stable while generation is active; deleting/replacing the app or model file during native compute can still crash at the OS signal level.
- JNI text handoff: native token bytes are converted to Java strings with the Java UTF-8 decoder rather than `NewStringUTF`, avoiding VM aborts on non-modified-UTF-8 byte sequences.
- Load size guard: the service rejects models above the current safe mobile budget before entering native load. The budget is capped at 3 GiB and keeps a dynamic memory reserve, capped at 768 MiB, so tiny valid GGUFs can still load on constrained emulators while very large GGUFs are rejected before native code can take down the app. Android low-memory state also fails closed even when the file is below the hard cap.
- Transcript storage: chat turns are owned by `InferenceService` and persisted to app-private `chat_transcript.json` using temp-file promotion. The UI renders the service transcript instead of local Compose-only state, so Activity recreation does not erase the visible conversation.

The Compose UI does not expose temperature, top-p, or context length yet because accepting those values without a validated JNI/native contract would create misleading controls and new crash surface. Add settings only after `NativeLlmBridge` accepts structured generation parameters and the native backend validates safe ranges before generation starts.
