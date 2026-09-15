#pragma once

// PIR-05 — transactional prefix cache / model-aware context policy.
//
// Header-only, dependency-free helpers that centralize the decision of whether a
// KV-cache prefix may be reused. Deliberately free of llama.cpp types (plain
// strings, vectors and integers only) so the accounting can be reasoned about
// and unit-tested without a model, and so adding it needs no CMakeLists change.
//
// The engine owns a `ConversationState` alongside `active_tokens` /
// `current_position`. The invariant it encodes is:
//
//   `valid == true`  <=>  `reusable_tokens == active_tokens.size()` and the live
//                         KV cache provably holds positions [0, reusable_tokens)
//                         for the main sequence, produced under `cache_identity`.
//
// Any operation that cannot prove the KV mutation succeeded must clear `valid`
// (invalidate), so no suffix is ever evaluated against a stale prefix.

#include <cstddef>
#include <string>
#include <vector>

namespace llmhost {

// Identity of everything that determines the layout/semantics of a KV entry.
// A prefix produced under one identity must never be reused under another:
// different model weights, chat template, context size, KV element types or
// FlashAttention kernel all change the meaning of cached keys/values.
struct CacheIdentity {
    std::string model_path;
    std::string chat_template;
    int context_length = 0;
    std::string kv_type_k;
    std::string kv_type_v;
    bool flash_attn = false;

    // Unambiguous, length-prefixed key. Field lengths are encoded so that
    // concatenation can never alias two different identities.
    std::string key() const {
        std::string out;
        out.reserve(model_path.size() + chat_template.size() + 64);
        appendField(out, model_path);
        appendField(out, chat_template);
        appendField(out, std::to_string(context_length));
        appendField(out, kv_type_k);
        appendField(out, kv_type_v);
        appendField(out, flash_attn ? "1" : "0");
        return out;
    }

    bool matches(const CacheIdentity& other) const {
        return key() == other.key();
    }

private:
    static void appendField(std::string& out, const std::string& field) {
        out += std::to_string(field.size());
        out += ':';
        out += field;
        out += '\x1f';
    }
};

// Transactional bookkeeping for the reusable KV prefix.
//
// `valid` means "the engine has proven that active_tokens/current_position still
// describe the live KV cache". It is set only after a sequence operation or a
// decode returns success, and is cleared on any reset, aborted/failed decode,
// context compaction that could not be verified, or cache-identity change.
struct ConversationState {
    std::string cache_identity;
    std::size_t reusable_tokens = 0;
    bool valid = false;
    bool last_logits_valid = false;

    // PIR-05: invalidate-on-abort default. After this call no prefix is marked
    // reusable, so the next request will replay from a clean KV cache.
    void invalidate() {
        cache_identity.clear();
        reusable_tokens = 0;
        valid = false;
        last_logits_valid = false;
    }

    // Record a *proven* committed prefix: only call after the corresponding
    // llama_decode / llama_memory_seq_rm actually returned success.
    void markCommitted(const std::string& identity, std::size_t committed_tokens, bool logits_valid) {
        cache_identity = identity;
        reusable_tokens = committed_tokens;
        valid = committed_tokens > 0;
        last_logits_valid = logits_valid;
    }

    void setLastLogitsValid(bool value) { last_logits_valid = value; }

    // Hot-path variant used inside the per-token decode loop. The cache identity
    // cannot change within a generation, so this updates only the counters and
    // avoids re-copying the (potentially multi-KB) identity key on every token.
    // Requires the identity to have been committed first (markCommitted).
    void noteProgress(std::size_t committed_tokens, bool logits_valid) {
        reusable_tokens = committed_tokens;
        valid = committed_tokens > 0;
        last_logits_valid = logits_valid;
    }

    // (b) Decide whether the stored prefix is reusable for a new request: it must
    // be valid, cover the requested common prefix, and have been produced under
    // the same cache identity.
    bool prefixReusable(const std::string& identity, std::size_t common_prefix) const {
        return valid &&
               common_prefix > 0 &&
               common_prefix <= reusable_tokens &&
               cache_identity == identity;
    }
};

// (a) Length of the longest common prefix of two token vectors. Templated so it
// works for `std::vector<llama_token>` and `std::vector<int32_t>` without naming
// a llama type here (llama_token is a typedef for int32_t).
template <typename T>
std::size_t commonPrefixLength(const std::vector<T>& a, const std::vector<T>& b) {
    const std::size_t limit = a.size() < b.size() ? a.size() : b.size();
    std::size_t i = 0;
    while (i < limit && a[i] == b[i]) {
        ++i;
    }
    return i;
}

} // namespace llmhost
