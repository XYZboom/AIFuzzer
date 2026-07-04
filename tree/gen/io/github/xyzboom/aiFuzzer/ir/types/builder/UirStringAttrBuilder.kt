

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package io.github.xyzboom.aiFuzzer.ir.types.builder

import io.github.xyzboom.aiFuzzer.ir.UirAttrKind
import io.github.xyzboom.aiFuzzer.ir.builder.BuilderDsl
import io.github.xyzboom.aiFuzzer.ir.types.UirStringAttr
import io.github.xyzboom.aiFuzzer.ir.types.impl.UirStringAttrImpl
import kotlin.contracts.*

@BuilderDsl
class UirStringAttrBuilder {
    var attrKind: UirAttrKind = UirAttrKind.STRING
    lateinit var value: String

    fun build(): UirStringAttr {
        return UirStringAttrImpl(
            attrKind,
            value,
        )
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildStringAttr(init: UirStringAttrBuilder.() -> Unit): UirStringAttr {
    contract {
        callsInPlace(init, InvocationKind.EXACTLY_ONCE)
    }
    return UirStringAttrBuilder().apply(init).build()
}
