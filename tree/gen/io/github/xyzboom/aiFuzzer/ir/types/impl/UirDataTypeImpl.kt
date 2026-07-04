

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode")

package io.github.xyzboom.aiFuzzer.ir.types.impl

import io.github.xyzboom.aiFuzzer.ir.types.UirDataType
import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor

internal class UirDataTypeImpl(
    override var name: String,
    override var bits: Int,
) : UirDataType() {

    override fun <R, D> acceptChildren(visitor: UirVisitor<R, D>, data: D) {}

    override fun <D> transformChildren(transformer: UirTransformer<D>, data: D): UirDataTypeImpl {
        return this
    }
}
