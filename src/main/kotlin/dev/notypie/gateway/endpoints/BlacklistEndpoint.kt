package dev.notypie.gateway.endpoints

import dev.notypie.gateway.configurations.AppConfig
import dev.notypie.gateway.service.BlacklistService
import dev.notypie.gateway.service.BlacklistType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.mono
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector
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
            val limit = appConfig.blacklist.adminListLimit
            BlacklistType.entries.associate { type ->
                val entries = blacklistService.list(type, limit)
                type.name to
                    mapOf(
                        "count" to entries.size,
                        // A listing that hit the cap is a partial view; say so rather than
                        // letting the caller read `count` as a total.
                        "truncated" to (entries.size >= limit),
                        "entries" to entries,
                    )
            }
        }

    @ReadOperation
    fun listType(
        @Selector type: String,
    ): Mono<Map<String, Any>> =
        mono {
            val parsed = parseType(type) ?: return@mono errorFor(type)
            val limit = appConfig.blacklist.adminListLimit
            val entries = blacklistService.list(parsed, limit)
            mapOf(
                "type" to parsed.name,
                "count" to entries.size,
                "truncated" to (entries.size >= limit),
                "entries" to entries,
            )
        }

    @ReadOperation
    fun check(
        @Selector type: String,
        @Selector value: String,
    ): Mono<Map<String, Any>> =
        mono {
            val parsed = parseType(type) ?: return@mono errorFor(type)
            mapOf(
                "type" to parsed.name,
                "value" to value,
                "blacklisted" to blacklistService.isBlacklisted(parsed, value),
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
            val stored = reason?.takeIf { it.isNotBlank() } ?: "manual"
            val ok = blacklistService.add(parsed, value, stored, ttlSeconds)
            auditLogger.warn {
                "decision=BLACKLIST_ADD type=${parsed.name} value=$value reason=$stored ttlSeconds=$ttlSeconds ok=$ok"
            }
            mapOf("type" to parsed.name, "value" to value, "reason" to stored, "added" to ok)
        }

    @DeleteOperation
    fun remove(
        @Selector type: String,
        @Selector value: String,
    ): Mono<Map<String, Any>> =
        mono {
            val parsed = parseType(type) ?: return@mono errorFor(type)
            val removed = blacklistService.remove(parsed, value)
            auditLogger.warn { "decision=BLACKLIST_REMOVE type=${parsed.name} value=$value removed=$removed" }
            mapOf("type" to parsed.name, "value" to value, "removed" to removed)
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
}
