cmake_minimum_required(VERSION 3.22.1)
project(llmhost)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(LLMHOST_DEBUG_HOOKS "Enable DEBUG_MOCK_MODEL and DEBUG_SIMULATE_RING hooks" OFF)
option(LLMHOST_ENABLE_KLEIDIAI "Enable KleidiAI optimized ARM CPU kernels for arm64 builds" OFF)
option(LLMHOST_ENABLE_VULKAN "Enable ggml Vulkan GPU acceleration for Android builds" ON)
option(LLMHOST_ENABLE_OPENCL "Experimental: enable ggml OpenCL backend for Adreno-capable Android builds" OFF)
option(LLMHOST_ENABLE_OPENMP "Experimental only: request OpenMP for non-Android builds" OFF)

set(LLAMA_CPP_DIR ${CMAKE_CURRENT_LIST_DIR}/third_party/llama.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_WEBUI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_STATIC ON CACHE BOOL "" FORCE)
if(LLMHOST_ENABLE_OPENMP)
    message(WARNING "LLMHost: GGML_OPENMP is intentionally kept OFF for Android; llama.cpp Android guidance does not treat OpenMP as a supported Android dependency path.")
endif()
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(LLMHOST_ENABLE_VULKAN)
    if(NOT Vulkan_GLSLC_EXECUTABLE AND EXISTS "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc.exe")
        set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc.exe" CACHE FILEPATH "NDK glslc executable" FORCE)
    elseif(NOT Vulkan_GLSLC_EXECUTABLE AND EXISTS "${ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe")
        set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe" CACHE FILEPATH "NDK glslc executable" FORCE)
    elseif(NOT Vulkan_GLSLC_EXECUTABLE AND EXISTS "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc")
        set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc" CACHE FILEPATH "NDK glslc executable" FORCE)
    endif()
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN_INTEGER_DOT_GLSLC_SUPPORT OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN_COOPMAT_GLSLC_SUPPORT OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN_COOPMAT2_GLSLC_SUPPORT OFF CACHE BOOL "" FORCE)
    message(STATUS "LLMHost: Vulkan backend enabled with glslc=${Vulkan_GLSLC_EXECUTABLE}")
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    message(STATUS "LLMHost: Vulkan backend disabled")
endif()
if(LLMHOST_ENABLE_OPENCL)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    message(STATUS "LLMHost: OpenCL enabled with embedded Adreno kernels")
else()
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
    message(STATUS "LLMHost: OpenCL disabled")
endif()
if(LLMHOST_ENABLE_KLEIDIAI AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    message(STATUS "LLMHost: KleidiAI enabled for ${ANDROID_ABI}")
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    message(STATUS "LLMHost: KleidiAI disabled for ${ANDROID_ABI}")
endif()
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMIT "bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3" CACHE STRING "" FORCE)
set(LLAMA_INSTALL_VERSION "0.0.1" CACHE STRING "" FORCE)

include_directories(${CMAKE_CURRENT_LIST_DIR}/third_party)

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(llama_speculative STATIC
    ${LLAMA_CPP_DIR}/common/speculative.cpp
    ${LLAMA_CPP_DIR}/common/sampling.cpp
    ${LLAMA_CPP_DIR}/common/common.cpp
    ${LLAMA_CPP_DIR}/common/log.cpp
    ${LLAMA_CPP_DIR}/common/ngram-cache.cpp
    ${LLAMA_CPP_DIR}/common/ngram-map.cpp
    ${LLAMA_CPP_DIR}/common/ngram-mod.cpp
    ${LLAMA_CPP_DIR}/common/reasoning-budget.cpp
    ${LLAMA_CPP_DIR}/common/unicode.cpp
    ${LLAMA_CPP_DIR}/common/fit.cpp
    ${CMAKE_CURRENT_LIST_DIR}/build-info.cpp
)
target_include_directories(llama_speculative PUBLIC
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)
target_link_libraries(llama_speculative PUBLIC llama ggml)
target_compile_options(llama_speculative PRIVATE -Wno-cast-qual)

add_library(llmhost SHARED
    llmhost_jni.cpp
    Engine.cpp
)

if(LLMHOST_DEBUG_HOOKS)
    target_compile_definitions(llmhost PRIVATE LLMHOST_DEBUG_HOOKS=1)
else()
    target_compile_definitions(llmhost PRIVATE LLMHOST_DEBUG_HOOKS=0)
endif()

if(GGML_CPU_KLEIDIAI)
    target_compile_definitions(llmhost PRIVATE LLMHOST_KLEIDIAI_ENABLED=1)
endif()

if(GGML_VULKAN)
    target_compile_definitions(llmhost PRIVATE LLMHOST_VULKAN_ENABLED=1)
endif()

if(GGML_OPENCL)
    target_compile_definitions(llmhost PRIVATE LLMHOST_OPENCL_ENABLED=1)
endif()

target_include_directories(llmhost PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
    ${LLAMA_CPP_DIR}/common
)

target_link_libraries(llmhost PRIVATE llama ggml llama_speculative log android vulkan)

target_compile_options(llmhost PRIVATE
    $<$<CONFIG:Release>:-O3>
)

target_link_options(llmhost PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
