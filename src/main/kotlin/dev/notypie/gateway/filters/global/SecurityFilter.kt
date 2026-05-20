package dev.notypie.gateway.filters.global

import com.fasterxml.jackson.databind.json.JsonMapper
import dev.notypie.gateway.configurations.AppConfig
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
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Instant

@Component
class SecurityFilter(
    private val blacklistService: BlacklistService,
    private val rateLimitService: RateLimitService,
    private val appConfig: AppConfig,
    private val jsonMapper: JsonMapper,
) : GlobalFilter,
    Ordered {
    private val logger = KotlinLogging.logger {}

    override fun getOrder(): Int = -100

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> =
        mono {
            val request = exchange.request
            val clientIp = getClientIp(request)
            val userId = request.headers.getFirst("X-User-ID")
            val apiKey = request.headers.getFirst("X-API-Key")
            val endpoint = request.path.pathWithinApplication().value()
            val config = appConfig.security

            try {
                withTimeout(config.timeoutMs) {
                    coroutineScope {
                        val isBlacklisted =
                            async {
                                if (config.enableBlacklist) {
                                    blacklistService.isAnyBlacklisted(ip = clientIp, userId = userId, apiKey = apiKey)
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
                                        limits =
                                            RateLimitConfig(
                                                ipMaxRequests = config.ipMaxRequests,
                                                userMaxRequests = config.userMaxRequests,
                                                apiKeyMaxRequests = config.apiKeyMaxRequests,
                                                endpointMaxRequests = config.endpointMaxRequests,
                                                windowSeconds = config.windowSeconds,
                                            ),
                                    )
                                } else {
                                    RateLimitResult.allowed(Long.MAX_VALUE, -1)
                                }
                            }

                        val blacklisted = isBlacklisted.await()
                        val rateLimit = rateLimitResult.await()

                        when {
                            blacklisted -> blockRequest(exchange, "BLACKLISTED", clientIp, userId, apiKey)
                            !rateLimit.allowed -> blockRequest(exchange, "RATE_LIMITED", clientIp, userId, apiKey)
                            else -> {
                                addRateLimitHeaders(exchange.response, rateLimit)
                                chain.filter(exchange).awaitSingleOrNull()
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logger.error { "Security check timeout - allowing request: IP: $clientIp" }
                chain.filter(exchange).awaitSingleOrNull()
            } catch (e: Exception) {
                logger.error(e) { "Security check failed - allowing request: IP: $clientIp" }
                chain.filter(exchange).awaitSingleOrNull()
            }
        }.then()

    private suspend fun blockRequest(
        exchange: ServerWebExchange,
        reason: String,
        clientIp: String,
        userId: String?,
        apiKey: String?,
    ) {
        logger.error {
            "BLOCKED REQUEST - IP: $clientIp, UserID: $userId, APIKey: ${apiKey?.take(
                8,
            )}***, Reason: $reason"
        }

        val (status, code, message) =
            when (reason) {
                "BLACKLISTED" -> Triple(HttpStatus.FORBIDDEN, "BLACKLISTED", "Your request has been blocked")
                "RATE_LIMITED" -> Triple(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Rate limit exceeded")
                else -> Triple(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied")
            }

        val response = exchange.response
        response.statusCode = status
        response.headers.contentType = MediaType.APPLICATION_JSON

        val body =
            jsonMapper.writeValueAsBytes(
                mapOf(
                    "error" to status.reasonPhrase,
                    "message" to message,
                    "code" to code,
                    "timestamp" to Instant.now().toString(),
                ),
            )
        response.writeWith(Mono.just(response.bufferFactory().wrap(body))).awaitSingleOrNull()
    }

    private fun addRateLimitHeaders(response: ServerHttpResponse, result: RateLimitResult) {
        response.headers["X-RateLimit-Remaining"] = result.remaining.toString()
        response.headers["X-RateLimit-Reset"] = result.resetTimeSeconds.toString()
        if (!result.allowed) {
            response.headers["Retry-After"] = result.resetTimeSeconds.toString()
        }
    }

    // Rightmost XFF entry is added by the trusted proxy (k8s ingress/LB).
    // The leftmost entry can be spoofed by the client.
    private fun getClientIp(request: ServerHttpRequest): String =
        request.headers
            .getFirst("X-Forwarded-For")
            ?.split(",")
            ?.last()
            ?.trim()
            ?: request.headers.getFirst("X-Real-IP")
            ?: request.headers.getFirst("X-Client-IP")
            ?: request.remoteAddress?.address?.hostAddress
            ?: "unknown"
}
