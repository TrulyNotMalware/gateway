package dev.notypie.gateway.filters.global

import dev.notypie.gateway.configurations.AppConfig
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Gateway 가 신뢰하는 헤더(예: X-User-ID, X-API-Key) 가 외부 클라이언트로부터 들어오는 경우를 1선에서 제거한다.
 *
 * - 다운스트림이 이 헤더들을 "Gateway 가 검증해서 박은 값" 으로 신뢰하기 때문에, 외부 입력을 그대로 전달하면 스푸핑 위험.
 * - 인증 자체(JWT 검증 → X-User-ID 주입) 는 별도 필터에서 처리하고, 본 필터는 *입력 sanitize* 만 담당한다.
 * - SecurityFilter 보다 *먼저* 동작하도록 우선순위는 매우 높게 둔다 (-200).
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
