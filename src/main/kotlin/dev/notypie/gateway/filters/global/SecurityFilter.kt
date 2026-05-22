package dev.notypie.gateway.filters.global

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

    // 보안 결정(차단/허용) 은 별도 AUDIT logger 로 분리. logback 설정에서 별도 파일/인덱스로 라우팅 가능.
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
     * Client IP 산정 — Spring Cloud Gateway 의 trusted-proxies + server.forward-headers-strategy=framework
     * 조합이 ServerHttpRequest.remoteAddress 를 *trusted proxy 체인을 제거한 첫 untrusted hop* 으로 채워준다.
     *
     * 따라서 X-Forwarded-For/X-Real-IP 를 직접 파싱하지 않고 remoteAddress 만 신뢰한다.
     * 직접 파싱 방식은 trusted-proxies 검증 없이 raw header 를 받아 spoofing 위험이 있었다.
     */
    private fun getClientIp(request: ServerHttpRequest): String =
        request.remoteAddress?.address?.hostAddress ?: "unknown"
}
