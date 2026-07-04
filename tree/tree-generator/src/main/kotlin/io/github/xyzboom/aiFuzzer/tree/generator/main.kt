package io.github.xyzboom.aiFuzzer.tree.generator

import io.github.xyzboom.aiFuzzer.tree.generator.model.Element
import io.github.xyzboom.aiFuzzer.tree.generator.model.Field
import io.github.xyzboom.aiFuzzer.tree.generator.printer.BuilderPrinter
import io.github.xyzboom.aiFuzzer.tree.generator.printer.DefaultVisitorVoidPrinter
import io.github.xyzboom.aiFuzzer.tree.generator.printer.ElementPrinter
import io.github.xyzboom.aiFuzzer.tree.generator.printer.ImplementationPrinter
import io.github.xyzboom.aiFuzzer.tree.generator.printer.TransformerPrinter
import io.github.xyzboom.aiFuzzer.tree.generator.printer.VisitorPrinter
import io.github.xyzboom.aiFuzzer.tree.generator.printer.VisitorVoidPrinter
import org.jetbrains.kotlin.generators.tree.InterfaceAndAbstractClassConfigurator
import org.jetbrains.kotlin.generators.tree.detectBaseTransformerTypes
import org.jetbrains.kotlin.generators.tree.printer.TreeGenerator
import java.io.File

fun main(args: Array<String>) {
    val model = TreeBuilder.build()
    val outputDir = if (args.isNotEmpty()) args[0] else "tree/gen"
    TreeGenerator(File(outputDir), "README.md").run {
        model.inheritFields()
        detectBaseTransformerTypes(model)

        ImplConfigurator.configureImplementations(model)
        val implementations = model.elements.flatMap { it.implementations }
        InterfaceAndAbstractClassConfigurator((model.elements + implementations))
            .configureInterfacesAndAbstractClasses()
        model.addPureAbstractElement(pureAbstractElementType)

        val builderConfigurator = BuilderConfigurator(model)
        builderConfigurator.configureBuilders()

        printElements(model, ::ElementPrinter)
        printElementImplementations(implementations, ::ImplementationPrinter)
        printElementBuilders(
            implementations.mapNotNull { it.builder },
            ::BuilderPrinter
        )
        printVisitors(
            model,
            listOf(
                irVisitorType to { p, t -> VisitorPrinter(p, t, false) },
                irDefaultVisitorType to { p, t -> VisitorPrinter(p, t, true) },
                irVisitorVoidType to { p, t -> VisitorVoidPrinter(p, t) },
                irDefaultVisitorVoidType to { p, t -> DefaultVisitorVoidPrinter(p, t) },
                irTransformerType to { p, t -> TransformerPrinter(p, t, model.rootElement) },
            )
        )
    }
}