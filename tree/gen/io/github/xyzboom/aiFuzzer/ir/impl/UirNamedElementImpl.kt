

// This file was generated automatically. See README.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode")

package io.github.xyzboom.aiFuzzer.ir.impl

import io.github.xyzboom.aiFuzzer.ir.UirNamedElement
import io.github.xyzboom.aiFuzzer.ir.UirPureAbstractElement
import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer
import io.github.xyzboom.aiFuzzer.ir.visitors.UirVisitor

internal class UirNamedElementImpl(
    override var name: String,
) : UirPureAbstractElement, UirNamedElement {

    override fun <R, D> acceptChildren(visitor: UirVisitor<R, D>, data: D) {}

    override fun <D> transformChildren(transformer: UirTransformer<D>, data: D): UirNamedElementImpl {
        return this
    }
}
