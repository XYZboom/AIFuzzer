package io.github.xyzboom.aiFuzzer.tree.generator.printer

import io.github.xyzboom.aiFuzzer.tree.generator.irVisitorType
import io.github.xyzboom.aiFuzzer.tree.generator.model.Element
import io.github.xyzboom.aiFuzzer.tree.generator.model.Field
import org.jetbrains.kotlin.generators.tree.*
import org.jetbrains.kotlin.generators.tree.printer.ImportCollectingPrinter

internal class TransformerPrinter(
    printer: ImportCollectingPrinter,
    override val visitorType: ClassRef<*>,
    private val rootElement: Element,
) : AbstractTransformerPrinter<Element, Field>(printer) {

    override val visitorSuperTypes: List<ClassRef<PositionTypeParameterRef>>
        get() = listOf(irVisitorType.withArgs(rootElement, visitorDataType))

    override val visitorTypeParameters: List<TypeVariable>
        get() = listOf(dataTypeVariable)

    override val visitorDataType: TypeRef
        get() = dataTypeVariable

    override val allowTypeParametersInVisitorMethods: Boolean
        get() = true

    override fun parentInVisitor(element: Element) = when {
        element.isRootElement -> null
        else -> rootElement
    }
}