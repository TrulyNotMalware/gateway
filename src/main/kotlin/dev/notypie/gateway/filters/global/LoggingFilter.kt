package dev.notypie.gateway.filters.global

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class LoggingFilter :
    GlobalFilter,
    Ordered {
    private val logger = KotlinLogging.logger {}

    override fun getOrder(): Int = -80

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> =
        mono {
            val request = exchange.request
            val start = System.currentTimeMillis()
            val requestId = request.headers.getFirst("X-Request-ID") ?: "-"
            // trusted-proxies + forward-headers-strategy=framework already populates remoteAddress with the client IP.
            val ip = request.remoteAddress?.address?.hostAddress ?: "-"

            try {
                chain.filter(exchange).awaitSingleOrNull()
            } finally {
                val ms = System.currentTimeMillis() - start
                val status = exchange.response.statusCode?.value() ?: 0
                logger.info { "[$requestId] ${request.method} ${request.path} → $status (${ms}ms) from $ip" }
            }
        }.then()
}
