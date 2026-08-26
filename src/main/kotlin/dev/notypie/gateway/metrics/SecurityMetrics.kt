package dev.notypie.gateway.metrics

import dev.notypie.gateway.configurations.RedisFailureMode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

/**
 * Micrometer instrumentation for the security path, scraped at `:8081/actuator/prometheus`.
 *
 * These exist because the block/allow decision and the Redis-failure fallback were previously
 * observable only in logs, which cannot be alerted on. In particular a `HYBRID_IN_MEMORY`
 * deployment absorbs a Redis outage silently — the request still succeeds — so without
 * [recordCheckFailure] and the module-level failure counter there is no signal at all that the
 * shared counters have stopped working.
 */
@Component
class SecurityMetrics(
    private val registry: MeterRegistry,
) {
    /** A request was refused. `reason` matches the audit log's `reason=` field. */
    fun recordBlock(reason: String) {
        registry.counter(BLOCKS, "reason", reason).increment()
    }

    /**
     * The parallel security check itself timed out or threw, and the configured stance decided
     * the outcome. `kind` is `timeout` or `exception`; `outcome` is `allowed` or `denied`.
     */
    fun recordCheckFailure(kind: String, mode: RedisFailureMode, allowed: Boolean) {
        registry
            .counter(
                CHECK_FAILURES,
                "kind",
                kind,
                "failureMode",
                mode.name,
                "outcome",
                if (allowed) "allowed" else "denied",
            ).increment()
    }

    /**
     * Wall time of the blacklist + rate-limit check. Feeds the judgement on whether
     * `security.timeout-ms` is set anywhere near the real latency distribution.
     */
    fun startCheckTimer(): Timer.Sample = Timer.start(registry)

    fun stopCheckTimer(sample: Timer.Sample, outcome: String) {
        sample.stop(
            Timer
                .builder(CHECK_DURATION)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry),
        )
    }

    companion object {
        const val BLOCKS = "gateway.security.blocks"
        const val CHECK_FAILURES = "gateway.security.check.failures"
        const val CHECK_DURATION = "gateway.security.check.duration"
    }
}
