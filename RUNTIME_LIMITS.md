# Runtime Limits

The current native runtime exposes generation settings that are validated end-to-end through Kotlin, JNI, and native code.

- **Context length**: User-configurable from `512` to `8192` tokens (clamped in `GenerationSettings` and mapped dynamically).
- **Prompt batch sizes**: Fixed in native C++ code at `n_batch = 512` and `n_ubatch = 512`.
- **Prompt formatting**: If a GGUF exposes `tokenizer.chat_template`, native generation formats the user prompt as a single chat turn before tokenization. ChatML/Qwen-style templates fall back to `<|im_start|>user ... <|im_start|>assistant` formatting if the lightweight llama.cpp template helper cannot render the stored Jinja template.
- **Sampling**: Custom sampler chain with repetition penalty, top-k, top-p, temperature, and random distribution sampling.
- **Grammar-Constrained Decoding**: Uses a native `llama_sampler_init_grammar` constraint to force valid JSON structures on the tool-decision turn.
  - **Selective JSON Grammar**: The GBNF is defined as `root ::= tool | prose`. If the model starts its response with `{`, it is strictly constrained to output the JSON tool-call schema. If it starts with any other character, it evaluates under `prose`, which is unconstrained.
  - **Limitation**: Due to the prefix-based nature of prefix grammars, if the model outputs `{` later in prose, the parser may still fail. This behavior is monitored in traces.
  - **Decision Scope**: Grammars are transient and passed as an optional per-request parameter. They apply only on the initial tool-decision turn, leaving follow-up turns unconstrained to allow natural conversational replies.
- **Maximum generated tokens**: UI-configurable from 1 to 512 in 32-token steps. Kotlin and native code both clamp the value before generation starts.
- **Threads**: UI-configurable from 1 to 8. Native load chooses a safe default from available hardware, and each generation can override it with a clamped value.
- **Max Agent Iterations**: Exposed as a configurable budget parameter inside `GenerationSettings`, clamped between `1` and `10` steps (default `3`).
- **Generation telemetry**: Native code logs one `generation_summary` line with token count, elapsed milliseconds, and approximate tokens/sec. The service also publishes prompt latency, decode latency, total milliseconds, generated token count, terminal reason, and approximate decode tokens/sec to the Compose UI. Per-token native logging is intentionally avoided because it can materially slow Android inference.
- **Performance testing APK**: Use `assembleBenchmark` for installable local performance tests. It is release-like and debug-signed with the `.benchmark` application ID suffix; it is not a production signing path.
- **KleidiAI ARM kernels**: Disabled by default so offline builds remain stable. Enable only for arm64 experiments with `LLMHOST_ENABLE_KLEIDIAI=true`.
- **Model memory mapping**: Real GGUF loads try `mmap` first and fall back to non-mmap loading if model initialization fails. This reduces RAM pressure for 2-3 GiB models, but the mapped file must remain stable while generation is active.
- **JNI text handoff**: Native token bytes are converted to Java strings with the Java UTF-8 decoder rather than `NewStringUTF`, avoiding VM aborts on non-modified-UTF-8 byte sequences.
- **Load size guard**: The service rejects models above the current safe mobile budget before entering native load. The budget is capped at 3 GiB and keeps a dynamic memory reserve, capped at 768 MiB, so tiny valid GGUFs can still load on constrained emulators while very large GGUFs are rejected before native C++ code can take down the app. Android low-memory state also fails closed even when the file is below the hard cap.
- **Transcript storage**: Chat turns are owned by `InferenceService` and persisted to app-private files using temp-file promotion. The UI renders the service transcript instead of local Compose-only state, so Activity recreation does not erase the visible conversation.
- **Sanitization Guard**: The service enforces explicit path traversal validation (`..`, `/`, `\`) on chat IDs and filenames, rejecting illegal requests early.
