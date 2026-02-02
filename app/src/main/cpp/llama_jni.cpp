/**
 * Minimal JNI bridge for llama.cpp C API (Qwen3 GGUF on Android).
 * Uses ggml-org/llama.cpp b7793 (supports Qwen3 architecture).
 */
#include <jni.h>
#include <cstring>
#include <string>
#include <vector>
#include <mutex>

#include "llama.h"
#include <android/log.h>

static bool s_backend_init = false;
#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

struct LlamaHandle
{
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    int n_gpu_layers_used = -1; // -1 = CPU only, >=0 = number of layers on GPU (Vulkan)
    int n_total_layers = 0;     // Total number of layers in the model
    std::mutex mtx;             // Mutex to prevent concurrent generation calls
};

extern "C"
{

    JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
    {
        (void)reserved;
        if (!s_backend_init)
        {
            llama_backend_init();
            s_backend_init = true;
        }
        return JNI_VERSION_1_6;
    }

    JNIEXPORT jlong JNICALL
    Java_com_dramebaz_app_ai_llm_LlamaNative_loadModel(JNIEnv *env, jclass clazz, jstring path_jstr)
    {
        (void)clazz;
        if (!path_jstr)
            return 0;
        const char *path = env->GetStringUTFChars(path_jstr, nullptr);
        if (!path)
            return 0;

        if (!s_backend_init)
        {
            llama_backend_init();
            s_backend_init = true;
        }

        llama_model_params mparams = llama_model_default_params();
        mparams.use_mmap = true;
        mparams.use_mlock = false;

        // Try GPU (Vulkan) first: offload all layers; fallback to CPU if unavailable
        int n_gpu_used = -1;
        mparams.n_gpu_layers = 99;
        llama_model *model = llama_model_load_from_file(path, mparams);
        if (model)
            n_gpu_used = 99;
        else
        {
            LOGI("GPU (Vulkan) load failed, falling back to CPU");
            mparams.n_gpu_layers = -1;
            model = llama_model_load_from_file(path, mparams);
        }
        env->ReleaseStringUTFChars(path_jstr, path);
        if (!model)
            return 0;

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = 4096;
        cparams.n_threads = 4;       // Increased from 2 for better CPU utilization on modern devices
        cparams.n_threads_batch = 4; // Increased from 2 for faster prompt processing

        llama_context *ctx = llama_init_from_model(model, cparams);
        if (!ctx)
        {
            llama_model_free(model);
            return 0;
        }

        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        llama_sampler *smpl = llama_sampler_chain_init(sparams);
        // Reduced top_k from 50 to 40 for faster sampling with minimal quality loss
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.8f));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        // Get the actual number of layers in the model
        int n_total_layers = llama_model_n_layer(model);

        // If GPU was used, the actual layers offloaded is min(requested, total)
        int n_gpu_actual = (n_gpu_used > 0) ? (n_gpu_used > n_total_layers ? n_total_layers : n_gpu_used) : 0;

        LlamaHandle *h = new LlamaHandle();
        h->model = model;
        h->ctx = ctx;
        h->sampler = smpl;
        h->n_gpu_layers_used = n_gpu_actual;
        h->n_total_layers = n_total_layers;

        LOGI("Model has %d layers total", n_total_layers);
        LOGI("Execution provider: %s (GPU layers: %d/%d)",
             n_gpu_actual > 0 ? "GPU (Vulkan)" : "CPU",
             n_gpu_actual,
             n_total_layers);
        return reinterpret_cast<jlong>(h);
    }

    JNIEXPORT jstring JNICALL
    Java_com_dramebaz_app_ai_llm_LlamaNative_getExecutionProvider(JNIEnv *env, jclass clazz, jlong handle)
    {
        (void)clazz;
        LlamaHandle *h = reinterpret_cast<LlamaHandle *>(handle);
        if (!h)
            return env->NewStringUTF("unknown");

        // Return provider with layer info: "GPU (Vulkan) [28/28 layers]" or "CPU"
        char buffer[128];
        if (h->n_gpu_layers_used > 0)
        {
            snprintf(buffer, sizeof(buffer), "GPU (Vulkan) [%d/%d layers]",
                     h->n_gpu_layers_used, h->n_total_layers);
        }
        else
        {
            snprintf(buffer, sizeof(buffer), "CPU");
        }
        return env->NewStringUTF(buffer);
    }

    JNIEXPORT jint JNICALL
    Java_com_dramebaz_app_ai_llm_LlamaNative_getGpuLayerCount(JNIEnv *env, jclass clazz, jlong handle)
    {
        (void)env;
        (void)clazz;
        LlamaHandle *h = reinterpret_cast<LlamaHandle *>(handle);
        if (!h)
            return 0;
        return h->n_gpu_layers_used;
    }

    JNIEXPORT jint JNICALL
    Java_com_dramebaz_app_ai_llm_LlamaNative_getTotalLayerCount(JNIEnv *env, jclass clazz, jlong handle)
    {
        (void)env;
        (void)clazz;
        LlamaHandle *h = reinterpret_cast<LlamaHandle *>(handle);
        if (!h)
            return 0;
        return h->n_total_layers;
    }

    JNIEXPORT void JNICALL
    Java_com_dramebaz_app_ai_llm_LlamaNative_release(JNIEnv *env, jclass clazz, jlong handle)
    {
        (void)env;
        (void)clazz;
        LlamaHandle *h = reinterpret_cast<LlamaHandle *>(handle);
        if (!h)
            return;
        if (h->sampler)
        {
            llama_sampler_free(h->sampler);
            h->sampler = nullptr;
        }
        if (h->ctx)
        {
            llama_free(h->ctx);
            h->ctx = nullptr;
        }
        if (h->model)
        {
            llama_model_free(h->model);
            h->model = nullptr;
        }
        delete h;
    }

    // Stop strings: stop generation when output contains these
    static const char *STOP_STRINGS[] = {"<|im_end|>", "<|endoftext|>"};
    static const int N_STOP = 2;

    static bool endsWithStop(const std::string &s)
    {
        for (int i = 0; i < N_STOP; i++)
        {
            size_t len = strlen(STOP_STRINGS[i]);
            if (s.length() >= len && s.compare(s.length() - len, len, STOP_STRINGS[i]) == 0)
                return true;
        }
        return false;
    }

    JNIEXPORT jstring JNICALL
    Java_com_dramebaz_app_ai_llm_LlamaNative_generate(JNIEnv *env, jclass clazz, jlong handle,
                                                      jstring prompt_jstr, jint max_tokens, jfloat temperature)
    {
        (void)clazz;
        LlamaHandle *h = reinterpret_cast<LlamaHandle *>(handle);
        if (!h || !h->ctx || !h->model || !h->sampler)
            return env->NewStringUTF("");
        if (!prompt_jstr)
            return env->NewStringUTF("");

        // Lock to prevent concurrent access - llama.cpp context is NOT thread-safe
        std::lock_guard<std::mutex> lock(h->mtx);

        const char *prompt_c = env->GetStringUTFChars(prompt_jstr, nullptr);
        if (!prompt_c)
            return env->NewStringUTF("");
        int prompt_len = (int)strlen(prompt_c);

        const llama_vocab *vocab = llama_model_get_vocab(h->model);
        uint32_t n_batch = llama_n_batch(h->ctx);
        const int max_prompt_tokens = (int)n_batch;
        std::vector<llama_token> prompt_tokens(std::min(8192, max_prompt_tokens * 4));
        int n_tokens = llama_tokenize(vocab, prompt_c, prompt_len, prompt_tokens.data(),
                                      (int)prompt_tokens.size(), true, false);
        env->ReleaseStringUTFChars(prompt_jstr, prompt_c);
        if (n_tokens <= 0)
            return env->NewStringUTF("");

        prompt_tokens.resize(n_tokens);

        // CRITICAL: Clear KV cache before each generation to prevent context overflow
        // Without this, tokens accumulate until n_ctx (4096) is exceeded, causing SIGSEGV
        llama_memory_t mem = llama_get_memory(h->ctx);
        if (mem)
        {
            llama_memory_clear(mem, true);
        }

        // Apply temperature to sampler (recreate chain with given temp)
        llama_sampler_free(h->sampler);
        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        h->sampler = llama_sampler_chain_init(sparams);
        // Reduced top_k from 50 to 40 for faster sampling with minimal quality loss
        llama_sampler_chain_add(h->sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(h->sampler, llama_sampler_init_top_p(0.9f, 1));
        float t = temperature <= 0.f ? 0.8f : temperature;
        llama_sampler_chain_add(h->sampler, llama_sampler_init_temp(t));
        llama_sampler_chain_add(h->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        // Decode prompt in chunks. llama_batch_get_one() returns batch with logits=NULL;
        // llama_decode() internally requests logits only for the last token when logits is NULL.
        int pos = 0;
        while (pos < n_tokens)
        {
            int chunk = std::min((int)n_batch, n_tokens - pos);
            llama_batch batch = llama_batch_get_one(&prompt_tokens[pos], chunk);
            int ret = llama_decode(h->ctx, batch);
            // Do not llama_batch_free: get_one does not allocate; token is our pointer.
            if (ret != 0)
            {
                return env->NewStringUTF("");
            }
            pos += chunk;
        }

        std::string output;
        char buf[256];
        int n_gen = 0;
        while (n_gen < max_tokens)
        {
            llama_token tok = llama_sampler_sample(h->sampler, h->ctx, -1);
            if (llama_vocab_is_eog(vocab, tok))
                break;
            int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
            if (n > 0)
            {
                output.append(buf, n);
                if (endsWithStop(output))
                    break;
            }
            llama_sampler_accept(h->sampler, tok);
            n_gen++;

            llama_batch batch = llama_batch_get_one(&tok, 1);
            int ret = llama_decode(h->ctx, batch);
            // Do not llama_batch_free: get_one does not allocate.
            if (ret != 0)
                break;
        }

        return env->NewStringUTF(output.c_str());
    }

} // extern "C"
