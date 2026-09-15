// PIR-05 — host-side context-replay fixtures over the REAL runtime header.
//
// This test encodes the transactional prefix-cache contract as deterministic
// fixtures against `runtime/ConversationState.hpp` (the production header the
// engine compiles). It needs no llama.cpp, no Android and no model: the reuse
// decision is mirrored here exactly as `Engine.cpp` applies it, and every
// fixture asserts the observable invariant the engine relies on.
//
// Contract under test
// -------------------
//   reusable = min(commonPrefixLength(prompt, active), reusable_tokens)
//              iff (valid && cache_identity == current_identity)
//            = 0 otherwise (full replay)
//
//   tokens_to_decode = prompt.size() - reusable
//
//   After invalidate() (reset / failed seq_rm / unverified shift / aborted
//   decode) the reusable count is 0 and the next request replays the WHOLE
//   prompt — never a suffix-only evaluation against a stale prefix.
//
// Assert-style with an own CHECK macro writing to stderr; exits non-zero on any
// failure. No gtest.

#include "runtime/ConversationState.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <random>
#include <string>
#include <utility>
#include <vector>

namespace {

int g_checks = 0;
int g_failures = 0;

void reportFailure(const char* file, int line, const char* expr) {
    ++g_failures;
    std::fprintf(stderr, "CHECK FAILED %s:%d: %s\n", file, line, expr);
}

} // namespace

// CHECK records every broken expectation (with location) instead of aborting,
// so one run reports the complete picture.
#define CHECK(cond)                                                            \
    do {                                                                       \
        ++g_checks;                                                            \
        if (!(cond)) {                                                         \
            reportFailure(__FILE__, __LINE__, #cond);                          \
        }                                                                      \
    } while (0)

using llmhost::CacheIdentity;
using llmhost::commonPrefixLength;
using llmhost::ConversationState;

using Tokens = std::vector<int32_t>;

namespace {

// ---------------------------------------------------------------------------
// Shared fixtures
// ---------------------------------------------------------------------------
CacheIdentity baseIdentity() {
    CacheIdentity id;
    id.model_path = "/models/qwen3-0.6b.gguf";
    id.chat_template = "chatml";
    id.context_length = 4096;
    id.kv_type_k = "f16";
    id.kv_type_v = "f16";
    id.flash_attn = true;
    return id;
}

Tokens makeTokens(std::size_t count, int32_t base) {
    Tokens out(count);
    for (std::size_t i = 0; i < count; ++i) {
        out[i] = base + static_cast<int32_t>(i);
    }
    return out;
}

Tokens randomTokens(std::mt19937& rng, int length) {
    std::uniform_int_distribution<int32_t> dist(0, 63);
    Tokens out(static_cast<std::size_t>(length));
    for (int32_t& token : out) {
        token = dist(rng);
    }
    return out;
}

// ---------------------------------------------------------------------------
// Policy mirrors of the engine decision points.
//
// `reuseCount` is the raw PIR-05 decision (Engine.cpp: identity_match gate +
// commonPrefixLength + reusable_tokens clamp).
//
// `planReplay` additionally derives tokens_to_decode and applies the
// "last logits" rule (Engine.cpp): an all-cached prompt whose last logits are
// not valid must re-evaluate at least the final prompt token before sampling.
// ---------------------------------------------------------------------------
struct ReplayPlan {
    std::size_t reusable = 0;
    std::size_t tokens_to_decode = 0;
};

std::size_t reuseCount(const Tokens& prompt,
                       const Tokens& active,
                       const ConversationState& state,
                       const std::string& current_identity) {
    if (!state.valid || state.cache_identity != current_identity) {
        return 0; // invalid, or produced under a different cache identity
    }
    const std::size_t common = commonPrefixLength(prompt, active);
    return std::min(common, state.reusable_tokens);
}

ReplayPlan planReplay(const Tokens& prompt,
                      const Tokens& active,
                      const ConversationState& state,
                      const std::string& current_identity,
                      bool need_logits_for_sampling) {
    std::size_t reusable = reuseCount(prompt, active, state, current_identity);
    // Defensive: commonPrefixLength/reusable_tokens must never exceed the prompt.
    if (reusable > prompt.size()) {
        reusable = prompt.size();
    }
    std::size_t to_decode = prompt.size() - reusable;

    if (need_logits_for_sampling && to_decode == 0 && reusable > 0 &&
        !state.last_logits_valid) {
        // Drop the final cached token and re-evaluate it to restore logits.
        reusable -= 1;
        to_decode = prompt.size() - reusable;
    }
    return {reusable, to_decode};
}

// ---------------------------------------------------------------------------
// 1. Full replay vs reuse.
// ---------------------------------------------------------------------------
void testFullReplayVsReuse() {
    const std::string id = baseIdentity().key();
    const Tokens prompt{1, 2, 3, 4, 5, 6};

    // (a) Identical prompt, fully proven cached prefix -> all-cached.
    {
        ConversationState state;
        state.markCommitted(id, prompt.size(), /*logits_valid=*/true);
        const Tokens active = prompt;
        const ReplayPlan plan = planReplay(prompt, active, state, id, false);
        CHECK(plan.reusable == prompt.size());
        CHECK(plan.tokens_to_decode == 0);
        std::printf("  identical:        reusable=%zu tokens_to_decode=%zu\n",
                    plan.reusable, plan.tokens_to_decode);
    }

    // (b) Extension: the prompt is a superset of the cached conversation.
    {
        const Tokens active{1, 2, 3, 4};
        ConversationState state;
        state.markCommitted(id, active.size(), true);
        const ReplayPlan plan = planReplay(prompt, active, state, id, false);
        CHECK(plan.reusable == active.size());
        CHECK(plan.tokens_to_decode == prompt.size() - active.size());
        std::printf("  extension:        reusable=%zu tokens_to_decode=%zu\n",
                    plan.reusable, plan.tokens_to_decode);
    }

    // (c) Divergence in the middle: reuse the true common prefix only.
    {
        const Tokens active{1, 2, 3, 9, 9, 9};
        ConversationState state;
        state.markCommitted(id, active.size(), true);
        const ReplayPlan plan = planReplay(prompt, active, state, id, false);
        CHECK(plan.reusable == 3);
        CHECK(plan.tokens_to_decode == 3);
        std::printf("  divergence_mid:   reusable=%zu tokens_to_decode=%zu\n",
                    plan.reusable, plan.tokens_to_decode);
    }

    // (c2) Common prefix longer than the *proven* prefix -> clamp to proven.
    {
        const Tokens active = prompt;
        ConversationState state;
        state.markCommitted(id, 4, true); // only 4 tokens proven committed
        const ReplayPlan plan = planReplay(prompt, active, state, id, false);
        CHECK(plan.reusable == 4);
        CHECK(plan.tokens_to_decode == prompt.size() - 4);
    }

    // (d) Empty cached state (invalid, nothing reusable) -> full replay.
    {
        ConversationState state; // default: valid == false, reusable == 0
        const Tokens active;
        const ReplayPlan plan = planReplay(prompt, active, state, id, false);
        CHECK(plan.reusable == 0);
        CHECK(plan.tokens_to_decode == prompt.size());
        CHECK(reuseCount(prompt, active, state, id) == 0);
        std::printf("  empty_cache:      reusable=%zu tokens_to_decode=%zu\n",
                    plan.reusable, plan.tokens_to_decode);
    }
}

// ---------------------------------------------------------------------------
// 2. Reset / shift invalidation -> always a full replay, never a suffix.
// ---------------------------------------------------------------------------
void testInvalidationForcesFullReplay() {
    const std::string id = baseIdentity().key();
    const Tokens prompt{10, 20, 30, 40, 50};

    // invalidate() is the shared response to a reset, a failed llama_memory_seq_rm,
    // a compaction that could not be verified, or an aborted decode.
    const char* reasons[] = {"reset", "seq_rm_failed", "unverified_shift", "aborted_decode"};
    for (const char* reason : reasons) {
        ConversationState state;
        state.markCommitted(id, prompt.size(), true);
        CHECK(reuseCount(prompt, prompt, state, id) == prompt.size());

        state.invalidate();

        CHECK(reuseCount(prompt, prompt, state, id) == 0);
        const ReplayPlan plan = planReplay(prompt, prompt, state, id, false);
        CHECK(plan.reusable == 0);
        CHECK(plan.tokens_to_decode == prompt.size()); // full replay, never suffix-only
        CHECK(!state.valid);
        CHECK(state.last_logits_valid == false);
        std::printf("  invalidate(%s): reusable=%zu tokens_to_decode=%zu\n",
                    reason, plan.reusable, plan.tokens_to_decode);
    }
}

// ---------------------------------------------------------------------------
// 3. Cache-identity change invalidates a previously reusable prefix.
// ---------------------------------------------------------------------------
void testIdentityChangeInvalidates() {
    const CacheIdentity base = baseIdentity();
    const Tokens prompt{1, 2, 3, 4};
    const Tokens active = prompt;

    std::vector<std::pair<const char*, CacheIdentity>> variants;
    {
        CacheIdentity c = base;
        c.model_path = "/models/other.gguf";
        variants.emplace_back("model_path", c);
    }
    {
        CacheIdentity c = base;
        c.chat_template = "llama3";
        variants.emplace_back("chat_template", c);
    }
    {
        CacheIdentity c = base;
        c.context_length = 8192;
        variants.emplace_back("context_length", c);
    }
    {
        CacheIdentity c = base;
        c.kv_type_k = "q8_0";
        variants.emplace_back("kv_type_k", c);
    }
    {
        CacheIdentity c = base;
        c.kv_type_v = "q4_0";
        variants.emplace_back("kv_type_v", c);
    }
    {
        CacheIdentity c = base;
        c.flash_attn = false;
        variants.emplace_back("flash_attn", c);
    }

    for (const auto& [name, changed] : variants) {
        ConversationState state;
        state.markCommitted(base.key(), prompt.size(), true);
        CHECK(reuseCount(prompt, active, state, base.key()) == prompt.size());

        // Same tokens, same proof — but a different identity makes them unusable.
        CHECK(reuseCount(prompt, active, state, changed.key()) == 0);
        const ReplayPlan plan = planReplay(prompt, active, state, changed.key(), false);
        CHECK(plan.reusable == 0);
        CHECK(plan.tokens_to_decode == prompt.size());
        CHECK(changed.key() != base.key());
        std::printf("  identity(%s): reusable=%zu\n", name, plan.reusable);
    }
}

// ---------------------------------------------------------------------------
// 4. Commit / abort.
// ---------------------------------------------------------------------------
void testCommitAndAbort() {
    const std::string id = baseIdentity().key();
    const Tokens prompt{1, 2, 3, 4, 5};

    // n > 0 enables reuse.
    {
        ConversationState state;
        state.markCommitted(id, 3, true);
        CHECK(state.valid == true);
        CHECK(reuseCount(prompt, prompt, state, id) == 3);
    }

    // n == 0 never enables reuse.
    {
        ConversationState state;
        state.markCommitted(id, 0, true);
        CHECK(state.valid == false);
        CHECK(state.reusable_tokens == 0);
        const ReplayPlan plan = planReplay(prompt, prompt, state, id, false);
        CHECK(plan.reusable == 0);
        CHECK(plan.tokens_to_decode == prompt.size());
    }

    // Aborted decode: invalidate() clears last_logits_valid, so even an
    // apparently all-cached continuation replays the whole prompt (>= 1 token).
    {
        ConversationState state;
        state.markCommitted(id, prompt.size(), true);
        state.invalidate();
        CHECK(state.last_logits_valid == false);

        const ReplayPlan plan = planReplay(prompt, prompt, state, id, true);
        CHECK(plan.reusable == 0);
        CHECK(plan.tokens_to_decode == prompt.size());
        CHECK(plan.tokens_to_decode >= 1);
    }

    // A proven prefix whose tail was truncated keeps `valid` but loses its
    // logits; an all-cached prompt must re-evaluate at least the final token.
    {
        ConversationState state;
        state.markCommitted(id, prompt.size(), /*logits_valid=*/false);
        CHECK(state.valid == true);
        const ReplayPlan plan = planReplay(prompt, prompt, state, id, true);
        CHECK(plan.reusable == prompt.size() - 1);
        CHECK(plan.tokens_to_decode == 1);
        CHECK(plan.tokens_to_decode >= 1);

        // Without the sampling requirement the prefix stays fully reusable.
        const ReplayPlan noLogits = planReplay(prompt, prompt, state, id, false);
        CHECK(noLogits.reusable == prompt.size());
        CHECK(noLogits.tokens_to_decode == 0);
    }
}

// ---------------------------------------------------------------------------
// 5. Repeated shifts keep the accounting invariant.
// ---------------------------------------------------------------------------
void testRepeatedShiftsKeepInvariants() {
    const std::string id = baseIdentity().key();
    ConversationState state;
    Tokens active = makeTokens(8, 0);
    state.markCommitted(id, active.size(), true);
    CHECK(state.reusable_tokens <= active.size());

    for (int turn = 0; turn < 64; ++turn) {
        Tokens prompt = active;
        prompt.push_back(100 + turn);

        const ReplayPlan plan = planReplay(prompt, active, state, id, false);
        CHECK(plan.reusable <= prompt.size());
        CHECK(plan.tokens_to_decode == prompt.size() - plan.reusable); // no underflow
        CHECK(plan.reusable <= state.reusable_tokens);
        CHECK(plan.reusable <= active.size());

        // Commit a successful decode of the whole prompt.
        state.markCommitted(id, prompt.size(), true);
        active = prompt;
        CHECK(state.reusable_tokens <= active.size());

        // Simulate the engine's sliding-window shift: drop a chunk after the
        // reserved prefix, then re-commit the surviving size.
        const std::size_t drop = 1 + (active.size() / 8);
        if (active.size() >= drop + 2) {
            active.erase(active.begin() + 1, active.begin() + 1 + drop);
            state.markCommitted(id, active.size(), state.last_logits_valid);
        }
        CHECK(state.reusable_tokens <= active.size());
        if (state.valid) {
            CHECK(state.reusable_tokens == active.size());
        }

        // A shorter prompt must still produce a non-negative decode count.
        Tokens shortPrompt(active.begin(), active.begin() + active.size() / 2);
        const ReplayPlan shortPlan = planReplay(shortPrompt, active, state, id, false);
        CHECK(shortPlan.reusable <= shortPrompt.size());
        CHECK(shortPlan.tokens_to_decode == shortPrompt.size() - shortPlan.reusable);
        CHECK(shortPlan.tokens_to_decode <= shortPrompt.size());
    }
    std::printf("  repeated_shifts:  64 turns, reusable<=active maintained\n");
}

// ---------------------------------------------------------------------------
// 6. Monotonic replay accounting over a fixed-seed random sequence.
// ---------------------------------------------------------------------------
void testMonotonicReplayAccounting() {
    const std::string id = baseIdentity().key();
    std::mt19937 rng(0xC0FFEEu); // fixed seed -> deterministic fixture
    std::uniform_int_distribution<int> lenDist(1, 24);
    std::uniform_int_distribution<int> actionDist(0, 4);
    std::uniform_int_distribution<int32_t> tokenDist(0, 63);

    ConversationState state;
    Tokens committed;
    std::size_t cumulativePrompt = 0;
    std::size_t cumulativeSaved = 0;
    int resets = 0;
    int extensions = 0;

    constexpr int kTurns = 256;
    for (int turn = 0; turn < kTurns; ++turn) {
        Tokens prompt;
        switch (actionDist(rng)) {
            case 0: // exact replay of the cached conversation
                prompt = committed;
                break;
            case 1: // extension of the cached conversation
                prompt = committed;
                prompt.push_back(tokenDist(rng));
                ++extensions;
                break;
            case 2: { // truncation to a cached prefix
                if (committed.size() > 1) {
                    const std::size_t keep = 1 + (rng() % (committed.size() - 1));
                    prompt.assign(committed.begin(), committed.begin() + keep);
                } else {
                    prompt = randomTokens(rng, lenDist(rng));
                }
                break;
            }
            case 3: // fresh, unrelated prompt
                prompt = randomTokens(rng, lenDist(rng));
                break;
            default: // reset / abort: cache invalidated, conversation dropped
                state.invalidate();
                committed.clear();
                prompt = randomTokens(rng, lenDist(rng));
                ++resets;
                break;
        }
        if (prompt.empty()) {
            prompt.push_back(tokenDist(rng));
        }

        const ReplayPlan plan = planReplay(prompt, committed, state, id, false);
        CHECK(plan.reusable <= prompt.size());
        CHECK(plan.tokens_to_decode == prompt.size() - plan.reusable);
        CHECK(plan.reusable <= committed.size());
        // Every reported reuse must be a genuine prefix of the committed tokens.
        CHECK(commonPrefixLength(prompt, committed) >= plan.reusable);

        cumulativePrompt += prompt.size();
        cumulativeSaved += plan.reusable;
        // Reuse can never exceed the tokens actually submitted for evaluation.
        CHECK(cumulativeSaved <= cumulativePrompt);

        // Full (or suffixed) decode succeeds and commits the entire prompt.
        state.markCommitted(id, prompt.size(), true);
        committed = prompt;
    }

    CHECK(extensions > 0);
    CHECK(resets > 0);
    CHECK(cumulativeSaved <= cumulativePrompt);
    std::printf("  accounting:       turns=%d prompt_tokens=%zu saved_tokens=%zu "
                "resets=%d extensions=%d\n",
                kTurns, cumulativePrompt, cumulativeSaved, resets, extensions);
}

} // namespace

int main() {
    std::printf("context_replay_test: exercising runtime/ConversationState.hpp\n");

    testFullReplayVsReuse();
    testInvalidationForcesFullReplay();
    testIdentityChangeInvalidates();
    testCommitAndAbort();
    testRepeatedShiftsKeepInvariants();
    testMonotonicReplayAccounting();

    std::printf("checks: %d, failures: %d\n", g_checks, g_failures);
    if (g_failures != 0) {
        std::fprintf(stderr, "context_replay_test: FAILED (%d/%d checks)\n",
                     g_failures, g_checks);
        return 1;
    }
    std::printf("context_replay_test: OK\n");
    return 0;
}
