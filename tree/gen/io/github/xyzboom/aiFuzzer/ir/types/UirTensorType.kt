

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

package io.github.xyzboom.aiFuzzer.ir.types

import io.github.xyzboom.aiFuzzer.ir.UirElement
import io.github.xyzboom.aiFuzzer.ir.UirTypeKind
import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor

/**
 * Generated from: [io.github.xyzboom.aiFuzzer.tree.generator.TreeBuilder.tensorType]
 */
abstract class UirTensorType : UirType() {
    abstract override val typeKind: UirTypeKind
    abstract var shape: UirShape
    abstract var dtype: UirDataType

    override fun <R, D> accept(visitor: UirVisitor<R, D>, data: D): R =
        visitor.visitTensorType(this, data)

    @Suppress("UNCHECKED_CAST")
    override fun <E : UirElement, D> transform(transformer: UirTransformer<D>, data: D): E =
        transformer.transformTensorType(this, data) as E
}
