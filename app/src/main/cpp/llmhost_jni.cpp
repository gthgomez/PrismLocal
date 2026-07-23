#include <jni.h>

#include "Engine.hpp"

#include <android/log.h>

#include <algorithm>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "LlmHostJni"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

jclass g_string_class = nullptr;
jmethodID g_string_ctor = nullptr;
jstring g_utf8_charset = nullptr;
std::once_flag g_string_cache_flag;

void ensureStringCache(JNIEnv* env) {
    std::call_once(g_string_cache_flag, [env]() {
        jclass local_class = env->FindClass("java/lang/String");
        if (local_class != nullptr) {
            g_string_class = static_cast<jclass>(env->NewGlobalRef(local_class));
            g_string_ctor = env->GetMethodID(g_string_class, "<init>", "([BLjava/lang/String;)V");
            env->DeleteLocalRef(local_class);
        }
        jstring local_utf8 = env->NewStringUTF("UTF-8");
        if (local_utf8 != nullptr) {
            g_utf8_charset = static_cast<jstring>(env->NewGlobalRef(local_utf8));
            env->DeleteLocalRef(local_utf8);
        }
    });
}

jclass g_drain_result_class = nullptr;
jfieldID g_tokens_buffer_field = nullptr;
jfieldID g_tokens_count_field = nullptr;
jfieldID g_text_buffer_field = nullptr;
jfieldID g_text_count_field = nullptr;
jfieldID g_text_overflow_field = nullptr;
jfieldID g_drain_result_state_field = nullptr;
jfieldID g_drain_result_prompt_tokens_field = nullptr;
std::once_flag g_drain_result_cache_flag;

bool drainResultFieldsReady() {
    return g_drain_result_class != nullptr
        && g_tokens_buffer_field != nullptr
        && g_tokens_count_field != nullptr
        && g_text_buffer_field != nullptr
        && g_text_count_field != nullptr
        && g_text_overflow_field != nullptr
        && g_drain_result_state_field != nullptr
        && g_drain_result_prompt_tokens_field != nullptr;
}

void ensureDrainResultCache(JNIEnv* env) {
    std::call_once(g_drain_result_cache_flag, [env]() {
        jclass local_class = env->FindClass("com/prismai/llmhost/NativeDrainResult");
        if (local_class != nullptr) {
            g_drain_result_class = static_cast<jclass>(env->NewGlobalRef(local_class));
            g_tokens_buffer_field = env->GetFieldID(g_drain_result_class, "tokensBuffer", "[I");
            g_tokens_count_field = env->GetFieldID(g_drain_result_class, "tokensCount", "I");
            g_text_buffer_field = env->GetFieldID(g_drain_result_class, "textBuffer", "[B");
            g_text_count_field = env->GetFieldID(g_drain_result_class, "textCount", "I");
            g_text_overflow_field = env->GetFieldID(g_drain_result_class, "textOverflow", "Ljava/lang/String;");
            g_drain_result_state_field = env->GetFieldID(g_drain_result_class, "state", "I");
            g_drain_result_prompt_tokens_field = env->GetFieldID(g_drain_result_class, "promptTokens", "I");
            env->DeleteLocalRef(local_class);
            if (!drainResultFieldsReady()) {
                LOGE("ensureDrainResultCache: incomplete field IDs (Kotlin/native layout mismatch)");
            }
        }
    });
}



llmhost::Engine* toEngine(jlong handle) {
    return reinterpret_cast<llmhost::Engine*>(handle);
}

std::string toString(JNIEnv* env, jstring value) {
    if (env == nullptr || value == nullptr) {
        return "";
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return "";
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJavaString(JNIEnv* env, const std::string& value) {
    if (env == nullptr || value.empty()) {
        return env != nullptr ? env->NewStringUTF("") : nullptr;
    }

    // Fast path: if the string is pure ASCII (chars 0x00-0x7F), use NewStringUTF directly.
    // This avoids the byte[] + String(byte[],charset) constructor path which allocates
    // 3+ JNI local references and a Java byte array per call.
    bool is_ascii = true;
    for (const char c : value) {
        if (static_cast<unsigned char>(c) > 0x7F) {
            is_ascii = false;
            break;
        }
    }
    if (is_ascii) {
        return env->NewStringUTF(value.c_str());
    }

    // Full path for non-ASCII text (uses String(byte[], charset) for correct UTF-8)
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (bytes == nullptr) {
        return env->NewStringUTF("");
    }
    env->SetByteArrayRegion(
        bytes,
        0,
        static_cast<jsize>(value.size()),
        reinterpret_cast<const jbyte*>(value.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(bytes);
        return env->NewStringUTF("");
    }

    ensureStringCache(env);

    if (g_string_class == nullptr || g_string_ctor == nullptr || g_utf8_charset == nullptr) {
        env->DeleteLocalRef(bytes);
        return env->NewStringUTF("");
    }

    auto result = static_cast<jstring>(env->NewObject(g_string_class, g_string_ctor, bytes, g_utf8_charset));
    env->DeleteLocalRef(bytes);
    if (result == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return env->NewStringUTF("");
    }
    return result;
}

jintArray toJintArray(JNIEnv* env, const std::vector<int32_t>& values) {
    if (env == nullptr) {
        return nullptr;
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result == nullptr || values.empty()) {
        return result;
    }
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

std::vector<int32_t> fromJintArray(JNIEnv* env, jintArray values) {
    std::vector<int32_t> result;
    if (env == nullptr || values == nullptr) {
        return result;
    }
    const jsize count = env->GetArrayLength(values);
    if (count <= 0) {
        return result;
    }
    result.resize(static_cast<size_t>(count));
    env->GetIntArrayRegion(values, 0, count, result.data());
    return result;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeCreateEngine(JNIEnv*, jclass, jboolean debug_hooks_enabled) {
    try {
        auto engine = std::make_unique<llmhost::Engine>(debug_hooks_enabled == JNI_TRUE);
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception&) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeDestroyEngine(JNIEnv*, jobject, jlong handle) {
    delete toEngine(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeLoadModel(JNIEnv* env, jobject, jlong handle, jstring path) {
    auto* engine = toEngine(handle);
    if (engine == nullptr) {
        return JNI_FALSE;
    }
    try {
        return engine->loadModel(toString(env, path)) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeLoadModelWithSettings(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring path,
    jint max_tokens,
    jint thread_count,
    jint context_length,
    jint batch_size,
    jfloat temperature,
    jint top_k,
    jfloat top_p,
    jfloat repeat_penalty,
    jint gpu_layers) {
    auto* engine = toEngine(handle);
    if (engine == nullptr) {
        return JNI_FALSE;
    }
    try {
        llmhost::GenerationConfig config;
        config.max_tokens = max_tokens;
        config.thread_count = thread_count;
        config.context_length = context_length;
        config.batch_size = batch_size;
        config.temperature = temperature;
        config.top_k = top_k;
        config.top_p = top_p;
        config.repeat_penalty = repeat_penalty;
        config.gpu_layers = gpu_layers;
        return engine->loadModel(toString(env, path), config) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeUnloadModel(JNIEnv*, jobject, jlong handle) {
    auto* engine = toEngine(handle);
    if (engine == nullptr) {
        return;
    }
    engine->unloadModel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeResetConversation(JNIEnv*, jobject, jlong handle) {
    auto* engine = toEngine(handle);
    if (engine == nullptr) {
        return;
    }
    engine->resetConversation();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeStartGeneration(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring prompt,
    jint gen_id,
    jint max_tokens,
    jint thread_count,
    jint context_length,
    jint batch_size,
    jfloat temperature,
    jint top_k,
    jfloat top_p,
    jfloat repeat_penalty,
    jint gpu_layers,
    jboolean continue_from_context,
    jstring grammar) {
    auto* engine = toEngine(handle);
    if (engine == nullptr) {
        return -1;
    }
    try {
        llmhost::GenerationConfig config;
        config.max_tokens = max_tokens;
        config.thread_count = thread_count;
        config.context_length = context_length;
        config.batch_size = batch_size;
        config.temperature = temperature;
        config.top_k = top_k;
        config.top_p = top_p;
        config.repeat_penalty = repeat_penalty;
        config.gpu_layers = gpu_layers;
        config.continue_from_context = continue_from_context == JNI_TRUE;
        config.grammar = toString(env, grammar);
        return engine->startGeneration(toString(env, prompt), gen_id, config);
    } catch (const std::exception&) {
        return -1;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeRunBenchmark(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint max_tokens,
    jint thread_count,
    jint context_length,
    jint batch_size,
    jfloat temperature,
    jint top_k,
    jfloat top_p,
    jfloat repeat_penalty,
    jint gpu_layers,
    jint prompt_tokens,
    jint generation_tokens,
    jint repetitions) {
    auto* engine = toEngine(handle);
    if (engine == nullptr) {
        return env->NewStringUTF("{}");
    }
    try {
        llmhost::GenerationConfig config;
        config.max_tokens = max_tokens;
        config.thread_count = thread_count;
        config.context_length = context_length;
        config.batch_size = batch_size;
        config.temperature = temperature;
        config.top_k = top_k;
        config.top_p = top_p;
        config.repeat_penalty = repeat_penalty;
        config.gpu_layers = gpu_layers;
        return toJavaString(env, engine->runBenchmark(config, prompt_tokens, generation_tokens, repetitions));
    } catch (const std::exception&) {
        return env->NewStringUTF("{\"error\":\"exception\"}");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeCancelGeneration(JNIEnv*, jobject, jlong handle, jint gen_id) {
    auto* engine = toEngine(handle);
    if (engine == nullptr) {
        return;
    }
    engine->cancelGeneration(gen_id);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeDrainTokens(JNIEnv* env, jobject, jlong handle, jint gen_id, jint max_tokens) {
    auto* engine = toEngine(handle);
    if (engine == nullptr || max_tokens <= 0) {
        return env->NewIntArray(0);
    }
    try {
        return toJintArray(env, engine->drainTokens(gen_id, max_tokens));
    } catch (const std::exception&) {
        return env->NewIntArray(0);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeAckEof(JNIEnv*, jobject, jlong handle, jint gen_id) {
    auto* engine = toEngine(handle);
    if (engine == nullptr) {
        return;
    }
    engine->ackEof(gen_id);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeDecodeTokens(JNIEnv* env, jobject, jlong handle, jint gen_id, jintArray tokens) {
    auto* engine = toEngine(handle);
    if (engine == nullptr) {
        return env->NewStringUTF("");
    }
    try {
        const std::string text = engine->decodeTokens(gen_id, fromJintArray(env, tokens));
        return toJavaString(env, text);
    } catch (const std::exception&) {
        return env->NewStringUTF("");
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeEncode(JNIEnv* env, jobject, jlong handle, jstring text) {
    auto* engine = toEngine(handle);
    if (engine == nullptr) {
        return env->NewFloatArray(0);
    }
    try {
        const std::string utf8 = toString(env, text);
        const std::vector<float> embedding = engine->encode(utf8);
        if (embedding.empty()) {
            return env->NewFloatArray(0);
        }
        jfloatArray result = env->NewFloatArray(static_cast<jsize>(embedding.size()));
        if (result == nullptr) return env->NewFloatArray(0);
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(embedding.size()), embedding.data());
        return result;
    } catch (const std::exception&) {
        return env->NewFloatArray(0);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeGetState(JNIEnv*, jobject, jlong handle, jint gen_id) {
    auto* engine = toEngine(handle);
    if (engine == nullptr) {
        return static_cast<jint>(llmhost::StreamState::Tombstoned);
    }
    return static_cast<jint>(engine->getState(gen_id));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeSetMemoryPressure(JNIEnv*, jobject, jlong handle, jint level) {
    auto* engine = toEngine(handle);
    if (engine == nullptr) {
        return;
    }
    engine->setMemoryPressure(level);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmhost_NativeLlmBridge_nativeDrainDecodeAndState(JNIEnv* env, jobject, jlong handle, jint gen_id, jint max_tokens, jobject result) {
    auto* engine = toEngine(handle);

    ensureDrainResultCache(env);

    if (result == nullptr) {
        return;
    }

    if (!drainResultFieldsReady()) {
        jclass ex = env->FindClass("java/lang/NoSuchFieldError");
        if (ex != nullptr) {
            env->ThrowNew(ex, "NativeDrainResult field ID mismatch (Kotlin/native layout out of sync)");
        }
        return;
    }

    if (engine == nullptr) {
        env->SetIntField(result, g_drain_result_state_field, static_cast<jint>(llmhost::StreamState::Tombstoned));
        return;
    }

    try {
        auto drain_result = engine->drainDecodeAndState(gen_id, max_tokens);

        // --- Tokens: copy into pre-allocated IntArray ---
        if (!drain_result.tokens.empty()) {
            jintArray buf = static_cast<jintArray>(
                env->GetObjectField(result, g_tokens_buffer_field));
            if (buf == nullptr) {
                env->SetIntField(result, g_tokens_count_field, 0);
            } else {
                const jsize buf_len = env->GetArrayLength(buf);
                const jsize count = static_cast<jsize>(drain_result.tokens.size());

                if (count > buf_len) {
                    // Should never happen if buffer matches kTokenCapacity, but fail safe.
                    LOGW("drain_tokens_overflow count=%d buf=%d; clamping", static_cast<int>(count), static_cast<int>(buf_len));
                }
                const jsize copy = std::min(count, buf_len);
                env->SetIntArrayRegion(buf, 0, copy, drain_result.tokens.data());
                env->SetIntField(result, g_tokens_count_field, copy);
                env->DeleteLocalRef(buf);
            }
        } else {
            env->SetIntField(result, g_tokens_count_field, 0);
        }

        // --- Text: copy raw UTF-8 bytes into pre-allocated ByteArray ---
        //     Overflow fallback: if text exceeds buffer, allocate jstring into textOverflow.
        if (!drain_result.text.empty()) {
            const jsize text_len = static_cast<jsize>(drain_result.text.size());
            jbyteArray buf = static_cast<jbyteArray>(
                env->GetObjectField(result, g_text_buffer_field));
            if (buf == nullptr) {
                env->SetIntField(result, g_text_count_field, 0);
                jstring overflow = toJavaString(env, drain_result.text);
                if (overflow != nullptr) {
                    env->SetObjectField(result, g_text_overflow_field, overflow);
                    env->DeleteLocalRef(overflow);
                }
            } else {
                const jsize buf_len = env->GetArrayLength(buf);

                if (text_len <= buf_len) {
                    // Fast path: zero-alloc copy into pre-allocated buffer
                    env->SetByteArrayRegion(buf, 0, text_len,
                        reinterpret_cast<const jbyte*>(drain_result.text.data()));
                    env->SetIntField(result, g_text_count_field, text_len);
                } else {
                    // Overflow path: fall back to dynamic jstring allocation
                    LOGW("drain_text_overflow text_len=%d buf=%d; using jstring fallback",
                         static_cast<int>(text_len), static_cast<int>(buf_len));
                    env->SetIntField(result, g_text_count_field, 0);
                    jstring overflow = toJavaString(env, drain_result.text);
                    if (overflow != nullptr) {
                        env->SetObjectField(result, g_text_overflow_field, overflow);
                        env->DeleteLocalRef(overflow);
                    }
                }
                env->DeleteLocalRef(buf);
            }
        } else {
            env->SetIntField(result, g_text_count_field, 0);
        }

        env->SetIntField(result, g_drain_result_state_field, drain_result.state);
        env->SetIntField(result, g_drain_result_prompt_tokens_field, drain_result.prompt_tokens);
    } catch (const std::exception&) {
        env->SetIntField(result, g_drain_result_state_field, static_cast<jint>(llmhost::StreamState::Error));
    }
}
