package dev.notypie.gateway.controllers

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class FallbackController {
    @RequestMapping("/fallback/be")
    fun fallback(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            mapOf(
                "error" to "Service Unavailable",
                "message" to "The service is temporarily unavailable. Please try again later.",
                "code" to "CIRCUIT_OPEN",
                "timestamp" to Instant.now().toString(),
            ),
        )
}
