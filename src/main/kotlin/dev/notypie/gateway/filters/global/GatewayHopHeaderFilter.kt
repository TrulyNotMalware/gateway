package dev.notypie.gateway.filters.global

import dev.notypie.gateway.configurations.AppConfig
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Injects the gateway hop-proof header (`X-Gateway-Auth`) into downstream requests.
 *
 * Intent: backends (blog_be, ...) verify this header as a defence layer beyond NetworkPolicy.
 *         Even if NetworkPolicy is misconfigured or bypassed, a caller that doesn't know the secret is rejected.
 *
 * Any same-named header from external clients is removed in advance by [TrustHeaderStripFilter],
 * so this filter's injection is safe.
 *
 * Disabled mode: when `app.config.security.gateway-shared-secret` is empty, no header is stamped
 *                (legacy compatibility). Must be set in prod.
 *
 * Order: between TrustHeaderStripFilter(-200) and SecurityFilter(-100); same layer as JwtUserIdInjectionFilter(-150).
 */
@Component
class GatewayHopHeaderFilter(
    private val appConfig: AppConfig,
) : GlobalFilter,
    Ordered {
    override fun getOrder(): Int = -140

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val secret = appConfig.security.gatewaySharedSecret
        if (secret.isEmpty()) return chain.filter(exchange)

        val mutated =
            exchange
                .mutate()
                .request(
                    exchange.request
                        .mutate()
                        .header(HEADER, secret)
                        .build(),
                ).build()
        return chain.filter(mutated)
    }

    companion object {
        const val HEADER = "X-Gateway-Auth"
    }
}
