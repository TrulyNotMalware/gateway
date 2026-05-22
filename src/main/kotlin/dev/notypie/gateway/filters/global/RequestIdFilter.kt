package dev.notypie.gateway.filters.global

import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * X-Request-ID 헤더를 모든 요청에 보장한다.
 *
 * - 외부에서 헤더를 박아오면 *검증 통과한 경우에만* 그대로 사용 (분산 추적과의 연결).
 * - 검증 실패하거나 헤더가 없으면 UUID 를 새로 생성.
 * - 다운스트림으로 propagate + 응답에도 박는다.
 *
 * 검증 규칙: 길이 1..128, [a-zA-Z0-9-_] 만 허용. 로그 인젝션 / 헤더 스머글링 방지.
 *
 * WebFilter 로 구현하여 gateway 라우팅 뿐 아니라 actuator/error 응답에도 적용된다.
 */
@Component
class RequestIdFilter :
    WebFilter,
    Ordered {
    // 모든 다른 필터 시작 전에 동작해야 X-Request-ID 가 로그·다운스트림에서 일관되게 사용 가능
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
