// PIR-00 / PIR-05 — host unit tests for the header-only prefix-cache accounting.
//
// Exercises the REAL production header (`runtime/ConversationState.hpp`) with a
// tiny assert-style CHECK macro. No gtest, no llama.cpp, no Android. Exits
// non-zero if any check fails.

#include "runtime/ConversationState.hpp"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace {

int g_checks = 0;
int g_failures = 0;

void reportFailure(const char* file, int line, const char* expr) {
    ++g_failures;
    std::fprintf(stderr, "CHECK FAILED %s:%d: %s\n", file, line, expr);
}

} // namespace

// Each CHECK bumps the counter and records a failure (with location) on
// mismatch. The macro never aborts, so one run reports every broken expectation.
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

// ---------------------------------------------------------------------------
// CacheIdentity
// ---------------------------------------------------------------------------
namespace {

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

void testCacheIdentityKeyStability() {
    // Identical fields produce identical keys.
    CHECK(baseIdentity().key() == baseIdentity().key());

    // A change in *any* field changes the key.
    {
        CacheIdentity changed = baseIdentity();
        changed.model_path = "/models/other.gguf";
        CHECK(changed.key() != baseIdentity().key());
    }
    {
        CacheIdentity changed = baseIdentity();
        changed.chat_template = "llama3";
        CHECK(changed.key() != baseIdentity().key());
    }
    {
        CacheIdentity changed = baseIdentity();
        changed.context_length = 8192;
        CHECK(changed.key() != baseIdentity().key());
    }
    {
        CacheIdentity changed = baseIdentity();
        changed.kv_type_k = "q8_0";
        CHECK(changed.key() != baseIdentity().key());
    }
    {
        CacheIdentity changed = baseIdentity();
        changed.kv_type_v = "q8_0";
        CHECK(changed.key() != baseIdentity().key());
    }
    {
        CacheIdentity changed = baseIdentity();
        changed.flash_attn = false;
        CHECK(changed.key() != baseIdentity().key());
    }

    // Length-prefixing must prevent concatenation aliasing: splitting a string
    // across two fields is a *different* identity, not the same bytes.
    {
        CacheIdentity ab_c;
        ab_c.model_path = "ab";
        ab_c.chat_template = "c";

        CacheIdentity a_bc;
        a_bc.model_path = "a";
        a_bc.chat_template = "bc";

        // Same total characters, same field order — must NOT alias.
        CHECK(ab_c.key() != a_bc.key());
    }
    {
        // Empty vs non-empty fields remain distinguishable.
        CacheIdentity empty;
        empty.model_path = "";
        empty.chat_template = "x";
        CacheIdentity nonempty;
        nonempty.model_path = "x";
        nonempty.chat_template = "";
        CHECK(empty.key() != nonempty.key());
    }
}

// ---------------------------------------------------------------------------
// commonPrefixLength
// ---------------------------------------------------------------------------
void testCommonPrefixLength() {
    // Empty vs non-empty -> 0 (both orderings).
    CHECK(commonPrefixLength(std::vector<int32_t>{}, std::vector<int32_t>{1, 2, 3}) == 0);
    CHECK(commonPrefixLength(std::vector<int32_t>{1, 2, 3}, std::vector<int32_t>{}) == 0);
    CHECK(commonPrefixLength(std::vector<int32_t>{}, std::vector<int32_t>{}) == 0);

    // Identical vectors -> full size.
    const std::vector<int32_t> same{1, 2, 3, 4, 5};
    CHECK(commonPrefixLength(same, same) == same.size());

    // Diverging vectors -> index of first mismatch.
    CHECK(commonPrefixLength(std::vector<int32_t>{1, 2, 3, 4},
                             std::vector<int32_t>{1, 2, 9}) == 2);

    // One is a prefix of the other -> length of the shorter vector.
    CHECK(commonPrefixLength(std::vector<int32_t>{1, 2, 3},
                             std::vector<int32_t>{1, 2, 3, 4, 5}) == 3);

    // Mismatch at the very first element.
    CHECK(commonPrefixLength(std::vector<int32_t>{7, 8},
                             std::vector<int32_t>{1, 2}) == 0);
}

// ---------------------------------------------------------------------------
// ConversationState
// ---------------------------------------------------------------------------
void testConversationStateDefaults() {
    ConversationState state;
    CHECK(state.valid == false);
    CHECK(state.cache_identity.empty());
    CHECK(state.reusable_tokens == 0);
    CHECK(state.last_logits_valid == false);
}

void testMarkCommittedPositive() {
    ConversationState state;
    state.markCommitted("identity-A", 7, true);

    CHECK(state.valid == true);
    CHECK(state.reusable_tokens == 7);
    CHECK(state.last_logits_valid == true);
    CHECK(state.cache_identity == "identity-A");

    // logits_valid is carried through independently of `valid`.
    ConversationState noLogits;
    noLogits.markCommitted("identity-B", 3, false);
    CHECK(noLogits.valid == true);
    CHECK(noLogits.reusable_tokens == 3);
    CHECK(noLogits.last_logits_valid == false);
    CHECK(noLogits.cache_identity == "identity-B");
}

void testMarkCommittedZeroLeavesInvalid() {
    ConversationState state;
    state.markCommitted("identity-zero", 0, true);

    CHECK(state.valid == false);
    CHECK(state.reusable_tokens == 0);
    // The identity is still stored even though nothing is reusable.
    CHECK(state.cache_identity == "identity-zero");
}

void testInvalidateClearsEverything() {
    ConversationState state;
    state.markCommitted("identity-A", 42, true);
    CHECK(state.valid == true);

    state.invalidate();

    CHECK(state.valid == false);
    CHECK(state.cache_identity.empty());
    CHECK(state.reusable_tokens == 0);
    CHECK(state.last_logits_valid == false);
}

void testNoteProgressPreservesIdentity() {
    ConversationState state;
    state.markCommitted("identity-stable", 5, true);

    state.noteProgress(9, false);

    CHECK(state.reusable_tokens == 9);
    CHECK(state.valid == true);
    CHECK(state.last_logits_valid == false);
    // The hot path must not touch the (potentially large) identity key.
    CHECK(state.cache_identity == "identity-stable");

    // noteProgress to zero flips valid off but keeps the identity.
    state.noteProgress(0, false);
    CHECK(state.valid == false);
    CHECK(state.reusable_tokens == 0);
    CHECK(state.cache_identity == "identity-stable");
}

// ---------------------------------------------------------------------------
// Cache-policy fixture — encodes the Engine's prefix-reuse contract.
//
// Given the engine's proof state, the number of KV tokens it may reuse is
//
//     min(common_prefix, reusable_tokens)   when valid && identity_match
//     0                                     otherwise
//
// and a failed `seq_rm` ALWAYS forces a full replay (0 reusable). This is the
// regression guard: reusing a suffix after a failed reset would evaluate tokens
// against a stale prefix.
// ---------------------------------------------------------------------------
struct ReuseDecision {
    std::size_t reusable = 0;
    bool full_replay = true;
};

// Pure policy mirror of the Engine decision point. Kept free of llama types so
// the contract can be tested on the host.
ReuseDecision decideReuse(bool valid,
                          bool identity_match,
                          std::size_t common_prefix,
                          std::size_t reusable_tokens,
                          bool seq_rm_ok) {
    if (!valid || !identity_match) {
        return {0, true};
    }
    if (!seq_rm_ok) {
        // A reset that did not succeed leaves the cache unproven: replay fully.
        return {0, true};
    }
    const std::size_t reusable = std::min(common_prefix, reusable_tokens);
    return {reusable, reusable == 0};
}

struct ReuseCase {
    const char* name;
    bool valid;
    bool identity_match;
    std::size_t common_prefix;
    std::size_t reusable_tokens;
    bool seq_rm_ok;
    std::size_t expected_reusable;
    bool expected_full_replay;
};

void testCachePolicyFixture() {
    const ReuseCase cases[] = {
        {"valid+match, whole prefix reusable", true, true, 8, 8, true, 8, false},
        {"valid+match, common prefix clamps", true, true, 3, 8, true, 3, false},
        {"valid+match, reusable clamps", true, true, 8, 3, true, 3, false},
        {"valid+match but empty common prefix", true, true, 0, 8, true, 0, true},
        {"invalid state -> full replay", false, true, 8, 8, true, 0, true},
        {"identity mismatch -> full replay", true, false, 8, 8, true, 0, true},
        // Regression guard: a failed seq_rm must NOT yield a reusable suffix.
        {"seq_rm failed -> full replay", true, true, 8, 8, false, 0, true},
        {"seq_rm failed, partial common prefix", true, true, 4, 8, false, 0, true},
    };

    for (const ReuseCase& c : cases) {
        const ReuseDecision d =
            decideReuse(c.valid, c.identity_match, c.common_prefix, c.reusable_tokens, c.seq_rm_ok);

        std::printf("  fixture: %-40s reusable=%zu full_replay=%s\n",
                    c.name, d.reusable, d.full_replay ? "yes" : "no");

        CHECK(d.reusable == c.expected_reusable);
        CHECK(d.full_replay == c.expected_full_replay);
        // The essential invariant: a full replay never leaves a reusable count.
        CHECK(!(d.full_replay && d.reusable != 0));
    }
}

} // namespace

int main() {
    std::printf("conversation_state_test: exercising runtime/ConversationState.hpp\n");

    testCacheIdentityKeyStability();
    testCommonPrefixLength();
    testConversationStateDefaults();
    testMarkCommittedPositive();
    testMarkCommittedZeroLeavesInvalid();
    testInvalidateClearsEverything();
    testNoteProgressPreservesIdentity();
    testCachePolicyFixture();

    std::printf("checks: %d, failures: %d\n", g_checks, g_failures);
    if (g_failures != 0) {
        std::fprintf(stderr, "conversation_state_test: FAILED (%d/%d checks)\n",
                     g_failures, g_checks);
        return 1;
    }
    std::printf("conversation_state_test: OK\n");
    return 0;
}
