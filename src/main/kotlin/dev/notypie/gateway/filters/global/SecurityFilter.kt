package dev.notypie.gateway.filters.global

import dev.notypie.gateway.service.BlacklistService
import dev.notypie.gateway.service.RateLimitConfig
import dev.notypie.gateway.service.RateLimitResult
import dev.notypie.gateway.service.RateLimitService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withTimeout
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.time.Instant

val logger = KotlinLogging.logger { }

@Component
@Order(-100)
class SecurityFilter(
    private val blacklistService: BlacklistService,
    private val rateLimitService: RateLimitService,
) : AbstractGatewayFilterFactory<SecurityFilter.Config>() {
    override fun apply(config: Config): GatewayFilter =
        GatewayFilter { exchange, chain ->
            mono {
                val request = exchange.request
                val clientIp = getClientIp(request)
                val userId = request.headers.getFirst("X-User-ID")
                val apiKey = request.headers.getFirst("X-API-Key")
                val endpoint = request.path.pathWithinApplication().value()

                try {
                    withTimeout(config.timeoutMs) {
                        coroutineScope {
                            val isBlacklisted =
                                async {
                                    if (config.enableBlacklist) {
                                        blacklistService.isAnyBlacklisted(clientIp, userId, apiKey)
                                    } else {
                                        false
                                    }
                                }

                            val rateLimitResult =
                                async {
                                    if (config.enableRateLimit) {
                                        rateLimitService.checkMultipleRateLimits(
                                            ip = clientIp,
                                            userId = userId,
                                            apiKey = apiKey,
                                            endpoint = endpoint,
                                            limits = config.rateLimitConfig,
                                        )
                                    } else {
                                        RateLimitResult.allowed(Long.MAX_VALUE, -1)
                                    }
                                }

                            val blacklisted = isBlacklisted.await()
                            val rateLimit = rateLimitResult.await()

                            when {
                                blacklisted -> {
                                    blockRequest(exchange, "BLACKLISTED", clientIp, userId, apiKey)
                                    null
                                }
                                !rateLimit.allowed -> {
                                    blockRequest(exchange, "RATE_LIMITED", clientIp, userId, apiKey)
                                    null
                                }
                                else -> {
                                    addRateLimitHeaders(exchange.response, rateLimit)
                                    chain.filter(exchange).awaitSingleOrNull()
                                }
                            }
                        }
                    }
                    // FIXME exception handling
                } catch (e: TimeoutCancellationException) {
                    logger.error { "Security check timeout - allowing request: IP: $clientIp" }
                    chain.filter(exchange).awaitSingleOrNull()
                } catch (e: Exception) {
                    logger.error(e) { "Security check failed - allowing request: IP: $clientIp" }
                    chain.filter(exchange).awaitSingleOrNull()
                }
            }
        }

    private suspend fun blockRequest(
        exchange: ServerWebExchange,
        reason: String,
        clientIp: String,
        userId: String?,
        apiKey: String?,
    ) {
        val response = exchange.response
        logBlockedRequest(reason, clientIp, userId, apiKey)

        val (status, errorCode, message) =
            when (reason) {
                "BLACKLISTED" ->
                    Triple(
                        HttpStatus.FORBIDDEN,
                        "BLACKLISTED",
                        "Your request has been blocked",
                    )
                "RATE_LIMITED" ->
                    Triple(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "RATE_LIMITED",
                        "Rate limit exceeded",
                    )
                else ->
                    Triple(
                        HttpStatus.FORBIDDEN,
                        "ACCESS_DENIED",
                        "Access denied",
                    )
            }

        response.statusCode = status
        response.headers.contentType = MediaType.APPLICATION_JSON
        // FIXME error response type & jackson serialize.
        val errorMessage =
            """
            {
                "error": "${status.reasonPhrase}",
                "message": "$message",
                "code": "$errorCode",
                "timestamp": "${Instant.now()}"
            }
            """.trimIndent()

        val buffer = response.bufferFactory().wrap(errorMessage.toByteArray(StandardCharsets.UTF_8))
        response.writeWith(Mono.just(buffer)).awaitSingleOrNull()
    }

    private fun addRateLimitHeaders(response: ServerHttpResponse, result: RateLimitResult) {
        response.headers.set("X-RateLimit-Remaining", result.remaining.toString())
        response.headers.set("X-RateLimit-Reset", result.resetTimeSeconds.toString())

        if (!result.allowed) {
            response.headers.set("Retry-After", result.resetTimeSeconds.toString())
        }
    }

    private fun logBlockedRequest(
        reason: String,
        clientIp: String,
        userId: String?,
        apiKey: String?,
    ) = logger.error {
        "BLOCKED REQUEST - IP: $clientIp, UserID: $userId, APIKey: ${apiKey?.take(
            8,
        )}***, Reason: $reason"
    }

    private fun getClientIp(request: ServerHttpRequest): String =
        request.headers
            .getFirst("X-Forwarded-For")
            ?.split(",")
            ?.first()
            ?.trim()
            ?: request.headers.getFirst("X-Real-IP")
            ?: request.headers.getFirst("X-Client-IP")
            ?: request.remoteAddress?.address?.hostAddress
            ?: "unknown"

    data class Config(
        var timeoutMs: Long = 1000L,
        var enableBlacklist: Boolean = true,
        var enableRateLimit: Boolean = true,
        var rateLimitConfig: RateLimitConfig = RateLimitConfig(),
    )
}
