

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode")

package io.github.xyzboom.aiFuzzer.ir.types.impl

import io.github.xyzboom.aiFuzzer.ir.types.UirDim
import io.github.xyzboom.aiFuzzer.ir.types.UirShape
import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor
import io.github.xyzboom.aiFuzzer.ir.visitors.transformInplace

internal class UirShapeImpl(
    override var dims: MutableList<UirDim>,
) : UirShape() {

    override fun <R, D> acceptChildren(visitor: UirVisitor<R, D>, data: D) {
        dims.forEach { it.accept(visitor, data) }
    }

    override fun <D> transformChildren(transformer: UirTransformer<D>, data: D): UirShapeImpl {
        dims.transformInplace(transformer, data)
        return this
    }
}
