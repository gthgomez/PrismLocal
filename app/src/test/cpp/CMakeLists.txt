cmake_minimum_required(VERSION 3.22)

project(prism_native_tests CXX)

# PIR-00 — portable host test harness for the header-only runtime helpers.
#
# This project is intentionally standalone: it does NOT link llama.cpp, does NOT
# require the Android NDK, and is NOT driven by Gradle. It compiles the plain
# C++ runtime headers (currently runtime/ConversationState.hpp) against the host
# toolchain so the KV-cache / prefix-reuse accounting can be exercised without a
# model or a device.

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(PRISM_ENABLE_SANITIZERS
       "Build the native tests with ASan/UBSan when the compiler supports them"
       OFF)
option(PRISM_REQUIRE_SANITIZERS
       "Fail CMake configuration if sanitizers are requested but unsupported"
       OFF)

# Directory that contains the production header-only helpers, so that
# `#include "runtime/ConversationState.hpp"` resolves.
set(PRISM_MAIN_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp")

include(CheckCXXCompilerFlag)

# Sanitizers must be present at link time too. `check_cxx_compiler_flag` puts the
# probed flag on the compile line but (on some CMake/compiler pairings) not on the
# link line, so a bare `-fsanitize=address` probe links a trivial program without
# libasan and reports a false negative. Mirror the flag into
# CMAKE_REQUIRED_LINK_OPTIONS so the probe links exactly like the real target.
set(PRISM_SANITIZER_FLAGS "")
set(PRISM_SANITIZERS_FOUND "")
if(PRISM_ENABLE_SANITIZERS)
    set(CMAKE_REQUIRED_LINK_OPTIONS "-fsanitize=address")
    check_cxx_compiler_flag("-fsanitize=address" PRISM_HAVE_ASAN)
    unset(CMAKE_REQUIRED_LINK_OPTIONS)

    set(CMAKE_REQUIRED_LINK_OPTIONS "-fsanitize=undefined")
    check_cxx_compiler_flag("-fsanitize=undefined" PRISM_HAVE_UBSAN)
    unset(CMAKE_REQUIRED_LINK_OPTIONS)

    if(PRISM_HAVE_ASAN)
        list(APPEND PRISM_SANITIZER_FLAGS "-fsanitize=address" "-fno-omit-frame-pointer")
        list(APPEND PRISM_SANITIZERS_FOUND "ASan")
    endif()
    if(PRISM_HAVE_UBSAN)
        list(APPEND PRISM_SANITIZER_FLAGS "-fsanitize=undefined" "-fno-omit-frame-pointer")
        list(APPEND PRISM_SANITIZERS_FOUND "UBSan")
    endif()

    if(PRISM_SANITIZERS_FOUND)
        list(JOIN PRISM_SANITIZERS_FOUND ", " PRISM_SANITIZERS_FOUND_STR)
        message(STATUS "PRISM: sanitizers enabled for native tests: ${PRISM_SANITIZERS_FOUND_STR}")
    else()
        if(PRISM_REQUIRE_SANITIZERS)
            message(FATAL_ERROR
                "PRISM_REQUIRE_SANITIZERS=ON but this compiler supports neither "
                "-fsanitize=address nor -fsanitize=undefined.")
        else()
            message(WARNING
                "PRISM_ENABLE_SANITIZERS=ON but this compiler supports neither "
                "-fsanitize=address nor -fsanitize=undefined; building without sanitizers.")
        endif()
    endif()
endif()

enable_testing()

function(prism_add_native_test target_name source_file)
    add_executable(${target_name} ${source_file})
    target_include_directories(${target_name} PRIVATE "${PRISM_MAIN_CPP_DIR}")
    target_compile_options(${target_name} PRIVATE -Wall -Wextra)
    if(PRISM_SANITIZER_FLAGS)
        target_compile_options(${target_name} PRIVATE ${PRISM_SANITIZER_FLAGS})
        target_link_options(${target_name} PRIVATE ${PRISM_SANITIZER_FLAGS})
    endif()
    add_test(NAME ${target_name} COMMAND ${target_name})
endfunction()

prism_add_native_test(harness_self_test harness_self_test.cpp)
prism_add_native_test(conversation_state_test conversation_state_test.cpp)
prism_add_native_test(stream_protocol_test stream/stream_protocol_test.cpp)
prism_add_native_test(drain_completion_test stream/drain_completion_test.cpp)
prism_add_native_test(context_replay_test context/context_replay_test.cpp)
prism_add_native_test(handle_registry_test handle_registry_test.cpp)
prism_add_native_test(native_error_code_test runtime/native_error_code_test.cpp)
prism_add_native_test(generation_lifecycle_gate_test runtime/generation_lifecycle_gate_test.cpp)
prism_add_native_test(generation_drain_ack_test runtime/generation_drain_ack_test.cpp)

# ---------------------------------------------------------------------------
# Engine mock-model qualification test.
#
# The tests above exercise only the header-only runtime helpers. This section
# goes further: it compiles the REAL production Engine.cpp (the same source
# Gradle compiles into libllmhost.so) against a host-built static llama.cpp,
# with LLMHOST_DEBUG_HOOKS=1. That activates the DEBUG_MOCK_MODEL /
# DEBUG_SIMULATE_RING paths, so the actual engine code — token ring,
# backpressure, cancellation, reset, memory-pressure handling, drain
# accounting, session lifecycle — executes on a plain Linux host WITHOUT a
# model, a device, an NDK, or Gradle.
#
# The mock path performs no llama decode: runDebugGeneration writes a
# deterministic token sequence into the real ring buffer with the real
# cancellation and accounting logic. Everything asserted is production code,
# not a simulation of it.
#
# Disable with -DPRISM_ENABLE_ENGINE_MOCK_TEST=OFF for a header-only run.
# ---------------------------------------------------------------------------
option(PRISM_ENABLE_ENGINE_MOCK_TEST
       "Build the real Engine.cpp against host llama.cpp and run its mock-model qualification tests"
       ON)

if(PRISM_ENABLE_ENGINE_MOCK_TEST)
    set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp/third_party/llama.cpp")

    # Same feature disables as the app's CMakeLists.txt: CPU-only, no Vulkan,
    # no OpenCL, no KleidiAI, no BLAS, no tests/tools/examples/server.
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_WEBUI OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
    set(GGML_STATIC ON CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
    set(GGML_BLAS OFF CACHE BOOL "" FORCE)
    set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

    add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

    find_package(Threads REQUIRED)

    add_executable(engine_mock_test
        engine/engine_mock_test.cpp
        "${PRISM_MAIN_CPP_DIR}/Engine.cpp"
    )
    target_include_directories(engine_mock_test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/host_shim" # android/log.h host shim
        "${PRISM_MAIN_CPP_DIR}"                      # Engine.hpp, runtime/, llama.h, common.h, sampling.h
        "${LLAMA_CPP_DIR}/include"
        "${LLAMA_CPP_DIR}/common"
        "${LLAMA_CPP_DIR}/ggml/include"
    )
    target_compile_definitions(engine_mock_test PRIVATE LLMHOST_DEBUG_HOOKS=1)
    target_compile_options(engine_mock_test PRIVATE -Wall -Wextra)
    target_link_libraries(engine_mock_test PRIVATE llama ggml Threads::Threads)
    add_test(NAME engine_mock_test COMMAND engine_mock_test)
endif()
