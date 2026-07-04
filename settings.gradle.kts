pluginManagement {
    plugins {
        kotlin("jvm") version "2.4.0"
    }
}
rootProject.name = "aiFuzzer"

include(":tree")
include(":tree:tree-generator")
