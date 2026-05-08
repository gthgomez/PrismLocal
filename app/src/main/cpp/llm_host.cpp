#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#define LOG_TAG "LlmHostNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint32_t kTokenCapacity = 1024;

enum class StreamState : uint32_t {
    Idle = 0,
    Generating = 1,
    CancelRequested = 2,
    Eof = 3,
    Cancelled = 4,
    Error = 5,
    Tombstoned = 6,
};

struct ControlBlock {
    std::atomic<uint32_t> state{static_cast<uint32_t>(StreamState::Idle)};
    std::atomic<uint32_t> generation_id{0};
    std::atomic<uint32_t> head{0};
    std::atomic<uint32_t> tail{0};
    std::atomic<uint32_t> capacity{kTokenCapacity};
    std::atomic<uint32_t> overflow{0};
    std::atomic<uint32_t> error_code{0};
};

size_t page_size() {
    static size_t cached = 0;
    if (cached == 0) {
        const long sys_page_size = sysconf(_SC_PAGESIZE);
        cached = sys_page_size > 0 ? static_cast<size_t>(sys_page_size) : 16384;
    }
    return cached;
}

struct StaticBuffers {
    void* ctrl_ptr = nullptr;
    void* token_ptr = nullptr;
    ControlBlock* control = nullptr;
    int32_t* tokens = nullptr;

    StaticBuffers() {
        const size_t size = page_size();
        if (posix_memalign(&ctrl_ptr, size, size) != 0 || ctrl_ptr == nullptr) {
            throw std::bad_alloc();
        }
        if (posix_memalign(&token_ptr, size, size * 2) != 0 || token_ptr == nullptr) {
            free(ctrl_ptr);
            ctrl_ptr = nullptr;
            throw std::bad_alloc();
        }
        std::memset(token_ptr, 0, size * 2);
        control = new (ctrl_ptr) ControlBlock();
        tokens = static_cast<int32_t*>(token_ptr);
    }

    ~StaticBuffers() {
        if (control != nullptr) {
            control->~ControlBlock();
        }
        free(ctrl_ptr);
        free(token_ptr);
    }
};

struct ModelRuntime {
    bool mock_model = false;
    std::string model_path;
};

struct GenerationSession {
    uint32_t generation_id = 0;
    std::thread worker;
    std::atomic<bool> cancel_requested{false};
    std::atomic<bool> eof_acknowledged{false};
    std::shared_ptr<ModelRuntime> runtime;

    ~GenerationSession() {
        if (worker.joinable()) {
            worker.join();
        }
    }
};

struct EngineHandle {
    std::mutex mu;
    StaticBuffers buffers;
    std::shared_ptr<ModelRuntime> active_runtime;
    std::shared_ptr<GenerationSession> active_session;
    std::atomic<int> memory_pressure_level{0};
    bool debug_hooks_enabled = false;
};

bool debug_hooks_allowed(const EngineHandle* engine) {
#if LLMHOST_DEBUG_HOOKS
    return engine != nullptr && engine->debug_hooks_enabled;
#else
    (void) engine;
    return false;
#endif
}

void clear_ring(ControlBlock* ctrl) {
    ctrl->head.store(0, std::memory_order_release);
    ctrl->tail.store(0, std::memory_order_release);
    ctrl->overflow.store(0, std::memory_order_release);
    ctrl->error_code.store(0, std::memory_order_release);
}

bool write_token(ControlBlock* ctrl, int32_t* tokens, int32_t token, const std::shared_ptr<GenerationSession>& session) {
    const uint32_t cap = ctrl->capacity.load(std::memory_order_acquire);
    while (!session->cancel_requested.load(std::memory_order_acquire)) {
        const uint32_t h = ctrl->head.load(std::memory_order_relaxed);
        const uint32_t t = ctrl->tail.load(std::memory_order_acquire);
        const uint32_t next = (h + 1) % cap;
        if (next == t) {
            ctrl->overflow.store(1, std::memory_order_release);
            std::this_thread::yield();
            continue;
        }
        tokens[h] = token;
        ctrl->head.store(next, std::memory_order_release);
        return true;
    }
    return false;
}

EngineHandle* to_engine(jlong handle) {
    return reinterpret_cast<EngineHandle*>(handle);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeCreateEngine(JNIEnv*, jclass, jboolean debug_hooks_enabled) {
    try {
        auto* engine = new EngineHandle();
        engine->debug_hooks_enabled = debug_hooks_enabled == JNI_TRUE;
        LOGI("Created LLM host engine. debug_hooks=%s", engine->debug_hooks_enabled ? "true" : "false");
        return reinterpret_cast<jlong>(engine);
    } catch (const std::bad_alloc&) {
        LOGE("Failed to allocate aligned static buffers.");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeDestroyEngine(JNIEnv*, jobject, jlong handle) {
    auto* engine = to_engine(handle);
    if (engine == nullptr) {
        return;
    }

    std::shared_ptr<GenerationSession> session_to_join;
    {
        std::lock_guard<std::mutex> lock(engine->mu);
        if (engine->active_session) {
            engine->active_session->cancel_requested.store(true, std::memory_order_release);
            session_to_join = engine->active_session;
        }
        engine->active_session.reset();
        engine->active_runtime.reset();
    }
    if (session_to_join && session_to_join->worker.joinable()) {
        session_to_join->worker.join();
    }
    delete engine;
    LOGI("Destroyed LLM host engine.");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeLoadModel(JNIEnv* env, jobject, jlong handle, jstring path) {
    auto* engine = to_engine(handle);
    if (engine == nullptr || path == nullptr) {
        return JNI_FALSE;
    }

    const char* c_path = env->GetStringUTFChars(path, nullptr);
    std::string path_str(c_path == nullptr ? "" : c_path);
    env->ReleaseStringUTFChars(path, c_path);

    std::lock_guard<std::mutex> lock(engine->mu);
    if (engine->active_session) {
        const uint32_t state = engine->buffers.control->state.load(std::memory_order_acquire);
        if (state != static_cast<uint32_t>(StreamState::Tombstoned) &&
            state != static_cast<uint32_t>(StreamState::Idle)) {
            return JNI_FALSE;
        }
    }

    #if LLMHOST_DEBUG_HOOKS
    if (path_str == "DEBUG_MOCK_MODEL") {
        if (!debug_hooks_allowed(engine)) {
            LOGW("DEBUG_MOCK_MODEL rejected because debug hooks are disabled.");
            return JNI_FALSE;
        }
        auto runtime = std::make_shared<ModelRuntime>();
        runtime->mock_model = true;
        engine->active_runtime = runtime;
        return JNI_TRUE;
    }
    #endif

    LOGW("Real GGUF loading is not linked because llama.cpp was not present in the doc packet.");
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeUnloadModel(JNIEnv*, jobject, jlong handle) {
    auto* engine = to_engine(handle);
    if (engine == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(engine->mu);
    if (engine->active_session) {
        engine->active_session->cancel_requested.store(true, std::memory_order_release);
    }
    engine->active_runtime.reset();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeStartGeneration(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring prompt,
    jint gen_id,
    jint max_tokens,
    jint thread_count) {
    (void) max_tokens;
    (void) thread_count;
    auto* engine = to_engine(handle);
    if (engine == nullptr || prompt == nullptr) {
        return -1;
    }

    std::shared_ptr<GenerationSession> old_session;
    std::shared_ptr<ModelRuntime> current_runtime;
    {
        std::lock_guard<std::mutex> lock(engine->mu);
        current_runtime = engine->active_runtime;
        old_session = engine->active_session;
        if (old_session) {
            old_session->cancel_requested.store(true, std::memory_order_release);
        }
    }
    if (old_session && old_session->worker.joinable()) {
        old_session->worker.join();
    }
    if (!current_runtime) {
        return -1;
    }

    const char* c_prompt = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(c_prompt == nullptr ? "" : c_prompt);
    env->ReleaseStringUTFChars(prompt, c_prompt);

    auto new_session = std::make_shared<GenerationSession>();
    new_session->generation_id = static_cast<uint32_t>(gen_id);
    new_session->runtime = current_runtime;

    {
        std::lock_guard<std::mutex> lock(engine->mu);
        engine->active_session = new_session;
        auto* ctrl = engine->buffers.control;
        ctrl->generation_id.store(static_cast<uint32_t>(gen_id), std::memory_order_release);
        clear_ring(ctrl);
        ctrl->state.store(static_cast<uint32_t>(StreamState::Generating), std::memory_order_release);
    }

    new_session->worker = std::thread([engine, new_session, prompt_str]() {
        auto* ctrl = engine->buffers.control;
        int32_t* tokens = engine->buffers.tokens;

        if (engine->memory_pressure_level.load(std::memory_order_acquire) >= 3) {
            new_session->cancel_requested.store(true, std::memory_order_release);
        }

        #if LLMHOST_DEBUG_HOOKS
        if (prompt_str == "DEBUG_SIMULATE_RING") {
            if (!debug_hooks_allowed(engine)) {
                ctrl->error_code.store(403, std::memory_order_release);
                ctrl->state.store(static_cast<uint32_t>(StreamState::Error), std::memory_order_release);
                return;
            }
            for (int i = 0; i < 1000; i++) {
                if (engine->memory_pressure_level.load(std::memory_order_acquire) >= 3) {
                    new_session->cancel_requested.store(true, std::memory_order_release);
                }
                if (!write_token(ctrl, tokens, 10000 + i, new_session)) {
                    break;
                }
                std::this_thread::sleep_for(std::chrono::microseconds(500));
            }
        } else {
        #endif
            ctrl->error_code.store(501, std::memory_order_release);
            ctrl->state.store(static_cast<uint32_t>(StreamState::Error), std::memory_order_release);
            LOGW("Real generation is not linked because llama.cpp was not present in the doc packet.");
            return;
        #if LLMHOST_DEBUG_HOOKS
        }
        #endif

        const StreamState final_state = new_session->cancel_requested.load(std::memory_order_acquire)
            ? StreamState::Cancelled
            : StreamState::Eof;
        ctrl->state.store(static_cast<uint32_t>(final_state), std::memory_order_release);
    });

    return gen_id;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeCancelGeneration(JNIEnv*, jobject, jlong handle, jint gen_id) {
    auto* engine = to_engine(handle);
    if (engine == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(engine->mu);
    if (engine->active_session && engine->active_session->generation_id == static_cast<uint32_t>(gen_id)) {
        engine->buffers.control->state.store(static_cast<uint32_t>(StreamState::CancelRequested), std::memory_order_release);
        engine->active_session->cancel_requested.store(true, std::memory_order_release);
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeDrainTokens(JNIEnv* env, jobject, jlong handle, jint gen_id, jint max_tokens) {
    auto* engine = to_engine(handle);
    if (engine == nullptr || max_tokens <= 0) {
        return env->NewIntArray(0);
    }

    auto* ctrl = engine->buffers.control;
    if (ctrl->generation_id.load(std::memory_order_acquire) != static_cast<uint32_t>(gen_id)) {
        return env->NewIntArray(0);
    }

    const uint32_t t = ctrl->tail.load(std::memory_order_acquire);
    const uint32_t h = ctrl->head.load(std::memory_order_acquire);
    const uint32_t cap = ctrl->capacity.load(std::memory_order_acquire);
    uint32_t count = h >= t ? h - t : cap - t + h;
    if (count > static_cast<uint32_t>(max_tokens)) {
        count = static_cast<uint32_t>(max_tokens);
    }
    if (count == 0) {
        return env->NewIntArray(0);
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(count));
    jint* elements = env->GetIntArrayElements(result, nullptr);
    if (elements == nullptr) {
        return env->NewIntArray(0);
    }
    for (uint32_t i = 0; i < count; i++) {
        elements[i] = engine->buffers.tokens[(t + i) % cap];
    }
    env->ReleaseIntArrayElements(result, elements, 0);
    ctrl->tail.store((t + count) % cap, std::memory_order_release);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeAckEof(JNIEnv*, jobject, jlong handle, jint gen_id) {
    auto* engine = to_engine(handle);
    if (engine == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(engine->mu);
    if (engine->active_session && engine->active_session->generation_id == static_cast<uint32_t>(gen_id)) {
        engine->active_session->eof_acknowledged.store(true, std::memory_order_release);
        engine->buffers.control->state.store(static_cast<uint32_t>(StreamState::Tombstoned), std::memory_order_release);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeDecodeTokens(JNIEnv* env, jobject, jlong handle, jint gen_id, jintArray tokens) {
    auto* engine = to_engine(handle);
    if (engine == nullptr || tokens == nullptr) {
        return env->NewStringUTF("");
    }
    auto* ctrl = engine->buffers.control;
    if (ctrl->generation_id.load(std::memory_order_acquire) != static_cast<uint32_t>(gen_id)) {
        return env->NewStringUTF("");
    }

    const jsize count = env->GetArrayLength(tokens);
    jint* c_tokens = env->GetIntArrayElements(tokens, nullptr);
    if (c_tokens == nullptr) {
        return env->NewStringUTF("");
    }

    std::string text;
    for (jsize i = 0; i < count; i++) {
        text += "T";
        text += std::to_string(c_tokens[i]);
        if (i + 1 < count) {
            text += " ";
        }
    }
    env->ReleaseIntArrayElements(tokens, c_tokens, JNI_ABORT);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeGetState(JNIEnv*, jobject, jlong handle, jint gen_id) {
    auto* engine = to_engine(handle);
    if (engine == nullptr) {
        return static_cast<jint>(StreamState::Tombstoned);
    }
    auto* ctrl = engine->buffers.control;
    if (ctrl->generation_id.load(std::memory_order_acquire) != static_cast<uint32_t>(gen_id)) {
        return static_cast<jint>(StreamState::Tombstoned);
    }
    return static_cast<jint>(ctrl->state.load(std::memory_order_acquire));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeSetMemoryPressure(JNIEnv*, jobject, jlong handle, jint level) {
    auto* engine = to_engine(handle);
    if (engine == nullptr) {
        return;
    }
    engine->memory_pressure_level.store(level, std::memory_order_release);
    if (level >= 3) {
        std::lock_guard<std::mutex> lock(engine->mu);
        if (engine->active_session) {
            LOGW("Critical memory pressure; cancelling generation.");
            engine->active_session->cancel_requested.store(true, std::memory_order_release);
        }
    }
}
