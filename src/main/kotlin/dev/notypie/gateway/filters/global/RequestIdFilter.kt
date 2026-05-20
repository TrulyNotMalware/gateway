package dev.notypie.gateway.filters.global

import kotlinx.coroutines.reactor.mono
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.UUID

@Component
class RequestIdFilter :
    GlobalFilter,
    Ordered {
    override fun getOrder(): Int = -90

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val requestId = exchange.request.headers.getFirst("X-Request-ID") ?: UUID.randomUUID().toString()
        exchange.response.beforeCommit {
            mono { exchange.response.headers["X-Request-ID"] = requestId }.then()
        }
        val mutated =
            exchange
                .mutate()
                .request(
                    exchange.request
                        .mutate()
                        .header("X-Request-ID", requestId)
                        .build(),
                ).build()
        return chain.filter(mutated)
    }
}
