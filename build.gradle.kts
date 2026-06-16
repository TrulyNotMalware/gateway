import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.tasks.GenerateReportsTask

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.spring") version "2.4.0"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "dev.notypie"
version = "alpha"

// remove ext
val springCloudVersion by extra("2025.1.2")
val kotestVersion by extra("6.2.0")
val mockkVersion by extra("1.14.11")
val springBootVersion by extra("4.1.0")
val redissonVersion by extra("4.6.0")
val kotlinxVersion by extra("1.11.0")
val reactorKotlinExtensionVersion by extra("1.3.1")
val kotlinLoggingVersion by extra("8.0.4")

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
    // Kotlinx-bom
    implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:$kotlinxVersion"))
    // Springboot-bom
    implementation(
        platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"),
    )
    // Redisson client
    implementation("org.redisson:redisson-spring-boot-starter:$redissonVersion")

    // Coroutine
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    // jackson
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
