#include "runtime/InferencePolicy.hpp"

#include <cstdio>
#include <string_view>

using llmhost::gpuLayerOffloadRequested;
using llmhost::shouldAddSpecialTokens;

namespace {

int fail(const char* message) {
    std::fputs(message, stderr);
    std::fputc('\n', stderr);
    return 1;
}

}  // namespace

int main() {
    if (gpuLayerOffloadRequested(false, 0) || gpuLayerOffloadRequested(true, 0) ||
        gpuLayerOffloadRequested(false, 32)) {
        return fail("zero GPU layers or a CPU request must not offload");
    }
    if (!gpuLayerOffloadRequested(true, 1) || !gpuLayerOffloadRequested(true, 99)) {
        return fail("vulkan with a positive layer count must offload");
    }

    constexpr std::string_view kChatMl =
        "<|im_start|>system\nYou are Assistant.<|im_end|>\n<|im_start|>assistant\n";
    constexpr std::string_view kLlama3 =
        "<|start_header_id|>system<|end_header_id|>\n\nYou are Assistant.<|eot_id|>";
    constexpr std::string_view kAlreadyBos = "<|begin_of_text|><|start_header_id|>system";
    constexpr std::string_view kMistral = "[INST] hello [/INST]";
    constexpr std::string_view kLegacyBos = "<s>hello";

    if (shouldAddSpecialTokens(kChatMl, true)) {
        return fail("ChatML must not gain a second BOS");
    }
    if (!shouldAddSpecialTokens(kLlama3, true)) {
        return fail("Llama 3 full prompt must keep tokenizer BOS");
    }
    if (shouldAddSpecialTokens(kLlama3, false)) {
        return fail("a suffix after a cached BOS must not gain another BOS");
    }
    if (shouldAddSpecialTokens(kAlreadyBos, true) || shouldAddSpecialTokens(kMistral, true) ||
        shouldAddSpecialTokens(kLegacyBos, true)) {
        return fail("prompts that already carry a boundary token must not add another");
    }
    if (!shouldAddSpecialTokens("Hello", true)) {
        return fail("an untemplated full prompt may add the tokenizer BOS");
    }

    std::puts("inference policy contract passed");
    return 0;
}
