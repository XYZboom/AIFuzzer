

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode")

package io.github.xyzboom.aiFuzzer.ir.impl

import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor
import io.github.xyzboom.aiFuzzer.ir.visitors.transformInplace

internal class UirGraphImpl(
    override var name: String,
    override var nodes: MutableList<UirNode>,
    override var inputs: MutableList<UirValueRef>,
    override var outputs: MutableList<UirValueRef>,
) : UirGraph() {

    override fun <R, D> acceptChildren(visitor: UirVisitor<R, D>, data: D) {
        nodes.forEach { it.accept(visitor, data) }
        inputs.forEach { it.accept(visitor, data) }
        outputs.forEach { it.accept(visitor, data) }
    }

    override fun <D> transformChildren(transformer: UirTransformer<D>, data: D): UirGraphImpl {
        nodes.transformInplace(transformer, data)
        inputs.transformInplace(transformer, data)
        outputs.transformInplace(transformer, data)
        return this
    }
}
