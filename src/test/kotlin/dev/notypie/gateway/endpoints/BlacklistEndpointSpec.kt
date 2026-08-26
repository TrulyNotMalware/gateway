package dev.notypie.gateway.endpoints

import dev.notypie.gateway.configurations.AppConfig
import dev.notypie.gateway.modules.redis.InMemoryModule
import dev.notypie.gateway.modules.redis.RedisModule
import dev.notypie.gateway.service.BlacklistService
import dev.notypie.gateway.service.BlacklistType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingle

class BlacklistEndpointSpec :
    BehaviorSpec({

        fun endpointWith(service: BlacklistService, listLimit: Int = 100) =
            BlacklistEndpoint(
                service,
                AppConfig(blacklist = AppConfig.Blacklist(adminEnabled = true, adminListLimit = listLimit)),
            )

        given("the blacklist admin endpoint") {
            `when`("an IP is added through the endpoint") {
                val svc = BlacklistService(InMemoryModule())
                val endpoint = endpointWith(svc)
                val result =
                    endpoint.add(type = "ip", value = "1.2.3.4", reason = "scanner", ttlSeconds = null).awaitSingle()
                then("the write reaches the service — the whole point of the endpoint") {
                    result["added"] shouldBe true
                    svc.isBlacklisted(BlacklistType.IP, "1.2.3.4") shouldBe true
                }
                then("the type is normalised to its canonical name") {
                    result["type"] shouldBe "IP"
                }
            }

            `when`("no reason is supplied") {
                val svc = BlacklistService(InMemoryModule())
                val result =
                    endpointWith(
                        svc,
                    ).add(type = "USER", value = "mallory", reason = null, ttlSeconds = 60).awaitSingle()
                then("it defaults to 'manual' rather than storing an empty value") {
                    result["reason"] shouldBe "manual"
                    svc.list(BlacklistType.USER, 10).single().reason shouldBe "manual"
                }
            }

            `when`("a blank value is submitted") {
                val svc = BlacklistService(InMemoryModule())
                val result =
                    endpointWith(
                        svc,
                    ).add(type = "IP", value = "   ", reason = null, ttlSeconds = null).awaitSingle()
                then("it is rejected and nothing is written") {
                    result["error"] shouldBe "value must not be blank"
                    svc.list(BlacklistType.IP, 10) shouldBe emptyList()
                }
            }

            `when`("an unknown type is used") {
                val svc = BlacklistService(InMemoryModule())
                val result = endpointWith(svc).check(type = "banana", value = arrayOf("x")).awaitSingle()
                then("the supported types are reported back instead of a 500") {
                    result["error"] shouldBe "unknown blacklist type 'banana'"
                    result["supported"] shouldBe listOf("IP", "USER", "API_KEY")
                }
            }

            `when`("an entry is checked and then removed") {
                val svc = BlacklistService(InMemoryModule())
                val endpoint = endpointWith(svc)
                svc.addIpToBlacklist("5.5.5.5")
                val before = endpoint.check(type = "IP", value = arrayOf("5.5.5.5")).awaitSingle()
                val removal = endpoint.remove(type = "IP", value = arrayOf("5.5.5.5")).awaitSingle()
                val after = endpoint.check(type = "IP", value = arrayOf("5.5.5.5")).awaitSingle()
                then("the check reflects the state on both sides of the removal") {
                    before["blacklisted"] shouldBe true
                    removal["removed"] shouldBe true
                    after["blacklisted"] shouldBe false
                }
            }

            `when`("listing every type at once") {
                val svc = BlacklistService(InMemoryModule())
                svc.addIpToBlacklist("1.1.1.1")
                svc.addUserToBlacklist("alice", "abuse")
                val result = endpointWith(svc).listAll().awaitSingle()
                then("each type reports its own entries") {
                    @Suppress("UNCHECKED_CAST")
                    val ip = result["IP"] as Map<String, Any>

                    @Suppress("UNCHECKED_CAST")
                    val apiKey = result["API_KEY"] as Map<String, Any>
                    ip["count"] shouldBe 1
                    apiKey["count"] shouldBe 0
                }
            }

            `when`("a value containing a slash is stored and then addressed") {
                // A base64 API key can legitimately contain '/'. With a single-segment @Selector
                // such an entry could be created by POST but never read back or deleted.
                val svc = BlacklistService(InMemoryModule())
                val endpoint = endpointWith(svc)
                endpoint.add(type = "API_KEY", value = "ab/cd/ef", reason = "leaked", ttlSeconds = null).awaitSingle()
                val checked = endpoint.check(type = "API_KEY", value = arrayOf("ab", "cd", "ef")).awaitSingle()
                val removed = endpoint.remove(type = "API_KEY", value = arrayOf("ab", "cd", "ef")).awaitSingle()
                then("the remaining path segments are rejoined into the original value") {
                    checked["value"] shouldBe "ab/cd/ef"
                    checked["blacklisted"] shouldBe true
                    removed["removed"] shouldBe true
                }
            }

            `when`("a value carries newlines aimed at the audit log") {
                val svc = BlacklistService(InMemoryModule())
                val forged = "1.2.3.4\ndecision=BLOCK reason=FORGED ip=9.9.9.9"
                val result =
                    endpointWith(
                        svc,
                    ).add(type = "IP", value = forged, reason = null, ttlSeconds = null).awaitSingle()
                then("the write still succeeds — sanitisation is applied to the log, not the data") {
                    result["added"] shouldBe true
                    result["value"] shouldBe forged
                }
            }

            `when`("an oversized value or reason is submitted") {
                val svc = BlacklistService(InMemoryModule())
                val endpoint = endpointWith(svc)
                val longValue =
                    endpoint.add(type = "IP", value = "x".repeat(300), reason = null, ttlSeconds = null).awaitSingle()
                val longReason =
                    endpoint
                        .add(
                            type = "IP",
                            value = "1.2.3.4",
                            reason = "y".repeat(600),
                            ttlSeconds = null,
                        ).awaitSingle()
                then("both are refused before reaching Redis or the log") {
                    longValue["error"] shouldBe "value exceeds 256 characters"
                    longReason["error"] shouldBe "reason exceeds 512 characters"
                    svc.list(BlacklistType.IP, 10) shouldBe emptyList()
                }
            }

            `when`("the store cannot be scanned") {
                // A Redis outage must not render as an empty blacklist.
                val failing =
                    object : RedisModule {
                        override suspend fun set(key: String, value: String, ttlSeconds: Long?) = true

                        override suspend fun get(key: String): String? = null

                        override suspend fun exists(key: String) = false

                        override suspend fun delete(key: String) = true

                        override suspend fun increment(key: String, count: Long, ttlSeconds: Long) = 1L

                        override suspend fun remainingTtl(key: String): Long = -1

                        override suspend fun scanKeys(pattern: String, limit: Int): List<String> =
                            throw IllegalStateException("redis down")
                    }
                val result = endpointWith(BlacklistService(failing)).listType(type = "IP").awaitSingle()
                then("the response says so instead of reporting count 0") {
                    result["count"] shouldBe null
                    result["error"] shouldBe "listing unavailable — the blacklist store could not be scanned"
                }
            }

            `when`("more entries exist than the configured list limit") {
                val svc = BlacklistService(InMemoryModule())
                repeat(5) { svc.addIpToBlacklist("10.0.0.$it") }
                val result = endpointWith(svc, listLimit = 2).listType(type = "IP").awaitSingle()
                then("the response says it is truncated so count is not read as a total") {
                    result["count"] shouldBe 2
                    result["truncated"] shouldBe true
                }
            }

            `when`("the entries fit within the limit") {
                val svc = BlacklistService(InMemoryModule())
                svc.addIpToBlacklist("10.0.0.1")
                val result = endpointWith(svc, listLimit = 10).listType(type = "IP").awaitSingle()
                then("truncated is false") {
                    result["truncated"] shouldBe false
                }
            }
        }
    })
