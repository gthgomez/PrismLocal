// PIR-00 — host harness self-test.
//
// Reports the toolchain, the active C++ standard and whether ASan/UBSan are
// compiled into this translation unit, then fails (non-zero) if C++20 is not
// active. It links nothing but the C++ standard library: no llama.cpp, no
// Android, no Gradle.

#include <cstdio>

// ---------------------------------------------------------------------------
// Sanitizer detection. Clang exposes __has_feature; GCC defines
// __SANITIZE_ADDRESS__ / __SANITIZE_UNDEFINED__.
// ---------------------------------------------------------------------------
#if defined(__has_feature)
#  if __has_feature(address_sanitizer)
#    define PRISM_HAS_ASAN 1
#  endif
#  if __has_feature(undefined_behavior_sanitizer)
#    define PRISM_HAS_UBSAN 1
#  endif
#endif

#if defined(__SANITIZE_ADDRESS__) && !defined(PRISM_HAS_ASAN)
#  define PRISM_HAS_ASAN 1
#endif
#if defined(__SANITIZE_UNDEFINED__) && !defined(PRISM_HAS_UBSAN)
#  define PRISM_HAS_UBSAN 1
#endif

#ifndef PRISM_HAS_ASAN
#  define PRISM_HAS_ASAN 0
#endif
#ifndef PRISM_HAS_UBSAN
#  define PRISM_HAS_UBSAN 0
#endif

int main() {
#if defined(__clang__)
    std::printf("compiler    : clang %d.%d.%d\n",
                __clang_major__, __clang_minor__, __clang_patchlevel__);
#elif defined(__GNUC__)
    std::printf("compiler    : gcc %d.%d.%d\n",
                __GNUC__, __GNUC_MINOR__, __GNUC_PATCHLEVEL__);
#elif defined(_MSC_VER)
    std::printf("compiler    : msvc %d\n", _MSC_VER);
#else
    std::printf("compiler    : unknown\n");
#endif

    std::printf("__cplusplus : %ld\n", static_cast<long>(__cplusplus));
    std::printf("C++20 active: %s\n", __cplusplus >= 202002L ? "yes" : "NO");
    std::printf("ASan        : %s\n", PRISM_HAS_ASAN ? "available" : "not active");
    std::printf("UBSan       : %s\n", PRISM_HAS_UBSAN ? "available" : "not active");

    if (__cplusplus < 202002L) {
        std::fprintf(stderr,
                     "harness_self_test: FAIL — C++20 is required but __cplusplus=%ld\n",
                     static_cast<long>(__cplusplus));
        return 1;
    }

    std::printf("harness_self_test: OK (C++20 active)\n");
    return 0;
}
