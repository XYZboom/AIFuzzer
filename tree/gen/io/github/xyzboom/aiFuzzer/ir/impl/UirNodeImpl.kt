

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode")

package io.github.xyzboom.aiFuzzer.ir.impl

import io.github.xyzboom.aiFuzzer.ir.Attribute
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor
import io.github.xyzboom.aiFuzzer.ir.visitors.transformInplace

internal class UirNodeImpl(
    override var name: String,
    override var op: String,
    override var inputs: MutableList<UirValueRef>,
    override var outputs: MutableList<UirValueRef>,
    override var attributes: MutableMap<String, Attribute>,
) : UirNode() {

    override fun <R, D> acceptChildren(visitor: UirVisitor<R, D>, data: D) {
        inputs.forEach { it.accept(visitor, data) }
        outputs.forEach { it.accept(visitor, data) }
    }

    override fun <D> transformChildren(transformer: UirTransformer<D>, data: D): UirNodeImpl {
        inputs.transformInplace(transformer, data)
        outputs.transformInplace(transformer, data)
        return this
    }
}
