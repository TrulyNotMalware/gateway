import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.spring") version "2.2.10"
    id("org.springframework.boot") version "3.5.5"
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
}

group = "dev.notypie"
version = "alpha"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

ktlint {
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.JSON)
    }
}

ext {
    set("springCloudVersion", "2025.0.0") // https://spring.io/projects/spring-cloud#overview
    set("kotestVersion", "6.0.3") // https://kotest.io/docs/extensions/spring.html
    set("mockkVersion", "1.14.5")
    set("springBootVersion", "3.5.5")
    set("redissonVersion", "3.51.0")
    set("kotlinxVersion", "1.10.2")
    set("reactorKotlinExtensionVersion", "1.3.0-RC4") // https://github.com/reactor/reactor-kotlin-extensions/releases
    set("kotlinLoggingVersion", "7.0.13")
}

dependencies {
    // Kotest-bom
    implementation(platform("io.kotest:kotest-bom:${rootProject.extra.get("kotestVersion")}"))
    // Spring-cloud-bom
    implementation(
        platform("org.springframework.cloud:spring-cloud-dependencies:${rootProject.extra.get("springCloudVersion")}"),
    )
    // Kotlinx-bom
    implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:${rootProject.extra.get("kotlinxVersion")}"))
    // Springboot-bom
    implementation(
        platform("org.springframework.boot:spring-boot-dependencies:${rootProject.extra.get("springBootVersion")}"),
    )
    // jackson
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Redisson client
    implementation("org.redisson:redisson-spring-boot-starter:${rootProject.extra.get("redissonVersion")}")

    // Coroutine
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation(
        "io.projectreactor.kotlin:reactor-kotlin-extensions:${rootProject.extra.get("reactorKotlinExtensionVersion")}",
    )
    // actuator
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Kotlin logging
    implementation("io.github.oshai:kotlin-logging-jvm:${rootProject.extra.get("kotlinLoggingVersion")}")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")

    testImplementation("io.mockk:mockk:${rootProject.extra.get("mockkVersion")}")
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-extensions-spring")
    testImplementation("io.kotest:kotest-assertions-core")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
