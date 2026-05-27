package dev.notypie.gateway.filters.global

import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Attaches security headers to every response.
 *
 * Implemented as a WebFilter so the headers apply to all outbound responses — not just
 * gateway-routed traffic but also actuator responses, error responses, etc.
 */
@Component
class SecurityHeadersFilter :
    WebFilter,
    Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        exchange.response.beforeCommit {
            val headers = exchange.response.headers
            DEFAULT_HEADERS.forEach { (name, value) -> headers.setIfAbsent(name, value) }
            Mono.empty()
        }
        return chain.filter(exchange)
    }

    private fun org.springframework.http.HttpHeaders.setIfAbsent(name: String, value: String) {
        if (this.getFirst(name) == null) this[name] = value
    }

    companion object {
        private val DEFAULT_HEADERS =
            mapOf(
                "Strict-Transport-Security" to "max-age=31536000; includeSubDomains",
                "X-Content-Type-Options" to "nosniff",
                "X-Frame-Options" to "DENY",
                "Referrer-Policy" to "strict-origin-when-cross-origin",
                "Permissions-Policy" to "geolocation=(), microphone=(), camera=()",
                "X-XSS-Protection" to "0",
            )
    }
}
