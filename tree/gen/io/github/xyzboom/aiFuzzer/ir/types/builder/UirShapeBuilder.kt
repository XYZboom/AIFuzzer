

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package io.github.xyzboom.aiFuzzer.ir.types.builder

import io.github.xyzboom.aiFuzzer.ir.builder.BuilderDsl
import io.github.xyzboom.aiFuzzer.ir.types.UirDim
import io.github.xyzboom.aiFuzzer.ir.types.UirShape
import io.github.xyzboom.aiFuzzer.ir.types.impl.UirShapeImpl
import kotlin.contracts.*

@BuilderDsl
class UirShapeBuilder {
    val dims: MutableList<UirDim> = mutableListOf()

    fun build(): UirShape {
        return UirShapeImpl(
            dims,
        )
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildShape(init: UirShapeBuilder.() -> Unit = {}): UirShape {
    contract {
        callsInPlace(init, InvocationKind.EXACTLY_ONCE)
    }
    return UirShapeBuilder().apply(init).build()
}
