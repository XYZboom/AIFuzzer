

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package io.github.xyzboom.aiFuzzer.ir.builder

import io.github.xyzboom.aiFuzzer.ir.Attribute
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.impl.UirNodeImpl
import kotlin.contracts.*

@BuilderDsl
class UirNodeBuilder {
    lateinit var name: String
    lateinit var op: String
    val inputs: MutableList<UirValueRef> = mutableListOf()
    val outputs: MutableList<UirValueRef> = mutableListOf()
    lateinit var attributes: MutableMap<String, Attribute>

    fun build(): UirNode {
        return UirNodeImpl(
            name,
            op,
            inputs,
            outputs,
            attributes,
        )
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildNode(init: UirNodeBuilder.() -> Unit): UirNode {
    contract {
        callsInPlace(init, InvocationKind.EXACTLY_ONCE)
    }
    return UirNodeBuilder().apply(init).build()
}
