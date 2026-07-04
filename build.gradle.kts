plugins {
    id("java")
    kotlin("jvm")
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