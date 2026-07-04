

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package io.github.xyzboom.aiFuzzer.ir.builder

import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.impl.UirValueRefImpl
import kotlin.contracts.*

@BuilderDsl
class UirValueRefBuilder {
    lateinit var valueId: String

    fun build(): UirValueRef {
        return UirValueRefImpl(
            valueId,
        )
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildValueRef(init: UirValueRefBuilder.() -> Unit): UirValueRef {
    contract {
        callsInPlace(init, InvocationKind.EXACTLY_ONCE)
    }
    return UirValueRefBuilder().apply(init).build()
}
