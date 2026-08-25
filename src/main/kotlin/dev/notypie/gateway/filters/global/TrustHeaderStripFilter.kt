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
 *
 * One value is captured before it is discarded: the inbound `X-API-Key` is stashed in the
 * [CAPTURED_API_KEY_ATTR] exchange attribute so [SecurityFilter] can use it as a rate-limit
 * dimension. The header itself is still removed, so a client-supplied key never reaches a
 * backend — the attribute is gateway-internal and cannot be set from outside.
 */
@Component
class TrustHeaderStripFilter(
    private val appConfig: AppConfig,
) : GlobalFilter,
    Ordered {
    override fun getOrder(): Int = -200

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        // Capture before any early return: SecurityFilter(-100) needs the inbound key even when
        // the strip list is empty, and it must read one consistent source in every configuration.
        exchange.request.headers.getFirst(API_KEY_HEADER)?.let { key ->
            exchange.attributes[CAPTURED_API_KEY_ATTR] = key
        }

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

    companion object {
        const val API_KEY_HEADER = "X-API-Key"

        /** Exchange attribute holding the inbound API key, read by [SecurityFilter]. */
        const val CAPTURED_API_KEY_ATTR = "dev.notypie.gateway.capturedApiKey"
    }
}
