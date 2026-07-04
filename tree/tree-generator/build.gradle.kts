plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

application {
    mainClass = "io.github.xyzboom.aiFuzzer.tree.generator.MainKt"
}

dependencies {
    // tree-generator-common: Kotlin 编译器源码中的 IR 树结构生成器
    implementation(files(rootProject.file("libs/tree-generator-common.jar")))
    testImplementation(kotlin("test"))
}
