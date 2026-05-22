package dev.notypie.gateway.controllers

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

@RestController
class FallbackController {
    @RequestMapping("/fallback/be")
    fun blogFallback(exchange: ServerWebExchange): ResponseEntity<Map<String, String?>> =
        unavailable(target = "blog-be", exchange = exchange)

    @RequestMapping("/fallback/generic")
    fun genericFallback(exchange: ServerWebExchange): ResponseEntity<Map<String, String?>> =
        unavailable(target = "downstream", exchange = exchange)

    private fun unavailable(target: String, exchange: ServerWebExchange): ResponseEntity<Map<String, String?>> {
        // RequestIdFilter (WebFilter) 가 검증·박은 X-Request-ID 값을 그대로 포함.
        val requestId = exchange.request.headers.getFirst("X-Request-ID")
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            mapOf(
                "error" to "Service Unavailable",
                "message" to "$target is temporarily unavailable. Please try again later.",
                "code" to "CIRCUIT_OPEN",
                "target" to target,
                "requestId" to requestId,
                "timestamp" to Instant.now().toString(),
            ),
        )
    }
}
