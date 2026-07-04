plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        kotlin.srcDir("src")
    }
}

val generateTree = tasks.register<JavaExec>("generateTree") {
    group = "generation"
    description = "Generate UIR tree sources into tree/gen"

    workingDir = rootDir
    classpath = project(":tree:tree-generator").sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.xyzboom.aiFuzzer.tree.generator.MainKt")

    val generationRoot = layout.projectDirectory.dir("gen")
    args(generationRoot.asFile.absolutePath)

    systemProperties["line.separator"] = "\n"

    val generatorSourceRoot = rootDir.resolve("tree/tree-generator/src")
    val generatorConfigFiles = fileTree(generatorSourceRoot) {
        include("**/*.kt")
    }
    inputs.files(generatorConfigFiles)
    outputs.dir(generationRoot)
}

// Wire generated sources to compilation
sourceSets.main.configure {
    kotlin.srcDir(layout.projectDirectory.dir("gen"))
}

// Ensure gen/ is generated before compilation
tasks.compileKotlin {
    dependsOn(generateTree)
}

kotlin {
    jvmToolchain(17)
}