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
 * Gateway 가 JWT 검증한 사용자 식별자(sub) 를 `X-User-ID` 헤더로 다운스트림에 박는다.
 *
 * 신뢰 모델 / 필터 순서:
 * 1. SecurityConfig 의 JWT WebFilter 가 인증 검증 → ReactiveSecurityContextHolder 에 JwtAuthenticationToken 박음.
 * 2. [TrustHeaderStripFilter] (GlobalFilter, order=-200) 가 외부 입력 X-User-ID 를 *제거*.
 * 3. 본 필터 (GlobalFilter, order=-150) 가 인증된 sub 를 *검증된 X-User-ID* 로 다시 박음.
 * 4. [SecurityFilter] (GlobalFilter, order=-100) 가 blacklist/rate-limit 체크 시 그 userId 활용.
 * 5. 다운스트림 (blog-be, file-be 등) 이 X-User-ID 를 신뢰.
 *
 * 핵심: WebFilter 가 아닌 *GlobalFilter* 로 둬야 한다. WebFilter 는 Gateway 체인 *앞* 에서 동작하므로,
 *      WebFilter 에서 박은 헤더는 TrustHeaderStripFilter(GlobalFilter) 가 다시 strip 해 다운스트림에 도달 못 한다.
 *      (1차 적용 시 WebFilter 였던 게 codex 리뷰에서 잡힘.)
 */
@Component
class JwtUserIdInjectionFilter :
    GlobalFilter,
    Ordered {
    private val logger = KotlinLogging.logger("AUDIT")

    // TrustHeaderStripFilter(-200) 와 SecurityFilter(-100) 의 사이.
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
                val sub = auth.name ?: return@flatMap chain.filter(exchange)
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
