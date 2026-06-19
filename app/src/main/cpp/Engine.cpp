#include "Engine.hpp"

#include <android/log.h>
#include <unistd.h>

#include "llama.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <mutex>
#include <new>
#include <sstream>
#include <thread>

#define LOG_TAG "LlmHostNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace llmhost {
namespace {

constexpr uint32_t kTokenCapacity = 1024;
constexpr int kMinGeneratedTokens = 1;
constexpr int kDefaultGeneratedTokens = 128;
constexpr int kMaxGeneratedTokens = 512;
constexpr int kMinThreadCount = 1;
constexpr int kDefaultThreadCount = 6;
constexpr int kMaxThreadCount = 8;
constexpr int kMinContextLength = 512;
constexpr int kDefaultContextLength = 2048;
constexpr int kMaxContextLength = 8192;
constexpr int kMinBatchSize = 128;
constexpr int kDefaultBatchSize = 512;
constexpr int kMaxBatchSize = 2048;
constexpr float kDefaultTemperature = 0.70f;
constexpr int kDefaultTopK = 40;
constexpr float kDefaultTopP = 0.95f;
constexpr float kDefaultRepeatPenalty = 1.10f;
constexpr int kMaxGpuLayers = 99;
constexpr llama_seq_id kMainSequence = 0;
constexpr int kContextHeadroom = 8;

struct ControlBlock {
    std::atomic<uint32_t> state{static_cast<uint32_t>(StreamState::Idle)};
    std::atomic<uint32_t> generation_id{0};
    std::atomic<uint32_t> head{0};
    std::atomic<uint32_t> tail{0};
    std::atomic<uint32_t> capacity{kTokenCapacity};
    std::atomic<uint32_t> overflow{0};
    std::atomic<uint32_t> error_code{0};
};

size_t pageSize() {
    static size_t cached = 0;
    if (cached == 0) {
        const long sys_page_size = sysconf(_SC_PAGESIZE);
        cached = sys_page_size > 0 ? static_cast<size_t>(sys_page_size) : 16384;
    }
    return cached;
}

size_t alignUp(size_t value, size_t alignment) {
    return ((value + alignment - 1) / alignment) * alignment;
}

void llamaLogCallback(ggml_log_level level, const char* text, void*) {
    if (text == nullptr || text[0] == '\0') {
        return;
    }
    const int android_level = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR :
        level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN :
        level == GGML_LOG_LEVEL_INFO ? ANDROID_LOG_INFO :
        ANDROID_LOG_DEBUG;
    __android_log_print(android_level, "llama", "%s", text);
}

void ensureLlamaBackend() {
    static std::once_flag once;
    std::call_once(once, []() {
        llama_log_set(llamaLogCallback, nullptr);
        llama_backend_init();
        LOGI("llama_backend_init complete; page_size=%zu; llama_system_info=%s",
             pageSize(),
             llama_print_system_info());
    });
}

void clearRing(ControlBlock* ctrl) {
    ctrl->head.store(0, std::memory_order_release);
    ctrl->tail.store(0, std::memory_order_release);
    ctrl->overflow.store(0, std::memory_order_release);
    ctrl->error_code.store(0, std::memory_order_release);
}

bool writeToken(ControlBlock* ctrl, int32_t* tokens, int32_t token, const std::atomic<bool>& cancel_requested) {
    const uint32_t cap = ctrl->capacity.load(std::memory_order_acquire);
    while (!cancel_requested.load(std::memory_order_acquire)) {
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

GenerationConfig sanitizeGenerationConfig(GenerationConfig config, int fallback_threads) {
    config.max_tokens = std::clamp(config.max_tokens, kMinGeneratedTokens, kMaxGeneratedTokens);
    const int fallback = std::clamp(
        fallback_threads > 0 ? fallback_threads : kDefaultThreadCount,
        kMinThreadCount,
        kMaxThreadCount);
    if (config.thread_count <= 0) {
        config.thread_count = fallback;
    }
    config.thread_count = std::clamp(config.thread_count, kMinThreadCount, kMaxThreadCount);
    config.context_length = std::clamp(config.context_length, kMinContextLength, kMaxContextLength);
    config.batch_size = std::clamp(config.batch_size, kMinBatchSize, kMaxBatchSize);
    config.batch_size = std::min(config.batch_size, config.context_length);
    if (!std::isfinite(config.temperature)) {
        config.temperature = kDefaultTemperature;
    }
    config.temperature = std::clamp(config.temperature, 0.05f, 1.50f);
    config.top_k = std::clamp(config.top_k, 1, 100);
    if (!std::isfinite(config.top_p)) {
        config.top_p = kDefaultTopP;
    }
    config.top_p = std::clamp(config.top_p, 0.05f, 1.0f);
    if (!std::isfinite(config.repeat_penalty)) {
        config.repeat_penalty = kDefaultRepeatPenalty;
    }
    config.repeat_penalty = std::clamp(config.repeat_penalty, 1.0f, 1.50f);
    config.gpu_layers = std::clamp(config.gpu_layers, 0, kMaxGpuLayers);
    return config;
}

std::string formatPromptForGeneration(llama_model* model, const std::string& prompt) {
    const char* tmpl = model != nullptr ? llama_model_chat_template(model, nullptr) : nullptr;
    if (tmpl == nullptr || tmpl[0] == '\0') {
        return prompt;
    }

    llama_chat_message messages[] = {
        {"user", prompt.c_str()},
    };

    int32_t required = llama_chat_apply_template(tmpl, messages, 1, true, nullptr, 0);
    if (required > 0) {
        std::string formatted(static_cast<size_t>(required), '\0');
        int32_t actual = llama_chat_apply_template(
            tmpl,
            messages,
            1,
            true,
            formatted.data(),
            static_cast<int32_t>(formatted.size()));
        if (actual > static_cast<int32_t>(formatted.size())) {
            formatted.resize(static_cast<size_t>(actual));
            actual = llama_chat_apply_template(
                tmpl,
                messages,
                1,
                true,
                formatted.data(),
                static_cast<int32_t>(formatted.size()));
        }
        if (actual > 0 && actual <= static_cast<int32_t>(formatted.size())) {
            formatted.resize(static_cast<size_t>(actual));
            return formatted;
        }
    }

    const std::string template_text(tmpl);
    if (template_text.find("<|im_start|>") != std::string::npos) {
        return "<|im_start|>user\n" + prompt + "<|im_end|>\n<|im_start|>assistant\n";
    }

    LOGW("chat_template_apply_failed; falling back to raw prompt");
    return prompt;
}

struct StaticBuffers {
    void* ctrl_ptr = nullptr;
    void* token_ptr = nullptr;
    ControlBlock* control = nullptr;
    int32_t* tokens = nullptr;

    StaticBuffers() {
        const size_t alignment = pageSize();
        const size_t token_bytes = alignUp(sizeof(int32_t) * kTokenCapacity, alignment);
        if (posix_memalign(&ctrl_ptr, alignment, alignment) != 0 || ctrl_ptr == nullptr) {
            throw std::bad_alloc();
        }
        if (posix_memalign(&token_ptr, alignment, token_bytes) != 0 || token_ptr == nullptr) {
            free(ctrl_ptr);
            ctrl_ptr = nullptr;
            throw std::bad_alloc();
        }
        std::memset(token_ptr, 0, token_bytes);
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
    bool mmap_used = false;
    int thread_count = kDefaultThreadCount;
    int context_length = kDefaultContextLength;
    int batch_size = kDefaultBatchSize;
    int gpu_layers = 0;
    llama_pos current_position = 0;
    std::string model_path;
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    std::mutex decode_mu;

    ~ModelRuntime() {
        std::lock_guard<std::mutex> lock(decode_mu);
        if (ctx != nullptr) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model != nullptr) {
            llama_model_free(model);
            model = nullptr;
        }
    }
};

struct GenerationSession {
    uint32_t generation_id = 0;
    GenerationConfig config;
    std::thread worker;
    std::atomic<bool> cancel_requested{false};
    std::atomic<bool> eof_acknowledged{false};
    std::shared_ptr<ModelRuntime> runtime;

    ~GenerationSession() {
        if (worker.joinable()) {
            if (worker.get_id() == std::this_thread::get_id()) {
                worker.detach();
            } else {
                worker.join();
            }
        }
    }
};

bool isTerminal(uint32_t state) {
    return state == static_cast<uint32_t>(StreamState::Eof) ||
           state == static_cast<uint32_t>(StreamState::Cancelled) ||
           state == static_cast<uint32_t>(StreamState::Error) ||
           state == static_cast<uint32_t>(StreamState::MaxTokens);
}

void resetRuntimeContext(ModelRuntime& runtime, bool clear_data) {
    if (runtime.ctx != nullptr) {
        llama_memory_clear(llama_get_memory(runtime.ctx), clear_data);
    }
    runtime.current_position = 0;
}

bool shiftRuntimeContextIfNeeded(ModelRuntime& runtime, int required_tokens) {
    if (runtime.ctx == nullptr) {
        return false;
    }
    const llama_pos limit = static_cast<llama_pos>(runtime.context_length - kContextHeadroom);
    if (runtime.current_position + required_tokens < limit) {
        return true;
    }
    if (required_tokens >= limit) {
        LOGW("context_required_too_large required=%d limit=%d; clearing kv", required_tokens, static_cast<int>(limit));
        resetRuntimeContext(runtime, false);
        return required_tokens < limit;
    }

    const llama_pos discard = std::max<llama_pos>(1, runtime.current_position / 2);
    llama_memory_t memory = llama_get_memory(runtime.ctx);
    const bool removed = llama_memory_seq_rm(memory, kMainSequence, 0, discard);
    if (!removed) {
        LOGW("context_shift_partial_remove_failed; clearing kv");
        resetRuntimeContext(runtime, false);
        return required_tokens < limit;
    }
    llama_memory_seq_add(memory, kMainSequence, discard, runtime.current_position, -discard);
    runtime.current_position -= discard;
    LOGI("context_shift discard=%d current_position=%d required=%d",
         static_cast<int>(discard),
         static_cast<int>(runtime.current_position),
         required_tokens);
    return runtime.current_position + required_tokens < limit;
}

int decodeTokensAt(ModelRuntime& runtime, const llama_token* tokens, int32_t count, llama_pos start_pos) {
    if (count <= 0) {
        return 0;
    }
    llama_batch batch = llama_batch_init(count, 0, 1);
    batch.n_tokens = count;
    for (int32_t i = 0; i < count; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = start_pos + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = kMainSequence;
        batch.logits[i] = (i == count - 1) ? 1 : 0;
    }
    const int result = llama_decode(runtime.ctx, batch);
    llama_batch_free(batch);
    return result;
}

std::shared_ptr<ModelRuntime> makeMockRuntime() {
    auto runtime = std::make_shared<ModelRuntime>();
    runtime->mock_model = true;
    runtime->model_path = "DEBUG_MOCK_MODEL";
    return runtime;
}

std::shared_ptr<ModelRuntime> loadRealRuntime(const std::string& path, bool use_mmap, GenerationConfig requested_config) {
    ensureLlamaBackend();
    const GenerationConfig config = sanitizeGenerationConfig(requested_config, kDefaultThreadCount);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = config.gpu_layers;
    model_params.use_mmap = use_mmap;
    model_params.use_mlock = false;
    model_params.check_tensors = true;

    llama_model* model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        LOGW("model_load_failed path=%s mmap=%s", path.c_str(), use_mmap ? "true" : "false");
        return nullptr;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = config.context_length;
    ctx_params.n_batch = config.batch_size;
    ctx_params.n_ubatch = config.batch_size;
    ctx_params.n_threads = config.thread_count;
    ctx_params.n_threads_batch = ctx_params.n_threads;
    ctx_params.no_perf = false;

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        LOGE("context_create_failed path=%s", path.c_str());
        return nullptr;
    }

    auto runtime = std::make_shared<ModelRuntime>();
    runtime->mock_model = false;
    runtime->mmap_used = use_mmap;
    runtime->model_path = path;
    runtime->model = model;
    runtime->ctx = ctx;
    runtime->vocab = llama_model_get_vocab(model);
    runtime->thread_count = ctx_params.n_threads;
    runtime->context_length = ctx_params.n_ctx;
    runtime->batch_size = ctx_params.n_batch;
    runtime->gpu_layers = config.gpu_layers;
    LOGI("model_loaded path=%s mmap=%s n_ctx=%u n_batch=%u threads=%d gpu_layers=%d",
         path.c_str(),
         use_mmap ? "true" : "false",
         ctx_params.n_ctx,
         ctx_params.n_batch,
         ctx_params.n_threads,
         config.gpu_layers);
    return runtime;
}

} // namespace

struct Engine::Impl {
    explicit Impl(bool debug_hooks) : debug_hooks_enabled(debug_hooks) {
        LOGI("Created LLM host engine. debug_hooks=%s page_size=%zu",
             debug_hooks_enabled ? "true" : "false",
             pageSize());
    }

    ~Impl() {
        cancelAndJoinActiveSession();
        {
            std::lock_guard<std::mutex> lock(mu);
            active_runtime.reset();
            buffers.control->state.store(static_cast<uint32_t>(StreamState::Tombstoned), std::memory_order_release);
        }
        LOGI("engine_destroyed");
    }

    bool debugHooksAllowed() const {
#if LLMHOST_DEBUG_HOOKS
        return debug_hooks_enabled;
#else
        return false;
#endif
    }

    void cancelAndJoinActiveSession() {
        std::shared_ptr<GenerationSession> session_to_join;
        {
            std::lock_guard<std::mutex> lock(mu);
            if (active_session) {
                active_session->cancel_requested.store(true, std::memory_order_release);
                session_to_join = active_session;
                active_session.reset();
            }
        }
        if (session_to_join && session_to_join->worker.joinable()) {
            session_to_join->worker.join();
        }
    }

    void finishSession(const std::shared_ptr<GenerationSession>& session, StreamState final_state) {
        buffers.control->state.store(static_cast<uint32_t>(final_state), std::memory_order_release);
        const char* terminal = final_state == StreamState::Cancelled ? "CANCELLED" :
            final_state == StreamState::Error ? "ERROR" :
            final_state == StreamState::MaxTokens ? "MAX_TOKENS" :
            final_state == StreamState::Eof ? "EOF" : "UNKNOWN";
        LOGI("terminal=%s generation_id=%u", terminal, session->generation_id);
    }

    void runDebugGeneration(const std::shared_ptr<GenerationSession>& session, const std::string& prompt) {
#if LLMHOST_DEBUG_HOOKS
        auto* ctrl = buffers.control;
        int32_t* token_buffer = buffers.tokens;
        if (prompt == "DEBUG_SIMULATE_RING") {
            if (!debugHooksAllowed()) {
                ctrl->error_code.store(403, std::memory_order_release);
                finishSession(session, StreamState::Error);
                return;
            }
            for (int i = 0; i < 1000; i++) {
                if (memory_pressure_level.load(std::memory_order_acquire) >= 3) {
                    session->cancel_requested.store(true, std::memory_order_release);
                }
                if (!writeToken(ctrl, token_buffer, 10000 + i, session->cancel_requested)) {
                    break;
                }
                std::this_thread::sleep_for(std::chrono::microseconds(500));
            }
            finishSession(session, session->cancel_requested.load(std::memory_order_acquire)
                ? StreamState::Cancelled
                : StreamState::Eof);
            return;
        }
#else
        (void) session;
        (void) prompt;
#endif
        buffers.control->error_code.store(501, std::memory_order_release);
        LOGW("debug_generation_rejected prompt=%s", prompt.c_str());
        finishSession(session, StreamState::Error);
    }

    void runRealGeneration(const std::shared_ptr<GenerationSession>& session, const std::string& prompt) {
        auto runtime = session->runtime;
        auto* ctrl = buffers.control;

        if (!runtime || runtime->model == nullptr || runtime->ctx == nullptr || runtime->vocab == nullptr) {
            ctrl->error_code.store(404, std::memory_order_release);
            finishSession(session, StreamState::Error);
            return;
        }

        if (memory_pressure_level.load(std::memory_order_acquire) >= 3) {
            session->cancel_requested.store(true, std::memory_order_release);
        }

        std::lock_guard<std::mutex> decode_lock(runtime->decode_mu);
        if (session->cancel_requested.load(std::memory_order_acquire)) {
            finishSession(session, StreamState::Cancelled);
            return;
        }

        const GenerationConfig config = sanitizeGenerationConfig(session->config, runtime->thread_count);
        llama_set_n_threads(runtime->ctx, config.thread_count, config.thread_count);
        LOGI("generation_config generation_id=%u max_tokens=%d threads=%d n_ctx=%d n_batch=%d temp=%.2f top_k=%d top_p=%.2f repeat=%.2f kv_pos=%d",
             session->generation_id,
             config.max_tokens,
             config.thread_count,
             runtime->context_length,
             runtime->batch_size,
             config.temperature,
             config.top_k,
             config.top_p,
             config.repeat_penalty,
             static_cast<int>(runtime->current_position));

        llama_perf_context_reset(runtime->ctx);
        if (config.continue_from_context) {
            if (runtime->current_position <= 0) {
                ctrl->error_code.store(427, std::memory_order_release);
                LOGE("continue_failed_no_context generation_id=%u", session->generation_id);
                finishSession(session, StreamState::Error);
                return;
            }
            if (!shiftRuntimeContextIfNeeded(*runtime, config.max_tokens + kContextHeadroom)) {
                ctrl->error_code.store(426, std::memory_order_release);
                LOGE("continue_context_shift_failed n_ctx=%d current_position=%d required=%d",
                     runtime->context_length,
                     static_cast<int>(runtime->current_position),
                     config.max_tokens);
                finishSession(session, StreamState::Error);
                return;
            }
            LOGI("continue_from_context generation_id=%u kv_pos=%d",
                 session->generation_id,
                 static_cast<int>(runtime->current_position));
        } else {
            const std::string formatted_prompt = formatPromptForGeneration(runtime->model, prompt);
            LOGI("prompt_formatted generation_id=%u raw_bytes=%zu formatted_bytes=%zu",
                 session->generation_id,
                 prompt.size(),
                formatted_prompt.size());

            const bool add_special = runtime->current_position == 0;
            const int32_t token_count = -llama_tokenize(
                runtime->vocab,
                formatted_prompt.c_str(),
                static_cast<int32_t>(formatted_prompt.size()),
                nullptr,
                0,
                add_special,
                true);
            if (token_count <= 0) {
                ctrl->error_code.store(422, std::memory_order_release);
                LOGE("tokenize_size_failed count=%d", token_count);
                finishSession(session, StreamState::Error);
                return;
            }

            std::vector<llama_token> prompt_tokens(static_cast<size_t>(token_count));
            const int32_t actual_tokens = llama_tokenize(
                runtime->vocab,
                formatted_prompt.c_str(),
                static_cast<int32_t>(formatted_prompt.size()),
                prompt_tokens.data(),
                token_count,
                add_special,
                true);
            if (actual_tokens < 0) {
                ctrl->error_code.store(423, std::memory_order_release);
                LOGE("tokenize_failed rc=%d", actual_tokens);
                finishSession(session, StreamState::Error);
                return;
            }

            int32_t usable_prompt_tokens = actual_tokens;
            const int max_prompt_tokens = runtime->context_length - config.max_tokens - kContextHeadroom;
            if (usable_prompt_tokens > max_prompt_tokens) {
                if (max_prompt_tokens <= 0) {
                    ctrl->error_code.store(422, std::memory_order_release);
                    LOGE("context_too_small n_ctx=%d max_tokens=%d", runtime->context_length, config.max_tokens);
                    finishSession(session, StreamState::Error);
                    return;
                }
                const int32_t dropped = usable_prompt_tokens - max_prompt_tokens;
                prompt_tokens.erase(prompt_tokens.begin(), prompt_tokens.begin() + dropped);
                usable_prompt_tokens = static_cast<int32_t>(prompt_tokens.size());
                LOGW("prompt_truncated generation_id=%u dropped_tokens=%d kept_tokens=%d",
                     session->generation_id,
                     dropped,
                     usable_prompt_tokens);
            }
            if (!shiftRuntimeContextIfNeeded(*runtime, usable_prompt_tokens + config.max_tokens + kContextHeadroom)) {
                ctrl->error_code.store(426, std::memory_order_release);
                LOGE("context_shift_failed n_ctx=%d current_position=%d required=%d",
                     runtime->context_length,
                     static_cast<int>(runtime->current_position),
                     usable_prompt_tokens + config.max_tokens);
                finishSession(session, StreamState::Error);
                return;
            }

            if (session->cancel_requested.load(std::memory_order_acquire)) {
                finishSession(session, StreamState::Cancelled);
                return;
            }

            const llama_pos prompt_start = runtime->current_position;
            const int decode_result = decodeTokensAt(*runtime, prompt_tokens.data(), usable_prompt_tokens, prompt_start);
            if (decode_result != 0) {
                ctrl->error_code.store(static_cast<uint32_t>(5000 + std::abs(decode_result)), std::memory_order_release);
                LOGE("prompt_eval_failed rc=%d", decode_result);
                finishSession(session, StreamState::Error);
                return;
            }
            runtime->current_position += usable_prompt_tokens;
            LOGI("prompt_eval_done generation_id=%u prompt_tokens=%d kv_pos=%d",
                 session->generation_id,
                 usable_prompt_tokens,
                 static_cast<int>(runtime->current_position));
        }

        if (session->cancel_requested.load(std::memory_order_acquire) ||
            memory_pressure_level.load(std::memory_order_acquire) >= 3) {
            finishSession(session, StreamState::Cancelled);
            return;
        }

        auto* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (sampler == nullptr) {
            ctrl->error_code.store(424, std::memory_order_release);
            finishSession(session, StreamState::Error);
            return;
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, config.repeat_penalty, 0.0f, 0.0f));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(config.top_k));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(config.top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(config.temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        const auto generation_start = std::chrono::steady_clock::now();
        int generated_tokens = 0;
        bool stopped_by_eog = false;
        for (int i = 0; i < config.max_tokens; i++) {
            if (memory_pressure_level.load(std::memory_order_acquire) >= 3) {
                session->cancel_requested.store(true, std::memory_order_release);
            }
            if (session->cancel_requested.load(std::memory_order_acquire)) {
                llama_sampler_free(sampler);
                finishSession(session, StreamState::Cancelled);
                return;
            }

            llama_token token = llama_sampler_sample(sampler, runtime->ctx, -1);
            if (token == LLAMA_TOKEN_NULL) {
                llama_sampler_free(sampler);
                ctrl->error_code.store(425, std::memory_order_release);
                LOGE("sample_failed token=null");
                finishSession(session, StreamState::Error);
                return;
            }
            llama_sampler_accept(sampler, token);

            if (llama_vocab_is_eog(runtime->vocab, token)) {
                LOGI("token_eog id=%d generation_id=%u", static_cast<int>(token), session->generation_id);
                stopped_by_eog = true;
                break;
            }

            if (writeToken(ctrl, buffers.tokens, static_cast<int32_t>(token), session->cancel_requested)) {
                generated_tokens++;
            }
            if (session->cancel_requested.load(std::memory_order_acquire)) {
                llama_sampler_free(sampler);
                finishSession(session, StreamState::Cancelled);
                return;
            }

            const int next_decode_result = decodeTokensAt(*runtime, &token, 1, runtime->current_position);
            if (next_decode_result != 0) {
                llama_sampler_free(sampler);
                ctrl->error_code.store(static_cast<uint32_t>(5100 + std::abs(next_decode_result)), std::memory_order_release);
                LOGE("token_eval_failed rc=%d", next_decode_result);
                finishSession(session, StreamState::Error);
                return;
            }
            runtime->current_position += 1;
        }

        const auto generation_end = std::chrono::steady_clock::now();
        const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            generation_end - generation_start).count();
        const double tokens_per_second = elapsed_ms > 0
            ? static_cast<double>(generated_tokens) * 1000.0 / static_cast<double>(elapsed_ms)
            : 0.0;
        LOGI("generation_summary generation_id=%u tokens=%d elapsed_ms=%lld tokens_per_second=%.2f",
             session->generation_id,
             generated_tokens,
             static_cast<long long>(elapsed_ms),
             tokens_per_second);

        llama_sampler_free(sampler);
        const StreamState final_state = session->cancel_requested.load(std::memory_order_acquire)
            ? StreamState::Cancelled
            : (!stopped_by_eog && generated_tokens >= config.max_tokens ? StreamState::MaxTokens : StreamState::Eof);
        finishSession(session, final_state);
    }

    void runGeneration(const std::shared_ptr<GenerationSession>& session, std::string prompt) {
        if (session->runtime && session->runtime->mock_model) {
            runDebugGeneration(session, prompt);
        } else {
            runRealGeneration(session, prompt);
        }
    }

    std::mutex mu;
    StaticBuffers buffers;
    std::shared_ptr<ModelRuntime> active_runtime;
    std::shared_ptr<GenerationSession> active_session;
    std::atomic<int> memory_pressure_level{0};
    bool debug_hooks_enabled = false;
};

Engine::Engine(bool debug_hooks_enabled) : impl_(std::make_unique<Impl>(debug_hooks_enabled)) {}

Engine::~Engine() = default;

bool Engine::loadModel(const std::string& path, GenerationConfig config) {
    if (!impl_) {
        return false;
    }
    impl_->cancelAndJoinActiveSession();
    const GenerationConfig safe_config = sanitizeGenerationConfig(config, kDefaultThreadCount);

#if LLMHOST_DEBUG_HOOKS
    if (path == "DEBUG_MOCK_MODEL") {
        if (!impl_->debugHooksAllowed()) {
            LOGW("DEBUG_MOCK_MODEL rejected because debug hooks are disabled.");
            return false;
        }
        std::lock_guard<std::mutex> lock(impl_->mu);
        impl_->active_runtime = makeMockRuntime();
        impl_->active_runtime->thread_count = safe_config.thread_count;
        impl_->active_runtime->context_length = safe_config.context_length;
        impl_->active_runtime->batch_size = safe_config.batch_size;
        impl_->active_runtime->gpu_layers = safe_config.gpu_layers;
        clearRing(impl_->buffers.control);
        impl_->buffers.control->state.store(static_cast<uint32_t>(StreamState::Idle), std::memory_order_release);
        return true;
    }
#endif

    if (path.empty()) {
        return false;
    }

    auto runtime = loadRealRuntime(path, true, safe_config);
    if (!runtime) {
        LOGW("model_load_mmap_failed; retrying without mmap path=%s", path.c_str());
        runtime = loadRealRuntime(path, false, safe_config);
    }
    if (!runtime) {
        return false;
    }

    std::lock_guard<std::mutex> lock(impl_->mu);
    impl_->active_runtime = runtime;
    clearRing(impl_->buffers.control);
    impl_->buffers.control->state.store(static_cast<uint32_t>(StreamState::Idle), std::memory_order_release);
    return true;
}

void Engine::resetConversation() {
    if (!impl_) {
        return;
    }
    impl_->cancelAndJoinActiveSession();
    std::shared_ptr<ModelRuntime> runtime;
    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        runtime = impl_->active_runtime;
        clearRing(impl_->buffers.control);
        impl_->buffers.control->state.store(static_cast<uint32_t>(StreamState::Idle), std::memory_order_release);
    }
    if (!runtime || runtime->mock_model) {
        return;
    }
    std::lock_guard<std::mutex> decode_lock(runtime->decode_mu);
    resetRuntimeContext(*runtime, false);
    LOGI("conversation_reset path=%s", runtime->model_path.c_str());
}

void Engine::unloadModel() {
    if (!impl_) {
        return;
    }
    impl_->cancelAndJoinActiveSession();
    std::string previous_path;
    std::lock_guard<std::mutex> lock(impl_->mu);
    previous_path = impl_->active_runtime ? impl_->active_runtime->model_path : "<none>";
    impl_->active_runtime.reset();
    clearRing(impl_->buffers.control);
    impl_->buffers.control->state.store(static_cast<uint32_t>(StreamState::Idle), std::memory_order_release);
    LOGI("model_unloaded previous_path=%s", previous_path.c_str());
}

int Engine::startGeneration(const std::string& prompt, int generation_id, GenerationConfig config) {
    if (!impl_ || (prompt.empty() && !config.continue_from_context)) {
        return -1;
    }

    std::shared_ptr<GenerationSession> old_session;
    std::shared_ptr<ModelRuntime> runtime;
    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        runtime = impl_->active_runtime;
        old_session = impl_->active_session;
        if (old_session) {
            old_session->cancel_requested.store(true, std::memory_order_release);
        }
        impl_->active_session.reset();
    }
    if (old_session && old_session->worker.joinable()) {
        old_session->worker.join();
    }
    if (!runtime) {
        return -1;
    }

    auto session = std::make_shared<GenerationSession>();
    session->generation_id = static_cast<uint32_t>(generation_id);
    session->runtime = runtime;
    session->config = sanitizeGenerationConfig(config, runtime->thread_count);

    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        impl_->active_session = session;
        auto* ctrl = impl_->buffers.control;
        ctrl->generation_id.store(static_cast<uint32_t>(generation_id), std::memory_order_release);
        clearRing(ctrl);
        ctrl->state.store(static_cast<uint32_t>(StreamState::Generating), std::memory_order_release);
    }

    session->worker = std::thread([impl = impl_.get(), session, prompt]() {
        impl->runGeneration(session, prompt);
    });
    return generation_id;
}

std::string Engine::runBenchmark(GenerationConfig config, int prompt_tokens, int generation_tokens, int repetitions) {
    if (!impl_) {
        return "{}";
    }
    std::shared_ptr<ModelRuntime> runtime;
    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        runtime = impl_->active_runtime;
    }
    if (!runtime || runtime->mock_model || runtime->ctx == nullptr || runtime->vocab == nullptr) {
        return "{\"error\":\"model_not_loaded\"}";
    }

    const GenerationConfig safe_config = sanitizeGenerationConfig(config, runtime->thread_count);
    const int pp = std::clamp(prompt_tokens, 16, runtime->context_length / 2);
    const int tg = std::clamp(generation_tokens, 16, 512);
    const int nr = std::clamp(repetitions, 1, 5);
    const llama_token token = llama_vocab_bos(runtime->vocab);

    std::lock_guard<std::mutex> decode_lock(runtime->decode_mu);
    llama_set_n_threads(runtime->ctx, safe_config.thread_count, safe_config.thread_count);
    double prompt_ms_total = 0.0;
    double decode_ms_total = 0.0;
    int completed = 0;

    for (int rep = 0; rep < nr; rep++) {
        resetRuntimeContext(*runtime, false);
        std::vector<llama_token> prompt(static_cast<size_t>(pp), token);
        const auto prompt_start = std::chrono::steady_clock::now();
        const int prompt_rc = decodeTokensAt(*runtime, prompt.data(), pp, 0);
        const auto prompt_end = std::chrono::steady_clock::now();
        if (prompt_rc != 0) {
            LOGE("benchmark_prompt_failed rc=%d pp=%d rep=%d", prompt_rc, pp, rep);
            continue;
        }
        runtime->current_position = pp;
        prompt_ms_total += std::chrono::duration<double, std::milli>(prompt_end - prompt_start).count();

        const auto decode_start = std::chrono::steady_clock::now();
        for (int i = 0; i < tg; i++) {
            const int decode_rc = decodeTokensAt(*runtime, &token, 1, runtime->current_position);
            if (decode_rc != 0) {
                LOGE("benchmark_decode_failed rc=%d tg=%d rep=%d i=%d", decode_rc, tg, rep, i);
                resetRuntimeContext(*runtime, false);
                return "{\"error\":\"decode_failed\"}";
            }
            runtime->current_position += 1;
        }
        const auto decode_end = std::chrono::steady_clock::now();
        decode_ms_total += std::chrono::duration<double, std::milli>(decode_end - decode_start).count();
        completed++;
    }
    resetRuntimeContext(*runtime, false);

    if (completed <= 0 || prompt_ms_total <= 0.0 || decode_ms_total <= 0.0) {
        return "{\"error\":\"benchmark_failed\"}";
    }
    const double prompt_tps = static_cast<double>(pp * completed) * 1000.0 / prompt_ms_total;
    const double decode_tps = static_cast<double>(tg * completed) * 1000.0 / decode_ms_total;

    std::ostringstream out;
    out.setf(std::ios::fixed);
    out.precision(3);
    out << "{\"source\":\"native\",\"pp\":" << pp
        << ",\"tg\":" << tg
        << ",\"nr\":" << completed
        << ",\"prompt_ms\":" << prompt_ms_total
        << ",\"decode_ms\":" << decode_ms_total
        << ",\"prompt_tps\":" << prompt_tps
        << ",\"decode_tps\":" << decode_tps
        << ",\"thread_count\":" << safe_config.thread_count
        << ",\"context_length\":" << runtime->context_length
        << ",\"batch_size\":" << runtime->batch_size
        << ",\"gpu_layers\":" << runtime->gpu_layers
        << "}";
    return out.str();
}

void Engine::cancelGeneration(int generation_id) {
    if (!impl_) {
        return;
    }
    std::shared_ptr<GenerationSession> session_to_join;
    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        if (impl_->active_session && impl_->active_session->generation_id == static_cast<uint32_t>(generation_id)) {
            impl_->buffers.control->state.store(static_cast<uint32_t>(StreamState::CancelRequested), std::memory_order_release);
            impl_->active_session->cancel_requested.store(true, std::memory_order_release);
            clearRing(impl_->buffers.control);
            session_to_join = impl_->active_session;
            impl_->active_session.reset();
        }
    }
    if (session_to_join && session_to_join->worker.joinable()) {
        session_to_join->worker.join();
    }
}

std::vector<int32_t> Engine::drainTokens(int generation_id, int max_tokens) {
    std::vector<int32_t> result;
    if (!impl_ || max_tokens <= 0) {
        return result;
    }

    auto* ctrl = impl_->buffers.control;
    if (ctrl->generation_id.load(std::memory_order_acquire) != static_cast<uint32_t>(generation_id)) {
        return result;
    }

    const uint32_t t = ctrl->tail.load(std::memory_order_acquire);
    const uint32_t h = ctrl->head.load(std::memory_order_acquire);
    const uint32_t cap = ctrl->capacity.load(std::memory_order_acquire);
    uint32_t count = h >= t ? h - t : cap - t + h;
    count = std::min<uint32_t>(count, static_cast<uint32_t>(max_tokens));
    result.reserve(count);
    for (uint32_t i = 0; i < count; i++) {
        result.push_back(impl_->buffers.tokens[(t + i) % cap]);
    }
    ctrl->tail.store((t + count) % cap, std::memory_order_release);
    return result;
}

void Engine::ackEof(int generation_id) {
    if (!impl_) {
        return;
    }
    std::shared_ptr<GenerationSession> session_to_join;
    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        if (impl_->active_session && impl_->active_session->generation_id == static_cast<uint32_t>(generation_id)) {
            impl_->active_session->eof_acknowledged.store(true, std::memory_order_release);
            const uint32_t state = impl_->buffers.control->state.load(std::memory_order_acquire);
            if (isTerminal(state)) {
                impl_->buffers.control->state.store(static_cast<uint32_t>(StreamState::Tombstoned), std::memory_order_release);
                session_to_join = impl_->active_session;
                impl_->active_session.reset();
            }
        }
    }
    if (session_to_join && session_to_join->worker.joinable()) {
        session_to_join->worker.join();
    }
}

std::string Engine::decodeTokens(int generation_id, const std::vector<int32_t>& tokens) {
    if (!impl_ || tokens.empty()) {
        return "";
    }
    auto* ctrl = impl_->buffers.control;
    if (ctrl->generation_id.load(std::memory_order_acquire) != static_cast<uint32_t>(generation_id)) {
        return "";
    }

    std::shared_ptr<ModelRuntime> runtime;
    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        runtime = impl_->active_runtime;
    }
    if (!runtime) {
        return "";
    }
    if (runtime->mock_model) {
        std::string text;
        for (size_t i = 0; i < tokens.size(); i++) {
            text += "T";
            text += std::to_string(tokens[i]);
            if (i + 1 < tokens.size()) {
                text += " ";
            }
        }
        return text;
    }
    if (runtime->vocab == nullptr) {
        return "";
    }

    std::vector<llama_token> llama_tokens;
    llama_tokens.reserve(tokens.size());
    for (const int32_t token : tokens) {
        llama_tokens.push_back(static_cast<llama_token>(token));
    }

    int32_t needed = llama_detokenize(
        runtime->vocab,
        llama_tokens.data(),
        static_cast<int32_t>(llama_tokens.size()),
        nullptr,
        0,
        false,
        false);
    if (needed < 0) {
        needed = -needed;
    }
    if (needed <= 0) {
        return "";
    }
    std::string text(static_cast<size_t>(needed), '\0');
    const int32_t actual = llama_detokenize(
        runtime->vocab,
        llama_tokens.data(),
        static_cast<int32_t>(llama_tokens.size()),
        text.data(),
        needed,
        false,
        false);
    if (actual <= 0) {
        return "";
    }
    text.resize(static_cast<size_t>(actual));
    return text;
}

int Engine::getState(int generation_id) const {
    if (!impl_) {
        return static_cast<int>(StreamState::Tombstoned);
    }
    auto* ctrl = impl_->buffers.control;
    if (ctrl->generation_id.load(std::memory_order_acquire) != static_cast<uint32_t>(generation_id)) {
        return static_cast<int>(StreamState::Tombstoned);
    }
    return static_cast<int>(ctrl->state.load(std::memory_order_acquire));
}

void Engine::setMemoryPressure(int level) {
    if (!impl_) {
        return;
    }
    impl_->memory_pressure_level.store(level, std::memory_order_release);
    if (level >= 3) {
        std::lock_guard<std::mutex> lock(impl_->mu);
        if (impl_->active_session) {
            LOGW("memory_pressure_critical cancelling generation_id=%u", impl_->active_session->generation_id);
            impl_->active_session->cancel_requested.store(true, std::memory_order_release);
        }
    }
}

} // namespace llmhost
