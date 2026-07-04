

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode")

package io.github.xyzboom.aiFuzzer.ir.types.impl

import io.github.xyzboom.aiFuzzer.ir.UirTypeKind
import io.github.xyzboom.aiFuzzer.ir.types.UirDataType
import io.github.xyzboom.aiFuzzer.ir.types.UirShape
import io.github.xyzboom.aiFuzzer.ir.types.UirTensorType
import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor

internal class UirTensorTypeImpl(
    override val typeKind: UirTypeKind,
    override var shape: UirShape,
    override var dtype: UirDataType,
) : UirTensorType() {

    override fun <R, D> acceptChildren(visitor: UirVisitor<R, D>, data: D) {}

    override fun <D> transformChildren(transformer: UirTransformer<D>, data: D): UirTensorTypeImpl {
        return this
    }
}
