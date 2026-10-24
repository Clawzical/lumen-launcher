package dev.lumen.launcher.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object Http {
    val json = Json {
        ignoreUnknownKeys = true   // APIs add fields all the time; never crash on them
        explicitNulls = false
        coerceInputValues = true
        prettyPrint = false
    }

    /**
     * One client for the whole app: a single connection pool with keep-alive is far cheaper
     * than a client per request. CIO is a pure-coroutine engine — no thread per connection.
     */
    fun createClient(userAgent: String): HttpClient = HttpClient(CIO) {
        expectSuccess = true
        engine {
            maxConnectionsCount = 64
            endpoint {
                maxConnectionsPerRoute = 16 // asset installs fetch thousands of tiny files from one host
                keepAliveTime = 5_000
                connectTimeout = 10_000
            }
        }
        install(UserAgent) { agent = userAgent }
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000 // per-read stall timeout; no total timeout so big files can finish
        }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            retryOnException(maxRetries = 3, retryOnTimeout = true)
            exponentialDelay()
        }
    }
}
