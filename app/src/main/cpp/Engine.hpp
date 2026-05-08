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
};

struct GenerationConfig {
    int max_tokens = 128;
    int thread_count = 6;
};

class Engine {
public:
    explicit Engine(bool debug_hooks_enabled);
    ~Engine();

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    bool loadModel(const std::string& path);
    void unloadModel();
    int startGeneration(const std::string& prompt, int generation_id, GenerationConfig config);
    void cancelGeneration(int generation_id);
    std::vector<int32_t> drainTokens(int generation_id, int max_tokens);
    void ackEof(int generation_id);
    std::string decodeTokens(int generation_id, const std::vector<int32_t>& tokens);
    int getState(int generation_id) const;
    void setMemoryPressure(int level);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace llmhost
