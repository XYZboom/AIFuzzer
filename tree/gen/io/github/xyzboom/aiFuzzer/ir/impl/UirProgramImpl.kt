

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode")

package io.github.xyzboom.aiFuzzer.ir.impl

import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.ir.UirPureAbstractElement
import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor
import io.github.xyzboom.aiFuzzer.ir.visitors.transformInplace

internal class UirProgramImpl(
    override var graphs: MutableList<UirGraph>,
    override var metadata: MutableMap<String, String>,
) : UirPureAbstractElement, UirProgram {

    override fun <R, D> acceptChildren(visitor: UirVisitor<R, D>, data: D) {
        graphs.forEach { it.accept(visitor, data) }
    }

    override fun <D> transformChildren(transformer: UirTransformer<D>, data: D): UirProgramImpl {
        graphs.transformInplace(transformer, data)
        return this
    }
}
