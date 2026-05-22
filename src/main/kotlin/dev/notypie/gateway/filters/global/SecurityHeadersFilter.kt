package dev.notypie.gateway.filters.global

import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * 모든 응답에 보안 헤더를 부착한다.
 *
 * WebFilter 로 구현했기 때문에 게이트웨이 라우팅된 트래픽 뿐만 아니라 actuator, 에러 응답 등
 * 모든 outbound 응답에 헤더가 적용된다.
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
