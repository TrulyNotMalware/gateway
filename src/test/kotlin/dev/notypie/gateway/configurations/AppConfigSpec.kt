package dev.notypie.gateway.configurations

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.reflect.full.primaryConstructor

class AppConfigSpec :
    BehaviorSpec({

        given("an AppConfig holding secrets") {
            // Obviously fake values: real-looking ones trip secret scanners on the pull request.
            val sharedSecret = "fake-shared-secret-fake-shared-secret"
            val apiKey = "fake-api-key-value"
            val redisPassword = "fake-redis-password"
            val config =
                AppConfig(
                    security =
                        AppConfig.Security(
                            gatewaySharedSecret = sharedSecret,
                            allowedApiKeys = listOf(apiKey),
                        ),
                    redis = AppConfig.Redis(password = redisPassword, host = "redis.internal"),
                )

            `when`("it is rendered with toString (logs, exception messages)") {
                val rendered = config.toString()
                then("no secret value appears, the rest of the settings still do") {
                    rendered shouldNotContain sharedSecret
                    rendered shouldNotContain apiKey
                    rendered shouldNotContain redisPassword
                    rendered shouldContain "gatewaySharedSecret=****"
                    rendered shouldContain "host=redis.internal"
                    rendered shouldContain "redisFailureMode=HYBRID_IN_MEMORY"
                }
            }
        }

        given("the hand-written toString overrides") {
            `when`("compared with the constructor properties") {
                val missing =
                    listOf(AppConfig.Security(), AppConfig.Redis()).flatMap { value ->
                        val rendered = value.toString()
                        value::class
                            .primaryConstructor!!
                            .parameters
                            .mapNotNull { it.name }
                            .filterNot { name -> "($name=" in rendered || ", $name=" in rendered }
                    }
                then("every property is listed, so a new field cannot silently drop out") {
                    missing shouldBe emptyList()
                }
            }
        }
    })
