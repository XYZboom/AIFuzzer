package io.github.xyzboom.aiFuzzer.tree.generator.utils

import org.jetbrains.kotlin.generators.tree.TypeRef

infix fun <T : TypeRef> T.bind(boolean: Boolean): T {
    return this
}