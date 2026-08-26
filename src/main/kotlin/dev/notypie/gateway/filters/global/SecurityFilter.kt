package dev.notypie.gateway.filters.global

import dev.notypie.gateway.configurations.AppConfig
import dev.notypie.gateway.configurations.RedisFailureMode
import dev.notypie.gateway.metrics.SecurityMetrics
import dev.notypie.gateway.service.BlacklistService
import dev.notypie.gateway.service.RateLimitConfig
import dev.notypie.gateway.service.RateLimitResult
import dev.notypie.gateway.service.RateLimitService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withTimeout
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * The outcome of the security check, decided before anything is written or forwarded.
 *
 * Keeping the decision separate from acting on it is what allows `security.timeoutMs` to bound
 * only the checks. An earlier version ran `chain.filter(...)` inside the timeout, so any
 * downstream slower than the budget was reported as a Redis timeout and then forwarded a
 * *second* time by the failure handler.
 */
private sealed interface Verdict {
    /** [rateLimit] is null when the checks never completed and the failure stance allowed through. */
    data class Allow(
        val rateLimit: RateLimitResult?,
    ) : Verdict

    data class Block(
        val reason: String,
    ) : Verdict
}

@Component
class SecurityFilter(
    private val blacklistService: BlacklistService,
    private val rateLimitService: RateLimitService,
    private val appConfig: AppConfig,
    private val jsonMapper: JsonMapper,
    private val remoteAddressResolver: RemoteAddressResolver,
    private val metrics: SecurityMetrics,
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
            val endpoint = request.path.pathWithinApplication().value()
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

            val sample = metrics.startCheckTimer()
            val verdict =
                try {
                    // The timeout covers the checks ONLY. chain.filter is invoked below, outside
                    // this block, so a slow downstream can never be mistaken for a slow Redis.
                    val decided =
                        withTimeout(config.timeoutMs) {
                            runChecks(clientIp = clientIp, userId = userId, endpoint = endpoint, apiKey = apiKey)
                        }
                    metrics.stopCheckTimer(sample, if (decided is Verdict.Allow) "allowed" else "blocked")
                    decided
                } catch (e: TimeoutCancellationException) {
                    metrics.stopCheckTimer(sample, "timeout")
                    verdictOnCheckFailure(clientIp, "timeout", e)
                } catch (e: CancellationException) {
                    // Not our timeout — the client disconnected or an outer scope was cancelled.
                    // CancellationException extends Exception, so without this it would fall into
                    // the branch below, be counted as a security-check failure, and (under
                    // FAIL_OPEN / HYBRID) reach chain.filter for a request nobody is waiting for.
                    metrics.stopCheckTimer(sample, "cancelled")
                    throw e
                } catch (e: Exception) {
                    metrics.stopCheckTimer(sample, "exception")
                    verdictOnCheckFailure(clientIp, "exception", e)
                }

            when (verdict) {
                is Verdict.Block -> blockRequest(exchange, verdict.reason, clientIp, userId)
                is Verdict.Allow -> {
                    verdict.rateLimit?.let { addRateLimitHeaders(exchange.response, it) }
                    chain.filter(exchange).awaitSingleOrNull()
                }
            }
        }.then()

    /**
     * Runs the blacklist and rate-limit dimensions concurrently and reduces them to a [Verdict].
     * Performs no I/O on the response and never touches the filter chain, so cancelling it (the
     * timeout) can only lose the decision — never leave a half-written response.
     */
    private suspend fun runChecks(
        clientIp: String,
        userId: String?,
        endpoint: String,
        apiKey: String?,
    ): Verdict =
        coroutineScope {
            val config = appConfig.security

            val isBlacklisted =
                async {
                    if (config.enableBlacklist) {
                        // apiKey is the allowlist-validated value, so a blacklisted key is
                        // enforced here rather than only being storable via the admin endpoint.
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
                tighter(
                    tighter(rateLimitResult.await(), loginResult.await()),
                    apiKeyResult.await(),
                )

            when {
                blacklisted -> Verdict.Block("BLACKLISTED")
                !rateLimit.allowed -> Verdict.Block("RATE_LIMITED")
                else -> Verdict.Allow(rateLimit)
            }
        }

    /**
     * Combine two RateLimit results: block if EITHER denies, otherwise carry the tighter
     * remaining. A denied result must win even when both have the same `remaining` (e.g. both 0),
     * so check `allowed` first rather than relying on `remaining` as a proxy for it.
     */
    private fun tighter(a: RateLimitResult, b: RateLimitResult): RateLimitResult =
        when {
            !a.allowed -> a
            !b.allowed -> b
            else -> if (a.remaining <= b.remaining) a else b
        }

    /**
     * Decide what to do when the security check itself fails or times out.
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
    private fun verdictOnCheckFailure(clientIp: String, kind: String, cause: Throwable): Verdict {
        val mode = appConfig.security.redisFailureMode
        return when (mode) {
            RedisFailureMode.FAIL_OPEN, RedisFailureMode.HYBRID_IN_MEMORY -> {
                metrics.recordCheckFailure(kind = kind, mode = mode, allowed = true)
                logger.error(cause) { "Security check $kind — allowing request (mode=$mode): IP: $clientIp" }
                Verdict.Allow(null)
            }

            RedisFailureMode.FAIL_CLOSED -> {
                metrics.recordCheckFailure(kind = kind, mode = mode, allowed = false)
                logger.error(cause) { "Security check $kind — denying request (mode=FAIL_CLOSED): IP: $clientIp" }
                Verdict.Block("RATE_LIMITED")
            }
        }
    }

    private suspend fun blockRequest(
        exchange: ServerWebExchange,
        reason: String,
        clientIp: String,
        userId: String?,
    ) {
        metrics.recordBlock(reason)
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
