package dev.notypie.gateway.filters.global

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Stamps the JWT-verified user identifier (`sub`) into the `X-User-ID` header forwarded downstream.
 *
 * Trust model / filter ordering:
 * 1. SecurityConfig's JWT WebFilter authenticates → ReactiveSecurityContextHolder holds a JwtAuthenticationToken.
 * 2. [TrustHeaderStripFilter] (GlobalFilter, order=-200) *removes* any externally supplied X-User-ID.
 * 3. This filter (GlobalFilter, order=-150) re-stamps the authenticated `sub` as a *verified* X-User-ID.
 * 4. [SecurityFilter] (GlobalFilter, order=-100) uses that userId for blacklist / rate-limit checks.
 * 5. Downstream services (blog-be, file-be, ...) trust X-User-ID.
 *
 * Must be a *GlobalFilter*, not a WebFilter: WebFilter runs *before* the gateway chain, so a header
 * stamped there would be stripped again by TrustHeaderStripFilter (GlobalFilter) before reaching downstream.
 */
@Component
class JwtUserIdInjectionFilter :
    GlobalFilter,
    Ordered {
    private val logger = KotlinLogging.logger("AUDIT")

    // Between TrustHeaderStripFilter(-200) and SecurityFilter(-100).
    override fun getOrder(): Int = -150

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> =
        ReactiveSecurityContextHolder
            .getContext()
            .flatMap { ctx ->
                val auth = ctx.authentication
                if (auth is JwtAuthenticationToken && auth.isAuthenticated) {
                    Mono.just(auth)
                } else {
                    Mono.empty()
                }
            }.flatMap { auth ->
                val sub = auth.name
                logger.debug { "JWT auth — propagating X-User-ID=$sub path=${exchange.request.path}" }
                val mutated =
                    exchange
                        .mutate()
                        .request(
                            exchange.request
                                .mutate()
                                .header(HEADER, sub)
                                .build(),
                        ).build()
                chain.filter(mutated)
            }.switchIfEmpty(chain.filter(exchange))

    companion object {
        const val HEADER = "X-User-ID"
    }
}
