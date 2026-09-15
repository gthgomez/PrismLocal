#include "Engine.hpp"

#include <android/log.h>
#include <unistd.h>

#include "llama.h"
#include "common.h"
#include "sampling.h"
#include "runtime/ConversationState.hpp" // PIR-05

#include <algorithm>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <mutex>
#include <new>
#include <sstream>
#include <thread>
#include <vector>

#define LOG_TAG "LlmHostNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace llmhost {
namespace {

constexpr uint32_t kTokenCapacity = 2048;
constexpr int kMinGeneratedTokens = 1;
constexpr int kDefaultGeneratedTokens = 128;
constexpr int kMaxGeneratedTokens = 1024;
constexpr int kMinThreadCount = 1;
constexpr int kDefaultThreadCount = 4;
constexpr int kMaxThreadCount = 8;
constexpr int kMinContextLength = 512;
constexpr int kDefaultContextLength = 2048;
constexpr int kMaxContextLength = 16384;
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

ggml_type parseGgmlType(const std::string& type_str) {
    if (type_str == "f16") return GGML_TYPE_F16;
    if (type_str == "q4_0") return GGML_TYPE_Q4_0;
    return GGML_TYPE_Q8_0;
}

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
    int overflow_spins = 0;
    while (!cancel_requested.load(std::memory_order_acquire)) {
        const uint32_t h = ctrl->head.load(std::memory_order_relaxed);
        const uint32_t t = ctrl->tail.load(std::memory_order_acquire);
        const uint32_t next = (h + 1) % cap;
        if (next == t) {
            ctrl->overflow.store(1, std::memory_order_release);
            // Exponential backoff from 100us to 1ms to avoid CPU spin
            const int backoff_us = std::min(1000, 100 * (1 << std::min(overflow_spins, 4)));
            std::this_thread::sleep_for(std::chrono::microseconds(backoff_us));
            overflow_spins++;
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
    if (prompt.empty()) {
        return prompt;
    }

    // Prevent double-wrapping if prompt is already formatted with real chat-
    // template markers. Do NOT match bare "User:"/"Assistant:" prose here:
    // the app's own transcript formatting prefixes every history line that
    // way, which previously disabled templating for all multi-turn chats.
    if (prompt.find("<|im_start|>") != std::string::npos ||
        prompt.find("<|start_header_id|>") != std::string::npos ||
        prompt.find("[INST]") != std::string::npos ||
        prompt.find("<|system|>") != std::string::npos ||
        prompt.find("<|user|>") != std::string::npos) {
        LOGI("prompt_already_formatted length=%zu", prompt.size());
        return prompt;
    }

    const char* tmpl = model != nullptr ? llama_model_chat_template(model, nullptr) : nullptr;
    if (tmpl == nullptr || tmpl[0] == '\0') {
        return prompt;
    }

    llama_chat_message messages[] = {
        {"user", prompt.c_str()},
    };

    int32_t required = llama_chat_apply_template(tmpl, messages, 1, true, nullptr, 0);
    if (required > 0) {
        std::string formatted(static_cast<size_t>(required) + 32, '\0');
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

/**
 * Generic role-tagged rendering used when a model has no usable chat template or
 * the template fails to render. Keeps the conversation content available instead
 * of returning an empty prompt (which would tokenize to zero tokens and error).
 */
std::string proseFallbackFromMessages(const std::vector<ChatMessage>& messages) {
    std::string out;
    for (const auto& message : messages) {
        out += message.role;
        out += ": ";
        out += message.content;
        out += '\n';
    }
    out += "assistant:";
    return out;
}

std::string formatMessagesForGeneration(llama_model* model, const std::vector<ChatMessage>& messages, const std::string& fallback_prompt) {
    if (messages.empty()) {
        return fallback_prompt;
    }
    if (model == nullptr) {
        return proseFallbackFromMessages(messages);
    }

    const char* tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr || tmpl[0] == '\0') {
        LOGW("chat_template_missing_for_messages messages=%zu; using prose fallback", messages.size());
        return proseFallbackFromMessages(messages);
    }

    // llama_chat_message borrows role/content pointers; keep `messages` alive.
    std::vector<llama_chat_message> chat;
    chat.reserve(messages.size());
    for (const auto& message : messages) {
        chat.push_back({message.role.c_str(), message.content.c_str()});
    }

    const int32_t message_count = static_cast<int32_t>(chat.size());
    int32_t required = llama_chat_apply_template(tmpl, chat.data(), message_count, true, nullptr, 0);
    if (required <= 0) {
        LOGW("chat_template_messages_apply_failed rc=%d messages=%zu", required, messages.size());
        return proseFallbackFromMessages(messages);
    }

    std::string formatted(static_cast<size_t>(required) + 32, '\0');
    int32_t actual = llama_chat_apply_template(
        tmpl,
        chat.data(),
        message_count,
        true,
        formatted.data(),
        static_cast<int32_t>(formatted.size()));
    if (actual > static_cast<int32_t>(formatted.size())) {
        formatted.resize(static_cast<size_t>(actual));
        actual = llama_chat_apply_template(
            tmpl,
            chat.data(),
            message_count,
            true,
            formatted.data(),
            static_cast<int32_t>(formatted.size()));
    }
    if (actual <= 0 || actual > static_cast<int32_t>(formatted.size())) {
        LOGW("chat_template_messages_apply_failed rc=%d messages=%zu", actual, messages.size());
        return proseFallbackFromMessages(messages);
    }

    formatted.resize(static_cast<size_t>(actual));
    LOGI("chat_template_messages_applied messages=%zu bytes=%zu", messages.size(), formatted.size());
    return formatted;
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

struct ModelRuntime;
void resetRuntimeContext(ModelRuntime& runtime, bool clear_data);

struct ModelRuntime {
    bool mock_model = false;
    bool mmap_used = false;
    int thread_count = kDefaultThreadCount;
    int context_length = kDefaultContextLength;
    int batch_size = kDefaultBatchSize;
    int gpu_layers = 0;             // verified offloaded layer count (0 if GPU init failed at runtime)
    std::string actual_backend_name = "CPU"; // set at load time from verified runtime state
    llama_pos current_position = 0;
    int system_prefix_length = 0;
    std::string model_path;
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    std::mutex decode_mu;

    bool single_batch_initialized = false;
    llama_batch single_batch;

    // Owns the current generation's sampler chain solely for destruction.
    // The chain is never reused across generations (see the rebuild site below).
    llama_sampler* owned_sampler = nullptr;

    std::vector<llama_token> active_tokens;

    // PIR-05: KV layout recorded from the context params that succeeded (after
    // any F16/defaults fallback). type_k/type_v are accurate for attention KV;
    // recurrent/hybrid architectures keep a separate FP32 state regardless.
    // flash_attn records the requested mode, not llama.cpp's internally resolved
    // AUTO value. All three are constant for a runtime, so they cannot by
    // themselves cause stale reuse; they only make the cache-identity log an
    // under-report of the resolved FlashAttention setting.
    std::string kv_type_k = "f16";
    std::string kv_type_v = "f16";
    bool flash_attn = false;

    // PIR-05: transactional prefix-cache bookkeeping (see ConversationState.hpp).
    ConversationState conversation;

    // Dynamic LoRA Adapters
    std::vector<llama_adapter_lora*> loaded_loras;

    ~ModelRuntime() {
        std::lock_guard<std::mutex> lock(decode_mu);
        if (single_batch_initialized) {
            llama_batch_free(single_batch);
            single_batch_initialized = false;
        }
        if (owned_sampler != nullptr) {
            llama_sampler_free(owned_sampler);
            owned_sampler = nullptr;
        }
        if (ctx != nullptr) {
            if (!loaded_loras.empty()) {
                llama_set_adapters_lora(ctx, nullptr, 0, nullptr);
            }
            llama_free(ctx);
            ctx = nullptr;
        }
        for (auto* adapter : loaded_loras) {
            if (adapter != nullptr) {
                llama_adapter_lora_free(adapter);
            }
        }
        loaded_loras.clear();
        if (model != nullptr) {
            llama_model_free(model);
            model = nullptr;
        }
    }

    bool applyLoraAdapters(const std::vector<LoraAdapterSpec>& specs) {
        std::lock_guard<std::mutex> lock(decode_mu);
        if (mock_model) {
            loaded_loras.clear();
            return true;
        }
        if (model == nullptr || ctx == nullptr) {
            LOGE("Cannot apply LoRA adapters: model or context is null");
            return false;
        }

        // Unbind current adapters from context
        llama_set_adapters_lora(ctx, nullptr, 0, nullptr);

        // Free previously loaded adapters
        for (auto* adapter : loaded_loras) {
            if (adapter != nullptr) {
                llama_adapter_lora_free(adapter);
            }
        }
        loaded_loras.clear();

        if (specs.empty()) {
            resetRuntimeContext(*this, false);
            LOGI("Cleared all LoRA adapters and reset KV cache");
            return true;
        }

        // Enforce maximum active adapters constraint (max 3)
        size_t count = std::min(specs.size(), static_cast<size_t>(3));
        std::vector<llama_adapter_lora*> new_adapters;
        std::vector<float> new_scales;
        new_adapters.reserve(count);
        new_scales.reserve(count);

        for (size_t i = 0; i < count; ++i) {
            const auto& spec = specs[i];
            if (spec.path.empty()) continue;
            LOGI("Loading LoRA adapter: path=%s scale=%.2f", spec.path.c_str(), spec.scale);
            llama_adapter_lora* adapter = llama_adapter_lora_init(model, spec.path.c_str());
            if (adapter == nullptr) {
                LOGW("Failed to load LoRA adapter at path: %s", spec.path.c_str());
                continue;
            }
            new_adapters.push_back(adapter);
            new_scales.push_back(spec.scale);
        }

        if (new_adapters.empty()) {
            LOGW("No valid LoRA adapters loaded from provided specs");
            resetRuntimeContext(*this, false);
            return false;
        }

        int32_t res = llama_set_adapters_lora(ctx, new_adapters.data(), new_adapters.size(), new_scales.data());
        if (res != 0) {
            LOGE("llama_set_adapters_lora failed with error code %d", res);
            for (auto* adapter : new_adapters) {
                llama_adapter_lora_free(adapter);
            }
            return false;
        }

        loaded_loras = std::move(new_adapters);

        // Purge KV cache so new adapter weights take effect cleanly
        resetRuntimeContext(*this, false);

        LOGI("Successfully applied %zu LoRA adapters to context and reset KV cache", loaded_loras.size());
        return true;
    }
};

struct GenerationSession {
    uint32_t generation_id = 0;
    GenerationConfig config;
    std::vector<ChatMessage> messages;
    std::thread worker;
    std::atomic<bool> cancel_requested{false};
    std::atomic<bool> eof_acknowledged{false};
    std::shared_ptr<ModelRuntime> runtime;
    std::atomic<int> prompt_tokens{0};
    std::atomic<int64_t> ttft_ms{0};
    std::atomic<float> tokens_per_sec{0.0f};
    std::atomic<int> active_threads{0};

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

int findSystemPrefixLength(const std::vector<llama_token>& tokens, const llama_vocab* vocab) {
    if (vocab == nullptr || tokens.empty()) return 0;

    std::string accumulated;
    std::vector<size_t> token_end_offsets;
    token_end_offsets.reserve(std::min(tokens.size(), static_cast<size_t>(512)));

    const size_t max_tokens_to_check = std::min(tokens.size(), static_cast<size_t>(512));
    for (size_t i = 0; i < max_tokens_to_check; i++) {
        char buf[256];
        int32_t len = llama_token_to_piece(vocab, tokens[i], buf, sizeof(buf) - 1, 0, true);
        if (len < 0 || len > static_cast<int32_t>(sizeof(buf) - 1)) {
            return 0;
        }
        if (len > 0) {
            accumulated.append(buf, static_cast<size_t>(len));
        }
        token_end_offsets.push_back(accumulated.size());
    }

    std::string closing_tag;
    const std::string chatml_system_prefix = "<|im_start|>system";
    const bool has_chatml_system_role =
        accumulated.rfind(chatml_system_prefix, 0) == 0 &&
        accumulated.size() > chatml_system_prefix.size() &&
        (accumulated[chatml_system_prefix.size()] == '\n' ||
         accumulated[chatml_system_prefix.size()] == '\r' ||
         accumulated[chatml_system_prefix.size()] == ' ' ||
         accumulated[chatml_system_prefix.size()] == '\t');

    if (has_chatml_system_role) {
        closing_tag = "<|im_end|>";
    } else if (accumulated.rfind("<|start_header_id|>system<|end_header_id|>", 0) == 0) {
        closing_tag = "<|eot_id|>";
    } else if (accumulated.rfind("<system>", 0) == 0) {
        closing_tag = "</system>";
    }

    if (closing_tag.empty()) {
        return 0;
    }

    const size_t end_tag = accumulated.find(closing_tag);
    if (end_tag == std::string::npos) {
        return 0;
    }
    const size_t system_end_pos = end_tag + closing_tag.size();

    for (size_t i = 0; i < token_end_offsets.size(); i++) {
        if (token_end_offsets[i] >= system_end_pos) {
            return static_cast<int>(i + 1);
        }
    }

    return 0;
}

// PIR-05: build the identity under which the current cache was produced. The
// chat template participates because a template change alters every rendered
// token; the actual KV element types/FlashAttn participate because they alter
// the cached key/value representation.
CacheIdentity makeCacheIdentity(const ModelRuntime& runtime) {
    CacheIdentity identity;
    identity.model_path = runtime.model_path;
    if (runtime.model != nullptr) {
        const char* tmpl = llama_model_chat_template(runtime.model, nullptr);
        if (tmpl != nullptr) {
            identity.chat_template = tmpl;
        }
    }
    identity.context_length = runtime.context_length;
    identity.kv_type_k = runtime.kv_type_k;
    identity.kv_type_v = runtime.kv_type_v;
    identity.flash_attn = runtime.flash_attn;
    return identity;
}

// PIR-05: explicit, clamped system-prefix boundary. The detection heuristic is
// unchanged, but it is only ever applied to a prompt that is about to be fully
// evaluated, and the result is clamped to the token count that will actually be
// committed so a partial decode can never leave an out-of-range prefix that a
// later shift would trust.
int computeSystemPrefixLength(const std::vector<llama_token>& tokens, std::size_t limit,
                              const llama_vocab* vocab) {
    if (limit == 0) {
        return 0;
    }
    const int detected = findSystemPrefixLength(tokens, vocab);
    return static_cast<int>(std::min(static_cast<std::size_t>(detected), limit));
}

// PIR-05: single cache-identity log line. Intentionally omits the raw template
// body (it is long and may contain user content); its byte length is enough to
// tell identities apart in logs.
void logCacheIdentity(const char* event, const CacheIdentity& identity,
                      const ConversationState& state) {
    LOGI("cache_identity event=%s model=%s ctx=%d kv_k=%s kv_v=%s fa=%d template_bytes=%zu valid=%s reusable=%zu last_logits=%s",
         event,
         identity.model_path.c_str(),
         identity.context_length,
         identity.kv_type_k.c_str(),
         identity.kv_type_v.c_str(),
         identity.flash_attn ? 1 : 0,
         identity.chat_template.size(),
         state.valid ? "true" : "false",
         state.reusable_tokens,
         state.last_logits_valid ? "true" : "false");
}

void resetRuntimeContext(ModelRuntime& runtime, bool clear_data) {
    if (runtime.ctx != nullptr) {
        llama_memory_clear(llama_get_memory(runtime.ctx), clear_data);
    }
    runtime.current_position = 0;
    runtime.active_tokens.clear();
    runtime.system_prefix_length = 0;
    // PIR-05: a cleared KV cache can never back a reusable prefix.
    runtime.conversation.invalidate();
}

// PIR-05: transactionally reduce the committed state to `common_prefix` tokens
// (an index into active_tokens). On entry the conversation must be valid, i.e.
// the KV cache provably holds [0, active_tokens.size()).
//
// - `common_prefix >= active_tokens.size()`: no sequence operation is needed and
//   the existing logits are left intact.
// - a tail must be dropped: llama_memory_seq_rm is return-checked BEFORE the
//   token/position state is mutated. On rejection the cache is reset and
//   `common_prefix` is set to 0 so the caller replays the whole prompt.
//
// Removing a tail invalidates the logits that used to match active_tokens.back().
void shrinkCommittedPrefix(ModelRuntime& runtime, const std::string& identity,
                           std::size_t& common_prefix) {
    if (common_prefix == 0) {
        resetRuntimeContext(runtime, false);
        return;
    }

    const std::size_t committed = runtime.active_tokens.size();
    if (common_prefix >= committed) {
        common_prefix = committed;
        runtime.current_position = static_cast<llama_pos>(committed);
        runtime.conversation.markCommitted(identity, committed, runtime.conversation.last_logits_valid);
        return;
    }

    llama_memory_t memory = llama_get_memory(runtime.ctx);
    const bool rm_ok = llama_memory_seq_rm(memory, kMainSequence, static_cast<llama_pos>(common_prefix), -1);
    if (!rm_ok) {
        // Do not evaluate a suffix against a stale prefix: invalidate + replay.
        resetRuntimeContext(runtime, false);
        common_prefix = 0;
        return;
    }
    runtime.active_tokens.resize(common_prefix);
    runtime.current_position = static_cast<llama_pos>(common_prefix);
    runtime.conversation.markCommitted(identity, common_prefix, /*logits_valid=*/false);
}

bool shiftRuntimeContextIfNeeded(ModelRuntime& runtime, int required_tokens, bool allow_full_reset,
                                 const CacheIdentity& cache_identity) {
    if (runtime.ctx == nullptr) {
        return false;
    }

    // PIR-05: never compact a prefix whose KV/token correspondence has not been
    // proven (e.g. after an aborted decode). Compacting a stale prefix would
    // silently shift positions that no longer match `active_tokens`.
    if (!runtime.conversation.valid && runtime.current_position > 0) {
        if (allow_full_reset) {
            LOGW("kv_shift_unverified_state; forcing full reset");
            logCacheIdentity("shift_unverified_reset", cache_identity, runtime.conversation);
            resetRuntimeContext(runtime, false);
            return required_tokens < static_cast<llama_pos>(runtime.context_length - kContextHeadroom);
        }
        LOGW("kv_shift_unverified_state; refusing to shift uncommitted context");
        return false;
    }

    const llama_pos limit = static_cast<llama_pos>(runtime.context_length - kContextHeadroom);
    if (runtime.current_position + required_tokens < limit) {
        return true;
    }

    const int max_prefix = static_cast<int>(runtime.current_position) / 2;
    const int system_prefix_tokens = std::min(runtime.system_prefix_length, max_prefix);
    const int available_tokens = static_cast<int>(runtime.current_position) - system_prefix_tokens;
    if (available_tokens <= 0) {
        if (allow_full_reset) {
            resetRuntimeContext(runtime, false);
            return required_tokens < limit;
        }
        return false;
    }

    int drop_count = available_tokens / 4;
    if (drop_count < 1) drop_count = 1;
    if (drop_count > available_tokens) drop_count = available_tokens;

    auto* memory = llama_get_memory(runtime.ctx);
    const llama_pos seq_start = static_cast<llama_pos>(system_prefix_tokens);
    const llama_pos seq_end = seq_start + static_cast<llama_pos>(drop_count);

    // PIR-05: the sequence remove must succeed before any token/position state is
    // mutated. On failure we invalidate and replay from a clean cache.
    bool rm_ok = llama_memory_seq_rm(memory, kMainSequence, seq_start, seq_end);
    if (rm_ok) {
        // Compact remaining sequence positions. llama_memory_seq_add returns void,
        // so correctness is verified below against the token/position invariant
        // (current_position == active_tokens.size()) rather than trusted blindly.
        llama_memory_seq_add(memory, kMainSequence, seq_end, runtime.current_position, -static_cast<llama_pos>(drop_count));
        if (static_cast<size_t>(system_prefix_tokens + drop_count) > runtime.active_tokens.size()) {
            LOGW("KV compaction token range mismatch: prefix=%d drop=%d active=%zu",
                 system_prefix_tokens, drop_count, runtime.active_tokens.size());
            resetRuntimeContext(runtime, false);
            return allow_full_reset && required_tokens < limit;
        }
        runtime.active_tokens.erase(runtime.active_tokens.begin() + system_prefix_tokens,
                                    runtime.active_tokens.begin() + system_prefix_tokens + drop_count);
        runtime.current_position -= static_cast<llama_pos>(drop_count);
        if (runtime.current_position != static_cast<llama_pos>(runtime.active_tokens.size())) {
            LOGW("KV compaction position mismatch: pos=%d active=%zu; resetting context",
                 static_cast<int>(runtime.current_position), runtime.active_tokens.size());
            resetRuntimeContext(runtime, false);
            return allow_full_reset && required_tokens < limit;
        }
        assert(runtime.current_position == static_cast<llama_pos>(runtime.active_tokens.size()));
        // PIR-05: the compacted remainder is a proven committed prefix. The tail
        // (and therefore the previously decoded logits) is preserved by the shift.
        runtime.conversation.markCommitted(cache_identity.key(),
                                          runtime.active_tokens.size(),
                                          runtime.conversation.last_logits_valid);
        LOGI("kv_cache_sliding_window_shift dropped=%d prefix=%d new_pos=%d limit=%d",
             drop_count, system_prefix_tokens, static_cast<int>(runtime.current_position), static_cast<int>(limit));
        logCacheIdentity("shift_committed", cache_identity, runtime.conversation);
    } else {
        LOGW("llama_memory_seq_rm failed; allow_full_reset=%s", allow_full_reset ? "true" : "false");
        if (allow_full_reset) {
            resetRuntimeContext(runtime, false);
            return required_tokens < limit;
        }
        return false;
    }
    return (runtime.current_position + required_tokens) < limit;
}

// Returns:
//   0        = success
//   negative = llama_decode error
//   -999     = cancelled between batch chunks (prompt eval only)
int decodeTokensAt(ModelRuntime& runtime, const llama_token* tokens, int32_t count,
                   llama_pos start_pos, const std::atomic<bool>* cancel_flag = nullptr) {
    if (count <= 0) {
        return 0;
    }
    if (count == 1) {
        // Single-token fast path — no cancellation check needed (5-30ms)
        if (!runtime.single_batch_initialized) {
            runtime.single_batch = llama_batch_init(1, 0, 1);
            runtime.single_batch_initialized = true;
        }
        runtime.single_batch.n_tokens = 1;
        runtime.single_batch.token[0] = tokens[0];
        runtime.single_batch.pos[0] = start_pos;
        runtime.single_batch.n_seq_id[0] = 1;
        runtime.single_batch.seq_id[0][0] = kMainSequence;
        runtime.single_batch.logits[0] = 1;
        return llama_decode(runtime.ctx, runtime.single_batch);
    }
    const int batch_size = runtime.batch_size;
    llama_batch batch = llama_batch_init(std::min(count, batch_size), 0, 1);
    for (int32_t offset = 0; offset < count; offset += batch_size) {
        // Check cancellation between batch chunks
        if (cancel_flag != nullptr && cancel_flag->load(std::memory_order_acquire)) {
            llama_batch_free(batch);
            return -999;
        }
        int32_t chunk = std::min(batch_size, count - offset);
        batch.n_tokens = chunk;
        for (int32_t i = 0; i < chunk; i++) {
            batch.token[i] = tokens[offset + i];
            batch.pos[i] = start_pos + offset + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = kMainSequence;
            batch.logits[i] = (i == chunk - 1 && offset + chunk == count) ? 1 : 0;
        }
        int result = llama_decode(runtime.ctx, batch);
        if (result != 0) {
            llama_batch_free(batch);
            return result;
        }
    }
    llama_batch_free(batch);
    return 0;
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
    // PIR-06: honour the explicit backend preference. A CPU request must not
    // enable GPU layer offload. n_gpu_layers=0 is the documented way to keep
    // the model resident on the CPU; the actually-applied backend is still
    // verified below via llama_supports_gpu_offload() and reported as observed,
    // never assumed from this request.
    const bool gpu_requested = config.use_vulkan && config.gpu_layers > 0;
    model_params.n_gpu_layers = gpu_requested ? config.gpu_layers : 0;
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
    ctx_params.type_k = parseGgmlType(config.kv_cache_type_k);
    ctx_params.type_v = parseGgmlType(config.kv_cache_type_v);
    ctx_params.flash_attn_type = config.enable_flash_attn ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    ctx_params.no_perf = false;

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        LOGW("primary_kv_cache_init_failed; retrying with F16 KV cache and disabled FlashAttn path=%s", path.c_str());
        ctx_params.type_k = GGML_TYPE_F16;
        ctx_params.type_v = GGML_TYPE_F16;
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        ctx = llama_init_from_model(model, ctx_params);
    }
    if (ctx == nullptr) {
        LOGW("f16_kv_cache_init_failed; retrying with default context params path=%s", path.c_str());
        ctx_params = llama_context_default_params();
        ctx_params.n_ctx = config.context_length;
        ctx_params.n_batch = config.batch_size;
        ctx_params.n_threads = config.thread_count;
        ctx = llama_init_from_model(model, ctx_params);
    }
    if (ctx == nullptr) {
        llama_model_free(model);
        LOGE("context_create_failed path=%s", path.c_str());
        return nullptr;
    }

    // Verify whether GGML actually initialized a live GPU backend, regardless of what was requested.
    // llama_supports_gpu_offload() returns true only when the linked GGML build has a working
    // GPU backend available at runtime (OpenCL driver present, device enumerated, etc.).
    const bool gpu_offload_live = gpu_requested && llama_supports_gpu_offload();
    const int verified_gpu_layers = gpu_offload_live ? config.gpu_layers : 0;

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
    runtime->gpu_layers = verified_gpu_layers;
    // PIR-05: record the KV layout actually selected (post-fallback) so it can be
    // part of the cache identity.
    runtime->kv_type_k = ggml_type_name(ctx_params.type_k);
    runtime->kv_type_v = ggml_type_name(ctx_params.type_v);
    runtime->flash_attn = (ctx_params.flash_attn_type == LLAMA_FLASH_ATTN_TYPE_ENABLED);

    // Derive backend name from verified runtime state, not compile-time macros alone.
#if defined(LLMHOST_VULKAN_ENABLED)
    runtime->actual_backend_name = gpu_offload_live ? "Vulkan GPU" :
#if defined(LLMHOST_KLEIDIAI_ENABLED)
        "CPU-KleidiAI";
#else
        "CPU";
#endif
#elif defined(LLMHOST_KLEIDIAI_ENABLED)
    runtime->actual_backend_name = gpu_offload_live ? "ARM KleidiAI + OpenCL" : "ARM KleidiAI";
#elif defined(LLMHOST_OPENCL_ENABLED)
    runtime->actual_backend_name = gpu_offload_live ? "Adreno OpenCL" : "CPU";
#else
    runtime->actual_backend_name = "CPU";
#endif

    LOGI("model_loaded path=%s mmap=%s n_ctx=%u n_batch=%u threads=%d gpu_layers_requested=%d gpu_layers_verified=%d backend=%s",
         path.c_str(),
         use_mmap ? "true" : "false",
         ctx_params.n_ctx,
         ctx_params.n_batch,
         ctx_params.n_threads,
         config.gpu_layers,
         verified_gpu_layers,
         runtime->actual_backend_name.c_str());

    if (!requested_config.lora_adapters.empty()) {
        runtime->applyLoraAdapters(requested_config.lora_adapters);
    }

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

        // Register per-generation abort callback so llama_decode can be interrupted mid-batch
        auto abort_fn = [](void* data) -> bool {
            return static_cast<std::atomic<bool>*>(data)->load(std::memory_order_acquire);
        };
        llama_set_abort_callback(runtime->ctx, abort_fn, const_cast<std::atomic<bool>*>(&session->cancel_requested));

        struct AbortCallbackGuard {
            llama_context* ctx;
            ~AbortCallbackGuard() {
                if (ctx != nullptr) {
                    llama_set_abort_callback(ctx, nullptr, nullptr);
                }
            }
        } abort_guard{runtime->ctx};

        llama_set_n_threads(runtime->ctx, session->config.thread_count, session->config.thread_count);
        LOGI("generation_config generation_id=%u max_tokens=%d threads=%d n_ctx=%d n_batch=%d temp=%.2f top_k=%d top_p=%.2f repeat=%.2f kv_pos=%d",
             session->generation_id,
             session->config.max_tokens,
             session->config.thread_count,
             runtime->context_length,
             runtime->batch_size,
             session->config.temperature,
             session->config.top_k,
             session->config.top_p,
             session->config.repeat_penalty,
             static_cast<int>(runtime->current_position));

        llama_perf_context_reset(runtime->ctx);

        // PIR-05: identity of the cache this generation will read/write. Any
        // change to model/template/context/KV/FA forces a clean replay.
        const CacheIdentity cache_identity = makeCacheIdentity(*runtime);
        const std::string cache_identity_key = cache_identity.key();
        logCacheIdentity("generation_begin", cache_identity, runtime->conversation);

        if (session->config.continue_from_context) {
            // PIR-05: continuing means sampling from the *current* last logits, so
            // both a committed prefix and valid last logits must have been proven.
            // A cancelled/failed previous decode leaves `valid == false`.
            if (runtime->current_position <= 0 ||
                !runtime->conversation.valid ||
                !runtime->conversation.last_logits_valid) {
                ctrl->error_code.store(427, std::memory_order_release);
                LOGE("continue_failed_no_committed_context generation_id=%u pos=%d valid=%s logits=%s",
                     session->generation_id,
                     static_cast<int>(runtime->current_position),
                     runtime->conversation.valid ? "true" : "false",
                     runtime->conversation.last_logits_valid ? "true" : "false");
                finishSession(session, StreamState::Error);
                return;
            }
            if (!shiftRuntimeContextIfNeeded(*runtime, session->config.max_tokens + kContextHeadroom, false,
                                             cache_identity)) {
                ctrl->error_code.store(426, std::memory_order_release);
                LOGE("continue_context_shift_failed n_ctx=%d current_position=%d required=%d",
                     runtime->context_length,
                     static_cast<int>(runtime->current_position),
                     session->config.max_tokens);
                finishSession(session, StreamState::Error);
                return;
            }
            LOGI("continue_from_context generation_id=%u kv_pos=%d",
                 session->generation_id,
                 static_cast<int>(runtime->current_position));
        } else {
            const std::string formatted_prompt = !session->messages.empty()
                ? formatMessagesForGeneration(runtime->model, session->messages, prompt)
                : formatPromptForGeneration(runtime->model, prompt);
            LOGI("prompt_formatted generation_id=%u raw_bytes=%zu formatted_bytes=%zu",
                 session->generation_id,
                 prompt.size(),
                formatted_prompt.size());

            // PIR-05: tokenize as a continuation only when a *proven* prefix is
            // present. After an aborted decode current_position may be > 0 while
            // the cache is invalid; a full replay starts at position 0, so the
            // prompt must regain its BOS/special tokens.
            bool add_special = (runtime->current_position == 0) || !runtime->conversation.valid;
            if (formatted_prompt.rfind("<s>", 0) == 0 ||
                formatted_prompt.rfind("<|im_start|>", 0) == 0 ||
                formatted_prompt.rfind("<|start_header_id|>", 0) == 0 ||
                formatted_prompt.rfind("<|begin_of_text|>", 0) == 0 ||
                formatted_prompt.rfind("[INST]", 0) == 0) {
                add_special = false;
            }

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
            if (actual_tokens > static_cast<int32_t>(prompt_tokens.size())) {
                ctrl->error_code.store(423, std::memory_order_release);
                LOGE("tokenize_count_mismatch actual=%d capacity=%zu", actual_tokens, prompt_tokens.size());
                finishSession(session, StreamState::Error);
                return;
            }
            prompt_tokens.resize(static_cast<size_t>(actual_tokens));
            const int max_prompt_tokens = runtime->context_length - session->config.max_tokens - kContextHeadroom;
            if (usable_prompt_tokens > max_prompt_tokens) {
                if (max_prompt_tokens <= 0) {
                    ctrl->error_code.store(422, std::memory_order_release);
                    LOGE("context_too_small n_ctx=%d max_tokens=%d", runtime->context_length, session->config.max_tokens);
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
            session->prompt_tokens.store(usable_prompt_tokens, std::memory_order_relaxed);

            // PIR-05: only trust the stored prefix when it was proven committed
            // under the current cache identity. Otherwise the cache is invalid and
            // the request must be replayed from scratch.
            const bool identity_match = runtime->conversation.valid &&
                runtime->conversation.cache_identity == cache_identity_key;
            std::size_t common_prefix = 0;
            if (identity_match) {
                common_prefix = commonPrefixLength(prompt_tokens, runtime->active_tokens);
                const std::size_t reusable_bound = std::min(
                    runtime->conversation.reusable_tokens,
                    static_cast<std::size_t>(runtime->current_position));
                if (common_prefix > reusable_bound) {
                    common_prefix = reusable_bound;
                }
            }

            if (common_prefix > 0) {
                shrinkCommittedPrefix(*runtime, cache_identity_key, common_prefix);
                if (common_prefix > 0) {
                    LOGI("prefix_cache_hit common_prefix=%zu identity_match=true", common_prefix);
                } else {
                    // PIR-05: do not evaluate a suffix against a stale prefix.
                    LOGE("prefix_cache_truncate_failed; replaying full prompt");
                }
            } else {
                resetRuntimeContext(*runtime, false);
                LOGI("prefix_cache_miss identity_match=%s; fully cleared kv", identity_match ? "true" : "false");
            }
            // PIR-05: explicit post-reset/post-truncation cache identity line.
            logCacheIdentity("prefix_decision", cache_identity, runtime->conversation);

            runtime->system_prefix_length = computeSystemPrefixLength(
                prompt_tokens, static_cast<std::size_t>(usable_prompt_tokens), runtime->vocab);

            int32_t tokens_to_decode = usable_prompt_tokens - static_cast<int32_t>(common_prefix);

            if (!shiftRuntimeContextIfNeeded(*runtime, tokens_to_decode + session->config.max_tokens + kContextHeadroom, true,
                                             cache_identity)) {
                ctrl->error_code.store(426, std::memory_order_release);
                LOGE("context_shift_failed n_ctx=%d current_position=%d required=%d",
                     runtime->context_length,
                     static_cast<int>(runtime->current_position),
                     tokens_to_decode + session->config.max_tokens);
                finishSession(session, StreamState::Error);
                return;
            }

            // PIR-05: a shift may have compacted (or reset) the cache, so the
            // reusable prefix and suffix must be recomputed against the post-shift
            // active_tokens. If the cache was cleared, common_prefix becomes 0 and
            // the full prompt is replayed - never a suffix-only evaluation.
            if (runtime->conversation.valid) {
                common_prefix = commonPrefixLength(prompt_tokens, runtime->active_tokens);
                const std::size_t post_shift_bound = std::min(
                    runtime->conversation.reusable_tokens,
                    static_cast<std::size_t>(runtime->current_position));
                if (common_prefix > post_shift_bound) {
                    common_prefix = post_shift_bound;
                }
                // The shift may have left a compacted prefix that only partially
                // matches the new prompt; drop the divergent tail transactionally
                // so no stale positions remain in the KV cache.
                shrinkCommittedPrefix(*runtime, cache_identity_key, common_prefix);
            } else {
                common_prefix = 0;
            }
            tokens_to_decode = usable_prompt_tokens - static_cast<int32_t>(common_prefix);
            // PIR-05: explicit post-shift cache identity line (recomputed suffix).
            logCacheIdentity("post_shift_decision", cache_identity, runtime->conversation);

            // Restore the current prompt's prefix metadata after any reset/shift.
            runtime->system_prefix_length = computeSystemPrefixLength(
                prompt_tokens, static_cast<std::size_t>(usable_prompt_tokens), runtime->vocab);

            // PIR-05: an all-cached request samples from logits left by a prior
            // decode. If the prefix was truncated (a tail was removed) those logits
            // no longer match active_tokens.back(), so re-evaluate the final prompt
            // token to restore last-logit availability before sampling.
            if (tokens_to_decode == 0 && common_prefix > 0 && !runtime->conversation.last_logits_valid) {
                common_prefix -= 1;
                shrinkCommittedPrefix(*runtime, cache_identity_key, common_prefix);
                tokens_to_decode = usable_prompt_tokens - static_cast<int32_t>(common_prefix);
                LOGI("last_logits_recompute common_prefix=%zu tokens_to_decode=%d",
                     common_prefix, tokens_to_decode);
            }

            if (session->cancel_requested.load(std::memory_order_acquire)) {
                runtime->conversation.invalidate(); // PIR-05: invalidate-on-abort
                finishSession(session, StreamState::Cancelled);
                return;
            }

            const auto prompt_start_time = std::chrono::steady_clock::now();
            if (tokens_to_decode > 0) {
                const llama_pos prompt_start = runtime->current_position;
                const int decode_result = decodeTokensAt(*runtime, prompt_tokens.data() + common_prefix,
                    tokens_to_decode, prompt_start, &session->cancel_requested);
                if (decode_result != 0) {
                    // PIR-05: a failed/aborted prefill may have written only part
                    // of the suffix into the KV cache, so the token/position
                    // bookkeeping can no longer be trusted. Default to
                    // invalidate-on-abort so the next request replays cleanly.
                    runtime->conversation.invalidate();
                    if (decode_result == -999) {
                        LOGI("prompt_eval_cancelled generation_id=%u",
                             session->generation_id);
                        finishSession(session, StreamState::Cancelled);
                        return;
                    }
                    if (common_prefix > 0) {
                        LOGW("prompt_eval_failed_on_prefix_cache rc=%d; retrying with clean KV cache", decode_result);
                        resetRuntimeContext(*runtime, false);
                        const int retry_rc = decodeTokensAt(*runtime, prompt_tokens.data(), usable_prompt_tokens, 0, &session->cancel_requested);
                        if (retry_rc == 0) {
                            runtime->current_position = usable_prompt_tokens;
                            runtime->active_tokens = prompt_tokens;
                            runtime->system_prefix_length = computeSystemPrefixLength(
                                prompt_tokens, static_cast<std::size_t>(usable_prompt_tokens), runtime->vocab);
                            runtime->conversation.markCommitted(cache_identity_key, runtime->current_position, true);
                            LOGI("prefix_cache_retry_success prompt_tokens=%d", usable_prompt_tokens);
                        } else {
                            runtime->conversation.invalidate(); // PIR-05: invalidate-on-abort
                            ctrl->error_code.store(static_cast<uint32_t>(5000 + std::abs(retry_rc)), std::memory_order_release);
                            LOGE("prompt_eval_failed rc=%d", retry_rc);
                            finishSession(session, StreamState::Error);
                            return;
                        }
                    } else {
                        ctrl->error_code.store(static_cast<uint32_t>(5000 + std::abs(decode_result)), std::memory_order_release);
                        LOGE("prompt_eval_failed rc=%d", decode_result);
                        finishSession(session, StreamState::Error);
                        return;
                    }
                } else {
                    runtime->current_position += tokens_to_decode;
                    runtime->active_tokens.insert(runtime->active_tokens.end(), prompt_tokens.begin() + common_prefix, prompt_tokens.begin() + usable_prompt_tokens);
                    // PIR-05: commit only after the decode actually succeeded.
                    runtime->conversation.markCommitted(cache_identity_key, runtime->current_position, true);
                }
            }
            const auto prompt_end_time = std::chrono::steady_clock::now();
            const int64_t ttft = std::chrono::duration_cast<std::chrono::milliseconds>(prompt_end_time - prompt_start_time).count();
            session->ttft_ms.store(ttft, std::memory_order_relaxed);
            LOGI("prompt_eval_done generation_id=%u prompt_tokens=%d kv_pos=%d cached_tokens=%zu decoded_tokens=%d ttft_ms=%lld",
                 session->generation_id,
                 usable_prompt_tokens,
                 static_cast<int>(runtime->current_position),
                 common_prefix,
                 tokens_to_decode,
                 static_cast<long long>(ttft));
            const float kv_usage_pct = static_cast<float>(runtime->current_position) / static_cast<float>(runtime->context_length) * 100.0f;
            LOGI("context_utilization generation_id=%u kv_pos=%d context_length=%d kv_usage_pct=%.1f%%",
                 session->generation_id,
                 static_cast<int>(runtime->current_position),
                 runtime->context_length,
                 kv_usage_pct);
        }

        if (session->cancel_requested.load(std::memory_order_acquire) ||
            memory_pressure_level.load(std::memory_order_acquire) >= 3) {
            runtime->conversation.invalidate(); // PIR-05: invalidate-on-abort
            finishSession(session, StreamState::Cancelled);
            return;
        }

        // Build a fresh sampler chain for every generation. llama_sampler_chain_add
        // transfers ownership of each member into the chain (llama.h), so reusing
        // chain or grammar sampler objects across generations previously produced
        // dangling cache entries and double-free/UAF paths. The chain is therefore
        // rebuilt here on each generation; `owned_sampler` only carries ownership
        // so the chain can be freed (on the next generation or at runtime teardown).
        // A fresh chain costs microseconds; the GBNF recompile below is logged and
        // is sub-millisecond for the tool-call grammars this app uses.
        if (runtime->owned_sampler != nullptr) {
            llama_sampler_free(runtime->owned_sampler);
            runtime->owned_sampler = nullptr;
        }
        llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (sampler == nullptr) {
            ctrl->error_code.store(424, std::memory_order_release);
            finishSession(session, StreamState::Error);
            return;
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, session->config.repeat_penalty, 0.2f, 0.2f));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(session->config.top_k));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(session->config.top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(session->config.temperature));
        if (!session->config.grammar.empty()) {
            const auto grammar_start = std::chrono::steady_clock::now();
            auto* grammar_sampler = llama_sampler_init_grammar(runtime->vocab, session->config.grammar.c_str(), "root");
            const auto grammar_elapsed = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - grammar_start).count();
            LOGI("grammar_compile_time_us=%lld", static_cast<long long>(grammar_elapsed));
            if (grammar_sampler != nullptr) {
                llama_sampler_chain_add(sampler, grammar_sampler);
            } else {
                // PIR-03: structured output was explicitly requested. A grammar that
                // fails to compile is a hard error, not a reason to silently emit
                // unconstrained text that violates the requested contract.
                LOGE("grammar_compile_failed generation_id=%u; failing closed",
                     session->generation_id);
                llama_sampler_free(sampler);
                ctrl->error_code.store(426, std::memory_order_release);
                finishSession(session, StreamState::Error);
                return;
            }
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        runtime->owned_sampler = sampler;

        const auto generation_start = std::chrono::steady_clock::now();
        int generated_tokens = 0;
        bool stopped_by_eog = false;
        std::vector<long long> decode_times_us;
        decode_times_us.reserve(static_cast<size_t>(session->config.max_tokens));
        for (int i = 0; i < session->config.max_tokens; i++) {
            if (memory_pressure_level.load(std::memory_order_acquire) >= 3) {
                session->cancel_requested.store(true, std::memory_order_release);
            }
            if (session->cancel_requested.load(std::memory_order_acquire)) {
                runtime->conversation.invalidate(); // PIR-05: invalidate-on-abort
                finishSession(session, StreamState::Cancelled);
                return;
            }

            // Safe dynamic thread tuning between decodes
            const int target_threads = pending_thread_count.load(std::memory_order_acquire);
            if (target_threads > 0 && target_threads != runtime->thread_count) {
                runtime->thread_count = target_threads;
                llama_set_n_threads(runtime->ctx, target_threads, target_threads);
                LOGI("dynamic_thread_updated generation_id=%u threads=%d", session->generation_id, target_threads);
            }
            session->active_threads.store(runtime->thread_count, std::memory_order_relaxed);

            // PIR-03: llama_sampler_sample(idx == -1) is documented and implemented
            // as "Sample and accept a token" (it calls llama_sampler_accept itself).
            // The explicit accept that used to follow double-counted every token in
            // the penalty/history state, so the chain is now advanced exactly once.
            const llama_token token = llama_sampler_sample(sampler, runtime->ctx, -1);
            if (token == LLAMA_TOKEN_NULL) {
                // No unconstrained fallback: when a required grammar rejects every
                // candidate, fail with a typed error instead of emitting output that
                // violates the requested structure.
                ctrl->error_code.store(425, std::memory_order_release);
                LOGE("sample_failed token=null grammar=%d generation_id=%u",
                     session->config.grammar.empty() ? 0 : 1,
                     session->generation_id);
                finishSession(session, StreamState::Error);
                return;
            }

            if (llama_vocab_is_eog(runtime->vocab, token)) {
                LOGI("token_eog id=%d generation_id=%u", static_cast<int>(token), session->generation_id);
                stopped_by_eog = true;
                break;
            }

            // PIR-03: the previous heuristic treated four repeating tokens as an EOG
            // and ended generation. That fabricated stops on valid repeated code/JSON;
            // stopping is now owned solely by the model's real EOG token.

            if (writeToken(ctrl, buffers.tokens, static_cast<int32_t>(token), session->cancel_requested)) {
                generated_tokens++;
                const auto now = std::chrono::steady_clock::now();
                const auto current_ms = std::chrono::duration_cast<std::chrono::milliseconds>(now - generation_start).count();
                if (current_ms > 0) {
                    const float current_tps = static_cast<float>(generated_tokens) * 1000.0f / static_cast<float>(current_ms);
                    session->tokens_per_sec.store(current_tps, std::memory_order_relaxed);
                }
            }
            if (session->cancel_requested.load(std::memory_order_acquire)) {
                // PIR-05: the token was emitted but not yet committed to the KV
                // cache; invalidate so no prefix the cache may not hold is reused.
                runtime->conversation.invalidate();
                finishSession(session, StreamState::Cancelled);
                return;
            }

            const auto token_decode_start = std::chrono::steady_clock::now();
            const int next_decode_result = decodeTokensAt(*runtime, &token, 1, runtime->current_position);
            const auto token_decode_us = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - token_decode_start).count();
            decode_times_us.push_back(token_decode_us);
            if (next_decode_result != 0) {
                runtime->conversation.invalidate(); // PIR-05: invalidate-on-abort
                ctrl->error_code.store(static_cast<uint32_t>(5100 + std::abs(next_decode_result)), std::memory_order_release);
                LOGE("token_eval_failed rc=%d", next_decode_result);
                finishSession(session, StreamState::Error);
                return;
            }
            // PIR-05: commit the token only after its KV entry was successfully
            // written, so current_position == active_tokens.size() and the last
            // logits always correspond to active_tokens.back().
            runtime->active_tokens.push_back(token);
            runtime->current_position += 1;
            runtime->conversation.noteProgress(runtime->current_position, true);
        }

        const auto generation_end = std::chrono::steady_clock::now();
        const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            generation_end - generation_start).count();
        const double tokens_per_second = elapsed_ms > 0
            ? static_cast<double>(generated_tokens) * 1000.0 / static_cast<double>(elapsed_ms)
            : 0.0;
        double avg_decode_us = 0.0;
        long long p99_decode_us = 0;
        if (!decode_times_us.empty()) {
            auto sorted = decode_times_us;
            std::sort(sorted.begin(), sorted.end());
            double sum = 0;
            for (auto t : sorted) sum += t;
            avg_decode_us = sum / sorted.size();
            size_t p99_idx = static_cast<size_t>(0.99 * (sorted.size() - 1));
            p99_decode_us = sorted[p99_idx];
        }
        LOGI("generation_summary generation_id=%u tokens=%d elapsed_ms=%lld tokens_per_second=%.2f avg_decode_us=%.0f p99_decode_us=%lld",
             session->generation_id,
             generated_tokens,
             static_cast<long long>(elapsed_ms),
             tokens_per_second,
             avg_decode_us,
             static_cast<long long>(p99_decode_us));

        const float kv_usage_pct = static_cast<float>(runtime->current_position) / static_cast<float>(runtime->context_length) * 100.0f;
        LOGI("generation_kv_usage generation_id=%u kv_pos=%d context_length=%d kv_usage_pct=%.1f%%",
             session->generation_id,
             static_cast<int>(runtime->current_position),
             runtime->context_length,
             kv_usage_pct);

        const StreamState final_state = session->cancel_requested.load(std::memory_order_acquire)
            ? StreamState::Cancelled
            : (!stopped_by_eog && generated_tokens >= session->config.max_tokens ? StreamState::MaxTokens : StreamState::Eof);
        finishSession(session, final_state);
    }

    void runGeneration(const std::shared_ptr<GenerationSession>& session, std::string prompt) {
        if (session->runtime && session->runtime->mock_model) {
            runDebugGeneration(session, prompt);
        } else {
            runRealGeneration(session, prompt);
        }
    }

    int startSessionInternal(std::string prompt, std::vector<ChatMessage> messages, int generation_id, GenerationConfig config) {
        std::shared_ptr<GenerationSession> old_session;
        std::shared_ptr<ModelRuntime> runtime;
        {
            std::lock_guard<std::mutex> lock(mu);
            runtime = active_runtime;
            old_session = active_session;
            if (old_session) {
                old_session->cancel_requested.store(true, std::memory_order_release);
            }
            active_session.reset();
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
        session->messages = std::move(messages);

        {
            std::lock_guard<std::mutex> lock(mu);
            active_session = session;
            auto* ctrl = buffers.control;
            ctrl->generation_id.store(static_cast<uint32_t>(generation_id), std::memory_order_release);
            clearRing(ctrl);
            ctrl->state.store(static_cast<uint32_t>(StreamState::Generating), std::memory_order_release);
        }

        session->worker = std::thread([impl = this, session, prompt]() {
            impl->runGeneration(session, prompt);
        });
        return generation_id;
    }

    std::mutex mu;
    StaticBuffers buffers;
    std::shared_ptr<ModelRuntime> active_runtime;
    std::shared_ptr<GenerationSession> active_session;
    std::atomic<int> memory_pressure_level{0};
    std::atomic<int> pending_thread_count{0};
    bool debug_hooks_enabled = false;

    void setThreadCount(int thread_count) {
        pending_thread_count.store(thread_count, std::memory_order_release);
    }
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
    return impl_->startSessionInternal(prompt, {}, generation_id, config);
}

int Engine::startGenerationChat(const std::vector<ChatMessage>& messages, int generation_id, GenerationConfig config) {
    if (!impl_ || messages.empty()) {
        return -1;
    }
    for (const auto& message : messages) {
        if (message.role.empty()) {
            return -1;
        }
    }
    return impl_->startSessionInternal("", messages, generation_id, config);
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

    // Pre-tokenize a realistic decode sequence — repeating BOS creates degenerate
    // KV cache patterns that under-report real decode time.
    const char* bench_decode_text = "The quick brown fox jumps over the lazy dog. ";
    std::string repeated_text;
    repeated_text.reserve(static_cast<size_t>(tg) * 20);
    for (int i = 0; i < (tg + 20) / 10; i++) {
        repeated_text += bench_decode_text;
    }
    std::vector<llama_token> bench_tokens(static_cast<size_t>(tg));
    const int32_t n_bench_tokens = llama_tokenize(
        runtime->vocab,
        repeated_text.c_str(),
        static_cast<int32_t>(repeated_text.size()),
        bench_tokens.data(),
        tg,
        true,
        true);
    const int32_t decode_count = n_bench_tokens > 0 ? std::min(n_bench_tokens, tg) : tg;
    // Fallback: if tokenization produced fewer tokens than needed, fill with BOS
    if (decode_count < tg) {
        for (int i = decode_count; i < tg; i++) {
            bench_tokens[i] = llama_vocab_bos(runtime->vocab);
        }
    }

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
            const int decode_rc = decodeTokensAt(*runtime, &bench_tokens[i], 1, runtime->current_position);
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

std::vector<float> Engine::encode(const std::string& text) {
    if (!impl_) return {};
    std::shared_ptr<ModelRuntime> runtime;
    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        runtime = impl_->active_runtime;
    }
    if (!runtime || runtime->mock_model || runtime->ctx == nullptr || runtime->vocab == nullptr) {
        return {};
    }

    std::lock_guard<std::mutex> decode_lock(runtime->decode_mu);

    // Tokenize the text
    const bool add_special = true;
    const int32_t token_count = -llama_tokenize(
        runtime->vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        nullptr, 0,
        add_special, true);
    if (token_count <= 0) return {};

    std::vector<llama_token> tokens(static_cast<size_t>(token_count));
    const int32_t actual = llama_tokenize(
        runtime->vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        tokens.data(), token_count,
        add_special, true);
    if (actual < 0) return {};

    // llama_encode runs the encoder graph, which hard-requires
    // n_ubatch >= n_tokens (GGML_ABORT otherwise) and would crash the whole
    // process on oversized inputs. Reject them instead; callers treat an empty
    // result as failure and can re-chunk.
    if (actual > runtime->batch_size) {
        LOGW("encode_input_too_large tokens=%d batch_size=%d", actual, runtime->batch_size);
        return {};
    }

    // Enable embeddings mode
    llama_set_embeddings(runtime->ctx, true);

    // Build batch and encode
    llama_batch batch = llama_batch_get_one(tokens.data(), actual);
    const int32_t rc = llama_encode(runtime->ctx, batch);
    if (rc != 0) {
        llama_set_embeddings(runtime->ctx, false);
        return {};
    }

    // Retrieve embeddings
    const int32_t n_embd = llama_model_n_embd(runtime->model);
    float* embeddings = llama_get_embeddings(runtime->ctx);
    if (embeddings == nullptr) {
        llama_set_embeddings(runtime->ctx, false);
        return {};
    }

    // Mean-pool across all token embeddings for a single representative vector.
    // llama_get_embeddings returns a flat array of shape [n_tokens × n_embd].
    // Using only the first token (as before) discards 85%+ of semantic signal
    // for multi-token chunks. Mean pooling is the standard approach for
    // sentence/paragraph embedding extraction from causal LMs.
    std::vector<float> result(n_embd, 0.0f);
    for (int32_t t = 0; t < actual; t++) {
        const float* token_emb = embeddings + static_cast<size_t>(t) * static_cast<size_t>(n_embd);
        for (int32_t e = 0; e < n_embd; e++) {
            result[e] += token_emb[e];
        }
    }
    const float inv_n = 1.0f / static_cast<float>(actual);
    for (int32_t e = 0; e < n_embd; e++) {
        result[e] *= inv_n;
    }

    // Restore non-embeddings mode
    llama_set_embeddings(runtime->ctx, false);

    return result;
}

Engine::DrainResult Engine::drainDecodeAndState(int generation_id, int max_tokens) {
    DrainResult result;
    if (!impl_) {
        return result;
    }
    result.tokens = drainTokens(generation_id, max_tokens);
    if (!result.tokens.empty()) {
        result.text = decodeTokens(generation_id, result.tokens);
    }
    result.state = getState(generation_id);

    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        if (impl_->active_session && impl_->active_session->generation_id == static_cast<uint32_t>(generation_id)) {
            result.prompt_tokens = impl_->active_session->prompt_tokens.load(std::memory_order_relaxed);
            result.ttft_ms = impl_->active_session->ttft_ms.load(std::memory_order_relaxed);
            result.tokens_per_sec = impl_->active_session->tokens_per_sec.load(std::memory_order_relaxed);
            result.active_threads = impl_->active_session->active_threads.load(std::memory_order_relaxed);
        }
        if (impl_->buffers.control != nullptr) {
            result.error_code = impl_->buffers.control->error_code.load(std::memory_order_acquire);
        }
    }

    return result;
}

void Engine::setThreadCount(int thread_count) {
    if (!impl_) {
        return;
    }
    impl_->setThreadCount(thread_count);
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



std::string Engine::get_backend_name() const {
    if (!impl_) {
        return "CPU";
    }
    std::lock_guard<std::mutex> lock(impl_->mu);
    if (!impl_->active_runtime) {
        return "CPU";
    }
    // actual_backend_name is set at load time from verified runtime state (llama_supports_gpu_offload).
    return impl_->active_runtime->actual_backend_name;
}

int32_t Engine::get_gpu_layers() const {
    if (!impl_) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(impl_->mu);
    if (!impl_->active_runtime) {
        return 0;
    }
    return impl_->active_runtime->gpu_layers;
}

bool Engine::is_kleidiai_enabled() const {
#if defined(LLMHOST_KLEIDIAI_ENABLED)
    return true;
#else
    return false;
#endif
}

bool Engine::is_vulkan_enabled() const {
#if defined(LLMHOST_VULKAN_ENABLED)
    return llama_supports_gpu_offload();
#else
    return false;
#endif
}

bool Engine::applyLoraAdapters(const std::vector<LoraAdapterSpec>& adapters) {
    if (!impl_) {
        return false;
    }
    impl_->cancelAndJoinActiveSession();
    std::lock_guard<std::mutex> lock(impl_->mu);
    if (!impl_->active_runtime) {
        LOGE("applyLoraAdapters: No active runtime model loaded");
        return false;
    }
    return impl_->active_runtime->applyLoraAdapters(adapters);
}

void Engine::clearLoraAdapters() {
    if (!impl_) {
        return;
    }
    impl_->cancelAndJoinActiveSession();
    std::lock_guard<std::mutex> lock(impl_->mu);
    if (impl_->active_runtime) {
        impl_->active_runtime->applyLoraAdapters({});
    }
}

} // namespace llmhost
