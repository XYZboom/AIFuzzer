

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package io.github.xyzboom.aiFuzzer.ir.types.builder

import io.github.xyzboom.aiFuzzer.ir.UirDimKind
import io.github.xyzboom.aiFuzzer.ir.builder.BuilderDsl
import io.github.xyzboom.aiFuzzer.ir.types.UirDim
import io.github.xyzboom.aiFuzzer.ir.types.impl.UirDimImpl
import kotlin.contracts.*

@BuilderDsl
class UirDimBuilder {
    lateinit var dimKind: UirDimKind
    var value: Int? = null

    fun build(): UirDim {
        return UirDimImpl(
            dimKind,
            value,
        )
    }

}

@OptIn(ExperimentalContracts::class)
inline fun buildDim(init: UirDimBuilder.() -> Unit): UirDim {
    contract {
        callsInPlace(init, InvocationKind.EXACTLY_ONCE)
    }
    return UirDimBuilder().apply(init).build()
}
