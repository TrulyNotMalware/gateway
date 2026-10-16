import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.tasks.GenerateReportsTask
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "dev.notypie"
version = "alpha"

// Versions the Spring Boot BOM does not manage. Declared as extra["…"] = "…" (a form Dependabot's
// Gradle parser reads) and read back below; `val x by extra("…")` is deprecated since Gradle 9.6.
// Spring Boot itself follows the plugin version (SpringBootPlugin.BOM_COORDINATES), and
// kotlinx-coroutines follows the Boot BOM: io.spring.dependency-management overrode the separate
// coroutines BOM anyway (1.11.0 -> 1.10.2).
extra["springCloudVersion"] = "2025.1.2"
extra["kotestVersion"] = "6.2.0"
extra["mockkVersion"] = "1.14.11"
extra["redissonVersion"] = "4.6.0"
extra["reactorKotlinExtensionVersion"] = "1.3.2"
extra["kotlinLoggingVersion"] = "8.0.4"

val springCloudVersion = extra["springCloudVersion"] as String
val kotestVersion = extra["kotestVersion"] as String
val mockkVersion = extra["mockkVersion"] as String
val redissonVersion = extra["redissonVersion"] as String
val reactorKotlinExtensionVersion = extra["reactorKotlinExtensionVersion"] as String
val kotlinLoggingVersion = extra["kotlinLoggingVersion"] as String

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            // -Xannotation-default-target=param-property dropped: it is the default in Kotlin 2.4+
            // and the compiler now flags it as redundant.
            "-java-parameters",
            "-jvm-default=no-compatibility",
        )
    }
}

dependencies {
    // Kotest-bom
    implementation(platform("io.kotest:kotest-bom:$kotestVersion"))
    // Spring-cloud-bom
    implementation(
        platform("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion"),
    )
    // Springboot-bom
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    // Redisson client
    implementation("org.redisson:redisson-spring-boot-starter:$redissonVersion")

    // Coroutine
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    // Reactor Kotlin extensions
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions:$reactorKotlinExtensionVersion")
    // actuator
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Kotlin logging
    implementation("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")

    implementation(kotlin("reflect"))
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")

    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")

    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-extensions-spring")
    testImplementation("io.kotest:kotest-assertions-core")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs(
        "-Xmx4g",
        "-Dfile.encoding=UTF-8",
        "-XX:+EnableDynamicAgentLoading",
        "--add-opens",
        "java.base/java.lang=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.util=ALL-UNNAMED",
    )
}

tasks.withType<GenerateReportsTask> {
    reportsOutputDirectory.set(
        rootProject.layout.buildDirectory.dir(
            "reports/ktlint/${project.name}",
        ),
    )
}

// io.spring.dependency-management applies the Spring Boot BOM to every configuration, ktlint's
// included, and the BOM moves org.jetbrains.kotlin artifacts to the Kotlin plugin version. ktlint
// then parses with that kotlin-compiler-embeddable instead of the one it was built against, which
// broke on Kotlin 2.4.21 ("Extensions storage is not registered"). Keep the versions ktlint asks for.
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            requested.version?.let { useVersion(it) }
        }
    }
}
