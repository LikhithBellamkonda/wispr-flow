#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <fstream>
#include <cstdint>
#include <android/log.h>

#define LOG_TAG "FlowWriteJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if FLOWWRITE_HAS_LLAMA_CPP
#include "llama.h"
#endif

namespace {
    std::mutex g_engine_mutex;
    std::atomic<bool> g_cancel_requested{false};
    std::string g_last_error;

#if FLOWWRITE_HAS_LLAMA_CPP
    llama_model* g_model = nullptr;
    llama_context* g_ctx = nullptr;

    bool llama_abort_callback(void* /* user_data */) {
        return g_cancel_requested.load(std::memory_order_relaxed);
    }
#endif

    /**
     * Verifies the file exists, is readable, and begins with the 4-byte 'GGUF' magic header (0x46554747).
     */
    bool verify_gguf_header(const std::string& path, std::string& err_out) {
        std::ifstream file(path, std::ios::binary | std::ios::ate);
        if (!file.is_open()) {
            err_out = "Cannot open model file at " + path;
            return false;
        }
        std::streamsize size = file.tellg();
        if (size < 64) {
            err_out = "File is too small to be a valid GGUF model (" + std::to_string(size) + " bytes).";
            return false;
        }
        file.seekg(0, std::ios::beg);
        char magic[4] = {0};
        if (!file.read(magic, 4)) {
            err_out = "Failed to read GGUF magic header.";
            return false;
        }
        if (magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
            err_out = "Invalid file format: expected 'GGUF' header.";
            return false;
        }
        return true;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_flowwrite_ai_llm_LlamaBridge_nativeIsLlamaLinked(JNIEnv* /* env */, jobject /* thiz */) {
#if FLOWWRITE_HAS_LLAMA_CPP
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_flowwrite_ai_llm_LlamaBridge_nativeGetLastError(JNIEnv* env, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_flowwrite_ai_llm_LlamaBridge_nativeLoadModel(
        JNIEnv* env,
        jobject /* thiz */,
        jstring modelPathJStr,
        jint nCtx,
        jint nThreads) {

    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_last_error.clear();

    const char* path_cstr = env->GetStringUTFChars(modelPathJStr, nullptr);
    std::string model_path(path_cstr);
    env->ReleaseStringUTFChars(modelPathJStr, path_cstr);

    if (!verify_gguf_header(model_path, g_last_error)) {
        LOGE("GGUF verification failed: %s", g_last_error.c_str());
        return JNI_FALSE;
    }

#if FLOWWRITE_HAS_LLAMA_CPP
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_free_model(g_model);
        g_model = nullptr;
    }

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;
    mparams.use_mlock = false;

    g_model = llama_load_model_from_file(model_path.c_str(), mparams);
    if (g_model == nullptr) {
        g_last_error = "llama_load_model_from_file returned nullptr for " + model_path;
        LOGE("%s", g_last_error.c_str());
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(nCtx > 0 ? nCtx : 1024);
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = nThreads > 0 ? nThreads : 4;
    cparams.abort_callback = llama_abort_callback;
    cparams.abort_callback_data = nullptr;

    g_ctx = llama_new_context_with_model(g_model, cparams);
    if (g_ctx == nullptr) {
        g_last_error = "llama_new_context_with_model failed (insufficient native heap RAM).";
        LOGE("%s", g_last_error.c_str());
        llama_free_model(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Loaded GGUF model successfully (n_ctx=%d, threads=%d)", nCtx, nThreads);
    return JNI_TRUE;
#else
    g_last_error = "Native library compiled without llama.cpp source tree. Clone llama.cpp into app/src/main/cpp/llama.cpp and rebuild.";
    LOGE("%s", g_last_error.c_str());
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_flowwrite_ai_llm_LlamaBridge_nativeGenerate(
        JNIEnv* env,
        jobject /* thiz */,
        jstring promptJStr,
        jint maxNewTokens,
        jfloat temperature) {

    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_cancel_requested.store(false, std::memory_order_relaxed);
    g_last_error.clear();

#if FLOWWRITE_HAS_LLAMA_CPP
    if (g_model == nullptr || g_ctx == nullptr) {
        g_last_error = "Model is not loaded in native memory.";
        return env->NewStringUTF("");
    }

    const char* prompt_cstr = env->GetStringUTFChars(promptJStr, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(promptJStr, prompt_cstr);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    llama_kv_cache_clear(g_ctx);

    // Tokenize input ChatML prompt
    std::vector<llama_token> prompt_tokens(prompt.size() + 32);
    int n_prompt_tokens = llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        prompt_tokens.data(),
        static_cast<int32_t>(prompt_tokens.size()),
        true,
        true
    );

    if (n_prompt_tokens < 0) {
        prompt_tokens.resize(-n_prompt_tokens);
        n_prompt_tokens = llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            prompt_tokens.data(),
            static_cast<int32_t>(prompt_tokens.size()),
            true,
            true
        );
    }

    if (n_prompt_tokens <= 0) {
        g_last_error = "Tokenization failed for prompt.";
        return env->NewStringUTF("");
    }
    prompt_tokens.resize(n_prompt_tokens);

    if (static_cast<uint32_t>(n_prompt_tokens + maxNewTokens) > llama_n_ctx(g_ctx)) {
        g_last_error = "Prompt length exceeds configured context window (n_ctx).";
        return env->NewStringUTF("");
    }

    // Evaluate prompt batch
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), n_prompt_tokens);
    if (llama_decode(g_ctx, batch) != 0) {
        g_last_error = "llama_decode failed or was cancelled during prompt evaluation.";
        return env->NewStringUTF("");
    }

    // Configure sampler chain (temperature ~ 0.2 or greedy if <= 0.05)
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    if (temperature <= 0.05f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.90f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string output_text;
    for (int i = 0; i < maxNewTokens; ++i) {
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            g_last_error = "Inference cancelled by user or timeout.";
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
        }

        llama_token new_token_id = llama_sampler_sample(smpl, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        char buf[256];
        int n_piece = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n_piece > 0) {
            std::string piece(buf, n_piece);
            // Stop immediately if Qwen2.5 emits ChatML end turn token string
            if (piece == "<|im_end|>" || piece == "<|endoftext|>") {
                break;
            }
            output_text.append(piece);
        }

        batch = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(g_ctx, batch) != 0) {
            g_last_error = "llama_decode aborted during token generation.";
            break;
        }
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(output_text.c_str());
#else
    g_last_error = "llama.cpp native engine is not linked.";
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_flowwrite_ai_llm_LlamaBridge_nativeCancelInference(JNIEnv* /* env */, jobject /* thiz */) {
    g_cancel_requested.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_flowwrite_ai_llm_LlamaBridge_nativeClearKvCache(JNIEnv* /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
#if FLOWWRITE_HAS_LLAMA_CPP
    if (g_ctx != nullptr) {
        llama_kv_cache_clear(g_ctx);
    }
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_flowwrite_ai_llm_LlamaBridge_nativeReleaseModel(JNIEnv* /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
#if FLOWWRITE_HAS_LLAMA_CPP
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_free_model(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
#endif
}
