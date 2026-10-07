#!/usr/bin/env bash
# Instrument the native host-test build.
#
# The CI log shows cc1plus SIGKILLed at ~97% of the llama.cpp sources, with a
# two-minute gap between the last build line and the kill. That is consistent
# with memory pressure but does not prove it. This records the evidence needed
# to decide, rather than assuming.
set -uo pipefail

BUILD_DIR="${1:-build/prism-native-tests}"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/ci-artifacts}"
mkdir -p "$ARTIFACT_DIR"

report() {
  local label="$1"
  {
    echo "=== $label ==="
    echo "utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "free_mb: $(free -m | awk '/^Mem:/{print $7}')"
    echo "total_mb: $(free -m | awk '/^Mem:/{print $2}')"
    echo "loadavg: $(cat /proc/loadavg)"
    echo "nproc: $(nproc)"
    if [ -f /sys/fs/cgroup/memory.max ]; then
      echo "cgroup_memory_max: $(cat /sys/fs/cgroup/memory.max)"
      echo "cgroup_memory_current: $(cat /sys/fs/cgroup/memory.current)"
      echo "cgroup_memory_events:"
      cat /sys/fs/cgroup/memory.events 2>/dev/null || echo "(unavailable)"
    fi
  } >> "$ARTIFACT_DIR/native-build-memory.log"
}

report "before-configure"

# cgroup memory.events oom_kill is the direct evidence of an OOM kill. Without
# it, a SIGKILL is just a SIGKILL.
if [ -f /sys/fs/cgroup/memory.events ]; then
  cp /sys/fs/cgroup/memory.events "$ARTIFACT_DIR/memory.events.before" 2>/dev/null || true
fi

report "before-build"

# --parallel is bounded explicitly. An unbounded parallel build against the full
# vendored llama.cpp graph is the leading suspect; capping it is safe regardless
# of whether it is the actual cause.
JOBS="${JOBS:-2}"
echo "building with --parallel $JOBS"
cmake --build "$BUILD_DIR" --parallel "$JOBS" 2>&1 | tee "$ARTIFACT_DIR/native-build.log"
build_status="${PIPESTATUS[0]}"

report "after-build"

if [ -f /sys/fs/cgroup/memory.events ]; then
  cp /sys/fs/cgroup/memory.events "$ARTIFACT_DIR/memory.events.after" 2>/dev/null || true
  echo "=== memory.events delta ==="
  diff "$ARTIFACT_DIR/memory.events.before" "$ARTIFACT_DIR/memory.events.after" \
    > "$ARTIFACT_DIR/memory.events.delta" 2>/dev/null || true
  cat "$ARTIFACT_DIR/memory.events.delta"
fi

echo "build_status=$build_status"
exit "$build_status"
