// Replicates PrismLocal's generation loop against libllama:
// legacy llama_chat_apply_template, the production special-token policy,
// chunked prefill with logits only on the last token, then
// llama_sampler_sample(ctx, -1). Prints prompt token ids and the top-k
// logits after prefill so the result can be compared with llama-completion.
//
// Build against the pinned llama.cpp (bbeb89d). See run-probe.sh.

#include "llama.h"
#include "runtime/InferencePolicy.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr int kCtx = 2048;
constexpr int kBatch = 256;

struct Args {
    std::string model;
    std::string system_prompt = "You are Assistant in a local Android chat. Use the recent conversation for context.";
    std::string user_prompt = "The capital of France is";
    int threads = 1;
    int n_predict = 16;
    float temperature = 0.0f;
    int top_k = 1;
    float top_p = 1.0f;
    float repeat_penalty = 1.0f;
    float freq_penalty = 0.0f;
    float presence_penalty = 0.0f;
    uint32_t seed = 1;
    bool repack = true;
    bool flash_attn = false;
    int runs = 1;
    int top_logits = 5;
    std::string second_user;
};

void usage() {
    std::fprintf(stderr,
        "usage: prism_loop_probe --model PATH [--user TEXT] [--system TEXT]\n"
        "  [--threads N] [--n N] [--temp F] [--top-k N] [--top-p F]\n"
        "  [--repeat F] [--freq F] [--presence F] [--seed N]\n"
        "  [--no-repack] [--flash-attn] [--runs N] [--top-logits N]\n"
        "  [--second-user TEXT]\n");
}

bool parseArgs(int argc, char** argv, Args& args) {
    for (int i = 1; i < argc; ++i) {
        const std::string key = argv[i];
        auto need = [&](const char* name) -> const char* {
            if (i + 1 >= argc) {
                std::fprintf(stderr, "missing value for %s\n", name);
                std::exit(2);
            }
            return argv[++i];
        };
        if (key == "--model") args.model = need("--model");
        else if (key == "--user") args.user_prompt = need("--user");
        else if (key == "--system") args.system_prompt = need("--system");
        else if (key == "--threads") args.threads = std::atoi(need("--threads"));
        else if (key == "--n") args.n_predict = std::atoi(need("--n"));
        else if (key == "--temp") args.temperature = std::strtof(need("--temp"), nullptr);
        else if (key == "--top-k") args.top_k = std::atoi(need("--top-k"));
        else if (key == "--top-p") args.top_p = std::strtof(need("--top-p"), nullptr);
        else if (key == "--repeat") args.repeat_penalty = std::strtof(need("--repeat"), nullptr);
        else if (key == "--freq") args.freq_penalty = std::strtof(need("--freq"), nullptr);
        else if (key == "--presence") args.presence_penalty = std::strtof(need("--presence"), nullptr);
        else if (key == "--seed") args.seed = static_cast<uint32_t>(std::strtoul(need("--seed"), nullptr, 10));
        else if (key == "--no-repack") args.repack = false;
        else if (key == "--flash-attn") args.flash_attn = true;
        else if (key == "--runs") args.runs = std::atoi(need("--runs"));
        else if (key == "--top-logits") args.top_logits = std::atoi(need("--top-logits"));
        else if (key == "--second-user") args.second_user = need("--second-user");
        else if (key == "--help") { usage(); std::exit(0); }
        else { std::fprintf(stderr, "unknown arg %s\n", key.c_str()); return false; }
    }
    return !args.model.empty();
}

std::string applyChat(
    const llama_model* model,
    const std::vector<std::pair<std::string, std::string>>& roles) {
    const char* tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr || tmpl[0] == '\0') {
        return roles.empty() ? std::string() : roles.back().second;
    }
    std::vector<llama_chat_message> messages;
    messages.reserve(roles.size());
    for (const auto& role : roles) {
        messages.push_back({role.first.c_str(), role.second.c_str()});
    }
    const int32_t required = llama_chat_apply_template(
        tmpl, messages.data(), static_cast<int32_t>(messages.size()), true, nullptr, 0);
    if (required <= 0) {
        std::fprintf(stderr, "chat_apply_template failed rc=%d\n", required);
        std::exit(3);
    }
    std::string formatted(static_cast<size_t>(required) + 32, '\0');
    int32_t actual = llama_chat_apply_template(
        tmpl, messages.data(), static_cast<int32_t>(messages.size()), true,
        formatted.data(), static_cast<int32_t>(formatted.size()));
    if (actual > static_cast<int32_t>(formatted.size())) {
        formatted.resize(static_cast<size_t>(actual));
        actual = llama_chat_apply_template(
            tmpl, messages.data(), static_cast<int32_t>(messages.size()), true,
            formatted.data(), static_cast<int32_t>(formatted.size()));
    }
    if (actual <= 0) {
        std::fprintf(stderr, "chat_apply_template failed rc=%d\n", actual);
        std::exit(3);
    }
    formatted.resize(static_cast<size_t>(actual));
    return formatted;
}

int decodeTokens(llama_context* ctx, const llama_token* tokens, int32_t count, llama_pos start_pos) {
    if (count <= 0) return 0;
    if (count == 1) {
        llama_batch batch = llama_batch_init(1, 0, 1);
        batch.n_tokens = 1;
        batch.token[0] = tokens[0];
        batch.pos[0] = start_pos;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        const int rc = llama_decode(ctx, batch);
        llama_batch_free(batch);
        return rc;
    }
    llama_batch batch = llama_batch_init(std::min(count, kBatch), 0, 1);
    for (int32_t offset = 0; offset < count; offset += kBatch) {
        const int32_t chunk = std::min(kBatch, count - offset);
        batch.n_tokens = chunk;
        for (int32_t i = 0; i < chunk; ++i) {
            batch.token[i] = tokens[offset + i];
            batch.pos[i] = start_pos + offset + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i == chunk - 1 && offset + chunk == count) ? 1 : 0;
        }
        const int rc = llama_decode(ctx, batch);
        if (rc != 0) {
            llama_batch_free(batch);
            return rc;
        }
    }
    llama_batch_free(batch);
    return 0;
}

void printTopLogits(llama_context* ctx, const llama_vocab* vocab, int k) {
    const float* logits = llama_get_logits_ith(ctx, -1);
    const int n = llama_vocab_n_tokens(vocab);
    if (logits == nullptr || n <= 0) {
        std::printf("logits=missing\n");
        return;
    }
    std::vector<int> idx(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) idx[static_cast<size_t>(i)] = i;
    const int keep = std::min(k, n);
    std::partial_sort(idx.begin(), idx.begin() + keep, idx.end(), [&](int a, int b) {
        return logits[a] > logits[b];
    });
    std::printf("top_logits");
    char piece[128];
    for (int i = 0; i < keep; ++i) {
        const int id = idx[static_cast<size_t>(i)];
        const int len = llama_token_to_piece(vocab, id, piece, static_cast<int>(sizeof(piece) - 1), 0, true);
        std::string text = len > 0 ? std::string(piece, piece + len) : "?";
        for (char& c : text) {
            if (c == '\n') c = ' ';
        }
        std::printf(" %d:%.6f:%s", id, logits[id], text.c_str());
    }
    std::printf("\n");
}

std::string pieceOf(const llama_vocab* vocab, llama_token token) {
    char buf[256];
    const int len = llama_token_to_piece(vocab, token, buf, static_cast<int>(sizeof(buf)), 0, false);
    if (len <= 0) return {};
    return std::string(buf, buf + len);
}

}  // namespace

int main(int argc, char** argv) {
    Args args;
    if (!parseArgs(argc, argv, args)) {
        usage();
        return 2;
    }

    llama_backend_init();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.use_mmap = true;
    model_params.use_extra_bufts = args.repack;
    model_params.check_tensors = true;

    llama_model* model = llama_model_load_from_file(args.model.c_str(), model_params);
    if (model == nullptr) {
        std::fprintf(stderr, "model load failed\n");
        return 4;
    }
    const llama_vocab* vocab = llama_model_get_vocab(model);
    const std::string formatted = applyChat(model, {
        {"system", args.system_prompt},
        {"user", args.user_prompt},
    });
    const bool add_special = llmhost::shouldAddSpecialTokens(formatted, true);
    std::printf("formatted_bytes=%zu add_special=%d\n", formatted.size(), add_special ? 1 : 0);
    std::printf("FORMATTED_BEGIN\n%sFORMATTED_END\n", formatted.c_str());

    const int32_t token_count = -llama_tokenize(
        vocab, formatted.c_str(), static_cast<int32_t>(formatted.size()), nullptr, 0, add_special, true);
    if (token_count <= 0) {
        std::fprintf(stderr, "tokenize size failed %d\n", token_count);
        return 5;
    }
    std::vector<llama_token> prompt(static_cast<size_t>(token_count));
    const int32_t actual = llama_tokenize(
        vocab, formatted.c_str(), static_cast<int32_t>(formatted.size()),
        prompt.data(), token_count, add_special, true);
    if (actual != token_count) {
        std::fprintf(stderr, "tokenize mismatch %d vs %d\n", actual, token_count);
        return 5;
    }
    std::printf("prompt_tokens=%d ids=", actual);
    for (int i = 0; i < actual; ++i) {
        std::printf("%s%d", i ? "," : "", prompt[static_cast<size_t>(i)]);
    }
    std::printf("\n");

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = kCtx;
    ctx_params.n_batch = kBatch;
    ctx_params.n_ubatch = kBatch;
    ctx_params.n_threads = args.threads;
    ctx_params.n_threads_batch = args.threads;
    ctx_params.type_k = GGML_TYPE_F16;
    ctx_params.type_v = GGML_TYPE_F16;
    ctx_params.flash_attn_type = args.flash_attn ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    ctx_params.offload_kqv = false;
    ctx_params.op_offload = false;

    for (int run = 0; run < args.runs; ++run) {
        llama_context* ctx = llama_init_from_model(model, ctx_params);
        if (ctx == nullptr) {
            std::fprintf(stderr, "context init failed\n");
            return 6;
        }
        const int prefill = decodeTokens(ctx, prompt.data(), actual, 0);
        if (prefill != 0) {
            std::fprintf(stderr, "prefill rc=%d\n", prefill);
            return 7;
        }
        if (run == 0) {
            printTopLogits(ctx, vocab, args.top_logits);
        }

        llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
            64, args.repeat_penalty, args.freq_penalty, args.presence_penalty));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(args.top_k));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(args.top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(args.temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(args.seed));

        std::string text;
        std::string ids;
        llama_pos pos = actual;
        int generated = 0;
        for (int i = 0; i < args.n_predict; ++i) {
            const llama_token token = llama_sampler_sample(sampler, ctx, -1);
            if (llama_vocab_is_eog(vocab, token)) {
                break;
            }
            if (!ids.empty()) ids += ',';
            ids += std::to_string(token);
            text += pieceOf(vocab, token);
            const int rc = decodeTokens(ctx, &token, 1, pos);
            if (rc != 0) {
                std::fprintf(stderr, "decode rc=%d\n", rc);
                return 8;
            }
            pos += 1;
            generated += 1;
        }
        std::printf("run=%d seed=%u generated=%d ids=%s\nTEXT_BEGIN\n%s\nTEXT_END\n",
            run, args.seed, generated, ids.c_str(), text.c_str());

        if (run == 0 && !args.second_user.empty() && generated > 0) {
            std::vector<llama_token> active = prompt;
            // Reconstruct generated ids from the printed sequence by re-tokenizing
            // the pieces is unnecessary: sample them again is wrong. Decode stored
            // them only as text. Re-parse `ids`.
            {
                std::string rest = ids;
                while (!rest.empty()) {
                    const auto comma = rest.find(',');
                    const std::string part = rest.substr(0, comma);
                    active.push_back(static_cast<llama_token>(std::atoi(part.c_str())));
                    if (comma == std::string::npos) break;
                    rest.erase(0, comma + 1);
                }
            }
            const std::string second = applyChat(model, {
                {"system", args.system_prompt},
                {"user", args.user_prompt},
                {"assistant", text},
                {"user", args.second_user},
            });
            const bool second_special = llmhost::shouldAddSpecialTokens(second, true);
            const int32_t second_count = -llama_tokenize(
                vocab, second.c_str(), static_cast<int32_t>(second.size()), nullptr, 0, second_special, true);
            std::vector<llama_token> second_tokens(static_cast<size_t>(second_count));
            const int32_t second_actual = llama_tokenize(
                vocab, second.c_str(), static_cast<int32_t>(second.size()),
                second_tokens.data(), second_count, second_special, true);
            std::size_t common = 0;
            const std::size_t limit = std::min(active.size(), static_cast<std::size_t>(second_actual));
            while (common < limit && active[common] == second_tokens[common]) {
                ++common;
            }
            std::printf("second_prompt_tokens=%d common_prefix=%zu\n", second_actual, common);
            if (common < active.size()) {
                const bool removed = llama_memory_seq_rm(
                    llama_get_memory(ctx), 0, static_cast<llama_pos>(common), -1);
                std::printf("seq_rm_ok=%d\n", removed ? 1 : 0);
                if (!removed) {
                    llama_memory_clear(llama_get_memory(ctx), false);
                    common = 0;
                }
            }
            const int32_t suffix = second_actual - static_cast<int32_t>(common);
            const int rc = decodeTokens(ctx, second_tokens.data() + common, suffix, static_cast<llama_pos>(common));
            std::printf("second_prefill_rc=%d suffix=%d\n", rc, suffix);
            if (rc == 0) {
                printTopLogits(ctx, vocab, args.top_logits);
                llama_sampler_reset(sampler);
                std::string second_text;
                std::string second_ids;
                llama_pos second_pos = second_actual;
                for (int i = 0; i < args.n_predict; ++i) {
                    const llama_token token = llama_sampler_sample(sampler, ctx, -1);
                    if (llama_vocab_is_eog(vocab, token)) break;
                    if (!second_ids.empty()) second_ids += ',';
                    second_ids += std::to_string(token);
                    second_text += pieceOf(vocab, token);
                    const int drc = decodeTokens(ctx, &token, 1, second_pos);
                    if (drc != 0) {
                        std::fprintf(stderr, "second decode rc=%d\n", drc);
                        return 9;
                    }
                    second_pos += 1;
                }
                std::printf("second_ids=%s\nSECOND_BEGIN\n%s\nSECOND_END\n", second_ids.c_str(), second_text.c_str());
            }
        }
        llama_sampler_free(sampler);
        llama_free(ctx);
    }

    llama_model_free(model);
    llama_backend_free();
    return 0;
}
