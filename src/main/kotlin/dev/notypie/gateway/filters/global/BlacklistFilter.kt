package dev.notypie.gateway.filters.global

import dev.notypie.gateway.service.BlacklistService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withTimeout
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.time.Instant

val logger = KotlinLogging.logger { }

class BlacklistFilter(
    private val blacklistService: BlacklistService,
) : AbstractGatewayFilterFactory<BlacklistFilter.Config>() {
    override fun apply(config: Config): GatewayFilter =
        GatewayFilter { exchange, chain ->
            mono {
                val request = exchange.request
                val clientIp = getClientIp(request)
                val userId = request.headers.getFirst("X-User-ID")
                val apiKey = request.headers.getFirst("X-API-Key")

                try {
                    val isBlocked =
                        withTimeout(config.timeoutMs) {
                            blacklistService.isAnyBlacklisted(clientIp, userId, apiKey)
                        }
                    if (isBlocked) {
                        blockRequest(exchange, clientIp, userId, apiKey)
                        null
                    } else {
                        chain.filter(exchange).awaitSingleOrNull()
                    }
                    // FIXME exception handling
                } catch (e: TimeoutCancellationException) {
                    // pass if timeout occurs
                    logger.error {
                        "BLOCKED REQUEST - TIMEOUT EXCEPTION - IP: $clientIp, UserID: $userId," +
                            " APIKey: ${apiKey?.take(8)}*** "
                    }
                    chain.filter(exchange).awaitSingleOrNull()
                } catch (e: Exception) {
                    logger.error {
                        "BLOCKED REQUEST - EXCEPTION - IP: $clientIp, UserID: $userId, " +
                            "APIKey: ${apiKey?.take(8)}*** "
                    }
                    // pass if any exception occurs
                    chain.filter(exchange).awaitSingleOrNull()
                }
            }
        }

    private suspend fun blockRequest(
        exchange: ServerWebExchange,
        clientIp: String,
        userId: String?,
        apiKey: String?,
    ) {
        val response = exchange.response
        logBlockedRequest(clientIp, userId, apiKey)

        response.statusCode = HttpStatus.FORBIDDEN
        response.headers.contentType = MediaType.APPLICATION_JSON

        val errorMessage =
            """
            {
                "error": "Access Denied",
                "message": "Your request has been blocked",
                "code": "BLACKLISTED",
                "timestamp": "${Instant.now()}"
            }
            """.trimIndent()

        val buffer = response.bufferFactory().wrap(errorMessage.toByteArray(StandardCharsets.UTF_8))
        response.writeWith(Mono.just(buffer)).awaitSingleOrNull()
    }

    private fun logBlockedRequest(clientIp: String, userId: String?, apiKey: String?) =
        logger.error { "BLOCKED REQUEST - IP: $clientIp, UserID: $userId, APIKey: ${apiKey?.take(8)}*** " }

    private fun getClientIp(request: ServerHttpRequest): String {
        // X-Forwarded-For header check
        request.headers.getFirst("X-Forwarded-For")?.let { xForwardedFor ->
            return xForwardedFor.split(",").first().trim()
        }
        // X-Real-IP header
        request.headers.getFirst("X-Real-IP")?.let { return it }
        // client Ip
        return request.remoteAddress?.address?.hostAddress ?: "unknown"
    }

    data class Config(
        var timeoutMs: Long = 500L,
        var enableIpCheck: Boolean = true,
        var enableUserCheck: Boolean = true,
        var enableApiKeyCheck: Boolean = true,
    )
}
