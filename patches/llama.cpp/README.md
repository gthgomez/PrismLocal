# llama.cpp local build patches

These patches capture local modifications made to the `llama.cpp` submodule
(`app/src/main/cpp/third_party/llama.cpp`) that cannot be pushed upstream
(submodule remote points at `ggml-org/llama.cpp`).

## Provenance

- Submodule base commit: `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3` (ggml-v0.18.0-863-gbbeb89d76)
- Patch generated: 2026-08-21 from the submodule working tree (`git diff`)
- Files touched:
  - `ggml/src/ggml-vulkan/CMakeLists.txt`
  - `ggml/src/ggml-vulkan/vulkan-shaders/vulkan-shaders-gen.cpp`

Purpose: [INFERRED] local ggml-vulkan build fixes so shader generation works with the vendored Vulkan headers in `app/src/main/cpp/third_party/{vulkan,spirv,vk_video}`.

## Applying after a submodule update/reset

```bash
cd app/src/main/cpp/third_party/llama.cpp
git checkout bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3   # or the recorded gitlink
git apply ../../patches/llama.cpp/ggml-vulkan-local-build.patch
```

Note: if the submodule is updated to a newer commit, re-apply may conflict;
regenerate this patch against the new base and rebuild.
