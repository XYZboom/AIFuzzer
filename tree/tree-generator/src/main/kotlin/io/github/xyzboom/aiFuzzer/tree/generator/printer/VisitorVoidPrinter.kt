package io.github.xyzboom.aiFuzzer.tree.generator.printer

import io.github.xyzboom.aiFuzzer.tree.generator.TreeBuilder
import io.github.xyzboom.aiFuzzer.tree.generator.irVisitorType
import io.github.xyzboom.aiFuzzer.tree.generator.model.Element
import io.github.xyzboom.aiFuzzer.tree.generator.model.Field
import org.jetbrains.kotlin.generators.tree.AbstractVisitorVoidPrinter
import org.jetbrains.kotlin.generators.tree.ClassRef
import org.jetbrains.kotlin.generators.tree.PositionTypeParameterRef
import org.jetbrains.kotlin.generators.tree.printer.ImportCollectingPrinter

internal class VisitorVoidPrinter(
    printer: ImportCollectingPrinter,
    override val visitorType: ClassRef<*>,
) : AbstractVisitorVoidPrinter<Element, Field>(printer) {

    override val visitorSuperClass: ClassRef<PositionTypeParameterRef>
        get() = irVisitorType

    override val allowTypeParametersInVisitorMethods: Boolean
        get() = true

    override val useAbstractMethodForRootElement: Boolean
        get() = true

    override val overriddenVisitMethodsAreFinal: Boolean
        get() = false

    override fun parentInVisitor(element: Element): Element = TreeBuilder.rootElement
}