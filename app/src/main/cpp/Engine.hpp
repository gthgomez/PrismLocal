#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace llmhost {

enum class StreamState : uint32_t {
    Idle = 0,
    Generating = 1,
    CancelRequested = 2,
    Eof = 3,
    Cancelled = 4,
    Error = 5,
    Tombstoned = 6,
    MaxTokens = 7,
};

struct LoraAdapterSpec {
    std::string path;
    float scale = 1.0f;
};

struct ChatMessage {
    std::string role;
    std::string content;
};

struct GenerationConfig {
    int max_tokens = 128;
    int thread_count = 4;
    int context_length = 2048;
    int batch_size = 512;
    float temperature = 0.70f;
    int top_k = 40;
    float top_p = 0.95f;
    float repeat_penalty = 1.10f;
    int gpu_layers = 0;
    bool continue_from_context = false;
    std::string grammar = "";
    std::string kv_cache_type_k = "q8_0";
    std::string kv_cache_type_v = "q8_0";
    bool enable_flash_attn = true;
    // PIR-06: explicit backend preference. When false, GPU layer offload is
    // disabled for this load (CPU-only). Native readback still reports the
    // actually-applied backend; this is only the request.
    bool use_vulkan = true;
    std::vector<LoraAdapterSpec> lora_adapters;
};

class Engine {
public:
    explicit Engine(bool debug_hooks_enabled);
    ~Engine();

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    bool loadModel(const std::string& path, GenerationConfig config = {});
    void unloadModel();
    void resetConversation();
    int startGeneration(const std::string& prompt, int generation_id, GenerationConfig config);
    int startGenerationChat(const std::vector<ChatMessage>& messages, int generation_id, GenerationConfig config = {});
    std::string runBenchmark(GenerationConfig config, int prompt_tokens, int generation_tokens, int repetitions);
    void cancelGeneration(int generation_id);
    std::vector<int32_t> drainTokens(int generation_id, int max_tokens);
    bool acknowledgeDrainedTokens(int generation_id, int expected_tail, int token_count);
    void ackEof(int generation_id);
    std::string decodeTokens(int generation_id, const std::vector<int32_t>& tokens);
    int getState(int generation_id) const;
    void setMemoryPressure(int level);
    void setThreadCount(int thread_count);
    struct DrainResult {
        std::vector<int32_t> tokens;
        std::string text;
        int state = 0;
        int prompt_tokens = 0;
        int64_t ttft_ms = 0;
        float tokens_per_sec = 0.0f;
        int active_threads = 0;
        int error_code = 0;
        // PIR-02: stream terminal/drain accounting. `produced` counts tokens
        // written into the ring; `drained` counts tokens handed to the consumer.
        // While `produced > drained` a terminal state must not be acknowledged.
        int schema_version = 2;
        int64_t produced = 0;
        int64_t drained = 0;
        int drain_tail = 0;
        bool pending = false;
    };
    DrainResult drainDecodeAndState(int generation_id, int max_tokens);

    // Encode text and return float embeddings. Returns empty vector on failure.
    std::vector<float> encode(const std::string& text);

    // Hardware telemetry methods
    std::string get_backend_name() const;
    int32_t get_gpu_layers() const;
    bool is_kleidiai_enabled() const;
    bool is_vulkan_enabled() const;

    // LoRA Adapter Management
    bool applyLoraAdapters(const std::vector<LoraAdapterSpec>& adapters);
    void clearLoraAdapters();

private:
    bool decodeTokensChecked(int generation_id, const std::vector<int32_t>& tokens, std::string& output);
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace llmhost
