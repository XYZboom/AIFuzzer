package io.github.xyzboom.aiFuzzer.tree.generator.printer

import io.github.xyzboom.aiFuzzer.tree.generator.TreeBuilder
import io.github.xyzboom.aiFuzzer.tree.generator.irVisitorType
import io.github.xyzboom.aiFuzzer.tree.generator.model.Element
import io.github.xyzboom.aiFuzzer.tree.generator.model.Field
import org.jetbrains.kotlin.generators.tree.*
import org.jetbrains.kotlin.generators.tree.printer.ImportCollectingPrinter

internal class VisitorPrinter(
    printer: ImportCollectingPrinter,
    override val visitorType: ClassRef<*>,
    private val visitSuperTypeByDefault: Boolean,
) : AbstractVisitorPrinter<Element, Field>(printer) {

    override val visitorTypeParameters: List<TypeVariable>
        get() = listOf(resultTypeVariable, dataTypeVariable)

    override val visitorSuperTypes: List<ClassRef<PositionTypeParameterRef>> =
        listOfNotNull(irVisitorType.takeIf { visitSuperTypeByDefault }?.withArgs(resultTypeVariable, dataTypeVariable))

    override val visitorDataType: TypeRef
        get() = dataTypeVariable

    override fun visitMethodReturnType(element: Element) = resultTypeVariable

    override val allowTypeParametersInVisitorMethods: Boolean
        get() = true

    override fun skipElement(element: Element): Boolean = visitSuperTypeByDefault && element.isRootElement

    override fun parentInVisitor(element: Element): Element? = when {
        element.isRootElement -> null
        visitSuperTypeByDefault -> element.parentInVisitor
        else -> TreeBuilder.rootElement
    }
}
