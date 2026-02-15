package com.dramebaz.app.ai.llm.models

/**
 * Configuration for remote AI server connection.
 * Used by RemoteServerModel to connect to an AIServer instance.
 *
 * Endpoints (AIServer unified API):
 * - POST /api/v1/generate - Text generation inference
 * - GET /api/v1/models - List available models
 * - GET /api/v1/status - Server status
 * - GET /health - Health check
 *
 * @param host Server hostname or IP address
 * @param port Server port number
 * @param modelId The model ID loaded on the server (e.g., "qwen3-1.7b")
 * @param connectTimeoutMs Connection timeout in milliseconds
 * @param readTimeoutMs Read timeout in milliseconds
 * @param useTls Whether to use HTTPS instead of HTTP
 */
data class RemoteServerConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val modelId: String = DEFAULT_MODEL_ID,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    val useTls: Boolean = false
) {
    companion object {
        const val DEFAULT_HOST = "192.168.1.100"
        const val DEFAULT_PORT = 8080
        const val DEFAULT_MODEL_ID = "qwen3-1.7b"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 600_000  // 10 minutes for inference

        /** Default configuration for local network server */
        val DEFAULT = RemoteServerConfig()

        /** Configuration for localhost testing */
        val LOCALHOST = RemoteServerConfig(host = "127.0.0.1")
    }

    /**
     * Get the base URL for API requests.
     */
    val baseUrl: String
        get() {
            val protocol = if (useTls) "https" else "http"
            return "$protocol://$host:$port"
        }

    /**
     * Get the generate endpoint URL.
     */
    val generateUrl: String
        get() = "$baseUrl/api/v1/generate"

    /**
     * Get the models list endpoint URL.
     */
    val modelsUrl: String
        get() = "$baseUrl/api/v1/models"

    /**
     * Get the health check endpoint URL.
     */
    val healthUrl: String
        get() = "$baseUrl/health"

    /**
     * Validate the configuration.
     * @return true if configuration is valid
     */
    fun isValid(): Boolean {
        return host.isNotBlank() &&
                port in 1..65535 &&
                modelId.isNotBlank() &&
                connectTimeoutMs > 0 &&
                readTimeoutMs > 0
    }
}

