package io.github.xyzboom.aiFuzzer.tree.generator.printer

import io.github.xyzboom.aiFuzzer.tree.generator.irVisitorVoidType
import io.github.xyzboom.aiFuzzer.tree.generator.model.Element
import io.github.xyzboom.aiFuzzer.tree.generator.model.Field
import org.jetbrains.kotlin.generators.tree.*
import org.jetbrains.kotlin.generators.tree.printer.ImportCollectingPrinter
import org.jetbrains.kotlin.generators.util.printBlock

internal class DefaultVisitorVoidPrinter(
    printer: ImportCollectingPrinter,
    override val visitorType: ClassRef<*>,
) : AbstractVisitorPrinter<Element, Field>(printer) {

    override val visitorTypeParameters: List<TypeVariable>
        get() = emptyList()

    override val visitorDataType: TypeRef
        get() = StandardTypes.nothing.copy(nullable = true)

    override fun visitMethodReturnType(element: Element) = StandardTypes.unit

    override val visitorSuperTypes: List<ClassRef<PositionTypeParameterRef>>
        get() = listOf(irVisitorVoidType)

    override val allowTypeParametersInVisitorMethods: Boolean
        get() = true

    override fun printMethodsForElement(element: Element) {
        val parentInVisitor = element.parentInVisitor ?: return
        printer.run {
            printVisitMethodDeclaration(
                element,
                hasDataParameter = false,
                override = true,
            )
            printBlock {
                println(parentInVisitor.visitFunctionName, "(", element.visitorParameterName, ")")
            }
            println()
        }
    }
}