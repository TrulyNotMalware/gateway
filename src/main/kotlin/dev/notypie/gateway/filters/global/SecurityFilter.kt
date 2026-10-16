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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withTimeout
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.PathContainer
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

@Component
class SecurityFilter(
    private val blacklistService: BlacklistService,
    private val rateLimitService: RateLimitService,
    private val appConfig: AppConfig,
    private val jsonMapper: JsonMapper,
    private val remoteAddressResolver: RemoteAddressResolver,
) : GlobalFilter,
    Ordered {
    private val logger = KotlinLogging.logger {}

    // Block/allow decisions go to a dedicated AUDIT logger so logback can route them to a separate file/index.
    private val auditLogger = KotlinLogging.logger("AUDIT")

    override fun getOrder(): Int = -100

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> =
        mono {
            val request = exchange.request
            val userId = request.headers.getFirst("X-User-ID")
            val clientIp = getClientIp(exchange)
            if (clientIp == null) {
                // No resolvable peer address (should never happen on the normal
                // istio → gateway path). Falling back to a literal "unknown" key
                // would make every such request share one global rate-limit
                // bucket — one client could exhaust it for all of them — so
                // refuse the request instead.
                blockRequest(exchange, "ACCESS_DENIED", "unknown", userId)
                return@mono
            }
            val endpoint = normalizedPath(request)
            val config = appConfig.security
            // TrustHeaderStripFilter(-200) removed X-API-Key from the request before this
            // filter(-100) runs, but captured the inbound value in an exchange attribute first.
            // Only a key the gateway recognises gets its own counter: an unrecognised value is
            // dropped here so a client cannot mint a fresh bucket per request with a random
            // header. With allowedApiKeys empty (the default) the dimension is inert.
            val apiKey =
                exchange
                    .getAttribute<String>(TrustHeaderStripFilter.CAPTURED_API_KEY_ATTR)
                    ?.takeIf { it in config.allowedApiKeys }

            // Only the checks run under the deadline and the catch below. chain.filter stays outside
            // both: inside, a backend slower than timeoutMs was cancelled mid-response, and the
            // failure handler then ran the rest of the chain a second time.
            val decision =
                try {
                    withTimeout(config.timeoutMs) {
                        evaluate(clientIp = clientIp, userId = userId, endpoint = endpoint, apiKey = apiKey)
                    }
                } catch (e: TimeoutCancellationException) {
                    handleSecurityCheckFailure(clientIp, "timeout", e)
                } catch (e: CancellationException) {
                    // Cancelled exchange (client gone): ensureActive rethrows, it is not a check
                    // failure. A CancellationException raised as an error by a library while the
                    // exchange is still live is an ordinary failure and follows redisFailureMode.
                    currentCoroutineContext().ensureActive()
                    handleSecurityCheckFailure(clientIp, "exception", e)
                } catch (e: Exception) {
                    handleSecurityCheckFailure(clientIp, "exception", e)
                }

            when (decision) {
                is Decision.Block -> {
                    blockRequest(exchange, decision.reason, clientIp, userId)
                }

                is Decision.Allow -> {
                    decision.rateLimit?.let { addRateLimitHeaders(exchange.response, it) }
                    chain.filter(exchange).awaitSingleOrNull()
                }
            }
        }.then()

    /** Outcome of the checks: computed under the deadline, acted on outside it. */
    private sealed interface Decision {
        data class Allow(
            val rateLimit: RateLimitResult?,
        ) : Decision

        data class Block(
            val reason: String,
        ) : Decision
    }

    private suspend fun evaluate(
        clientIp: String,
        userId: String?,
        endpoint: String,
        apiKey: String?,
    ): Decision =
        coroutineScope {
            val config = appConfig.security
            val isBlacklisted =
                async {
                    if (config.enableBlacklist) {
                        blacklistService.isAnyBlacklisted(ip = clientIp, userId = userId)
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
                            endpoint = endpoint,
                            limits =
                                RateLimitConfig(
                                    ipMaxRequests = config.ipMaxRequests,
                                    userMaxRequests = config.userMaxRequests,
                                    endpointMaxRequests = config.endpointMaxRequests,
                                    windowSeconds = config.windowSeconds,
                                ),
                        )
                    } else {
                        RateLimitResult.allowed(Long.MAX_VALUE, -1)
                    }
                }

            // Login endpoints are pre-auth (identity == IP) with small fixed account
            // sets, so the generic 100/min endpoint quota is too loose. Run a dedicated
            // tight IP-keyed check and combine: block if EITHER exceeds, report the
            // tighter remaining. Same increment path → redisFailureMode/HYBRID fallback
            // applies identically.
            val loginResult =
                async {
                    if (config.enableRateLimit && endpoint in config.loginPaths) {
                        rateLimitService.checkLoginRateLimit(
                            ip = clientIp,
                            maxRequests = config.loginMaxRequests,
                            windowSeconds = config.loginWindowSeconds,
                        )
                    } else {
                        RateLimitResult.allowed(Long.MAX_VALUE, -1)
                    }
                }

            val apiKeyResult =
                async {
                    if (config.enableRateLimit && apiKey != null) {
                        rateLimitService.checkApiKeyRateLimit(
                            apiKey = apiKey,
                            maxRequests = config.apiKeyMaxRequests,
                            windowSeconds = config.windowSeconds,
                        )
                    } else {
                        RateLimitResult.allowed(Long.MAX_VALUE, -1)
                    }
                }

            val blacklisted = isBlacklisted.await()
            // Every extra dimension can only lower the surviving `remaining`, so
            // adding one never loosens an existing limit.
            val rateLimit =
                RateLimitResult.tighter(
                    RateLimitResult.tighter(rateLimitResult.await(), loginResult.await()),
                    apiKeyResult.await(),
                )

            when {
                blacklisted -> Decision.Block("BLACKLISTED")
                !rateLimit.allowed -> Decision.Block("RATE_LIMITED")
                else -> Decision.Allow(rateLimit)
            }
        }

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
     *
     * Only the checks reach this handler; a failure of the downstream call propagates as is.
     */
    private fun handleSecurityCheckFailure(clientIp: String, kind: String, cause: Throwable): Decision =
        when (appConfig.security.redisFailureMode) {
            RedisFailureMode.FAIL_OPEN, RedisFailureMode.HYBRID_IN_MEMORY -> {
                logger.error(cause) {
                    "Security check $kind — allowing request (mode=${appConfig.security.redisFailureMode}): IP: $clientIp"
                }
                Decision.Allow(rateLimit = null)
            }

            RedisFailureMode.FAIL_CLOSED -> {
                logger.error(cause) { "Security check $kind — denying request (mode=FAIL_CLOSED): IP: $clientIp" }
                Decision.Block("RATE_LIMITED")
            }
        }

    private suspend fun blockRequest(
        exchange: ServerWebExchange,
        reason: String,
        clientIp: String,
        userId: String?,
    ) {
        val requestId = exchange.request.headers.getFirst("X-Request-ID") ?: "-"
        val path =
            exchange.request.path
                .pathWithinApplication()
                .value()
        auditLogger.warn {
            "decision=BLOCK reason=$reason ip=$clientIp userId=$userId path=$path requestId=$requestId"
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
     * The path the way routing, Spring Security and a decoding backend read it: each segment
     * percent-decoded, `;` path parameters dropped. Keyed on the raw `pathWithinApplication().value()`,
     * `/v1/auth/%6Cogin` (the same login route for Spring Security's permitAll and for the backend)
     * skipped the login limiter, and every encoding of a path got its own endpoint counter.
     */
    private fun normalizedPath(request: ServerHttpRequest): String =
        request.path.pathWithinApplication().elements().joinToString("") { element ->
            if (element is PathContainer.PathSegment) element.valueToMatch() else element.value()
        }

    /**
     * Resolve the client IP via `XForwardedRemoteAddressResolver(maxTrustedIndex = trustedProxyHops)`.
     * It trusts N hops from the right of X-Forwarded-For, so a client cannot spoof a leftmost-prepended
     * address; with no XFF (in-cluster) it falls back to the socket peer.
     *
     * Returns null when no peer address is resolvable; the caller refuses such requests
     * rather than rate-limiting them under a shared fallback key.
     */
    private fun getClientIp(exchange: ServerWebExchange): String? =
        remoteAddressResolver.resolve(exchange)?.address?.hostAddress
}
