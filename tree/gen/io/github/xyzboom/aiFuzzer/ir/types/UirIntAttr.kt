

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

package io.github.xyzboom.aiFuzzer.ir.types

import io.github.xyzboom.aiFuzzer.ir.UirAttrKind
import io.github.xyzboom.aiFuzzer.ir.UirElement
import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor

/**
 * Generated from: [io.github.xyzboom.aiFuzzer.tree.generator.TreeBuilder.intAttr]
 */
abstract class UirIntAttr : UirAttribute() {
    abstract override val attrKind: UirAttrKind
    abstract var value: Int

    override fun <R, D> accept(visitor: UirVisitor<R, D>, data: D): R =
        visitor.visitIntAttr(this, data)

    @Suppress("UNCHECKED_CAST")
    override fun <E : UirElement, D> transform(transformer: UirTransformer<D>, data: D): E =
        transformer.transformIntAttr(this, data) as E
}
