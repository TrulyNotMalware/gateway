package dev.notypie.gateway.configurations

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.mock.env.MockEnvironment

class AppConfigValidatorSpec :
    BehaviorSpec({

        fun validator(config: AppConfig, profiles: List<String> = emptyList()): AppConfigValidator {
            val env = MockEnvironment()
            profiles.forEach(env::addActiveProfile)
            return AppConfigValidator(config, env)
        }

        given("redis storage requires a real redis backend") {
            `when`("blacklist.storage-mode=REDIS but redis.mode=NONE") {
                val cfg =
                    AppConfig(
                        blacklist = AppConfig.Blacklist(storageMode = StorageMode.REDIS),
                        redis = AppConfig.Redis(mode = RedisMode.NONE),
                    )
                then("startup fails fast — silent in-memory fallback would split state across pods") {
                    shouldThrow<IllegalStateException> { validator(cfg).validate() }
                }
            }

            `when`("storage-mode=REDIS with redis.mode=STANDALONE") {
                val cfg =
                    AppConfig(
                        blacklist = AppConfig.Blacklist(storageMode = StorageMode.REDIS),
                        redis = AppConfig.Redis(mode = RedisMode.STANDALONE),
                    )
                then("validation passes") {
                    validator(cfg).validate()
                }
            }
        }

        given("prod profile invariants") {
            `when`("gateway-shared-secret is blank in prod") {
                val cfg =
                    AppConfig(
                        security = AppConfig.Security(gatewaySharedSecret = ""),
                    )
                then("validation fails with a hop-proof message") {
                    val ex =
                        shouldThrow<IllegalStateException> {
                            validator(cfg, profiles = listOf("prod")).validate()
                        }
                    ex.message.orEmpty() shouldContain "gateway-shared-secret"
                }
            }

            `when`("gateway-shared-secret is shorter than the min length in prod") {
                val cfg =
                    AppConfig(
                        security = AppConfig.Security(gatewaySharedSecret = "too-short"),
                    )
                then("validation fails on length") {
                    val ex =
                        shouldThrow<IllegalStateException> {
                            validator(cfg, profiles = listOf("prod")).validate()
                        }
                    ex.message.orEmpty() shouldContain "32 chars"
                }
            }

            `when`("gateway-shared-secret is blank but profile is local") {
                val cfg =
                    AppConfig(
                        security = AppConfig.Security(gatewaySharedSecret = ""),
                    )
                then("validation passes — local dev does not require the hop secret") {
                    validator(cfg, profiles = listOf("local")).validate()
                }
            }

            `when`("gateway-shared-secret is >=32 chars in prod") {
                val cfg =
                    AppConfig(
                        security =
                            AppConfig.Security(
                                gatewaySharedSecret = "x".repeat(32),
                            ),
                        jwt =
                            AppConfig.Jwt(
                                issuers =
                                    listOf(
                                        AppConfig.Jwt.IssuerEntry(
                                            issuer = "blog-be",
                                            jwksUri = "http://blog/jwks",
                                            audience = "blog-be",
                                        ),
                                    ),
                            ),
                    )
                then("validation passes") {
                    validator(cfg, profiles = listOf("prod")).validate()
                }
            }

            `when`("gateway-shared-secret looks long enough but is only padding") {
                val cfg =
                    AppConfig(
                        security =
                            AppConfig.Security(
                                gatewaySharedSecret = " ".repeat(40),
                            ),
                    )
                then("validation fails — trim before measuring catches padded weak secrets") {
                    shouldThrow<IllegalStateException> {
                        validator(cfg, profiles = listOf("prod")).validate()
                    }
                }
            }
        }

        // The prod-issuers checks require the gateway-shared-secret to also be valid,
        // so reuse the canonical 32-char value across these cases.
        val okSecret = "x".repeat(32)

        given("prod requires at least one configured issuer") {
            `when`("issuers list is empty in prod") {
                val cfg =
                    AppConfig(
                        security = AppConfig.Security(gatewaySharedSecret = okSecret),
                        jwt = AppConfig.Jwt(issuers = emptyList()),
                    )
                then("validation fails — no verifier can be built") {
                    val ex =
                        shouldThrow<IllegalStateException> {
                            validator(cfg, profiles = listOf("prod")).validate()
                        }
                    ex.message.orEmpty() shouldContain "issuers"
                }
            }

            `when`("an entry has a blank issuer name in prod") {
                val cfg =
                    AppConfig(
                        security = AppConfig.Security(gatewaySharedSecret = okSecret),
                        jwt =
                            AppConfig.Jwt(
                                issuers =
                                    listOf(
                                        AppConfig.Jwt.IssuerEntry(
                                            issuer = "",
                                            jwksUri = "http://x/jwks",
                                            audience = "x",
                                        ),
                                    ),
                            ),
                    )
                then("validation fails on the offending index") {
                    shouldThrow<IllegalStateException> {
                        validator(cfg, profiles = listOf("prod")).validate()
                    }
                }
            }

            `when`("an entry has a blank audience in prod") {
                val cfg =
                    AppConfig(
                        security = AppConfig.Security(gatewaySharedSecret = okSecret),
                        jwt =
                            AppConfig.Jwt(
                                issuers =
                                    listOf(
                                        AppConfig.Jwt.IssuerEntry(
                                            issuer = "blog-be",
                                            jwksUri = "http://x/jwks",
                                            audience = "",
                                        ),
                                    ),
                            ),
                    )
                then("validation fails — each issuer must declare its aud target") {
                    val ex =
                        shouldThrow<IllegalStateException> {
                            validator(cfg, profiles = listOf("prod")).validate()
                        }
                    ex.message.orEmpty() shouldContain "audience"
                }
            }

            `when`("two entries share the same issuer name") {
                val cfg =
                    AppConfig(
                        security = AppConfig.Security(gatewaySharedSecret = okSecret),
                        jwt =
                            AppConfig.Jwt(
                                issuers =
                                    listOf(
                                        AppConfig.Jwt.IssuerEntry(
                                            issuer = "blog-be",
                                            jwksUri = "http://a/jwks",
                                            audience = "blog-be",
                                        ),
                                        AppConfig.Jwt.IssuerEntry(
                                            issuer = "blog-be",
                                            jwksUri = "http://b/jwks",
                                            audience = "blog-be",
                                        ),
                                    ),
                            ),
                    )
                then("validation fails — resolver routes by issuer name, can't disambiguate") {
                    val ex =
                        shouldThrow<IllegalStateException> {
                            validator(cfg, profiles = listOf("prod")).validate()
                        }
                    ex.message.orEmpty() shouldContain "duplicate"
                }
            }

            `when`("issuers are well-formed in prod") {
                val cfg =
                    AppConfig(
                        security = AppConfig.Security(gatewaySharedSecret = okSecret),
                        jwt =
                            AppConfig.Jwt(
                                issuers =
                                    listOf(
                                        AppConfig.Jwt.IssuerEntry(
                                            issuer = "blog-be",
                                            jwksUri = "http://blog/jwks",
                                            audience = "blog-be",
                                        ),
                                        AppConfig.Jwt.IssuerEntry(
                                            issuer = "file-be",
                                            jwksUri = "http://file/jwks",
                                            audience = "file-be",
                                        ),
                                    ),
                            ),
                    )
                then("validation passes") {
                    validator(cfg, profiles = listOf("prod")).validate()
                }
            }
        }

        given("MIN_GATEWAY_SHARED_SECRET_LEN should mirror the blog_be enforcement") {
            then("32 — keep in sync with blog_be's `_MIN_GATEWAY_SHARED_SECRET_LEN`") {
                AppConfigValidator.MIN_GATEWAY_SHARED_SECRET_LEN shouldBe 32
            }
        }
    })
