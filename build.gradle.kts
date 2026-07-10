plugins {
    id("java")
    kotlin("jvm")
    application
    kotlin("plugin.serialization") version "2.4.0"
}

group = "io.github.xyzboom"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

subprojects {
    pluginManager.apply("org.jetbrains.kotlin.jvm")
    kotlin {
        compilerOptions {
            extraWarnings = true
            allWarningsAsErrors = true
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(kotlin("stdlib"))
    implementation(project(":tree"))
    // YAML 配置文件解析
    implementation("org.yaml:snakeyaml:2.0")
    // CLIKT 命令行参数解析
    implementation("com.github.ajalt.clikt:clikt-jvm:4.2.2")
    // JSON 序列化/反序列化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Kotlin 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // Ktor HTTP 客户端（与 daemon 通信）
    implementation("io.ktor:ktor-client-core:3.1.2")
    implementation("io.ktor:ktor-client-cio:3.1.2")
    // Kotlin-logging（日志框架）
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    // SLF4J 实现（Logback）
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

application {
    mainClass = "io.github.xyzboom.aiFuzzer.AppKt"
}

sourceSets {
    main {
        kotlin {
            srcDirs("src/main/kotlin")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}