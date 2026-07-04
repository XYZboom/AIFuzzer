

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package io.github.xyzboom.aiFuzzer.ir.types.builder

import io.github.xyzboom.aiFuzzer.ir.UirAttrKind
import io.github.xyzboom.aiFuzzer.ir.builder.BuilderDsl
import io.github.xyzboom.aiFuzzer.ir.types.UirIntAttr
import io.github.xyzboom.aiFuzzer.ir.types.impl.UirIntAttrImpl
import kotlin.contracts.*

@BuilderDsl
class UirIntAttrBuilder {
    var attrKind: UirAttrKind = UirAttrKind.INT
    var value: Int by kotlin.properties.Delegates.notNull<Int>()

    fun build(): UirIntAttr {
        return UirIntAttrImpl(
            attrKind,
            value,
        )
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildIntAttr(init: UirIntAttrBuilder.() -> Unit): UirIntAttr {
    contract {
        callsInPlace(init, InvocationKind.EXACTLY_ONCE)
    }
    return UirIntAttrBuilder().apply(init).build()
}
