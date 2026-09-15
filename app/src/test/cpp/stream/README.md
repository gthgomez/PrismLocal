# PIR-02 — stream/terminal protocol host test

Host-only test for the portable token-pipe accounting in
[`app/src/main/cpp/runtime/StreamProtocol.hpp`](../../../main/cpp/runtime/StreamProtocol.hpp).

No gtest, no llama.cpp, no Android NDK, no Gradle. It compiles against the host
toolchain like the other `app/src/test/cpp` tests and exits non-zero on failure.

## What it covers

Deterministic (`std::mt19937 rng(0xC0FFEE)`) producer/consumer stress, all
scenarios running with a capacity-8 ring to force head/tail wrap:

1. Random produce/commit/drain interleaving with a slow consumer — the popped
   value sequence must equal the produced sequence exactly, and
   `produced == committed == drained` at the end.
2. A 200-token (>128) committed backlog followed by a terminal —
   `can_acknowledge_terminal()` is false until everything is drained, then true
   exactly once.
3. Ring wrap — repeated fill/drain verifies strict FIFO ordering and that
   full/empty pushes/pops are rejected without corruption.
4. Cancellation mid-drain — the terminal is not acknowledgeable while output
   remains, and no further produce/commit is accepted after the terminal.
5. Invariant guards — `validate()` holds throughout, and deliberately invalid
   calls (`on_commit(produced+1)`, negative counts, ...) are rejected with the
   state left valid.

## Build & run directly (no CMake)

```sh
g++ -std=c++20 -Wall -Wextra -I app/src/main/cpp \
    app/src/test/cpp/stream/stream_protocol_test.cpp -o /tmp/stream_protocol_test \
  && /tmp/stream_protocol_test
```

With sanitizers:

```sh
g++ -std=c++20 -Wall -Wextra -fsanitize=address -fsanitize=undefined \
    -fno-omit-frame-pointer -I app/src/main/cpp \
    app/src/test/cpp/stream/stream_protocol_test.cpp -o /tmp/stream_protocol_test_san \
  && /tmp/stream_protocol_test_san
```

## Wiring into CTest (integrator)

This directory intentionally does not carry a `CMakeLists.txt`. Add a target in
[`app/src/test/cpp/CMakeLists.txt`](../CMakeLists.txt) alongside the existing
registrations — the helper already puts `app/src/main/cpp` on the include path,
so `#include "runtime/StreamProtocol.hpp"` resolves:

```cmake
prism_add_native_test(stream_protocol_test stream/stream_protocol_test.cpp)
```
