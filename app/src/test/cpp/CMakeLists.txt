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
        message(WARNING
            "PRISM_ENABLE_SANITIZERS=ON but this compiler supports neither "
            "-fsanitize=address nor -fsanitize=undefined; building without sanitizers.")
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
