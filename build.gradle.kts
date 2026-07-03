plugins {
    id("java")
    kotlin("jvm")
}

group = "io.github.xyzboom"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(kotlin("stdlib"))

    // tree-generator-common: Kotlin 编译器源码中的 IR 树结构生成器
    implementation(files(File(rootDir, "libs/tree-generator-common.jar")))
}

tasks.test {
    useJUnitPlatform()
}