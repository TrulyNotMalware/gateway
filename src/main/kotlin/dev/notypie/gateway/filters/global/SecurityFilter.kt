package dev.notypie.gateway.filters.global

import dev.notypie.gateway.configurations.AppConfig
import dev.notypie.gateway.configurations.RedisFailureMode
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
import tools.jackson.databind.json.JsonMapper
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

    // Block/allow decisions go to a dedicated AUDIT logger so logback can route them to a separate file/index.
    private val auditLogger = KotlinLogging.logger("AUDIT")

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
                handleSecurityCheckFailure(exchange, chain, clientIp, userId, apiKey, "timeout", e)
            } catch (e: Exception) {
                handleSecurityCheckFailure(exchange, chain, clientIp, userId, apiKey, "exception", e)
            }
        }.then()

    /**
     * Decide what to do when the parallel security check itself fails or times out.
     *
     * Per-call `ReactiveRedissonClientModule.increment` already dispatches by
     * [RedisFailureMode] when its Redis await throws. But a Redis stall that runs past
     * `security.timeoutMs` is cancelled here *before* the inner await throws, so the
     * fallback was never consulted. We honour the operator's configured stance instead
     * of unconditionally allowing — otherwise FAIL_CLOSED silently degrades to FAIL_OPEN
     * the moment Redis is slow rather than down.
     *
     * HYBRID is treated as allow on timeout: its goal is "throttle locally when Redis is
     * unreachable", not "deny on slow Redis". A timeout means the per-call dispatch never
     * reached the in-memory path, so we cannot honour the local counter retroactively.
     */
    private suspend fun handleSecurityCheckFailure(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
        clientIp: String,
        userId: String?,
        apiKey: String?,
        kind: String,
        cause: Throwable,
    ) {
        when (appConfig.security.redisFailureMode) {
            RedisFailureMode.FAIL_OPEN, RedisFailureMode.HYBRID_IN_MEMORY -> {
                logger.error(cause) {
                    "Security check $kind — allowing request (mode=${appConfig.security.redisFailureMode}): IP: $clientIp"
                }
                chain.filter(exchange).awaitSingleOrNull()
            }
            RedisFailureMode.FAIL_CLOSED -> {
                logger.error(cause) { "Security check $kind — denying request (mode=FAIL_CLOSED): IP: $clientIp" }
                blockRequest(exchange, "RATE_LIMITED", clientIp, userId, apiKey)
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
        val requestId = exchange.request.headers.getFirst("X-Request-ID") ?: "-"
        val path =
            exchange.request.path
                .pathWithinApplication()
                .value()
        auditLogger.warn {
            "decision=BLOCK reason=$reason ip=$clientIp userId=$userId " +
                "apiKey=${apiKey?.take(8)?.let { "$it..." }} path=$path requestId=$requestId"
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

    /**
     * Resolve the client IP — Spring Cloud Gateway's `trusted-proxies` plus
     * `server.forward-headers-strategy=framework` populates `ServerHttpRequest.remoteAddress`
     * with the first untrusted hop after stripping the trusted-proxy chain.
     *
     * We therefore trust `remoteAddress` rather than parsing X-Forwarded-For / X-Real-IP directly.
     * Direct parsing would accept raw client headers without trusted-proxy validation and enable spoofing.
     */
    private fun getClientIp(request: ServerHttpRequest): String =
        request.remoteAddress?.address?.hostAddress ?: "unknown"
}
