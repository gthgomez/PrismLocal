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

python3 - "$OUT" "$ROOT/tools/inference-conformance/fixtures/qwen2.5-0.5b-instruct-greedy.txt" <<'PY'
import pathlib, sys
out = pathlib.Path(sys.argv[1]).read_text()
fix = pathlib.Path(sys.argv[2]).read_text()
need = {}
for line in fix.splitlines():
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    need[key] = value
checks = {
    "prompt_tokens": f"prompt_tokens={need['prompt_tokens']} ",
    "prompt_ids": f"ids={need['prompt_ids']}",
    "top1_id": f"top_logits {need['top1_id']}:",
    "greedy_ids": f"ids={need['greedy_ids']}",
    "greedy_text": need["greedy_text"],
    "second_text": need["second_text"],
}
failed = False
for name, needle in checks.items():
    if needle not in out:
        print(f"MISSING {name}: {needle}", file=sys.stderr)
        failed = True
if "add_special=0" not in out:
    print("Qwen ChatML prompt unexpectedly requested a BOS token", file=sys.stderr)
    failed = True
sys.exit(1 if failed else 0)
PY

echo "greedy fixture matched"
