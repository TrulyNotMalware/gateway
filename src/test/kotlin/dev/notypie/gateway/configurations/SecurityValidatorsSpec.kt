package dev.notypie.gateway.configurations

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

class SecurityValidatorsSpec :
    BehaviorSpec({

        fun baseToken(
            headers: Map<String, Any> = mapOf("kid" to "v1", "alg" to "RS256"),
            claims: Map<String, Any> = mapOf("sub" to "admin", "typ" to "access"),
        ): Jwt =
            Jwt
                .withTokenValue("token")
                .headers { it.putAll(headers) }
                .claims { it.putAll(claims) }
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build()

        given("KidPresenceValidator") {
            val validator = KidPresenceValidator()

            `when`("the token carries a non-blank kid header") {
                val result = validator.validate(baseToken())
                then("validation succeeds") {
                    result.hasErrors() shouldBe false
                }
            }

            `when`("the kid header is missing entirely") {
                val token = baseToken(headers = mapOf("alg" to "RS256"))
                val result = validator.validate(token)
                then("validation fails with invalid_token") {
                    result.hasErrors() shouldBe true
                    result.errors.first().description shouldContain "kid"
                }
            }

            `when`("the kid header is a blank string") {
                val token = baseToken(headers = mapOf("kid" to "   ", "alg" to "RS256"))
                val result = validator.validate(token)
                then("validation fails") {
                    result.hasErrors() shouldBe true
                }
            }

            `when`("the kid header is a non-string value (defensive)") {
                val token = baseToken(headers = mapOf("kid" to 123, "alg" to "RS256"))
                val result = validator.validate(token)
                then("validation fails — only string kids are accepted") {
                    result.hasErrors() shouldBe true
                }
            }
        }

        given("AccessTokenTypeValidator") {
            val validator = AccessTokenTypeValidator()

            `when`("typ claim is 'access'") {
                val result = validator.validate(baseToken())
                then("succeeds") {
                    result.hasErrors() shouldBe false
                }
            }

            `when`("typ claim is 'refresh'") {
                val token = baseToken(claims = mapOf("sub" to "admin", "typ" to "refresh"))
                val result = validator.validate(token)
                then("fails — refresh tokens are not accepted at the gateway") {
                    result.hasErrors() shouldBe true
                    result.errors.first().description shouldContain "typ"
                }
            }

            `when`("typ claim is missing") {
                val token = baseToken(claims = mapOf("sub" to "admin"))
                val result = validator.validate(token)
                then("passes — issuers that don't use the typ convention are accepted") {
                    result.hasErrors() shouldBe false
                }
            }
        }

        given("RequiredClaimsValidator") {
            val validator = RequiredClaimsValidator()

            fun token(sub: String? = "admin", exp: Instant? = Instant.now().plusSeconds(60)): Jwt {
                val b =
                    Jwt
                        .withTokenValue("token")
                        .headers { it["kid"] = "v1" }
                        .issuedAt(Instant.now())
                if (sub != null) b.claim("sub", sub)
                if (exp != null) b.expiresAt(exp)
                // Jwt requires at least one claim; add a filler when sub is null so the
                // builder doesn't fall back to silently injecting one.
                if (sub == null) b.claim("iss", "blog-be")
                return b.build()
            }

            `when`("both sub and exp are present") {
                then("validation succeeds") {
                    validator.validate(token()).hasErrors() shouldBe false
                }
            }

            `when`("sub is missing") {
                then("validation fails on sub") {
                    val r = validator.validate(token(sub = null))
                    r.hasErrors() shouldBe true
                    r.errors.any { it.description?.contains("sub") == true } shouldBe true
                }
            }

            `when`("exp is missing") {
                then("validation fails on exp — default chain only checks expiry of present exp") {
                    val r = validator.validate(token(exp = null))
                    r.hasErrors() shouldBe true
                    r.errors.any { it.description?.contains("exp") == true } shouldBe true
                }
            }

            `when`("sub is blank") {
                then("validation fails — empty principal would break downstream") {
                    val r = validator.validate(token(sub = ""))
                    r.hasErrors() shouldBe true
                }
            }
        }

        given("JOSE header typ allowlist (RFC 9068 + RFC 7519)") {
            // Direct test of the widened JwtTypeValidator wired in `buildManager`.
            // The full validator chain composes more checks, but here we isolate typ
            // so a future regression on the allowlist surfaces with a clean signal.
            val typValidator =
                org.springframework.security.oauth2.jwt
                    .JwtTypeValidator(
                        listOf("JWT", "at+jwt", "application/at+jwt"),
                    ).apply { setAllowEmpty(true) }

            fun tokenWith(typ: String?): Jwt {
                val b =
                    Jwt
                        .withTokenValue("token")
                        .claim("sub", "admin")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .header("kid", "v1")
                if (typ != null) b.header("typ", typ)
                return b.build()
            }

            listOf("JWT", "at+jwt", "application/at+jwt", null).forEach { typ ->
                `when`("header typ = ${typ ?: "<absent>"}") {
                    then("validation succeeds") {
                        typValidator.validate(tokenWith(typ)).hasErrors() shouldBe false
                    }
                }
            }

            `when`("header typ = some-unknown") {
                then("validation fails") {
                    typValidator.validate(tokenWith("some-unknown")).hasErrors() shouldBe true
                }
            }
        }

        given("audienceValidator") {
            val validator = audienceValidator("blog-be")

            fun tokenWithAud(aud: Any?): Jwt {
                val b =
                    Jwt
                        .withTokenValue("token")
                        .header("kid", "v1")
                        .claim("sub", "admin")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                if (aud != null) b.claim("aud", aud)
                return b.build()
            }

            `when`("aud is the expected single value") {
                then("succeeds") {
                    validator.validate(tokenWithAud(listOf("blog-be"))).hasErrors() shouldBe false
                }
            }

            `when`("aud is a list containing the expected value alongside others") {
                then("succeeds — RFC 7519 allows multi-audience tokens") {
                    validator.validate(tokenWithAud(listOf("file-be", "blog-be"))).hasErrors() shouldBe false
                }
            }

            `when`("aud is a different value") {
                then("fails — catches cross-service replay") {
                    validator.validate(tokenWithAud(listOf("file-be"))).hasErrors() shouldBe true
                }
            }

            `when`("aud claim is missing entirely") {
                then("fails — issuer contract requires explicit audience") {
                    validator.validate(tokenWithAud(null)).hasErrors() shouldBe true
                }
            }

            `when`("aud claim is an empty list") {
                then("fails — empty audience implies the issuer dropped the binding") {
                    validator.validate(tokenWithAud(emptyList<String>())).hasErrors() shouldBe true
                }
            }
        }
    })
