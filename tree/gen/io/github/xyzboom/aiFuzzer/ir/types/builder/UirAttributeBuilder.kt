

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package io.github.xyzboom.aiFuzzer.ir.types.builder

import io.github.xyzboom.aiFuzzer.ir.UirAttrKind
import io.github.xyzboom.aiFuzzer.ir.builder.BuilderDsl
import io.github.xyzboom.aiFuzzer.ir.types.UirAttribute
import io.github.xyzboom.aiFuzzer.ir.types.impl.UirAttributeImpl
import kotlin.contracts.*

@BuilderDsl
class UirAttributeBuilder {
    var attrKind: UirAttrKind = UirAttrKind.INT

    fun build(): UirAttribute {
        return UirAttributeImpl(
            attrKind,
        )
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildAttribute(init: UirAttributeBuilder.() -> Unit = {}): UirAttribute {
    contract {
        callsInPlace(init, InvocationKind.EXACTLY_ONCE)
    }
    return UirAttributeBuilder().apply(init).build()
}
