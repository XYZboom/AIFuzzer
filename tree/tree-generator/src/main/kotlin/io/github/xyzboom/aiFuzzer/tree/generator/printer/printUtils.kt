package io.github.xyzboom.aiFuzzer.tree.generator.printer

import io.github.xyzboom.aiFuzzer.tree.generator.irTransformerType
import io.github.xyzboom.aiFuzzer.tree.generator.model.Field
import io.github.xyzboom.aiFuzzer.tree.generator.model.ListField
import io.github.xyzboom.aiFuzzer.tree.generator.model.SimpleField
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.generators.tree.*
import org.jetbrains.kotlin.generators.tree.printer.FunctionParameter
import org.jetbrains.kotlin.generators.tree.printer.ImportCollectingPrinter
import org.jetbrains.kotlin.generators.tree.printer.printFunctionDeclaration
import org.jetbrains.kotlin.utils.IndentingPrinter

fun ImportCollectingPrinter.transformFunctionDeclaration(
    field: Field,
    returnType: TypeRef,
    override: Boolean,
    implementationKind: ImplementationKind,
) {
    transformFunctionDeclaration(field.name.replaceFirstChar(Char::uppercaseChar), returnType, override, implementationKind)
}

fun ImportCollectingPrinter.transformOtherChildrenFunctionDeclaration(
    element: TypeRef,
    override: Boolean,
    implementationKind: ImplementationKind,
) {
    transformFunctionDeclaration("OtherChildren", element, override, implementationKind)
}

private fun ImportCollectingPrinter.transformFunctionDeclaration(
    transformName: String,
    returnType: TypeRef,
    override: Boolean,
    implementationKind: ImplementationKind,
) {
    val dataTP = TypeVariable("D")
    printFunctionDeclaration(
        name = "transform$transformName",
        parameters = listOf(
            FunctionParameter("transformer", irTransformerType.withArgs(dataTP)),
            FunctionParameter("data", dataTP),
        ),
        returnType = returnType,
        typeParameters = listOf(dataTP),
        modality = Modality.ABSTRACT.takeIf {
            implementationKind == ImplementationKind.AbstractClass || implementationKind == ImplementationKind.SealedClass
        },
        override = override,
    )
}

fun ImportCollectingPrinter.replaceFunctionDeclaration(
    field: Field,
    override: Boolean,
    implementationKind: ImplementationKind,
    overriddenType: TypeRefWithNullability? = null,
    forceNullable: Boolean = false,
) {
    val capName = field.name.replaceFirstChar(Char::uppercaseChar)
    val type = overriddenType ?: field.typeRef
    val typeWithNullable = if (forceNullable) type.copy(nullable = true) else type

    printFunctionDeclaration(
        name = "replace$capName",
        parameters = listOf(FunctionParameter("new$capName", typeWithNullable)),
        returnType = StandardTypes.unit,
        modality = Modality.ABSTRACT.takeIf {
            implementationKind == ImplementationKind.AbstractClass || implementationKind == ImplementationKind.SealedClass
        },
        override = override,
        optInAnnotation = field.replaceOptInAnnotation,
    )
}

fun Field.getMutableType(forBuilder: Boolean = false): TypeRefWithNullability = when (this) {
    is ListField -> when {
        forBuilder -> StandardTypes.mutableList
        !isMutable -> StandardTypes.list
        else -> StandardTypes.mutableList
    }.withArgs(baseType).copy(nullable)
    else -> typeRef
}

inline fun IndentingPrinter.withIndent(block: () -> Unit) {
    pushIndent()
    block()
    popIndent()
}