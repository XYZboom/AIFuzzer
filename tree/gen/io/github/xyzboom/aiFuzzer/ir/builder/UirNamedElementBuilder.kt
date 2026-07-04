

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package io.github.xyzboom.aiFuzzer.ir.builder

import io.github.xyzboom.aiFuzzer.ir.UirNamedElement
import io.github.xyzboom.aiFuzzer.ir.impl.UirNamedElementImpl
import kotlin.contracts.*

@BuilderDsl
class UirNamedElementBuilder {
    lateinit var name: String

    fun build(): UirNamedElement {
        return UirNamedElementImpl(
            name,
        )
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildNamedElement(init: UirNamedElementBuilder.() -> Unit): UirNamedElement {
    contract {
        callsInPlace(init, InvocationKind.EXACTLY_ONCE)
    }
    return UirNamedElementBuilder().apply(init).build()
}
