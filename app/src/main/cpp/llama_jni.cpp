// JNI bridge for Qwen LLM (GGUF). Path is provided by QwenModel.kt (e.g. qwen2.5-3b-instruct-q4_k_m.gguf in Downloads).
#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include <unistd.h>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "QwenModel"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Structure to hold model and context together
struct LlamaContextWrapper {
    llama_model* model;
    llama_context* ctx;
    const llama_vocab* vocab;
    llama_sampler* sampler;
    
    LlamaContextWrapper() : model(nullptr), ctx(nullptr), vocab(nullptr), sampler(nullptr) {}
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_dramebaz_app_ai_llm_QwenModel_llamaInitFromFile(JNIEnv *env, jobject thiz, jstring model_path, jint n_gpu_layers) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    int gpu_layers = (int) n_gpu_layers;
    LOGI("llamaInitFromFile called with path: %s, n_gpu_layers: %d", path, gpu_layers);
    
    try {
        // Initialize llama backend
        llama_backend_init();
        
        // Load model with optimizations for mobile; n_gpu_layers from Kotlin (device config)
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = gpu_layers; // -1 = all on GPU, 0 = CPU only, >0 = that many layers on GPU
        model_params.use_mmap = true;  // Faster load and inference (default, explicit for clarity)
        model_params.use_mlock = false; // Avoid locking full model in RAM (can OOM on mobile)
        
        LOGI("Loading model from: %s (n_gpu_layers=%d)", path, gpu_layers);
        llama_model *model = llama_model_load_from_file(path, model_params);
        
        if (model == nullptr) {
            LOGE("Failed to load model from: %s", path);
            env->ReleaseStringUTFChars(model_path, path);
            return 0;
        }
        LOGI("Model loaded successfully");
        
        // Get vocabulary
        const llama_vocab *vocab = llama_model_get_vocab(model);
        if (vocab == nullptr) {
            LOGE("Failed to get vocabulary from model");
            llama_model_free(model);
            env->ReleaseStringUTFChars(model_path, path);
            return 0;
        }
        LOGI("Vocabulary obtained successfully");
        
        // Create context with optimizations for mobile (tuned for speed)
        int n_cores = (int) sysconf(_SC_NPROCESSORS_ONLN);
        if (n_cores <= 0) n_cores = 4;
        // Use all available cores (up to 8) for faster inference; leave 1 core for UI on 4+ core devices
        int n_threads = (n_cores >= 4) ? std::min(8, n_cores - 1) : std::min(8, n_cores);
        n_threads = std::max(1, n_threads);
        int n_threads_batch = std::max(n_threads, std::min(8, n_cores));
        LOGI("CPU cores=%d, n_threads=%d, n_threads_batch=%d", n_cores, n_threads, n_threads_batch);
        
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = 2048; // Context size
        ctx_params.n_batch = 2048; // Larger batch = faster prompt decode (up to n_ctx)
        ctx_params.n_ubatch = 512;  // Micro batch (512 is a good default for mobile)
        ctx_params.n_threads = n_threads;
        ctx_params.n_threads_batch = n_threads_batch;
        ctx_params.no_perf = true; // Disable performance counters
        
        LOGI("Creating context with n_ctx=%d, n_batch=%d, n_ubatch=%d", ctx_params.n_ctx, ctx_params.n_batch, ctx_params.n_ubatch);
        llama_context *ctx = llama_init_from_model(model, ctx_params);
        
        if (ctx == nullptr) {
            LOGE("Failed to create context");
            llama_model_free(model);
            env->ReleaseStringUTFChars(model_path, path);
            return 0;
        }
        LOGI("Context created successfully");
        
        // Initialize sampler chain with greedy sampling
        auto sparams = llama_sampler_chain_default_params();
        sparams.no_perf = true;
        llama_sampler *sampler = llama_sampler_chain_init(sparams);
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
        LOGI("Sampler initialized");
        
        // Wrap in structure
        LlamaContextWrapper *wrapper = new LlamaContextWrapper();
        wrapper->model = model;
        wrapper->ctx = ctx;
        wrapper->vocab = vocab;
        wrapper->sampler = sampler;
        
        LOGI("Model initialization complete");
        env->ReleaseStringUTFChars(model_path, path);
        return reinterpret_cast<jlong>(wrapper);
        
    } catch (const std::exception& e) {
        LOGE("Exception in llamaInitFromFile: %s", e.what());
        env->ReleaseStringUTFChars(model_path, path);
        return 0;
    } catch (...) {
        LOGE("Unknown exception in llamaInitFromFile");
        env->ReleaseStringUTFChars(model_path, path);
        return 0;
    }
}

JNIEXPORT jstring JNICALL
Java_com_dramebaz_app_ai_llm_QwenModel_llamaGenerate(JNIEnv *env, jobject thiz, jlong context,
                                                      jstring prompt, jint max_tokens,
                                                      jfloat temperature, jfloat top_p, jfloat top_k) {
    LOGD("llamaGenerate called");
    
    if (context == 0) {
        LOGE("Invalid context (null)");
        return env->NewStringUTF("");
    }
    
    LlamaContextWrapper *wrapper = reinterpret_cast<LlamaContextWrapper*>(context);
    if (wrapper == nullptr || wrapper->ctx == nullptr || wrapper->model == nullptr || 
        wrapper->vocab == nullptr || wrapper->sampler == nullptr) {
        LOGE("Invalid wrapper or null components");
        return env->NewStringUTF("");
    }
    
    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_str == nullptr) {
        LOGE("Failed to get prompt string");
        return env->NewStringUTF("");
    }
    
    size_t prompt_len = strlen(prompt_str);
    LOGI("llamaGenerate: prompt length=%zu, max_tokens=%d", prompt_len, max_tokens);
    
    // Limit prompt length to prevent overflow; allow enough for a page of text
    if (prompt_len > 8000) {
        LOGI("Truncating prompt from %zu to 8000 chars", prompt_len);
        prompt_len = 8000;
    }
    
    std::string result;
    
    try {
        llama_context *ctx = wrapper->ctx;
        const llama_vocab *vocab = wrapper->vocab;
        llama_sampler *smpl = wrapper->sampler;
        
        // Tokenize prompt - first get the number of tokens needed
        int n_prompt = -llama_tokenize(vocab, prompt_str, prompt_len, NULL, 0, true, true);
        LOGD("Need %d tokens for prompt", n_prompt);
        
        if (n_prompt <= 0) {
            LOGE("Failed to calculate token count");
            env->ReleaseStringUTFChars(prompt, prompt_str);
            return env->NewStringUTF("");
        }
        
        // Allocate and tokenize
        std::vector<llama_token> prompt_tokens(n_prompt);
        if (llama_tokenize(vocab, prompt_str, prompt_len, prompt_tokens.data(), prompt_tokens.size(), true, true) < 0) {
            LOGE("Failed to tokenize prompt");
            env->ReleaseStringUTFChars(prompt, prompt_str);
            return env->NewStringUTF("");
        }
        LOGI("Tokenized prompt: %d tokens", n_prompt);
        
        // Check context and batch sizes
        int n_ctx = llama_n_ctx(ctx);
        int n_batch = llama_n_batch(ctx);
        int n_predict = std::min(std::max((int)max_tokens, 64), 2048); // Allow up to 2048 tokens for full JSON
        
        LOGD("Context size: %d, Batch size: %d, Prompt tokens: %d", n_ctx, n_batch, n_prompt);
        
        if (n_prompt > n_batch) {
            LOGE("Prompt (%d tokens) exceeds batch size (%d)", n_prompt, n_batch);
            // Truncate prompt to fit batch
            n_prompt = n_batch - 1;
            prompt_tokens.resize(n_prompt);
            LOGI("Truncated prompt to %d tokens", n_prompt);
        }
        
        if (n_prompt + n_predict > n_ctx) {
            LOGE("Prompt + prediction (%d + %d) exceeds context size (%d)", n_prompt, n_predict, n_ctx);
            n_predict = n_ctx - n_prompt - 1;
            if (n_predict <= 0) {
                env->ReleaseStringUTFChars(prompt, prompt_str);
                return env->NewStringUTF("");
            }
        }
        
        // Create batch for prompt
        llama_batch batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());
        
        // Decode the prompt
        LOGD("Decoding prompt (%d tokens)...", n_prompt);
        if (llama_decode(ctx, batch)) {
            LOGE("Failed to decode prompt");
            env->ReleaseStringUTFChars(prompt, prompt_str);
            return env->NewStringUTF("");
        }
        LOGD("Prompt decoded successfully");
        
        // Generate tokens
        int n_pos = n_prompt;
        int n_decode = 0;
        
        for (int i = 0; i < n_predict; i++) {
            // Sample the next token using the sampler
            llama_token new_token_id = llama_sampler_sample(smpl, ctx, -1);
            
            // Check for end of generation
            if (llama_vocab_is_eog(vocab, new_token_id)) {
                LOGD("End of generation token at iteration %d", i);
                break;
            }
            
            // Convert token to text
            char buf[128];
            int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf) - 1, 0, true);
            if (n > 0) {
                buf[n] = '\0';
                result.append(buf, n);
            } else if (n < 0) {
                LOGD("Token %d needs more space (%d)", new_token_id, -n);
            }
            
            // Prepare next batch with the sampled token
            batch = llama_batch_get_one(&new_token_id, 1);
            
            // Decode the new token
            if (llama_decode(ctx, batch)) {
                LOGE("Failed to decode token at iteration %d", i);
                break;
            }
            
            n_decode++;
            n_pos++;
            
            // Log progress every 100 tokens (reduced for less overhead in hot path)
            if ((i + 1) % 100 == 0) {
                LOGD("Generated %d tokens...", i + 1);
            }
        }
        
        env->ReleaseStringUTFChars(prompt, prompt_str);
        
        LOGI("Generation complete: %d tokens, %zu characters", n_decode, result.length());
        return env->NewStringUTF(result.c_str());
        
    } catch (const std::exception& e) {
        LOGE("Exception in llamaGenerate: %s", e.what());
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("");
    } catch (...) {
        LOGE("Unknown exception in llamaGenerate");
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("");
    }
}

JNIEXPORT void JNICALL
Java_com_dramebaz_app_ai_llm_QwenModel_llamaFree(JNIEnv *env, jobject thiz, jlong context) {
    if (context == 0) {
        LOGD("llamaFree: context already null");
        return;
    }
    
    LOGI("llamaFree called");
    
    try {
        LlamaContextWrapper *wrapper = reinterpret_cast<LlamaContextWrapper*>(context);
        if (wrapper != nullptr) {
            if (wrapper->sampler != nullptr) {
                llama_sampler_free(wrapper->sampler);
                wrapper->sampler = nullptr;
            }
            if (wrapper->ctx != nullptr) {
                llama_free(wrapper->ctx);
                wrapper->ctx = nullptr;
            }
            if (wrapper->model != nullptr) {
                llama_model_free(wrapper->model);
                wrapper->model = nullptr;
            }
            wrapper->vocab = nullptr; // vocab is owned by model
            delete wrapper;
        }
        llama_backend_free();
        LOGI("llamaFree complete");
    } catch (const std::exception& e) {
        LOGE("Exception in llamaFree: %s", e.what());
    } catch (...) {
        LOGE("Unknown exception in llamaFree");
    }
}

} // extern "C"
