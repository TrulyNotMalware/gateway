package dev.notypie.gateway.filters.global

import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * Ensures every request carries an X-Request-ID header.
 *
 * - If the client supplies the header, reuse it *only if it passes validation* (to preserve distributed-trace linking).
 * - If validation fails or the header is missing, generate a fresh UUID.
 * - Propagate downstream and echo on the response.
 *
 * Validation: length 1..128, only [a-zA-Z0-9-_]. Prevents log injection and header smuggling.
 *
 * Implemented as a WebFilter so it covers actuator/error responses, not just gateway-routed traffic.
 */
@Component
class RequestIdFilter :
    WebFilter,
    Ordered {
    // Must run before all other filters so X-Request-ID is consistent across logs and downstream calls.
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 10

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val supplied = exchange.request.headers.getFirst(HEADER)
        val requestId =
            if (supplied != null && isValid(supplied)) {
                supplied
            } else {
                UUID.randomUUID().toString()
            }
        exchange.response.beforeCommit {
            exchange.response.headers[HEADER] = requestId
            Mono.empty()
        }
        val mutated =
            exchange
                .mutate()
                .request(
                    exchange.request
                        .mutate()
                        .header(HEADER, requestId)
                        .build(),
                ).build()
        return chain.filter(mutated)
    }

    private fun isValid(id: String): Boolean = id.length in 1..MAX_LENGTH && VALID_PATTERN.matches(id)

    companion object {
        const val HEADER = "X-Request-ID"
        const val MAX_LENGTH = 128
        private val VALID_PATTERN = Regex("^[A-Za-z0-9_-]+$")
    }
}
