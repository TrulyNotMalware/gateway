import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.tasks.GenerateReportsTask

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    id("org.springframework.boot") version "4.0.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
}

group = "dev.notypie"
version = "alpha"

// remove ext
val springCloudVersion by extra("2025.1.0")
val kotestVersion by extra("6.0.3")
val mockkVersion by extra("1.14.6")
val springBootVersion by extra("4.0.1")
val redissonVersion by extra("3.51.0")
val kotlinxVersion by extra("1.10.2")
val reactorKotlinExtensionVersion by extra("1.3.0")
val kotlinLoggingVersion by extra("7.0.13")

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
            "-Xannotation-default-target=param-property",
            "-java-parameters",
            "-Xjvm-default=all",
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
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")

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
