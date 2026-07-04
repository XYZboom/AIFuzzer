package io.github.xyzboom.aiFuzzer.tree.generator.printer

import io.github.xyzboom.aiFuzzer.tree.generator.irBuilderDslAnnotation
import io.github.xyzboom.aiFuzzer.tree.generator.irImplementationDetailType
import io.github.xyzboom.aiFuzzer.tree.generator.model.Element
import io.github.xyzboom.aiFuzzer.tree.generator.model.Field
import io.github.xyzboom.aiFuzzer.tree.generator.model.Implementation
import io.github.xyzboom.aiFuzzer.tree.generator.model.ListField
import org.jetbrains.kotlin.generators.tree.AbstractBuilderPrinter
import org.jetbrains.kotlin.generators.tree.ClassRef
import org.jetbrains.kotlin.generators.tree.printer.ImportCollectingPrinter

internal class BuilderPrinter(
    printer: ImportCollectingPrinter
) : AbstractBuilderPrinter<Element, Implementation, Field>(printer) {

    override val implementationDetailAnnotation: ClassRef<*>
        get() = irImplementationDetailType

    override val builderDslAnnotation: ClassRef<*>
        get() = irBuilderDslAnnotation

    override fun actualTypeOfField(field: Field) = field.getMutableType(true)

    override fun ImportCollectingPrinter.printFieldReferenceInImplementationConstructorCall(field: Field) {
        print(field.name)
        if (field is ListField && field.isMutableOrEmptyList) {
            // keep as is
        }
    }
}