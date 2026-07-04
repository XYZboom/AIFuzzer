

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode")

package io.github.xyzboom.aiFuzzer.ir.impl

import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor

internal class UirValueRefImpl(
    override var valueId: String,
) : UirValueRef() {

    override fun <R, D> acceptChildren(visitor: UirVisitor<R, D>, data: D) {}

    override fun <D> transformChildren(transformer: UirTransformer<D>, data: D): UirValueRefImpl {
        return this
    }
}
