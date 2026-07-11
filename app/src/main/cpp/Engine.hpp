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

struct GenerationConfig {
    int max_tokens = 128;
    int thread_count = 6;
    int context_length = 2048;
    int batch_size = 512;
    float temperature = 0.70f;
    int top_k = 40;
    float top_p = 0.95f;
    float repeat_penalty = 1.10f;
    int gpu_layers = 0;
    bool continue_from_context = false;
    std::string grammar = "";
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
    std::string runBenchmark(GenerationConfig config, int prompt_tokens, int generation_tokens, int repetitions);
    void cancelGeneration(int generation_id);
    std::vector<int32_t> drainTokens(int generation_id, int max_tokens);
    void ackEof(int generation_id);
    std::string decodeTokens(int generation_id, const std::vector<int32_t>& tokens);
    int getState(int generation_id) const;
    void setMemoryPressure(int level);
    struct DrainResult {
        std::vector<int32_t> tokens;
        std::string text;
        int state = 0;
        int prompt_tokens = 0;
    };
    DrainResult drainDecodeAndState(int generation_id, int max_tokens);

    // Encode text and return float embeddings. Returns empty vector on failure.
    std::vector<float> encode(const std::string& text);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace llmhost
