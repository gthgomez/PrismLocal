# Third-Party Notices & Licenses

PrismLocal incorporates open-source software and headers subject to the following third-party licenses.

---

## 1. llama.cpp

- **Repository:** https://github.com/ggml-org/llama.cpp
- **Submodule Path:** `app/src/main/cpp/third_party/llama.cpp`
- **Pinned Snapshot Commit:** `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`
- **License:** MIT License

```text
MIT License

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. Khronos Vulkan & SPIR-V Headers

- **Headers Path:** `app/src/main/cpp/third_party/{vulkan, spirv, vk_video}`
- **Copyright:** Copyright 2014-2024 The Khronos Group Inc.
- **License:** Apache License 2.0 (`vulkan/vulkan_core.h`, `vk_video/vulkan_video_codecs_common.h`) / MIT License (`spirv/unified1/spirv.h`)

Licensed under the Apache License, Version 2.0 or the MIT License.

---

## 3. AndroidX & Jetpack Components

- **Resolved Dependencies:**
  - `androidx.activity:activity-compose:1.13.0`
  - `androidx.annotation:annotation:1.10.0`
  - `androidx.core:core-ktx:1.18.0`
  - `androidx.compose.*` (BOM `2026.04.01`)
  - `androidx.lifecycle:lifecycle-runtime-compose:2.10.0`
  - `androidx.navigation:navigation-compose:2.9.7`
  - `androidx.work:work-runtime-ktx:2.10.0`
  - `androidx.security:security-crypto:1.1.0-alpha06`
- **Copyright:** Copyright (C) 2005-2026 The Android Open Source Project
- **License:** Apache License, Version 2.0

---

## 4. KotlinX Coroutines

- **Resolved Dependencies:**
  - `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1`
- **Copyright:** Copyright (C) 2016-2026 JetBrains s.r.o.
- **License:** Apache License, Version 2.0
