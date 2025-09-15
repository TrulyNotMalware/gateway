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
    set("springCloudVersion", "2025.0.0")
    set("kotestVersion", "6.0.3") // https://kotest.io/docs/extensions/spring.html
    set("mockkVersion", "1.14.5")
    set("springBootVersion", "3.5.5")
}

dependencies {
    // Kotest-bom
    implementation(platform("io.kotest:kotest-bom:${rootProject.extra.get("kotestVersion")}"))
    // Spring-cloud-bom
    implementation(
        platform("org.springframework.cloud:spring-cloud-dependencies:${rootProject.extra.get("springCloudVersion")}"),
    )
    implementation(
        platform("org.springframework.boot:spring-boot-dependencies:${rootProject.extra.get("springBootVersion")}"),
    )

    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
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
