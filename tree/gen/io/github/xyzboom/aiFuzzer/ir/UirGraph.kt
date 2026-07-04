

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

package io.github.xyzboom.aiFuzzer.ir

import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor

/**
 * Generated from: [io.github.xyzboom.aiFuzzer.tree.generator.TreeBuilder.graph]
 */
abstract class UirGraph : UirNamedElement, UirPureAbstractElement {
    abstract override var name: String
    abstract var nodes: MutableList<UirNode>
    abstract var inputs: MutableList<UirValueRef>
    abstract var outputs: MutableList<UirValueRef>

    override fun <R, D> accept(visitor: UirVisitor<R, D>, data: D): R =
        visitor.visitGraph(this, data)

    @Suppress("UNCHECKED_CAST")
    override fun <E : UirElement, D> transform(transformer: UirTransformer<D>, data: D): E =
        transformer.transformGraph(this, data) as E
}
