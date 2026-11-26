#!/usr/bin/env bash
# Build and run the PrismLocal-loop probe against a pinned llama.cpp.
#
# Required:
#   LLAMA_CPP_DIR   source checkout at bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3
#   LLAMA_LIB_DIR   directory containing libllama.so and libggml*.so
#   MODEL           GGUF path. The checked-in fixture is for the Qwen2.5 0.5B
#                   Instruct Q4_K_M or Q8_0 files named in fixtures/.
#
# Exit 77 when MODEL is missing so a checkout without weights is a skip.
# Exit 1 when the greedy token ids or text differ from the fixture.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LLAMA_CPP_DIR="${LLAMA_CPP_DIR:-}"
LLAMA_LIB_DIR="${LLAMA_LIB_DIR:-}"
MODEL="${MODEL:-}"
CXX="${CXX:-g++}"

if [[ -z "$MODEL" || ! -f "$MODEL" ]]; then
  echo "SKIP: set MODEL to the Qwen2.5-0.5B-Instruct GGUF (Q4_K_M or Q8_0)" >&2
  exit 77
fi
if [[ -z "$LLAMA_CPP_DIR" || -z "$LLAMA_LIB_DIR" ]]; then
  echo "LLAMA_CPP_DIR and LLAMA_LIB_DIR are required" >&2
  exit 2
fi
if [[ "$(git -C "$LLAMA_CPP_DIR" rev-parse HEAD)" != "bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3" ]]; then
  echo "The probe requires the pinned llama.cpp revision" >&2
  exit 2
fi
model_sha="$(sha256sum -- "$MODEL")"
case "${model_sha%% *}" in
  74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db|ca59ca7f13d0e15a8cfa77bd17e65d24f6844b554a7b6c12e07a5f89ff76844e)
    echo "model_sha256=${model_sha%% *}" ;;
  *) echo "The model does not match a pinned Qwen fixture SHA-256" >&2; exit 2 ;;
esac

PROBE="$ROOT/tools/inference-conformance/prism_loop_probe.cpp"
BIN="${PROBE_BIN:-/tmp/prism_loop_probe}"
OUT="${PROBE_OUT:-/tmp/prism_loop_probe.out}"

"$CXX" -std=c++20 -O2 \
  -I"$LLAMA_CPP_DIR/include" \
  -I"$LLAMA_CPP_DIR/ggml/include" \
  -I"$ROOT/app/src/main/cpp" \
  "$PROBE" -o "$BIN" \
  -L"$LLAMA_LIB_DIR" -lllama -lggml -lggml-cpu -lggml-base -pthread \
  -Wl,-rpath,"$LLAMA_LIB_DIR"

"$BIN" --model "$MODEL" --threads 1 --n 12 --temp 0 --top-k 1 \
  --repeat 1 --freq 0 --presence 0 --seed 1 \
  --second-user "What is two plus two?" \
  >"$OUT"

echo "----- probe stdout -----"
cat "$OUT"

python3 "$ROOT/tools/inference-conformance/check_greedy_output.py" "$OUT"

echo "greedy fixture matched"
