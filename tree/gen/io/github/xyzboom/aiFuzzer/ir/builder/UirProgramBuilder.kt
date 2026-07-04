

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package io.github.xyzboom.aiFuzzer.ir.builder

import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.ir.impl.UirProgramImpl
import kotlin.contracts.*

@BuilderDsl
class UirProgramBuilder {
    val graphs: MutableList<UirGraph> = mutableListOf()
    var metadata: MutableMap<String, String> = mutableMapOf()

    fun build(): UirProgram {
        return UirProgramImpl(
            graphs,
            metadata,
        )
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildProgram(init: UirProgramBuilder.() -> Unit = {}): UirProgram {
    contract {
        callsInPlace(init, InvocationKind.EXACTLY_ONCE)
    }
    return UirProgramBuilder().apply(init).build()
}
