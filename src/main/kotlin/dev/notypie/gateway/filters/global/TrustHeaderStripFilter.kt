package dev.notypie.gateway.filters.global

import dev.notypie.gateway.configurations.AppConfig
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Strips gateway-trusted headers (e.g. X-User-ID, X-API-Key) that arrive from external clients.
 *
 * - Downstream services trust these headers as "values stamped by the gateway after verification";
 *   forwarding external input directly would enable spoofing.
 * - Authentication itself (JWT verification → X-User-ID injection) is handled by a separate filter;
 *   this filter only handles *input sanitization*.
 * - Runs *before* SecurityFilter via a very high priority (-200).
 */
@Component
class TrustHeaderStripFilter(
    private val appConfig: AppConfig,
) : GlobalFilter,
    Ordered {
    override fun getOrder(): Int = -200

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val toStrip = appConfig.security.strippedTrustHeaders
        if (toStrip.isEmpty()) return chain.filter(exchange)

        val original = exchange.request.headers
        val needsStrip = toStrip.any { original.getFirst(it) != null }
        if (!needsStrip) return chain.filter(exchange)

        val mutated =
            exchange
                .mutate()
                .request(
                    exchange.request
                        .mutate()
                        .headers { headers ->
                            toStrip.forEach { headers.remove(it) }
                        }.build(),
                ).build()
        return chain.filter(mutated)
    }
}
