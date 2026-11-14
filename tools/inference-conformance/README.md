# Pinned Qwen inference comparison

This fixture compares real generated token IDs, not just whether a model loads.
It requires the llama.cpp gitlink checked out at
`bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3` and the official Qwen2.5-0.5B-Instruct
Q4_K_M GGUF. The [publisher's model card](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF)
labels this model Apache-2.0. Keep the weights outside Git.

## Acquire and verify the model

For example, in PowerShell, use a new task-owned directory with enough space:

```powershell
$modelDir = 'D:\AgentData\evidence\PrismLocal\my-run\models'
New-Item -ItemType Directory -Path $modelDir -Force | Out-Null
$partial = Join-Path $modelDir 'qwen2.5-0.5b-instruct-q4_k_m.gguf.part'
$model = Join-Path $modelDir 'qwen2.5-0.5b-instruct-q4_k_m.gguf'
if ((Test-Path -LiteralPath $partial) -or (Test-Path -LiteralPath $model)) { throw 'Choose an empty destination' }
curl.exe --fail --location --output $partial 'https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/12145bd1d629190a4d44254073650877954d02c9/qwen2.5-0.5b-instruct-q4_k_m.gguf'
if ($LASTEXITCODE -ne 0) { throw "Download failed: $LASTEXITCODE" }
$hash = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant()
if ($hash -ne '74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db') { throw "SHA-256 mismatch: $hash; partial file retained" }
Move-Item -LiteralPath $partial -Destination $model
```

## Run the Linux host reference

Run from the repository root. This example reuses the static CPU libraries from
the native host tests, so it also works when no shared llama libraries exist.
Choose a new task-owned scratch directory and set `MODEL` to the verified file:

```bash
build=/path/to/task-scratch/native-tests
libs=/path/to/task-scratch/probe-libs
cmake -S app/src/test/cpp -B "$build" -DCMAKE_BUILD_TYPE=Debug \
  -DPRISM_ENABLE_SANITIZERS=ON -DPRISM_REQUIRE_SANITIZERS=ON
cmake --build "$build" --parallel 2
ctest --test-dir "$build" --output-on-failure
mkdir -p "$libs"
cp "$build/llama.cpp/src/libllama.a" "$libs/"
cp "$build/llama.cpp/ggml/src/"libggml*.a "$libs/"
LLAMA_CPP_DIR="$PWD/app/src/main/cpp/third_party/llama.cpp" \
LLAMA_LIB_DIR="$libs" MODEL="$MODEL" \
  bash tools/inference-conformance/run-probe.sh
```

The script checks the exact ChatML prompt IDs, top token `785`, generated IDs,
first answer, and a teacher-forced second turn against the checked-in fixture.
It exits 77 if the model is absent and 1 if the fixture differs. Preserve its
stdout, stderr, exit code, model hash, source commit, compiler, and backend
observations. A host pass is not Android or physical ARM qualification.
