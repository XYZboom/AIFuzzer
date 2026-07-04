

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode")

package io.github.xyzboom.aiFuzzer.ir.types.impl

import io.github.xyzboom.aiFuzzer.ir.UirAttrKind
import io.github.xyzboom.aiFuzzer.ir.types.UirStringAttr
import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor

internal class UirStringAttrImpl(
    override val attrKind: UirAttrKind,
    override var value: String,
) : UirStringAttr() {

    override fun <R, D> acceptChildren(visitor: UirVisitor<R, D>, data: D) {}

    override fun <D> transformChildren(transformer: UirTransformer<D>, data: D): UirStringAttrImpl {
        return this
    }
}
