

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

package io.github.xyzboom.aiFuzzer.ir.types

import io.github.xyzboom.aiFuzzer.ir.UirDimKind
import io.github.xyzboom.aiFuzzer.ir.UirElement
import io.github.xyzboom.aiFuzzer.ir.UirPureAbstractElement
import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor

/**
 * Generated from: [io.github.xyzboom.aiFuzzer.tree.generator.TreeBuilder.dim]
 */
abstract class UirDim : UirElement, UirPureAbstractElement {
    abstract var dimKind: UirDimKind
    abstract var value: Int?

    override fun <R, D> accept(visitor: UirVisitor<R, D>, data: D): R =
        visitor.visitDim(this, data)

    @Suppress("UNCHECKED_CAST")
    override fun <E : UirElement, D> transform(transformer: UirTransformer<D>, data: D): E =
        transformer.transformDim(this, data) as E
}
