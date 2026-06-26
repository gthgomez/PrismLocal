#include <jni.h>

#include "Engine.hpp"

#include <exception>
#include <memory>
#include <string>
#include <vector>
#include <mutex>

namespace {

jclass g_string_class = nullptr;
jmethodID g_string_ctor = nullptr;
std::once_flag g_string_cache_flag;

void ensureStringCache(JNIEnv* env) {
    std::call_once(g_string_cache_flag, [env]() {
        jclass local_class = env->FindClass("java/lang/String");
        if (local_class != nullptr) {
            g_string_class = static_cast<jclass>(env->NewGlobalRef(local_class));
            g_string_ctor = env->GetMethodID(g_string_class, "<init>", "([BLjava/lang/String;)V");
            env->DeleteLocalRef(local_class);
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

    jstring charset = env->NewStringUTF("UTF-8");
    if (charset == nullptr) {
        env->DeleteLocalRef(bytes);
        return env->NewStringUTF("");
    }

    ensureStringCache(env);

    if (g_string_class == nullptr || g_string_ctor == nullptr) {
        env->DeleteLocalRef(charset);
        env->DeleteLocalRef(bytes);
        return env->NewStringUTF("");
    }

    auto result = static_cast<jstring>(env->NewObject(g_string_class, g_string_ctor, bytes, charset));
    env->DeleteLocalRef(charset);
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
