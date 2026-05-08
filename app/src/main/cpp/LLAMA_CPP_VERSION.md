# llama.cpp Snapshot

- Repository: `https://github.com/ggml-org/llama.cpp.git`
- Local path: `app/src/main/cpp/third_party/llama.cpp`
- Snapshot commit: `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`
- Commit date: `2026-05-05T09:43:03-07:00`
- Commit subject: `Hexagon: Process M-tail rows on HMX instead of HVX (#22724)`
- Acquisition command: `git clone --depth 1 https://github.com/ggml-org/llama.cpp.git .\app\src\main\cpp\third_party\llama.cpp`

Build integration notes:

- `LLAMA_BUILD_COMMON`, tests, tools, examples, server, web UI, and OpenSSL are disabled.
- `GGML_OPENMP`, `GGML_LLAMAFILE`, `GGML_NATIVE`, `GGML_CPU_KLEIDIAI`, and BLAS are disabled for the first Android CPU pass.
- `llmhost` links the static `llama`/`ggml` targets into the app-owned `libllmhost.so`.
