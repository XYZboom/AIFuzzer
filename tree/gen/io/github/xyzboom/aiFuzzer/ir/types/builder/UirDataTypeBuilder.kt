

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package io.github.xyzboom.aiFuzzer.ir.types.builder

import io.github.xyzboom.aiFuzzer.ir.builder.BuilderDsl
import io.github.xyzboom.aiFuzzer.ir.types.UirDataType
import io.github.xyzboom.aiFuzzer.ir.types.impl.UirDataTypeImpl
import kotlin.contracts.*

@BuilderDsl
class UirDataTypeBuilder {
    lateinit var name: String
    var bits: Int by kotlin.properties.Delegates.notNull<Int>()

    fun build(): UirDataType {
        return UirDataTypeImpl(
            name,
            bits,
        )
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildDataType(init: UirDataTypeBuilder.() -> Unit): UirDataType {
    contract {
        callsInPlace(init, InvocationKind.EXACTLY_ONCE)
    }
    return UirDataTypeBuilder().apply(init).build()
}
