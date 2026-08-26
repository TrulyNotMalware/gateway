package dev.notypie.gateway.endpoints

import dev.notypie.gateway.configurations.AppConfig
import dev.notypie.gateway.service.BlacklistService
import dev.notypie.gateway.service.BlacklistType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.mono
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector
import org.springframework.boot.actuate.endpoint.annotation.Selector.Match
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Read/write access to the blacklist, served on the **management port** (8081).
 *
 * Why an actuator endpoint rather than a `@RestController`: a controller would be published on
 * the public port (8080). Actuator endpoints live on `management.server.port`, which
 * `k8s/networkpolicy.yaml` only admits traffic to from the `monitoring` namespace.
 *
 * Before this existed, `BlacklistService`'s write methods had no caller anywhere in the
 * application — the blacklist could only be populated by writing `blacklist:*` keys into Redis
 * by hand, while `enable-blacklist: true` suggested a working feature.
 *
 * ### Security
 * The management port carries **no authentication**. Its only protection is the NetworkPolicy,
 * so anything running in `monitoring` can reach a write operation here. The endpoint is
 * therefore **disabled by default** and must be turned on deliberately with
 * `app.config.blacklist.admin-enabled=true`, on top of adding `blacklist` to
 * `management.endpoints.web.exposure.include`. Turn it on only where the NetworkPolicy is
 * actually enforced (the CNI must support it — plain flannel does not).
 *
 * ### Operations
 * ```
 * GET    /actuator/blacklist                 # counts + entries for every type
 * GET    /actuator/blacklist/{type}          # entries of one type (IP | USER | API_KEY)
 * GET    /actuator/blacklist/{type}/{value}  # is this value blacklisted?
 * POST   /actuator/blacklist                 # {"type","value","reason","ttlSeconds"}
 * DELETE /actuator/blacklist/{type}/{value}
 * ```
 *
 * `{value}` uses `Match.ALL_REMAINING` because a value may legitimately contain `/` — a base64
 * API key, for instance. With the default single-segment selector such an entry could be created
 * by POST but never read back or deleted.
 */
@Component
@Endpoint(id = "blacklist")
@ConditionalOnProperty(prefix = "app.config.blacklist", name = ["admin-enabled"], havingValue = "true")
class BlacklistEndpoint(
    private val blacklistService: BlacklistService,
    private val appConfig: AppConfig,
) {
    private val auditLogger = KotlinLogging.logger("AUDIT")

    @ReadOperation
    fun listAll(): Mono<Map<String, Any>> =
        mono {
            BlacklistType.entries.associate { type -> type.name to listingFor(type) }
        }

    @ReadOperation
    fun listType(
        @Selector type: String,
    ): Mono<Map<String, Any>> =
        mono {
            val parsed = parseType(type) ?: return@mono errorFor(type)
            listingFor(parsed) + ("type" to parsed.name)
        }

    @ReadOperation
    fun check(
        @Selector type: String,
        @Selector(match = Match.ALL_REMAINING) value: Array<String>,
    ): Mono<Map<String, Any>> =
        mono {
            val parsed = parseType(type) ?: return@mono errorFor(type)
            val target = value.joinToString("/")
            mapOf(
                "type" to parsed.name,
                "value" to target,
                "blacklisted" to blacklistService.isBlacklisted(parsed, target),
            )
        }

    /**
     * `ttlSeconds` null means no expiry — an entry that has to be removed by hand. Prefer a TTL
     * for anything automated so a false positive ages out on its own.
     */
    @WriteOperation
    fun add(
        type: String,
        value: String,
        reason: String?,
        ttlSeconds: Long?,
    ): Mono<Map<String, Any>> =
        mono {
            val parsed = parseType(type) ?: return@mono errorFor(type)
            if (value.isBlank()) {
                return@mono mapOf("error" to "value must not be blank")
            }
            if (value.length > MAX_VALUE_LENGTH) {
                return@mono mapOf("error" to "value exceeds $MAX_VALUE_LENGTH characters")
            }
            if ((reason?.length ?: 0) > MAX_REASON_LENGTH) {
                return@mono mapOf("error" to "reason exceeds $MAX_REASON_LENGTH characters")
            }
            val stored = reason?.takeIf { it.isNotBlank() } ?: "manual"
            val ok = blacklistService.add(parsed, value, stored, ttlSeconds)
            auditLogger.warn {
                "decision=BLACKLIST_ADD type=${parsed.name} value=${forLog(value)} " +
                    "reason=${forLog(stored)} ttlSeconds=$ttlSeconds ok=$ok"
            }
            mapOf("type" to parsed.name, "value" to value, "reason" to stored, "added" to ok)
        }

    @DeleteOperation
    fun remove(
        @Selector type: String,
        @Selector(match = Match.ALL_REMAINING) value: Array<String>,
    ): Mono<Map<String, Any>> =
        mono {
            val parsed = parseType(type) ?: return@mono errorFor(type)
            val target = value.joinToString("/")
            val removed = blacklistService.remove(parsed, target)
            auditLogger.warn {
                "decision=BLACKLIST_REMOVE type=${parsed.name} value=${forLog(target)} removed=$removed"
            }
            mapOf("type" to parsed.name, "value" to target, "removed" to removed)
        }

    /**
     * A Redis outage must not render as an empty blacklist. `scanKeys` propagates its failure
     * (unlike the other read paths, which degrade quietly), so the listing reports the error
     * explicitly — an operator reading `count: 0` would otherwise conclude nothing is blocked
     * when the truth is that we cannot tell.
     */
    private suspend fun listingFor(type: BlacklistType): Map<String, Any> {
        val limit = appConfig.blacklist.adminListLimit
        return try {
            val entries = blacklistService.list(type, limit)
            mapOf(
                "count" to entries.size,
                // A listing that hit the cap is a partial view; say so rather than letting the
                // caller read `count` as a total.
                "truncated" to (entries.size >= limit),
                "entries" to entries,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            auditLogger.warn {
                "decision=BLACKLIST_LIST_FAILED type=${type.name} error=${forLog(
                    e.message ?: "unknown",
                )}"
            }
            mapOf(
                "error" to "listing unavailable — the blacklist store could not be scanned",
                "detail" to forLog(e.message ?: e::class.java.simpleName),
            )
        }
    }

    /**
     * Caller-supplied text reaches the AUDIT log, which is line-oriented and parsed downstream.
     * A value containing a newline could forge whole audit records, and the management port has
     * no caller authentication — "the caller is trusted" is not an argument available here.
     */
    private fun forLog(raw: String): String {
        val cleaned = raw.map { if (it.isISOControl()) '_' else it }.joinToString("")
        return if (cleaned.length <= MAX_LOGGED_LENGTH) cleaned else cleaned.take(MAX_LOGGED_LENGTH) + "…(truncated)"
    }

    private fun parseType(raw: String): BlacklistType? =
        BlacklistType.entries.firstOrNull {
            it.name.equals(raw, ignoreCase = true)
        }

    private fun errorFor(raw: String): Map<String, Any> =
        mapOf(
            "error" to "unknown blacklist type '$raw'",
            "supported" to BlacklistType.entries.map { it.name },
        )

    companion object {
        const val MAX_VALUE_LENGTH = 256
        const val MAX_REASON_LENGTH = 512
        const val MAX_LOGGED_LENGTH = 256
    }
}
