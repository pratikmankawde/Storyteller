/**
 * JNI bridge for Stable Audio Open Small inference using TensorFlow Lite C++ API.
 * Based on official Arm ML-examples audiogen.cpp implementation.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

// TensorFlow Lite C++ API
#include "tensorflow/lite/c/common.h"
#include "tensorflow/lite/interpreter.h"
#include "tensorflow/lite/kernels/register.h"
#include "tensorflow/lite/kernels/builtin_op_kernels.h"  // For Register_FULLY_CONNECTED
#include "tensorflow/lite/model.h"

// GPU delegate support - requires tensorflow/lite/delegates/gpu/delegate.h
// Define TFLITE_GPU_DELEGATE_ENABLED=1 to enable GPU delegate support
#ifdef TFLITE_GPU_DELEGATE_ENABLED
#include "tensorflow/lite/delegates/gpu/delegate.h"
#define HAS_GPU_DELEGATE 1
#else
#define HAS_GPU_DELEGATE 0
#endif

// XNNPACK delegate support - requires tensorflow/lite/delegates/xnnpack/xnnpack_delegate.h
// Define TFLITE_XNNPACK_DELEGATE_ENABLED=1 when the header is available
#ifdef TFLITE_XNNPACK_DELEGATE_ENABLED
#include "tensorflow/lite/delegates/xnnpack/xnnpack_delegate.h"
#define HAS_XNNPACK_DELEGATE 1
#else
#define HAS_XNNPACK_DELEGATE 0
#endif

// Memory optimization: BuildFromFile already uses mmap by default on Android
// Additional memory optimizations:
// 1. GPU shader serialization to avoid recompilation
// 2. Only apply GPU delegate to large models (DiT, AutoEncoder)
// 3. Use SetNumThreads for CPU operations

#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fstream>
#include <mutex>
#include <atomic>
#include <random>
#include <string>
#include <thread>  // For sleep_for (memory cleanup pause)
#include <vector>
#include <sys/stat.h>  // For mkdir (GPU cache directory creation)
#include <unistd.h>    // For access() (check file existence)

#include <sentencepiece_processor.h>
#include <android/log.h>

#define LOG_TAG "StableAudioJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Helper to log tensor info
static void log_tensor_info(const char* name, TfLiteTensor* tensor) {
    if (!tensor) {
        LOGE("  %s: NULL tensor!", name);
        return;
    }
    std::string dims_str;
    for (int i = 0; i < tensor->dims->size; ++i) {
        if (i > 0) dims_str += "x";
        dims_str += std::to_string(tensor->dims->data[i]);
    }
    size_t bytes = tensor->bytes;
    const char* type_name = "unknown";
    switch (tensor->type) {
        case kTfLiteFloat32: type_name = "float32"; break;
        case kTfLiteFloat16: type_name = "float16"; break;
        case kTfLiteInt32: type_name = "int32"; break;
        case kTfLiteInt64: type_name = "int64"; break;
        case kTfLiteUInt8: type_name = "uint8"; break;
        case kTfLiteInt8: type_name = "int8"; break;
        default: break;
    }
    LOGD("  %s: dims=[%s], type=%s, bytes=%zu (%.2f MB)",
         name, dims_str.c_str(), type_name, bytes, bytes / (1024.0 * 1024.0));
}

// Log interpreter memory usage
static void log_interpreter_memory(const char* name, tflite::Interpreter* interpreter) {
    if (!interpreter) {
        LOGE("%s interpreter is NULL!", name);
        return;
    }

    size_t total_bytes = 0;
    size_t input_bytes = 0;
    size_t output_bytes = 0;

    // Count input tensors
    for (int idx : interpreter->inputs()) {
        TfLiteTensor* tensor = interpreter->tensor(idx);
        if (tensor) {
            input_bytes += tensor->bytes;
            total_bytes += tensor->bytes;
        }
    }

    // Count output tensors
    for (int idx : interpreter->outputs()) {
        TfLiteTensor* tensor = interpreter->tensor(idx);
        if (tensor) {
            output_bytes += tensor->bytes;
            total_bytes += tensor->bytes;
        }
    }

    // Count all tensors
    size_t all_tensor_bytes = 0;
    for (int i = 0; i < interpreter->tensors_size(); ++i) {
        TfLiteTensor* tensor = interpreter->tensor(i);
        if (tensor) {
            all_tensor_bytes += tensor->bytes;
        }
    }

    LOGD("%s: inputs=%.2f MB, outputs=%.2f MB, all_tensors=%.2f MB, tensor_count=%zu",
         name,
         input_bytes / (1024.0 * 1024.0),
         output_bytes / (1024.0 * 1024.0),
         all_tensor_bytes / (1024.0 * 1024.0),
         interpreter->tensors_size());
}

// Audio parameters
constexpr int32_t k_audio_sr = 44100;
constexpr int32_t k_audio_num_channels = 2;
constexpr int32_t k_bits_per_sample = 32;

// Model tensor indices (from official audiogen.cpp) - DEPRECATED, use findTensorByName instead
// These are fallback indices for old model format
constexpr size_t k_t5_ids_in_idx = 0;
constexpr size_t k_t5_attnmask_in_idx = 1;
constexpr size_t k_t5_audio_len_in_idx = 2;
constexpr size_t k_t5_crossattn_out_idx = 0;
constexpr size_t k_t5_globalcond_out_idx = 2;

// Helper function to find tensor index by name (partial match supported)
static int findTensorByName(tflite::Interpreter* interp, const std::vector<int>& tensor_list,
                            const std::vector<std::string>& name_patterns) {
    for (int idx : tensor_list) {
        TfLiteTensor* t = interp->tensor(idx);
        if (t && t->name) {
            std::string name(t->name);
            for (const auto& pattern : name_patterns) {
                // Check if tensor name contains any of the patterns
                if (name.find(pattern) != std::string::npos) {
                    LOGD("Found tensor '%s' at index %d", name.c_str(), idx);
                    return idx;
                }
            }
        }
    }
    return -1; // Not found
}

// Sigma parameters (from official audiogen.cpp)
constexpr float k_logsnr_max = -6.0f;
constexpr float k_sigma_min = 0.0f;
constexpr float k_sigma_max = 1.0f;

static std::string g_last_error;
static std::mutex g_error_mutex;

static void set_error(const std::string& error) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = error;
    LOGE("%s", error.c_str());
}

// GPU delegate deleter (only when GPU delegate is available)
#if HAS_GPU_DELEGATE
struct TfLiteGpuDelegateDeleter {
    void operator()(TfLiteDelegate* delegate) const {
        if (delegate) {
            TfLiteGpuDelegateV2Delete(delegate);
        }
    }
};
#endif

// XNNPACK delegate deleter (only when XNNPACK delegate is available)
#if HAS_XNNPACK_DELEGATE
struct TfLiteXNNPackDelegateDeleter {
    void operator()(TfLiteDelegate* delegate) const {
        if (delegate) {
            TfLiteXNNPackDelegateDelete(delegate);
        }
    }
};
#endif

// Helper to get number of elements from TfLiteIntArray (from official audiogen.cpp)
static size_t get_num_elems(const TfLiteIntArray* dims) {
    size_t x = 1;
    for (size_t i = 0; i < dims->size; ++i) {
        x *= dims->data[i];
    }
    return x;
}

// Fill buffer with random normal distribution (from official audiogen.cpp)
static void fill_random_norm_dist(float* buff, size_t buff_sz, size_t seed) {
    std::random_device rd{};
    std::mt19937 gen(seed);
    std::normal_distribution<float> dis(0.0f, 1.0f);
    auto gen_fn = [&dis, &gen](){ return dis(gen); };
    std::generate(buff, buff + buff_sz, gen_fn);
}

// Fill sigma schedule (from official audiogen.cpp)
static void fill_sigmas(std::vector<float>& arr, float start, float end, float sigma_max) {
    const int32_t sz = static_cast<int32_t>(arr.size());
    const float step = ((end - start) / static_cast<float>(sz - 1));

    // Linspace
    arr[0] = start;
    arr[sz - 1] = end;
    for (int32_t i = 1; i < sz - 1; ++i) {
        arr[i] = arr[i - 1] + step;
    }

    // Sigmoid(-logsnr)
    for (int32_t i = 0; i < sz; ++i) {
        arr[i] = 1.0f / (1.0f + std::exp(arr[i]));
    }

    arr[0] = sigma_max;
    arr[sz - 1] = k_sigma_min;
}

// Ping-pong sampler (from official audiogen.cpp)
static void sampler_ping_pong(float* dit_out_data, float* dit_x_in_data, size_t dit_x_in_sz,
                               float cur_t, float next_t, size_t step_idx, size_t seed) {
    for (size_t i = 0; i < dit_x_in_sz; i++) {
        dit_out_data[i] = dit_x_in_data[i] - (cur_t * dit_out_data[i]);
    }

    std::vector<float> rand_tensor(dit_x_in_sz);
    fill_random_norm_dist(rand_tensor.data(), dit_x_in_sz, seed);

    // x = (1-t_next) * denoised + t_next * torch.randn_like(x)
    for (size_t i = 0; i < dit_x_in_sz; i++) {
        dit_x_in_data[i] = ((1.0f - next_t) * dit_out_data[i]) + (next_t * rand_tensor[i]);
    }
}

// Save audio as WAV file (from official audiogen.cpp)
static bool save_as_wav(const std::string& path, const float* left_ch, const float* right_ch, size_t buffer_sz) {
    constexpr uint16_t audio_format = 3; // IEEE float

    const int32_t byte_rate = k_audio_sr * k_audio_num_channels * (k_bits_per_sample / 8);
    const int32_t block_align = k_audio_num_channels * (k_bits_per_sample / 8);
    const int32_t data_chunk_sz = buffer_sz * 2 * sizeof(float);
    const int32_t fmt_chunk_sz = 16;
    const int32_t header_sz = 44;
    const int32_t file_sz = header_sz + data_chunk_sz - 8;

    std::ofstream out_file(path, std::ios::binary);
    if (!out_file) {
        set_error("Failed to open output file: " + path);
        return false;
    }

    out_file.write("RIFF", 4);
    out_file.write(reinterpret_cast<const char*>(&file_sz), 4);
    out_file.write("WAVE", 4);
    out_file.write("fmt ", 4);
    out_file.write(reinterpret_cast<const char*>(&fmt_chunk_sz), 4);
    out_file.write(reinterpret_cast<const char*>(&audio_format), 2);
    out_file.write(reinterpret_cast<const char*>(&k_audio_num_channels), 2);
    out_file.write(reinterpret_cast<const char*>(&k_audio_sr), 4);
    out_file.write(reinterpret_cast<const char*>(&byte_rate), 4);
    out_file.write(reinterpret_cast<const char*>(&block_align), 2);
    out_file.write(reinterpret_cast<const char*>(&k_bits_per_sample), 2);

    out_file.write("data", 4);
    out_file.write(reinterpret_cast<const char*>(&data_chunk_sz), 4);

    for (size_t i = 0; i < buffer_sz; ++i) {
        out_file.write(reinterpret_cast<const char*>(&left_ch[i]), sizeof(float));
        out_file.write(reinterpret_cast<const char*>(&right_ch[i]), sizeof(float));
    }

    out_file.close();
    return true;
}

// Convert prompt to token IDs (from official audiogen.cpp)
static std::vector<int32_t> convert_prompt_to_ids(sentencepiece::SentencePieceProcessor* sp, const std::string& prompt) {
    std::vector<int32_t> ids;
    sp->Encode(prompt, &ids);

    // Make sure we have 1 (EOS) at the end
    if (ids.empty() || ids.back() != 1) {
        ids.push_back(1);
    }
    return ids;
}

// Stable Audio handle with LAZY LOADING support
// Memory optimization: Only load models when needed, unload after use
// This reduces peak memory from ~2.7GB to ~1GB (one model at a time)
struct StableAudioHandle {
    // Model paths (stored at init, models loaded lazily)
    std::string t5_model_path;
    std::string dit_model_path;
    std::string autoencoder_model_path;

    // Models (C++ API) - LAZY LOADED
    std::unique_ptr<tflite::FlatBufferModel> t5_model;
    std::unique_ptr<tflite::FlatBufferModel> dit_model;
    std::unique_ptr<tflite::FlatBufferModel> autoencoder_model;

    // Interpreters (C++ API) - LAZY LOADED
    std::unique_ptr<tflite::Interpreter> t5_interpreter;
    std::unique_ptr<tflite::Interpreter> dit_interpreter;
    std::unique_ptr<tflite::Interpreter> autoencoder_interpreter;

	    // GPU delegates (one per interpreter since they cannot be shared)
	#if HAS_GPU_DELEGATE
	    std::unique_ptr<TfLiteDelegate, TfLiteGpuDelegateDeleter> gpu_delegate_dit;
	    std::unique_ptr<TfLiteDelegate, TfLiteGpuDelegateDeleter> gpu_delegate_autoencoder;
	#endif

	    // XNNPACK delegates for optimized CPU execution (one per interpreter)
	#if HAS_XNNPACK_DELEGATE
	    std::unique_ptr<TfLiteDelegate, TfLiteXNNPackDelegateDeleter> xnnpack_delegate_t5;
	    std::unique_ptr<TfLiteDelegate, TfLiteXNNPackDelegateDeleter> xnnpack_delegate_dit;
	    std::unique_ptr<TfLiteDelegate, TfLiteXNNPackDelegateDeleter> xnnpack_delegate_autoencoder;
	#endif

    // Tokenizer (always loaded - small memory footprint)
    std::unique_ptr<sentencepiece::SentencePieceProcessor> tokenizer;

    std::string model_directory;
    int num_threads = 2;
    bool gpu_enabled = false;
    bool try_gpu = false;  // Whether to try GPU when loading models

    std::atomic<float> progress{0.0f};
    std::atomic<bool> cancelled{false};
    std::mutex generation_mutex;

    // Lazy loading helper functions
    bool loadT5();
    void unloadT5();
    bool loadDiT();
    void unloadDiT();
    bool loadAutoEncoder();
    void unloadAutoEncoder();
};

// Implementation of lazy loading helpers
bool StableAudioHandle::loadT5() {
    if (t5_interpreter) {
        LOGD("T5 already loaded");
        return true;
    }
    LOGI("Loading T5 model (lazy)...");
    auto start = std::chrono::steady_clock::now();

    t5_model = tflite::FlatBufferModel::BuildFromFile(t5_model_path.c_str());
    if (!t5_model) {
        set_error("Failed to load T5 model: " + t5_model_path);
        return false;
    }

        tflite::ops::builtin::BuiltinOpResolver resolver;

        // The quantized T5 conditioners model uses FULLY_CONNECTED op version 12,
        // which is newer than what our bundled TFLite runtime advertises by
        // default. Extend the supported version range for FULLY_CONNECTED so
        // that version 12 is handled by the same kernel implementation.
        resolver.AddBuiltin(
                tflite::BuiltinOperator_FULLY_CONNECTED,
                tflite::ops::builtin::Register_FULLY_CONNECTED(),
                /*min_version=*/1,
                /*max_version=*/12);
    tflite::InterpreterBuilder builder(*t5_model, resolver);
    t5_interpreter = std::make_unique<tflite::Interpreter>();
    builder(&t5_interpreter);
    if (!t5_interpreter) {
        set_error("Failed to build T5 interpreter");
        t5_model.reset();
        return false;
    }

	    t5_interpreter->SetNumThreads(num_threads);
	    // T5 always uses CPU (small model); optionally use XNNPACK for optimized kernels.

	#if HAS_XNNPACK_DELEGATE
	    {
	        TfLiteXNNPackDelegateOptions xnnpack_options = TfLiteXNNPackDelegateOptionsDefault();
	        xnnpack_options.num_threads = num_threads;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_QS8;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_QU8;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_DYNAMIC_FULLY_CONNECTED;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_ENABLE_SUBGRAPH_RESHAPING;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_ENABLE_LATEST_OPERATORS;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_VARIABLE_OPERATORS;

	        xnnpack_delegate_t5.reset(TfLiteXNNPackDelegateCreate(&xnnpack_options));
	        if (xnnpack_delegate_t5 &&
	            t5_interpreter->ModifyGraphWithDelegate(xnnpack_delegate_t5.get()) == kTfLiteOk) {
	            LOGI("T5: XNNPACK delegate applied");
	        } else {
	            LOGW("T5: XNNPACK delegate failed, falling back to default CPU kernels");
	            xnnpack_delegate_t5.reset();
	        }
	    }
	#endif

	    if (t5_interpreter->AllocateTensors() != kTfLiteOk) {
        set_error("Failed to allocate T5 tensors");
	#if HAS_XNNPACK_DELEGATE
	        xnnpack_delegate_t5.reset();
	#endif
        t5_interpreter.reset();
        t5_model.reset();
        return false;
    }

    auto end = std::chrono::steady_clock::now();
    log_interpreter_memory("T5", t5_interpreter.get());
    LOGI("T5 loaded in %lldms", (long long)std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count());
    return true;
}

void StableAudioHandle::unloadT5() {
    if (t5_interpreter || t5_model) {
        LOGI("Unloading T5 model to free memory");
	#if HAS_XNNPACK_DELEGATE
	        xnnpack_delegate_t5.reset();
	#endif
        t5_interpreter.reset();
        t5_model.reset();
    }
}

bool StableAudioHandle::loadDiT() {
    if (dit_interpreter) {
        LOGD("DiT already loaded");
        return true;
    }
    LOGI("Loading DiT model (lazy)...");
    auto start = std::chrono::steady_clock::now();

    dit_model = tflite::FlatBufferModel::BuildFromFile(dit_model_path.c_str());
    if (!dit_model) {
        set_error("Failed to load DiT model: " + dit_model_path);
        return false;
    }

    // Use MutableOpResolver to support newer op versions (e.g., FULLY_CONNECTED v12)
    tflite::ops::builtin::BuiltinOpResolverWithoutDefaultDelegates resolver;
    // Explicitly register FULLY_CONNECTED with extended version support (up to v12)
    resolver.AddBuiltin(
            tflite::BuiltinOperator_FULLY_CONNECTED,
            tflite::ops::builtin::Register_FULLY_CONNECTED(),
            /*min_version=*/1,
            /*max_version=*/12);
    tflite::InterpreterBuilder builder(*dit_model, resolver);
    dit_interpreter = std::make_unique<tflite::Interpreter>();
    if (builder(&dit_interpreter) != kTfLiteOk || !dit_interpreter) {
        set_error("Failed to build DiT interpreter - check for unsupported ops");
        dit_model.reset();
        return false;
    }

	    dit_interpreter->SetNumThreads(num_threads);

	    bool used_gpu = false;

	#if HAS_GPU_DELEGATE
	    if (try_gpu) {
	        TfLiteGpuDelegateOptionsV2 gpu_options = TfLiteGpuDelegateOptionsV2Default();
	        gpu_options.inference_preference = TFLITE_GPU_INFERENCE_PREFERENCE_SUSTAINED_SPEED;
	        gpu_options.inference_priority1 = TFLITE_GPU_INFERENCE_PRIORITY_MIN_LATENCY;
	        gpu_options.inference_priority2 = TFLITE_GPU_INFERENCE_PRIORITY_MIN_MEMORY_USAGE;
	        gpu_options.inference_priority3 = TFLITE_GPU_INFERENCE_PRIORITY_MAX_PRECISION;
	        gpu_options.experimental_flags = TFLITE_GPU_EXPERIMENTAL_FLAGS_ENABLE_QUANT;
	        std::string cache_dir = model_directory + "/gpu_cache";
	        gpu_options.serialization_dir = cache_dir.c_str();
	        gpu_options.model_token = "stable_audio_v1";

	        gpu_delegate_dit.reset(TfLiteGpuDelegateV2Create(&gpu_options));
	        if (gpu_delegate_dit && dit_interpreter->ModifyGraphWithDelegate(gpu_delegate_dit.get()) == kTfLiteOk) {
	            LOGI("DiT: GPU delegate applied");
	            gpu_enabled = true;
	            used_gpu = true;
	        } else {
	            LOGW("DiT: GPU delegate failed, using CPU");
	            gpu_delegate_dit.reset();
	        }
	    }
	#endif

	#if HAS_XNNPACK_DELEGATE
	    if (!used_gpu) {
	        TfLiteXNNPackDelegateOptions xnnpack_options = TfLiteXNNPackDelegateOptionsDefault();
	        xnnpack_options.num_threads = num_threads;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_QS8;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_QU8;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_DYNAMIC_FULLY_CONNECTED;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_ENABLE_SUBGRAPH_RESHAPING;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_ENABLE_LATEST_OPERATORS;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_VARIABLE_OPERATORS;

	        xnnpack_delegate_dit.reset(TfLiteXNNPackDelegateCreate(&xnnpack_options));
	        if (xnnpack_delegate_dit &&
	            dit_interpreter->ModifyGraphWithDelegate(xnnpack_delegate_dit.get()) == kTfLiteOk) {
	            LOGI("DiT: XNNPACK delegate applied");
	        } else {
	            LOGW("DiT: XNNPACK delegate failed, falling back to default CPU kernels");
	            xnnpack_delegate_dit.reset();
	        }
	    }
	#endif

	    if (dit_interpreter->AllocateTensors() != kTfLiteOk) {
        set_error("Failed to allocate DiT tensors");
#if HAS_GPU_DELEGATE
        gpu_delegate_dit.reset();
#endif
	#if HAS_XNNPACK_DELEGATE
	        xnnpack_delegate_dit.reset();
	#endif
        dit_interpreter.reset();
        dit_model.reset();
        return false;
    }

    auto end = std::chrono::steady_clock::now();
    log_interpreter_memory("DiT", dit_interpreter.get());
    LOGI("DiT loaded in %lldms", (long long)std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count());
    return true;
}

void StableAudioHandle::unloadDiT() {
    if (dit_interpreter || dit_model) {
        LOGI("Unloading DiT model to free memory");
#if HAS_GPU_DELEGATE
        gpu_delegate_dit.reset();
#endif
	#if HAS_XNNPACK_DELEGATE
	        xnnpack_delegate_dit.reset();
	#endif
        dit_interpreter.reset();
        dit_model.reset();
    }
}

bool StableAudioHandle::loadAutoEncoder() {
    if (autoencoder_interpreter) {
        LOGD("AutoEncoder already loaded");
        return true;
    }
    LOGI("Loading AutoEncoder model (lazy)...");
    auto start = std::chrono::steady_clock::now();

    autoencoder_model = tflite::FlatBufferModel::BuildFromFile(autoencoder_model_path.c_str());
    if (!autoencoder_model) {
        set_error("Failed to load AutoEncoder model: " + autoencoder_model_path);
        return false;
    }

    tflite::ops::builtin::BuiltinOpResolver resolver;
    tflite::InterpreterBuilder builder(*autoencoder_model, resolver);
    autoencoder_interpreter = std::make_unique<tflite::Interpreter>();
    builder(&autoencoder_interpreter);
    if (!autoencoder_interpreter) {
        set_error("Failed to build AutoEncoder interpreter");
        autoencoder_model.reset();
        return false;
    }

	    // MEMORY OPTIMIZATION: Use only 1 thread for AutoEncoder to reduce working memory
	    // Each thread requires its own intermediate tensor buffers during inference.
	    // AutoEncoder is the memory bottleneck (312MB model with large intermediate activations).
	    // Using 1 thread trades speed for memory stability.
	    int ae_threads = 1;
	    LOGI("AutoEncoder: Using %d thread(s) for memory optimization (was %d)", ae_threads, num_threads);
	    autoencoder_interpreter->SetNumThreads(ae_threads);

	    bool used_gpu = false;

	    // GPU delegate for AutoEncoder - only when user explicitly requests GPU
	    // GPU inference blocks UI rendering and causes ANR on some devices.
	    // CPU is safer but uses more RAM - we rely on lazy loading to manage memory.
	#if HAS_GPU_DELEGATE
	    if (try_gpu) {
	        std::string cache_dir = model_directory + "/gpu_cache";
	        TfLiteGpuDelegateOptionsV2 gpu_options = TfLiteGpuDelegateOptionsV2Default();
	        gpu_options.inference_preference = TFLITE_GPU_INFERENCE_PREFERENCE_SUSTAINED_SPEED;
	        gpu_options.inference_priority1 = TFLITE_GPU_INFERENCE_PRIORITY_MIN_LATENCY;
	        gpu_options.inference_priority2 = TFLITE_GPU_INFERENCE_PRIORITY_MIN_MEMORY_USAGE;
	        gpu_options.inference_priority3 = TFLITE_GPU_INFERENCE_PRIORITY_MAX_PRECISION;
	        gpu_options.experimental_flags = TFLITE_GPU_EXPERIMENTAL_FLAGS_ENABLE_QUANT;
	        gpu_options.serialization_dir = cache_dir.c_str();
	        gpu_options.model_token = "stable_audio_v1";

	        gpu_delegate_autoencoder.reset(TfLiteGpuDelegateV2Create(&gpu_options));
	        if (gpu_delegate_autoencoder && autoencoder_interpreter->ModifyGraphWithDelegate(gpu_delegate_autoencoder.get()) == kTfLiteOk) {
	            LOGI("AutoEncoder: GPU delegate applied");
	            gpu_enabled = true;
	            used_gpu = true;
	        } else {
	            LOGW("AutoEncoder: GPU delegate failed, using CPU");
	            gpu_delegate_autoencoder.reset();
	        }
	    }
	#endif

	#if HAS_XNNPACK_DELEGATE
	    if (!used_gpu) {
	        TfLiteXNNPackDelegateOptions xnnpack_options = TfLiteXNNPackDelegateOptionsDefault();
	        xnnpack_options.num_threads = ae_threads;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_QS8;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_QU8;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_DYNAMIC_FULLY_CONNECTED;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_ENABLE_SUBGRAPH_RESHAPING;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_ENABLE_LATEST_OPERATORS;
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_VARIABLE_OPERATORS;
	        // Force FP16 computations for the most computationally expensive model
	        xnnpack_options.flags |= TFLITE_XNNPACK_DELEGATE_FLAG_FORCE_FP16;

	        xnnpack_delegate_autoencoder.reset(TfLiteXNNPackDelegateCreate(&xnnpack_options));
	        if (xnnpack_delegate_autoencoder &&
	            autoencoder_interpreter->ModifyGraphWithDelegate(xnnpack_delegate_autoencoder.get()) == kTfLiteOk) {
	            LOGI("AutoEncoder: XNNPACK FP16 delegate applied");
	        } else {
	            LOGW("AutoEncoder: XNNPACK delegate failed, falling back to default CPU kernels");
	            xnnpack_delegate_autoencoder.reset();
	        }
	    }
	#endif

        // Log tensor info before allocation to help debug
        LOGI("AutoEncoder: %zu inputs, %zu outputs, %zu tensors total",
             autoencoder_interpreter->inputs().size(),
             autoencoder_interpreter->outputs().size(),
             autoencoder_interpreter->tensors_size());

        // Log input tensor details
        for (size_t i = 0; i < autoencoder_interpreter->inputs().size(); i++) {
            int idx = autoencoder_interpreter->inputs()[i];
            TfLiteTensor* t = autoencoder_interpreter->tensor(idx);
            if (t) {
                std::string dims_str;
                for (int d = 0; d < t->dims->size; d++) {
                    dims_str += std::to_string(t->dims->data[d]);
                    if (d < t->dims->size - 1) dims_str += "x";
                }
                LOGI("AutoEncoder input[%zu]: idx=%d, type=%d, dims=[%s], bytes=%zu",
                     i, idx, t->type, dims_str.c_str(), t->bytes);
            }
        }

        // Log output tensor details
        for (size_t i = 0; i < autoencoder_interpreter->outputs().size(); i++) {
            int idx = autoencoder_interpreter->outputs()[i];
            TfLiteTensor* t = autoencoder_interpreter->tensor(idx);
            if (t) {
                std::string dims_str;
                for (int d = 0; d < t->dims->size; d++) {
                    dims_str += std::to_string(t->dims->data[d]);
                    if (d < t->dims->size - 1) dims_str += "x";
                }
                LOGI("AutoEncoder output[%zu]: idx=%d, type=%d, dims=[%s], bytes=%zu",
                     i, idx, t->type, dims_str.c_str(), t->bytes);
            }
        }

        LOGI("AutoEncoder: Calling AllocateTensors()...");
	    if (autoencoder_interpreter->AllocateTensors() != kTfLiteOk) {
        LOGE("AutoEncoder: AllocateTensors() FAILED!");
        set_error("Failed to allocate AutoEncoder tensors");
#if HAS_GPU_DELEGATE
        gpu_delegate_autoencoder.reset();
#endif
	#if HAS_XNNPACK_DELEGATE
	        xnnpack_delegate_autoencoder.reset();
	#endif
        autoencoder_interpreter.reset();
        autoencoder_model.reset();
        return false;
    }

    auto end = std::chrono::steady_clock::now();
    log_interpreter_memory("AutoEncoder", autoencoder_interpreter.get());
    LOGI("AutoEncoder loaded in %lldms", (long long)std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count());
    return true;
}

void StableAudioHandle::unloadAutoEncoder() {
    if (autoencoder_interpreter || autoencoder_model) {
        LOGI("Unloading AutoEncoder model to free memory");
#if HAS_GPU_DELEGATE
        gpu_delegate_autoencoder.reset();
#endif
	#if HAS_XNNPACK_DELEGATE
	        xnnpack_delegate_autoencoder.reset();
	#endif
        autoencoder_interpreter.reset();
        autoencoder_model.reset();
    }
}

extern "C" {

/**
 * LAZY LOADING IMPLEMENTATION
 *
 * Instead of loading all models at once (which uses ~2.7GB RAM and causes OOM),
 * we now only load the tokenizer at init time. Models are loaded on-demand
 * during generate() and unloaded after use.
 *
 * Memory usage: ~15MB at init, ~1GB peak during generation (one model at a time)
 */
JNIEXPORT jlong JNICALL
Java_com_dramebaz_app_ai_audio_StableAudioNative_loadModels(JNIEnv* env, jclass clazz,
                                                             jstring model_dir_jstr, jint num_threads,
                                                             jboolean use_gpu) {
    (void)clazz;

    if (!model_dir_jstr) {
        set_error("Model directory is null");
        return 0;
    }

    const char* model_dir = env->GetStringUTFChars(model_dir_jstr, nullptr);
    if (!model_dir) {
        set_error("Failed to get model directory string");
        return 0;
    }

    std::string model_directory(model_dir);
    env->ReleaseStringUTFChars(model_dir_jstr, model_dir);

    bool want_gpu = (use_gpu == JNI_TRUE);
    LOGI("Initializing StableAudio (LAZY LOADING mode)");
    LOGI("  Model directory: %s", model_directory.c_str());
    LOGI("  Threads: %d, GPU: %s", num_threads, want_gpu ? "requested" : "disabled");

    // Construct model paths
    // Models are stored as 8-bit quantized TFLite files on device.
    std::string t5_tflite = model_directory + "/conditioners_int8.tflite";
    std::string dit_tflite = model_directory + "/dit_model_int8.tflite";
    std::string autoencoder_tflite = model_directory + "/autoencoder_model_int8.tflite";
    std::string sentence_model_path = model_directory + "/spiece.model";

    // Verify model files exist (don't load them yet!)
    struct stat st;
    if (stat(t5_tflite.c_str(), &st) != 0) {
        set_error("T5 model not found: " + t5_tflite);
        return 0;
    }
    if (stat(dit_tflite.c_str(), &st) != 0) {
        set_error("DiT model not found: " + dit_tflite);
        return 0;
    }
    if (stat(autoencoder_tflite.c_str(), &st) != 0) {
        set_error("AutoEncoder model not found: " + autoencoder_tflite);
        return 0;
    }
    LOGI("Model files verified");

    // Create handle
    auto* handle = new StableAudioHandle();
    handle->model_directory = model_directory;
    handle->num_threads = (num_threads > 0) ? num_threads : 2;
    handle->try_gpu = want_gpu;

    // Store model paths for lazy loading
    handle->t5_model_path = t5_tflite;
    handle->dit_model_path = dit_tflite;
    handle->autoencoder_model_path = autoencoder_tflite;

    // Load tokenizer (small, always needed, ~few MB)
    handle->tokenizer = std::make_unique<sentencepiece::SentencePieceProcessor>();
    auto status = handle->tokenizer->Load(sentence_model_path);
    if (!status.ok()) {
        set_error("Failed to load tokenizer: " + sentence_model_path);
        delete handle;
        return 0;
    }
    LOGI("Tokenizer loaded: %s", sentence_model_path.c_str());

    // Create GPU cache directory if needed
    if (want_gpu) {
        std::string cache_dir = model_directory + "/gpu_cache";
        mkdir(cache_dir.c_str(), 0755);
    }

    LOGI("✅ StableAudio initialized (lazy loading mode - models will be loaded on demand)");
    LOGI("   Memory footprint: minimal (~15MB tokenizer only)");
    return reinterpret_cast<jlong>(handle);
}

/**
 * LAZY LOADING GENERATION
 *
 * Memory optimization: Load models one at a time, unload after use.
 * Flow: T5 → (buffer) → DiT → (buffer) → AutoEncoder → WAV
 * Peak memory: ~1GB (one model at a time) instead of ~2.7GB (all at once)
 */
JNIEXPORT jboolean JNICALL
Java_com_dramebaz_app_ai_audio_StableAudioNative_generate(JNIEnv* env, jclass clazz,
                                                          jlong handle_ptr, jstring prompt_jstr,
                                                          jfloat duration_seconds, jint num_steps,
                                                          jlong seed, jstring output_path_jstr) {
    (void)clazz;
    auto* handle = reinterpret_cast<StableAudioHandle*>(handle_ptr);
    if (!handle) {
        set_error("Invalid handle");
        return JNI_FALSE;
    }

    // Lock to prevent concurrent generation
    std::lock_guard<std::mutex> lock(handle->generation_mutex);
    handle->progress.store(0.0f);
    handle->cancelled.store(false);

    // Get strings
    const char* prompt_c = env->GetStringUTFChars(prompt_jstr, nullptr);
    const char* output_path_c = env->GetStringUTFChars(output_path_jstr, nullptr);
    if (!prompt_c || !output_path_c) {
        if (prompt_c) env->ReleaseStringUTFChars(prompt_jstr, prompt_c);
        if (output_path_c) env->ReleaseStringUTFChars(output_path_jstr, output_path_c);
        set_error("Failed to get string parameters");
        return JNI_FALSE;
    }

    std::string prompt(prompt_c);
    std::string output_path(output_path_c);
    env->ReleaseStringUTFChars(prompt_jstr, prompt_c);
    env->ReleaseStringUTFChars(output_path_jstr, output_path_c);

    float audio_len_sec = std::max(0.5f, std::min(11.0f, duration_seconds));
    size_t steps = std::max(1, std::min(100, num_steps));
    float sigma_max = k_sigma_max;

    LOGI("=== LAZY LOADING GENERATION ===");
    LOGI("Generating audio: prompt='%s', duration=%.1fs, steps=%zu, seed=%ld",
         prompt.c_str(), audio_len_sec, steps, (long)seed);

    // Convert prompt to token IDs (tokenizer is always loaded)
    std::vector<int32_t> ids = convert_prompt_to_ids(handle->tokenizer.get(), prompt);
    LOGI("Tokenized prompt: %zu tokens", ids.size());

    // Buffers to store intermediate results between models
    std::vector<float> crossattn_buffer;  // T5 output → DiT input
    std::vector<float> globalcond_buffer; // T5 output → DiT input
    std::vector<float> latent_buffer;     // DiT output → AutoEncoder input

    handle->progress.store(0.02f);

    // ========== PHASE 1: T5 Conditioners ==========
    LOGI("--- Phase 1: T5 Conditioners ---");
    if (!handle->loadT5()) {
        return JNI_FALSE;
    }

    tflite::Interpreter* t5 = handle->t5_interpreter.get();

    // Find T5 tensor indices dynamically by name
    std::vector<int> t5_inputs(t5->inputs().begin(), t5->inputs().end());
    std::vector<int> t5_outputs(t5->outputs().begin(), t5->outputs().end());

    // Find input tensors (try name-based lookup first, fall back to index)
    int t5_ids_in_id = findTensorByName(t5, t5_inputs, {"input_ids", "ids"});
    if (t5_ids_in_id < 0 && t5_inputs.size() > k_t5_ids_in_idx) {
        t5_ids_in_id = t5_inputs[k_t5_ids_in_idx];
    }

    int t5_attnmask_in_id = findTensorByName(t5, t5_inputs, {"attention_mask", "attn_mask"});
    if (t5_attnmask_in_id < 0 && t5_inputs.size() > k_t5_attnmask_in_idx) {
        t5_attnmask_in_id = t5_inputs[k_t5_attnmask_in_idx];
    }

    int t5_time_in_id = findTensorByName(t5, t5_inputs, {"seconds_total", "time", "duration"});
    if (t5_time_in_id < 0 && t5_inputs.size() > k_t5_audio_len_in_idx) {
        t5_time_in_id = t5_inputs[k_t5_audio_len_in_idx];
    }

    // Find output tensors (cross-attention conditioning is usually first, global conditioning may not exist)
    int t5_crossattn_out_id = findTensorByName(t5, t5_outputs, {"cross_attn", "encoder_hidden", "conditioning"});
    if (t5_crossattn_out_id < 0 && t5_outputs.size() > 0) {
        t5_crossattn_out_id = t5_outputs[0];  // Fall back to first output
    }

    int t5_globalcond_out_id = findTensorByName(t5, t5_outputs, {"global_cond", "global"});
    if (t5_globalcond_out_id < 0 && t5_outputs.size() > k_t5_globalcond_out_idx) {
        t5_globalcond_out_id = t5_outputs[k_t5_globalcond_out_idx];  // Fall back to index
    }

    LOGI("T5 tensors: ids=%d, attnmask=%d, time=%d, crossattn_out=%d, globalcond_out=%d",
         t5_ids_in_id, t5_attnmask_in_id, t5_time_in_id, t5_crossattn_out_id, t5_globalcond_out_id);

    // Get tensor metadata
    TfLiteTensor* t5_ids_tensor = t5->tensor(t5_ids_in_id);
    TfLiteTensor* t5_attnmask_tensor = t5->tensor(t5_attnmask_in_id);
    TfLiteTensor* t5_time_tensor = t5->tensor(t5_time_in_id);
    TfLiteIntArray* t5_ids_in_dims = t5_ids_tensor->dims;
    TfLiteIntArray* t5_attnmask_in_dims = t5_attnmask_tensor->dims;

    // Log tensor types for debugging
    LOGD("T5 input tensor types: ids=%d, attnmask=%d, time=%d",
         t5_ids_tensor->type, t5_attnmask_tensor->type, t5_time_tensor->type);

    // Initialize T5 inputs - handle each tensor type independently
    // The quantized model may have different types for each input tensor
    size_t ids_num_elems = get_num_elems(t5_ids_in_dims);
    size_t attnmask_num_elems = get_num_elems(t5_attnmask_in_dims);

    // === Set IDs input tensor ===
    if (t5_ids_tensor->type == kTfLiteInt32) {
        int32_t* t5_ids_in_data = t5->typed_tensor<int32_t>(t5_ids_in_id);
        if (!t5_ids_in_data) {
            set_error("Failed to get T5 ids int32 tensor");
            handle->unloadT5();
            return JNI_FALSE;
        }
        memset(t5_ids_in_data, 0, ids_num_elems * sizeof(int32_t));
        for (size_t i = 0; i < ids.size(); ++i) {
            t5_ids_in_data[i] = static_cast<int32_t>(ids[i]);
        }
        LOGD("T5 ids set as int32");
    } else if (t5_ids_tensor->type == kTfLiteInt64) {
        int64_t* t5_ids_in_data = t5->typed_tensor<int64_t>(t5_ids_in_id);
        if (!t5_ids_in_data) {
            set_error("Failed to get T5 ids int64 tensor");
            handle->unloadT5();
            return JNI_FALSE;
        }
        memset(t5_ids_in_data, 0, ids_num_elems * sizeof(int64_t));
        for (size_t i = 0; i < ids.size(); ++i) {
            t5_ids_in_data[i] = ids[i];
        }
        LOGD("T5 ids set as int64");
    } else {
        LOGE("Unsupported T5 ids tensor type: %d", t5_ids_tensor->type);
        set_error("Unsupported T5 ids tensor type");
        handle->unloadT5();
        return JNI_FALSE;
    }

    // === Set attention mask input tensor ===
    if (t5_attnmask_tensor->type == kTfLiteInt32) {
        int32_t* t5_attnmask_in_data = t5->typed_tensor<int32_t>(t5_attnmask_in_id);
        if (!t5_attnmask_in_data) {
            set_error("Failed to get T5 attnmask int32 tensor");
            handle->unloadT5();
            return JNI_FALSE;
        }
        memset(t5_attnmask_in_data, 0, attnmask_num_elems * sizeof(int32_t));
        for (size_t i = 0; i < ids.size(); ++i) {
            t5_attnmask_in_data[i] = 1;
        }
        LOGD("T5 attnmask set as int32");
    } else if (t5_attnmask_tensor->type == kTfLiteInt64) {
        int64_t* t5_attnmask_in_data = t5->typed_tensor<int64_t>(t5_attnmask_in_id);
        if (!t5_attnmask_in_data) {
            set_error("Failed to get T5 attnmask int64 tensor");
            handle->unloadT5();
            return JNI_FALSE;
        }
        memset(t5_attnmask_in_data, 0, attnmask_num_elems * sizeof(int64_t));
        for (size_t i = 0; i < ids.size(); ++i) {
            t5_attnmask_in_data[i] = 1;
        }
        LOGD("T5 attnmask set as int64");
    } else if (t5_attnmask_tensor->type == kTfLiteFloat32) {
        float* t5_attnmask_in_data = t5->typed_tensor<float>(t5_attnmask_in_id);
        if (!t5_attnmask_in_data) {
            set_error("Failed to get T5 attnmask float32 tensor");
            handle->unloadT5();
            return JNI_FALSE;
        }
        memset(t5_attnmask_in_data, 0, attnmask_num_elems * sizeof(float));
        for (size_t i = 0; i < ids.size(); ++i) {
            t5_attnmask_in_data[i] = 1.0f;
        }
        LOGD("T5 attnmask set as float32");
    } else {
        LOGE("Unsupported T5 attnmask tensor type: %d", t5_attnmask_tensor->type);
        set_error("Unsupported T5 attnmask tensor type");
        handle->unloadT5();
        return JNI_FALSE;
    }

    // === Set time/duration input tensor ===
    if (t5_time_tensor->type == kTfLiteFloat32) {
        float* t5_time_in_data = t5->typed_tensor<float>(t5_time_in_id);
        if (!t5_time_in_data) {
            set_error("Failed to get T5 time float32 tensor");
            handle->unloadT5();
            return JNI_FALSE;
        }
        memcpy(t5_time_in_data, &audio_len_sec, sizeof(float));
        LOGD("T5 time set as float32");
    } else if (t5_time_tensor->type == kTfLiteInt64) {
        int64_t* t5_time_in_data = t5->typed_tensor<int64_t>(t5_time_in_id);
        if (!t5_time_in_data) {
            set_error("Failed to get T5 time int64 tensor");
            handle->unloadT5();
            return JNI_FALSE;
        }
        // Convert seconds to integer (model may expect samples or milliseconds)
        int64_t time_val = static_cast<int64_t>(audio_len_sec);
        *t5_time_in_data = time_val;
        LOGD("T5 time set as int64 (%lld)", (long long)time_val);
    } else if (t5_time_tensor->type == kTfLiteInt32) {
        int32_t* t5_time_in_data = t5->typed_tensor<int32_t>(t5_time_in_id);
        if (!t5_time_in_data) {
            set_error("Failed to get T5 time int32 tensor");
            handle->unloadT5();
            return JNI_FALSE;
        }
        int32_t time_val = static_cast<int32_t>(audio_len_sec);
        *t5_time_in_data = time_val;
        LOGD("T5 time set as int32 (%d)", time_val);
    } else {
        LOGE("Unsupported T5 time tensor type: %d", t5_time_tensor->type);
        set_error("Unsupported T5 time tensor type");
        handle->unloadT5();
        return JNI_FALSE;
    }

    handle->progress.store(0.05f);

    // Run T5
    LOGI("Running T5 conditioners...");
    auto start_t5 = std::chrono::steady_clock::now();
    if (t5->Invoke() != kTfLiteOk) {
        set_error("T5 inference failed");
        handle->unloadT5();
        return JNI_FALSE;
    }
    auto end_t5 = std::chrono::steady_clock::now();
    LOGI("T5 done in %lldms", (long long)std::chrono::duration_cast<std::chrono::milliseconds>(end_t5 - start_t5).count());

    // Copy T5 outputs to buffers before unloading
    // Cross-attention output (required)
    if (t5_crossattn_out_id >= 0) {
        TfLiteTensor* crossattn_tensor = t5->tensor(t5_crossattn_out_id);
        size_t crossattn_elems = crossattn_tensor->bytes / sizeof(float);
        crossattn_buffer.resize(crossattn_elems);
        memcpy(crossattn_buffer.data(), t5->typed_tensor<float>(t5_crossattn_out_id), crossattn_elems * sizeof(float));
        LOGD("T5 cross-attn output saved: %.2f MB (%zu elements)",
             crossattn_elems * sizeof(float) / (1024.0 * 1024.0), crossattn_elems);
    } else {
        LOGE("T5 cross-attention output not found!");
        set_error("T5 cross-attention output not found");
        handle->unloadT5();
        return JNI_FALSE;
    }

    // Global conditioning output (optional - not all model formats have this)
    if (t5_globalcond_out_id >= 0) {
        TfLiteTensor* globalcond_tensor = t5->tensor(t5_globalcond_out_id);
        size_t globalcond_elems = globalcond_tensor->bytes / sizeof(float);
        globalcond_buffer.resize(globalcond_elems);
        memcpy(globalcond_buffer.data(), t5->typed_tensor<float>(t5_globalcond_out_id), globalcond_elems * sizeof(float));
        LOGD("T5 global-cond output saved: %.2f MB (%zu elements)",
             globalcond_elems * sizeof(float) / (1024.0 * 1024.0), globalcond_elems);
    } else {
        LOGI("T5 model does not have global_cond output (new model format)");
    }

    // Unload T5 to free memory
    handle->unloadT5();
    handle->progress.store(0.10f);

    if (handle->cancelled.load()) {
        set_error("Generation cancelled");
        return JNI_FALSE;
    }

    // ========== PHASE 2: DiT Diffusion ==========
    LOGI("--- Phase 2: DiT Diffusion ---");
    if (!handle->loadDiT()) {
        return JNI_FALSE;
    }

    tflite::Interpreter* dit = handle->dit_interpreter.get();

    // Find DiT tensor indices dynamically by name (supports both old and new model formats)
    // Old format: serving_default_x:0, serving_default_t:0, etc.
    // New format: x, t, cross_attn_cond, etc.
    std::vector<int> dit_inputs(dit->inputs().begin(), dit->inputs().end());
    std::vector<int> dit_outputs(dit->outputs().begin(), dit->outputs().end());

    int dit_x_in_id = findTensorByName(dit, dit_inputs, {"_x:", "_x", "x:"});
    if (dit_x_in_id < 0) dit_x_in_id = findTensorByName(dit, dit_inputs, {"x"});

    int dit_t_in_id = findTensorByName(dit, dit_inputs, {"_t:", "_t", "t:"});
    if (dit_t_in_id < 0) dit_t_in_id = findTensorByName(dit, dit_inputs, {"t"});

    int dit_crossattn_in_id = findTensorByName(dit, dit_inputs, {"cross_attn", "crossattn"});
    int dit_globalcond_in_id = findTensorByName(dit, dit_inputs, {"global_cond", "globalcond"});
    int dit_out_id = dit_outputs.empty() ? -1 : dit_outputs[0];  // First output

    // Log what we found
    LOGI("DiT tensors: x=%d, t=%d, crossattn=%d, globalcond=%d, out=%d",
         dit_x_in_id, dit_t_in_id, dit_crossattn_in_id, dit_globalcond_in_id, dit_out_id);

    // Validate required tensors
    if (dit_x_in_id < 0 || dit_t_in_id < 0 || dit_out_id < 0) {
        set_error("Failed to find required DiT tensors");
        handle->unloadDiT();
        return JNI_FALSE;
    }

    float* dit_x_in_data = dit->typed_tensor<float>(dit_x_in_id);
    float* dit_t_in_data = dit->typed_tensor<float>(dit_t_in_id);
    float* dit_out_data = dit->typed_tensor<float>(dit_out_id);
    TfLiteIntArray* dit_x_in_dims = dit->tensor(dit_x_in_id)->dims;

    // Handle cross-attention (required)
    float* dit_crossattn_in_data = nullptr;
    if (dit_crossattn_in_id >= 0) {
        dit_crossattn_in_data = dit->typed_tensor<float>(dit_crossattn_in_id);
        TfLiteTensor* crossattn_tensor = dit->tensor(dit_crossattn_in_id);
        size_t expected_size = crossattn_tensor->bytes / sizeof(float);

        // Copy T5 cross-attention output to DiT input
        // Handle potential size mismatch due to transposed dimensions
        if (crossattn_buffer.size() == expected_size) {
            memcpy(dit_crossattn_in_data, crossattn_buffer.data(), crossattn_buffer.size() * sizeof(float));
        } else {
            LOGW("Cross-attn size mismatch: buffer=%zu, expected=%zu - attempting to copy available data",
                 crossattn_buffer.size(), expected_size);
            size_t copy_size = std::min(crossattn_buffer.size(), expected_size);
            memcpy(dit_crossattn_in_data, crossattn_buffer.data(), copy_size * sizeof(float));
            // Zero out any remaining space
            if (expected_size > crossattn_buffer.size()) {
                memset(dit_crossattn_in_data + crossattn_buffer.size(), 0,
                       (expected_size - crossattn_buffer.size()) * sizeof(float));
            }
        }
    }

    // Handle global conditioning (optional in new model format)
    if (dit_globalcond_in_id >= 0) {
        float* dit_globalcond_in_data = dit->typed_tensor<float>(dit_globalcond_in_id);
        TfLiteTensor* globalcond_tensor = dit->tensor(dit_globalcond_in_id);
        size_t expected_size = globalcond_tensor->bytes / sizeof(float);

        if (globalcond_buffer.size() == expected_size) {
            memcpy(dit_globalcond_in_data, globalcond_buffer.data(), globalcond_buffer.size() * sizeof(float));
        } else {
            LOGW("Global-cond size mismatch: buffer=%zu, expected=%zu", globalcond_buffer.size(), expected_size);
            size_t copy_size = std::min(globalcond_buffer.size(), expected_size);
            memcpy(dit_globalcond_in_data, globalcond_buffer.data(), copy_size * sizeof(float));
        }
    } else {
        LOGI("DiT model does not have global_cond input (new model format)");
    }

    // Free T5 output buffers (no longer needed)
    crossattn_buffer.clear();
    crossattn_buffer.shrink_to_fit();
    globalcond_buffer.clear();
    globalcond_buffer.shrink_to_fit();

    // Initialize noise
    const size_t dit_x_num_elems = get_num_elems(dit_x_in_dims);
    fill_random_norm_dist(dit_x_in_data, dit_x_num_elems, seed);

    // Compute sigma schedule
    std::vector<float> t_buffer(steps + 1);
    float logsnr_max = k_logsnr_max;
    if (sigma_max < 1) {
        logsnr_max = std::log(((1 - sigma_max) / sigma_max) + 1e-6f);
    }
    fill_sigmas(t_buffer, logsnr_max, 2.0f, sigma_max);
    LOGI("Sigma schedule: t[0]=%.4f, t[%zu]=%.4f", t_buffer[0], steps, t_buffer[steps]);

    // Run diffusion loop
    LOGI("Running %zu diffusion steps...", steps);
    auto start_dit = std::chrono::steady_clock::now();

    for (size_t i = 0; i < steps; ++i) {
        if (handle->cancelled.load()) {
            set_error("Generation cancelled");
            handle->unloadDiT();
            return JNI_FALSE;
        }

        const float curr_t = t_buffer[i];
        const float next_t = t_buffer[i + 1];
        memcpy(dit_t_in_data, &curr_t, sizeof(float));

        if (dit->Invoke() != kTfLiteOk) {
            set_error("DiT inference failed at step " + std::to_string(i));
            handle->unloadDiT();
            return JNI_FALSE;
        }

        sampler_ping_pong(dit_out_data, dit_x_in_data, dit_x_num_elems, curr_t, next_t, i, seed + i + 4564);

        float step_progress = 0.10f + (0.75f * (i + 1) / steps);
        handle->progress.store(step_progress);

        if (i < 2 || i >= steps - 2) {
            LOGI("Step %zu/%zu: t=%.4f -> %.4f", i + 1, steps, curr_t, next_t);
        }
    }

    auto end_dit = std::chrono::steady_clock::now();
    LOGI("DiT done in %lldms (avg %.1fms/step)",
         (long long)std::chrono::duration_cast<std::chrono::milliseconds>(end_dit - start_dit).count(),
         (double)std::chrono::duration_cast<std::chrono::milliseconds>(end_dit - start_dit).count() / (double)steps);

    // Save DiT output to buffer before unloading
    latent_buffer.resize(dit_x_num_elems);
    memcpy(latent_buffer.data(), dit_x_in_data, dit_x_num_elems * sizeof(float));
    LOGD("Latent saved: %.2f MB", dit_x_num_elems * sizeof(float) / (1024.0 * 1024.0));

    // Unload DiT to free memory
    handle->unloadDiT();

    // MEMORY OPTIMIZATION: Clear all T5/DiT related buffers before loading AutoEncoder
    // AutoEncoder is memory-intensive; we need to minimize memory usage
    crossattn_buffer.clear();
    crossattn_buffer.shrink_to_fit();
    globalcond_buffer.clear();
    globalcond_buffer.shrink_to_fit();

    // Small pause to allow OS to reclaim memory
    LOGI("Memory cleanup before AutoEncoder, pausing briefly...");
    std::this_thread::sleep_for(std::chrono::milliseconds(100));

    handle->progress.store(0.85f);

    if (handle->cancelled.load()) {
        set_error("Generation cancelled");
        return JNI_FALSE;
    }

    // ========== PHASE 3: AutoEncoder Decode ==========
    LOGI("--- Phase 3: AutoEncoder Decode ---");
    if (!handle->loadAutoEncoder()) {
        return JNI_FALSE;
    }

    tflite::Interpreter* autoencoder = handle->autoencoder_interpreter.get();

    // Get AutoEncoder tensor info
    const size_t ae_in_id = autoencoder->inputs()[0];
    const size_t ae_out_id = autoencoder->outputs()[0];
    float* ae_in_data = autoencoder->typed_tensor<float>(ae_in_id);
    TfLiteTensor* ae_in_tensor = autoencoder->tensor(ae_in_id);

    // Verify size match
    size_t ae_in_elems = ae_in_tensor->bytes / sizeof(float);
    if (latent_buffer.size() != ae_in_elems) {
        LOGE("SIZE MISMATCH! Latent (%zu) != AutoEncoder input (%zu)", latent_buffer.size(), ae_in_elems);
        set_error("Size mismatch between latent and AutoEncoder input");
        handle->unloadAutoEncoder();
        return JNI_FALSE;
    }

    // Copy latent to AutoEncoder input
    memcpy(ae_in_data, latent_buffer.data(), latent_buffer.size() * sizeof(float));

    // Free latent buffer (no longer needed)
    latent_buffer.clear();
    latent_buffer.shrink_to_fit();

    // Run AutoEncoder
    LOGI("Running AutoEncoder decoder...");
    auto start_ae = std::chrono::steady_clock::now();
    if (autoencoder->Invoke() != kTfLiteOk) {
        set_error("AutoEncoder inference failed");
        handle->unloadAutoEncoder();
        return JNI_FALSE;
    }
    auto end_ae = std::chrono::steady_clock::now();
    LOGI("AutoEncoder done in %lldms", (long long)std::chrono::duration_cast<std::chrono::milliseconds>(end_ae - start_ae).count());

    handle->progress.store(0.95f);

    // Get audio output
    float* ae_out_data = autoencoder->typed_tensor<float>(ae_out_id);
    TfLiteIntArray* ae_out_dims = autoencoder->tensor(ae_out_id)->dims;

    if (!ae_out_data || !ae_out_dims) {
        set_error("AutoEncoder output is NULL");
        handle->unloadAutoEncoder();
        return JNI_FALSE;
    }

    const size_t total_elems = get_num_elems(ae_out_dims);
    const size_t total_audio_samples = total_elems / 2;
    const float* left_ch = ae_out_data;
    const float* right_ch = ae_out_data + total_audio_samples;

    // Trim to requested duration
    size_t requested_samples = static_cast<size_t>(audio_len_sec * k_audio_sr);
    size_t num_audio_samples = std::min(requested_samples, total_audio_samples);
    LOGI("Audio output: total=%zu samples (%.2fs), trimmed to %zu samples (%.2fs)",
         total_audio_samples, (float)total_audio_samples / k_audio_sr,
         num_audio_samples, (float)num_audio_samples / k_audio_sr);

    // Save WAV file
    LOGD("Saving WAV file to: %s", output_path.c_str());
    if (!save_as_wav(output_path, left_ch, right_ch, num_audio_samples)) {
        handle->unloadAutoEncoder();
        return JNI_FALSE;
    }

    // Unload AutoEncoder to free memory
    handle->unloadAutoEncoder();

    handle->progress.store(1.0f);
    LOGI("✅ Audio saved to: %s", output_path.c_str());
    LOGI("=== GENERATION COMPLETE ===");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_dramebaz_app_ai_audio_StableAudioNative_getLastError(JNIEnv* env, jclass clazz) {
    (void)clazz;
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jfloat JNICALL
Java_com_dramebaz_app_ai_audio_StableAudioNative_getProgress(JNIEnv* env, jclass clazz, jlong handle_ptr) {
    (void)env;
    (void)clazz;
    auto* handle = reinterpret_cast<StableAudioHandle*>(handle_ptr);
    if (!handle) return 0.0f;
    return handle->progress.load();
}

JNIEXPORT void JNICALL
Java_com_dramebaz_app_ai_audio_StableAudioNative_cancel(JNIEnv* env, jclass clazz, jlong handle_ptr) {
    (void)env;
    (void)clazz;
    auto* handle = reinterpret_cast<StableAudioHandle*>(handle_ptr);
    if (handle) {
        handle->cancelled.store(true);
    }
}

JNIEXPORT void JNICALL
Java_com_dramebaz_app_ai_audio_StableAudioNative_release(JNIEnv* env, jclass clazz, jlong handle_ptr) {
    (void)env;
    (void)clazz;
    auto* handle = reinterpret_cast<StableAudioHandle*>(handle_ptr);
    if (handle) {
        LOGI("Releasing StableAudioHandle");
        delete handle;
    }
}

// Helper to get tensor type name
static const char* getTensorTypeName(TfLiteType type) {
    switch (type) {
        case kTfLiteFloat32: return "FLOAT32";
        case kTfLiteInt32: return "INT32";
        case kTfLiteUInt8: return "UINT8";
        case kTfLiteInt64: return "INT64";
        case kTfLiteString: return "STRING";
        case kTfLiteBool: return "BOOL";
        case kTfLiteInt16: return "INT16";
        case kTfLiteComplex64: return "COMPLEX64";
        case kTfLiteInt8: return "INT8";
        case kTfLiteFloat16: return "FLOAT16";
        default: return "UNKNOWN";
    }
}

// Helper to log tensor details
static void logTensorDetails(const char* modelName, const char* tensorType,
                             tflite::Interpreter* interpreter, const std::vector<int>& indices) {
    for (size_t i = 0; i < indices.size(); i++) {
        int idx = indices[i];
        TfLiteTensor* t = interpreter->tensor(idx);
        if (t) {
            std::string dims_str;
            size_t total_elems = 1;
            for (int d = 0; d < t->dims->size; d++) {
                dims_str += std::to_string(t->dims->data[d]);
                total_elems *= t->dims->data[d];
                if (d < t->dims->size - 1) dims_str += " x ";
            }
            LOGI("%s %s[%zu]: name='%s', type=%s, dims=[%s], elements=%zu, bytes=%zu",
                 modelName, tensorType, i,
                 t->name ? t->name : "(null)",
                 getTensorTypeName(t->type),
                 dims_str.c_str(), total_elems, t->bytes);
        }
    }
}

/**
 * Inspect all models and log their input/output tensor specifications.
 * This helps debug tensor mismatches and understand the pipeline.
 */
JNIEXPORT jstring JNICALL
Java_com_dramebaz_app_ai_audio_StableAudioNative_inspectModels(JNIEnv* env, jclass clazz, jstring model_dir_jstr) {
    (void)clazz;

    const char* model_dir_cstr = env->GetStringUTFChars(model_dir_jstr, nullptr);
    std::string model_directory(model_dir_cstr);
    env->ReleaseStringUTFChars(model_dir_jstr, model_dir_cstr);

    std::string result;
    result += "=== MODEL INSPECTION REPORT ===\n\n";

    // Model paths
    std::string t5_path = model_directory + "/conditioners_int8.tflite";
    std::string dit_path = model_directory + "/dit_model_int8.tflite";
    std::string ae_path = model_directory + "/autoencoder_model_int8.tflite";

    // Use BuiltinOpResolver with extended FULLY_CONNECTED version support (up to v12)
    // to match the main model loading code and support int8 quantized models
    tflite::ops::builtin::BuiltinOpResolver resolver;
    resolver.AddBuiltin(
            tflite::BuiltinOperator_FULLY_CONNECTED,
            tflite::ops::builtin::Register_FULLY_CONNECTED(),
            /*min_version=*/1,
            /*max_version=*/12);

    // ========== T5 / Conditioners Model ==========
    LOGI("========== T5 / CONDITIONERS MODEL ==========");
    result += "=== T5 / CONDITIONERS MODEL ===\n";
    result += "Path: " + t5_path + "\n";

    auto t5_model = tflite::FlatBufferModel::BuildFromFile(t5_path.c_str());
    if (t5_model) {
        std::unique_ptr<tflite::Interpreter> t5_interp;
        tflite::InterpreterBuilder(*t5_model, resolver)(&t5_interp);
        if (t5_interp) {
            result += "Inputs: " + std::to_string(t5_interp->inputs().size()) + "\n";
            result += "Outputs: " + std::to_string(t5_interp->outputs().size()) + "\n";

            LOGI("T5 Inputs: %zu", t5_interp->inputs().size());
            logTensorDetails("T5", "INPUT", t5_interp.get(), t5_interp->inputs());

            LOGI("T5 Outputs: %zu", t5_interp->outputs().size());
            logTensorDetails("T5", "OUTPUT", t5_interp.get(), t5_interp->outputs());

            // Add to result string
            for (size_t i = 0; i < t5_interp->inputs().size(); i++) {
                TfLiteTensor* t = t5_interp->tensor(t5_interp->inputs()[i]);
                if (t) {
                    std::string dims_str;
                    for (int d = 0; d < t->dims->size; d++) {
                        dims_str += std::to_string(t->dims->data[d]);
                        if (d < t->dims->size - 1) dims_str += "x";
                    }
                    result += "  Input[" + std::to_string(i) + "]: " +
                              (t->name ? t->name : "?") + " [" + dims_str + "] " +
                              getTensorTypeName(t->type) + "\n";
                }
            }
            for (size_t i = 0; i < t5_interp->outputs().size(); i++) {
                TfLiteTensor* t = t5_interp->tensor(t5_interp->outputs()[i]);
                if (t) {
                    std::string dims_str;
                    for (int d = 0; d < t->dims->size; d++) {
                        dims_str += std::to_string(t->dims->data[d]);
                        if (d < t->dims->size - 1) dims_str += "x";
                    }
                    result += "  Output[" + std::to_string(i) + "]: " +
                              (t->name ? t->name : "?") + " [" + dims_str + "] " +
                              getTensorTypeName(t->type) + "\n";
                }
            }
        } else {
            result += "ERROR: Failed to build T5 interpreter\n";
            LOGE("Failed to build T5 interpreter");
        }
    } else {
        result += "ERROR: Failed to load T5 model\n";
        LOGE("Failed to load T5 model: %s", t5_path.c_str());
    }

    result += "\n";

    // ========== DiT Model ==========
    LOGI("========== DiT MODEL ==========");
    result += "=== DiT MODEL ===\n";
    result += "Path: " + dit_path + "\n";

    auto dit_model = tflite::FlatBufferModel::BuildFromFile(dit_path.c_str());
    if (dit_model) {
        std::unique_ptr<tflite::Interpreter> dit_interp;
        tflite::InterpreterBuilder(*dit_model, resolver)(&dit_interp);
        if (dit_interp) {
            result += "Inputs: " + std::to_string(dit_interp->inputs().size()) + "\n";
            result += "Outputs: " + std::to_string(dit_interp->outputs().size()) + "\n";

            LOGI("DiT Inputs: %zu", dit_interp->inputs().size());
            logTensorDetails("DiT", "INPUT", dit_interp.get(), dit_interp->inputs());

            LOGI("DiT Outputs: %zu", dit_interp->outputs().size());
            logTensorDetails("DiT", "OUTPUT", dit_interp.get(), dit_interp->outputs());

            for (size_t i = 0; i < dit_interp->inputs().size(); i++) {
                TfLiteTensor* t = dit_interp->tensor(dit_interp->inputs()[i]);
                if (t) {
                    std::string dims_str;
                    for (int d = 0; d < t->dims->size; d++) {
                        dims_str += std::to_string(t->dims->data[d]);
                        if (d < t->dims->size - 1) dims_str += "x";
                    }
                    result += "  Input[" + std::to_string(i) + "]: " +
                              (t->name ? t->name : "?") + " [" + dims_str + "] " +
                              getTensorTypeName(t->type) + "\n";
                }
            }
            for (size_t i = 0; i < dit_interp->outputs().size(); i++) {
                TfLiteTensor* t = dit_interp->tensor(dit_interp->outputs()[i]);
                if (t) {
                    std::string dims_str;
                    for (int d = 0; d < t->dims->size; d++) {
                        dims_str += std::to_string(t->dims->data[d]);
                        if (d < t->dims->size - 1) dims_str += "x";
                    }
                    result += "  Output[" + std::to_string(i) + "]: " +
                              (t->name ? t->name : "?") + " [" + dims_str + "] " +
                              getTensorTypeName(t->type) + "\n";
                }
            }
        } else {
            result += "ERROR: Failed to build DiT interpreter\n";
            LOGE("Failed to build DiT interpreter");
        }
    } else {
        result += "ERROR: Failed to load DiT model\n";
        LOGE("Failed to load DiT model: %s", dit_path.c_str());
    }

    result += "\n";

    // ========== AutoEncoder Model ==========
    LOGI("========== AUTOENCODER MODEL ==========");
    result += "=== AUTOENCODER MODEL ===\n";
    result += "Path: " + ae_path + "\n";

    auto ae_model = tflite::FlatBufferModel::BuildFromFile(ae_path.c_str());
    if (ae_model) {
        std::unique_ptr<tflite::Interpreter> ae_interp;
        tflite::InterpreterBuilder(*ae_model, resolver)(&ae_interp);
        if (ae_interp) {
            result += "Inputs: " + std::to_string(ae_interp->inputs().size()) + "\n";
            result += "Outputs: " + std::to_string(ae_interp->outputs().size()) + "\n";

            LOGI("AutoEncoder Inputs: %zu", ae_interp->inputs().size());
            logTensorDetails("AutoEncoder", "INPUT", ae_interp.get(), ae_interp->inputs());

            LOGI("AutoEncoder Outputs: %zu", ae_interp->outputs().size());
            logTensorDetails("AutoEncoder", "OUTPUT", ae_interp.get(), ae_interp->outputs());

            for (size_t i = 0; i < ae_interp->inputs().size(); i++) {
                TfLiteTensor* t = ae_interp->tensor(ae_interp->inputs()[i]);
                if (t) {
                    std::string dims_str;
                    for (int d = 0; d < t->dims->size; d++) {
                        dims_str += std::to_string(t->dims->data[d]);
                        if (d < t->dims->size - 1) dims_str += "x";
                    }
                    result += "  Input[" + std::to_string(i) + "]: " +
                              (t->name ? t->name : "?") + " [" + dims_str + "] " +
                              getTensorTypeName(t->type) + "\n";
                }
            }
            for (size_t i = 0; i < ae_interp->outputs().size(); i++) {
                TfLiteTensor* t = ae_interp->tensor(ae_interp->outputs()[i]);
                if (t) {
                    std::string dims_str;
                    for (int d = 0; d < t->dims->size; d++) {
                        dims_str += std::to_string(t->dims->data[d]);
                        if (d < t->dims->size - 1) dims_str += "x";
                    }
                    result += "  Output[" + std::to_string(i) + "]: " +
                              (t->name ? t->name : "?") + " [" + dims_str + "] " +
                              getTensorTypeName(t->type) + "\n";
                }
            }
        } else {
            result += "ERROR: Failed to build AutoEncoder interpreter\n";
            LOGE("Failed to build AutoEncoder interpreter");
        }
    } else {
        result += "ERROR: Failed to load AutoEncoder model\n";
        LOGE("Failed to load AutoEncoder model: %s", ae_path.c_str());
    }

    result += "\n=== END OF REPORT ===\n";
    LOGI("=== MODEL INSPECTION COMPLETE ===");

    return env->NewStringUTF(result.c_str());
}

/**
 * Check if GPU shader cache exists and is valid.
 * Returns true if shaders are ready to use (cached from previous compilation).
 */
JNIEXPORT jboolean JNICALL
Java_com_dramebaz_app_ai_audio_StableAudioNative_isGpuShadersReady(JNIEnv* env, jclass clazz, jstring model_dir_jstr) {
    (void)clazz;
#if HAS_GPU_DELEGATE
    const char* model_dir = env->GetStringUTFChars(model_dir_jstr, nullptr);
    std::string cache_dir = std::string(model_dir) + "/gpu_cache";
    env->ReleaseStringUTFChars(model_dir_jstr, model_dir);

    // Check if cache directory exists and has shader cache files
    // TFLite GPU delegate creates files like: stable_audio_v1_*.bin
    std::string marker_file = cache_dir + "/.shaders_ready";
    struct stat st;
    if (stat(marker_file.c_str(), &st) == 0) {
        LOGI("GPU shaders ready (cache found at %s)", cache_dir.c_str());
        return JNI_TRUE;
    }
    LOGD("GPU shaders not ready (no cache at %s)", cache_dir.c_str());
    return JNI_FALSE;
#else
    (void)env;
    (void)model_dir_jstr;
    return JNI_FALSE;
#endif
}

/**
 * Compile GPU shaders in background with reduced resources.
 * This function loads models, compiles GPU shaders, then releases everything.
 * Uses low thread count to minimize system impact.
 *
 * @param model_dir_jstr Path to model directory
 * @param num_threads Number of threads to use (1-2 recommended for background)
 * @return true if shader compilation succeeded
 */
JNIEXPORT jboolean JNICALL
Java_com_dramebaz_app_ai_audio_StableAudioNative_prepareGpuShaders(JNIEnv* env, jclass clazz, jstring model_dir_jstr, jint num_threads) {
    (void)clazz;
#if HAS_GPU_DELEGATE
    const char* model_dir = env->GetStringUTFChars(model_dir_jstr, nullptr);
    std::string model_directory(model_dir);
    env->ReleaseStringUTFChars(model_dir_jstr, model_dir);

    LOGI("Starting background GPU shader compilation (threads: %d)", num_threads);

    // Construct model paths
    std::string dit_tflite = model_directory + "/dit_model_int8.tflite";
    std::string autoencoder_tflite = model_directory + "/autoencoder_model_int8.tflite";

    // Load models with memory mapping
    auto dit_model = tflite::FlatBufferModel::BuildFromFile(dit_tflite.c_str());
    auto autoencoder_model = tflite::FlatBufferModel::BuildFromFile(autoencoder_tflite.c_str());

    if (!dit_model || !autoencoder_model) {
        LOGE("Failed to load models for GPU shader compilation");
        return JNI_FALSE;
    }

    // Build interpreters
    tflite::ops::builtin::BuiltinOpResolver resolver;
    std::unique_ptr<tflite::Interpreter> dit_interpreter;
    std::unique_ptr<tflite::Interpreter> autoencoder_interpreter;

    tflite::InterpreterBuilder dit_builder(*dit_model, resolver);
    tflite::InterpreterBuilder autoencoder_builder(*autoencoder_model, resolver);

    dit_builder(&dit_interpreter);
    autoencoder_builder(&autoencoder_interpreter);

    if (!dit_interpreter || !autoencoder_interpreter) {
        LOGE("Failed to build interpreters for GPU shader compilation");
        return JNI_FALSE;
    }

    // Set low thread count for background operation
    dit_interpreter->SetNumThreads(num_threads);
    autoencoder_interpreter->SetNumThreads(num_threads);

    // Configure GPU delegate with shader caching
    TfLiteGpuDelegateOptionsV2 gpu_options = TfLiteGpuDelegateOptionsV2Default();
    gpu_options.inference_preference = TFLITE_GPU_INFERENCE_PREFERENCE_SUSTAINED_SPEED;
    gpu_options.inference_priority1 = TFLITE_GPU_INFERENCE_PRIORITY_MIN_LATENCY;
    gpu_options.inference_priority2 = TFLITE_GPU_INFERENCE_PRIORITY_MIN_MEMORY_USAGE;
    gpu_options.inference_priority3 = TFLITE_GPU_INFERENCE_PRIORITY_MAX_PRECISION;
    gpu_options.experimental_flags = TFLITE_GPU_EXPERIMENTAL_FLAGS_ENABLE_QUANT;

    // Enable shader serialization
    std::string cache_dir = model_directory + "/gpu_cache";
    mkdir(cache_dir.c_str(), 0755);
    gpu_options.serialization_dir = cache_dir.c_str();
    gpu_options.model_token = "stable_audio_v1";

    LOGI("Compiling GPU shaders (this may take 60-90 seconds)...");

    // Create and apply GPU delegates
    auto dit_delegate = TfLiteGpuDelegateV2Create(&gpu_options);
    auto autoencoder_delegate = TfLiteGpuDelegateV2Create(&gpu_options);

    bool success = true;
    if (dit_delegate && autoencoder_delegate) {
        bool dit_ok = (dit_interpreter->ModifyGraphWithDelegate(dit_delegate) == kTfLiteOk);
        bool ae_ok = (autoencoder_interpreter->ModifyGraphWithDelegate(autoencoder_delegate) == kTfLiteOk);

        if (dit_ok && ae_ok) {
            // Allocate tensors to trigger full shader compilation
            if (dit_interpreter->AllocateTensors() == kTfLiteOk &&
                autoencoder_interpreter->AllocateTensors() == kTfLiteOk) {
                LOGI("✅ GPU shaders compiled and cached successfully");

                // Create marker file to indicate shaders are ready
                std::string marker_file = cache_dir + "/.shaders_ready";
                std::ofstream marker(marker_file);
                if (marker.is_open()) {
                    marker << "1";
                    marker.close();
                }
            } else {
                LOGE("Failed to allocate tensors during shader compilation");
                success = false;
            }
        } else {
            LOGE("Failed to apply GPU delegates (DiT: %s, AutoEncoder: %s)",
                 dit_ok ? "OK" : "FAILED", ae_ok ? "OK" : "FAILED");
            success = false;
        }
    } else {
        LOGE("Failed to create GPU delegates for shader compilation");
        success = false;
    }

    // Clean up
    if (dit_delegate) TfLiteGpuDelegateV2Delete(dit_delegate);
    if (autoencoder_delegate) TfLiteGpuDelegateV2Delete(autoencoder_delegate);

    return success ? JNI_TRUE : JNI_FALSE;
#else
    (void)env;
    (void)model_dir_jstr;
    (void)num_threads;
    LOGW("GPU delegate not available - shader compilation skipped");
    return JNI_FALSE;
#endif
}

/**
 * Check if GPU delegate is available in this build.
 */
JNIEXPORT jboolean JNICALL
Java_com_dramebaz_app_ai_audio_StableAudioNative_isGpuDelegateAvailable(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
#if HAS_GPU_DELEGATE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

} // extern "C"

