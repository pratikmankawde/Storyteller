package com.dramebaz.app.ai.audio

/**
 * Configuration for remote SFX server connection.
 * Used by SfxEngine for remote SFX generation via AIServer.
 *
 * Endpoints (AIServer unified API):
 * - POST /api/v1/audio-gen - Audio/SFX generation (unified format)
 * - GET /api/v1/models - List available models (includes audio models)
 * - GET /health - Health check
 *
 * @param host Server hostname or IP address
 * @param port Server port number
 * @param modelId The audio generation model ID on the server (e.g., "stable-audio")
 * @param connectTimeoutMs Connection timeout in milliseconds
 * @param readTimeoutMs Read timeout in milliseconds
 * @param useTls Whether to use HTTPS instead of HTTP
 */
data class RemoteSfxConfig(
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
        const val DEFAULT_MODEL_ID = "stable-audio"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 180_000  // 3 minutes for SFX generation

        /** Default configuration for local network server */
        val DEFAULT = RemoteSfxConfig()

        /** Configuration for localhost testing */
        val LOCALHOST = RemoteSfxConfig(host = "127.0.0.1")
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
     * Get the audio generation endpoint URL.
     */
    val audioGenUrl: String
        get() = "$baseUrl/api/v1/audio-gen"

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

