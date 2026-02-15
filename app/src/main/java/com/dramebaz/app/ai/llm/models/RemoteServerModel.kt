package com.dramebaz.app.ai.llm.models

import com.dramebaz.app.ai.llm.ModelCapabilities
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * LlmModel implementation that connects to a remote AIServer for inference.
 * Uses HTTP/REST API to communicate with the server.
 *
 * Design Patterns:
 * - **Strategy Pattern**: Interchangeable with other LlmModel implementations
 * - **Adapter Pattern**: Adapts REST API to LlmModel interface
 *
 * @param config Remote server configuration
 */
class RemoteServerModel(
    private val config: RemoteServerConfig = RemoteServerConfig.DEFAULT
) : LlmModel {

    companion object {
        private const val TAG = "RemoteServerModel"
    }

    private val gson = Gson()
    private var defaultParams = GenerationParams.DEFAULT
    private var sessionParams = SessionParams.DEFAULT
    @Volatile
    private var serverConnected = false

    // ==================== Lifecycle ====================

    override suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Checking connection to remote server: ${config.baseUrl}")
        try {
            val url = URL(config.healthUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = config.connectTimeoutMs
            connection.readTimeout = 5000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            connection.disconnect()

            serverConnected = responseCode == 200
            if (serverConnected) {
                AppLogger.i(TAG, "✅ Connected to remote server: ${config.baseUrl}")
            } else {
                AppLogger.w(TAG, "❌ Server returned status $responseCode")
            }
            serverConnected
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ Failed to connect to server: ${e.message}", e)
            serverConnected = false
            false
        }
    }

    override fun isModelLoaded(): Boolean = serverConnected

    override fun release() {
        serverConnected = false
        AppLogger.d(TAG, "Released remote server connection")
    }

    // ==================== Configuration ====================

    override fun getDefaultParams(): GenerationParams = defaultParams

    override fun updateDefaultParams(params: GenerationParams) {
        defaultParams = params.validated()
    }

    override fun getSessionParams(): SessionParams = sessionParams

    override fun updateSessionParams(params: SessionParams): Boolean {
        sessionParams = params.validated()
        return true
    }

    override fun getSessionParamsSupport(): SessionParamsSupport = SessionParamsSupport(
        supportsRepeatPenalty = true,
        supportsContextLength = false,
        supportsGpuLayers = false,
        supportsAccelerators = false,
        requiresReload = false
    )

    // ==================== Core Inference ====================

    override suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        params: GenerationParams
    ): String = withContext(Dispatchers.IO) {
        if (!serverConnected) {
            AppLogger.w(TAG, "Server not connected, attempting reconnect...")
            if (!loadModel()) {
                AppLogger.e(TAG, "Failed to reconnect to server")
                return@withContext ""
            }
        }

        try {
            val combinedPrompt = buildCombinedPrompt(systemPrompt, userPrompt)
            val stopSequence = params.stopSequences.firstOrNull()
            val request = GenerateRequest(
                model = config.modelId,
                prompt = combinedPrompt,
                maxTokens = params.maxTokens,
                temperature = params.temperature,
                topP = params.topP,
                topK = params.topK,
                repeatPenalty = sessionParams.repeatPenalty,
                stop = stopSequence
            )

            AppLogger.d(TAG, "Sending inference request to ${config.generateUrl}")
            val response = sendGenerateRequest(request)
            if (response?.error != null) {
                AppLogger.e(TAG, "Server error: ${response.error.code} - ${response.error.message}")
                return@withContext ""
            }
            response?.generatedText ?: ""
        } catch (e: Exception) {
            AppLogger.e(TAG, "Inference failed: ${e.message}", e)
            serverConnected = false
            ""
        }
    }

    // ==================== Metadata & Capabilities ====================

    override fun getExecutionProvider(): String = "Remote Server (${config.host}:${config.port})"

    override fun isUsingGpu(): Boolean = true  // Server likely uses GPU

    override fun getModelCapabilities(): ModelCapabilities = ModelCapabilities(
        modelName = "Remote: ${config.modelId}",
        supportsImage = false,
        supportsAudio = false,
        supportsStreaming = false,
        maxContextLength = 8192,
        recommendedMaxTokens = 2048
    )

    override fun getModelInfo(): ModelInfo = ModelInfo(
        name = "Remote: ${config.modelId}",
        format = ModelFormat.REMOTE,
        filePath = config.baseUrl,
        version = null,
        sizeBytes = null
    )

    // ==================== Private Helpers ====================

    private fun buildCombinedPrompt(systemPrompt: String, userPrompt: String): String {
        return if (systemPrompt.isNotBlank()) {
            "<|system|>\n$systemPrompt\n<|user|>\n$userPrompt\n<|assistant|>"
        } else {
            userPrompt
        }
    }

    private fun sendGenerateRequest(request: GenerateRequest): GenerateResponse? {
        val url = URL(config.generateUrl)
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.connectTimeout = config.connectTimeoutMs
            connection.readTimeout = config.readTimeoutMs
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val jsonBody = gson.toJson(request)
            AppLogger.d(TAG, "Request body: $jsonBody")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                AppLogger.d(TAG, "Response: $responseBody")
                gson.fromJson(responseBody, GenerateResponse::class.java)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                AppLogger.e(TAG, "Server error $responseCode: $errorBody")
                null
            }
        } finally {
            connection.disconnect()
        }
    }
}

// ==================== Request/Response DTOs ====================

/**
 * Request body for /api/v1/generate endpoint.
 * Unified request format matching AIServer API.
 */
private data class GenerateRequest(
    @SerializedName("model") val model: String,
    @SerializedName("prompt") val prompt: String,
    @SerializedName("max_tokens") val maxTokens: Int,
    @SerializedName("temperature") val temperature: Float,
    @SerializedName("top_p") val topP: Float,
    @SerializedName("top_k") val topK: Int,
    @SerializedName("repeat_penalty") val repeatPenalty: Float,
    @SerializedName("stop") val stop: String? = null
)

/**
 * Response body from /api/v1/generate endpoint.
 * Unified response format matching AIServer API.
 */
private data class GenerateResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("model") val model: String?,
    @SerializedName("task") val task: String?,
    @SerializedName("generated_text") val generatedText: String?,
    @SerializedName("usage") val usage: UsageInfo?,
    @SerializedName("timing") val timing: TimingInfo?,
    @SerializedName("error") val error: ErrorInfo?
)

private data class UsageInfo(
    @SerializedName("prompt_tokens") val promptTokens: Int?,
    @SerializedName("completion_tokens") val completionTokens: Int?,
    @SerializedName("total_tokens") val totalTokens: Int?
)

private data class TimingInfo(
    @SerializedName("inference_time_ms") val inferenceTimeMs: Double?,
    @SerializedName("tokens_per_second") val tokensPerSecond: Double?
)

private data class ErrorInfo(
    @SerializedName("code") val code: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("http_status") val httpStatus: Int?
)

