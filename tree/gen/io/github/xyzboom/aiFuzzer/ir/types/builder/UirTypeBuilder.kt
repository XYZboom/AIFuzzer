

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package io.github.xyzboom.aiFuzzer.ir.types.builder

import io.github.xyzboom.aiFuzzer.ir.UirTypeKind
import io.github.xyzboom.aiFuzzer.ir.builder.BuilderDsl
import io.github.xyzboom.aiFuzzer.ir.types.UirType
import io.github.xyzboom.aiFuzzer.ir.types.impl.UirTypeImpl
import kotlin.contracts.*

@BuilderDsl
class UirTypeBuilder {
    lateinit var typeKind: UirTypeKind

    fun build(): UirType {
        return UirTypeImpl(
            typeKind,
        )
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildType(init: UirTypeBuilder.() -> Unit): UirType {
    contract {
        callsInPlace(init, InvocationKind.EXACTLY_ONCE)
    }
    return UirTypeBuilder().apply(init).build()
}
