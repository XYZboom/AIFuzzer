

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

package io.github.xyzboom.aiFuzzer.ir

import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor

/**
 * Generated from: [io.github.xyzboom.aiFuzzer.tree.generator.TreeBuilder.program]
 */
interface UirProgram : UirElement {
    var graphs: MutableList<UirGraph>
    var metadata: MutableMap<String, String>

    override fun <R, D> accept(visitor: UirVisitor<R, D>, data: D): R =
        visitor.visitProgram(this, data)

    @Suppress("UNCHECKED_CAST")
    override fun <E : UirElement, D> transform(transformer: UirTransformer<D>, data: D): E =
        transformer.transformProgram(this, data) as E
}
