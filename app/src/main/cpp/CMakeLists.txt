cmake_minimum_required(VERSION 3.22.1)
project(llmhost)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(LLMHOST_DEBUG_HOOKS "Enable DEBUG_MOCK_MODEL and DEBUG_SIMULATE_RING hooks" OFF)
option(LLMHOST_ENABLE_KLEIDIAI "Enable KleidiAI optimized ARM CPU kernels for arm64 builds" OFF)
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

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(llmhost SHARED
    llmhost_jni.cpp
    Engine.cpp
)

if(LLMHOST_DEBUG_HOOKS)
    target_compile_definitions(llmhost PRIVATE LLMHOST_DEBUG_HOOKS=1)
else()
    target_compile_definitions(llmhost PRIVATE LLMHOST_DEBUG_HOOKS=0)
endif()

target_include_directories(llmhost PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

target_link_libraries(llmhost PRIVATE llama ggml log android)

target_compile_options(llmhost PRIVATE
    $<$<CONFIG:Release>:-O3>
)

target_link_options(llmhost PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
