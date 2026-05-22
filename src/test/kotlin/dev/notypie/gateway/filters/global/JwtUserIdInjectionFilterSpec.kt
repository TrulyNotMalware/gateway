package dev.notypie.gateway.filters.global

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * 본 필터의 positive path(인증된 SecurityContext → X-User-ID 헤더 주입)는
 * Spring WebFlux 의 reactor Context 전파에 의존하므로 unit 테스트에서 안정적으로 재현하기 어렵다.
 * 그 부분은 prod 부팅 smoke test 에서 실제 JWT 토큰으로 검증한다.
 *
 * 본 spec 은 인증 정보가 없는 경우 (public path) 의 negative 동작만 보장한다 —
 * 헤더가 추가되지 않고 체인이 그대로 통과해야 함.
 */
class JwtUserIdInjectionFilterSpec :
    BehaviorSpec({

        given("JwtUserIdInjectionFilter") {
            val filter = JwtUserIdInjectionFilter()

            `when`("SecurityContext 에 인증 정보가 없으면 (public path)") {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/posts"))
                var downstream: ServerWebExchange? = null
                val chain =
                    GatewayFilterChain { ex ->
                        downstream = ex
                        Mono.empty()
                    }

                filter.filter(exchange, chain).awaitSingleOrNull()

                then("X-User-ID 가 박히지 않고 체인이 그대로 통과한다") {
                    downstream shouldBe exchange
                    downstream!!.request.headers.getFirst("X-User-ID") shouldBe null
                }
            }
        }
    })
