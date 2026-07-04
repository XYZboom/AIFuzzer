

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package io.github.xyzboom.aiFuzzer.ir.builder

import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.impl.UirGraphImpl
import kotlin.contracts.*

@BuilderDsl
class UirGraphBuilder {
    lateinit var name: String
    val nodes: MutableList<UirNode> = mutableListOf()
    val inputs: MutableList<UirValueRef> = mutableListOf()
    val outputs: MutableList<UirValueRef> = mutableListOf()

    fun build(): UirGraph {
        return UirGraphImpl(
            name,
            nodes,
            inputs,
            outputs,
        )
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildGraph(init: UirGraphBuilder.() -> Unit): UirGraph {
    contract {
        callsInPlace(init, InvocationKind.EXACTLY_ONCE)
    }
    return UirGraphBuilder().apply(init).build()
}
