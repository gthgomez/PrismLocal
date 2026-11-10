#pragma once

#include <string_view>

namespace llmhost {

// GPU weight placement and host-weight op offload are one decision.
// llama.cpp keeps every enumerated GPU in the model device list when
// `devices` is null, and with op_offload left at its default a host-resident
// weight matmul can still run on that GPU even when n_gpu_layers is 0.
// The logged backend name is a compile-time label and does not reveal that.
// A CPU request is therefore "no Vulkan switch" OR "zero GPU layers".
inline bool gpuLayerOffloadRequested(bool use_vulkan, int gpu_layers) {
    return use_vulkan && gpu_layers > 0;
}

// The generation loop tokenizes the full rendered prompt on every turn, then
// reuses a KV prefix. `full_prompt` must be true for that call. Passing false
// is only correct when the text is a suffix that already sits after a BOS
// token held in the cache.
//
// ChatML and Mistral templates already contain their own boundary tokens.
// Llama 3 templates start with `<|start_header_id|>` and still require the
// tokenizer to insert `<|begin_of_text|>`. Treating that header as a BOS
// marker dropped the real BOS on the first turn, and keying the decision off
// a warm KV position dropped it again on the next turn.
inline bool shouldAddSpecialTokens(std::string_view formatted_prompt, bool full_prompt) {
    if (!full_prompt) {
        return false;
    }
    return formatted_prompt.rfind("<s>", 0) != 0 &&
        formatted_prompt.rfind("<|im_start|>", 0) != 0 &&
        formatted_prompt.rfind("<|begin_of_text|>", 0) != 0 &&
        formatted_prompt.rfind("[INST]", 0) != 0;
}

}  // namespace llmhost
