#pragma once

// Host shim for <android/log.h>.
//
// Engine.cpp is written against the Android logging macros
// (__android_log_print with ANDROID_LOG_* priorities). To compile the REAL
// Engine.cpp on a plain Linux host toolchain — so the mock-model generation,
// cancellation, reset and memory-pressure paths can be executed without a
// device or a model — this header provides an equivalent inline
// implementation that forwards to stderr. It is only used by the host test
// harness; the Android build never sees this file.

#include <cstdarg>
#include <cstdio>

enum AndroidLogPriority {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT,
};

inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    (void) prio;
    std::fprintf(stderr, "[%s] ", tag);
    va_list ap;
    va_start(ap, fmt);
    std::vfprintf(stderr, fmt, ap);
    va_end(ap);
    std::fputc('\n', stderr);
    return 0;
}
