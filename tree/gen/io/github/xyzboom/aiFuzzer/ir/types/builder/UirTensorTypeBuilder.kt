

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package io.github.xyzboom.aiFuzzer.ir.types.builder

import io.github.xyzboom.aiFuzzer.ir.UirTypeKind
import io.github.xyzboom.aiFuzzer.ir.builder.BuilderDsl
import io.github.xyzboom.aiFuzzer.ir.types.UirDataType
import io.github.xyzboom.aiFuzzer.ir.types.UirShape
import io.github.xyzboom.aiFuzzer.ir.types.UirTensorType
import io.github.xyzboom.aiFuzzer.ir.types.impl.UirTensorTypeImpl
import kotlin.contracts.*

@BuilderDsl
class UirTensorTypeBuilder {
    lateinit var typeKind: UirTypeKind
    lateinit var shape: UirShape
    lateinit var dtype: UirDataType

    fun build(): UirTensorType {
        return UirTensorTypeImpl(
            typeKind,
            shape,
            dtype,
        )
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildTensorType(init: UirTensorTypeBuilder.() -> Unit): UirTensorType {
    contract {
        callsInPlace(init, InvocationKind.EXACTLY_ONCE)
    }
    return UirTensorTypeBuilder().apply(init).build()
}
