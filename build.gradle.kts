plugins {
    id("java")
    kotlin("jvm")
    application
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