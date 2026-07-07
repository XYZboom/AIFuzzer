package io.github.xyzboom.aiFuzzer.tree.generator

import io.github.xyzboom.aiFuzzer.tree.generator.model.Element
import io.github.xyzboom.aiFuzzer.tree.generator.model.Field
import io.github.xyzboom.aiFuzzer.tree.generator.model.ListField
import io.github.xyzboom.aiFuzzer.tree.generator.model.SimpleField
import io.github.xyzboom.aiFuzzer.tree.generator.attrKindType
import io.github.xyzboom.aiFuzzer.tree.generator.blockKindType
import io.github.xyzboom.aiFuzzer.tree.generator.dimKindType
import io.github.xyzboom.aiFuzzer.tree.generator.generatedType
import io.github.xyzboom.aiFuzzer.tree.generator.typeKindType
import org.jetbrains.kotlin.generators.tree.ImplementationKind
import org.jetbrains.kotlin.generators.tree.StandardTypes
import org.jetbrains.kotlin.generators.tree.TypeRef
import org.jetbrains.kotlin.generators.tree.TypeRefWithNullability
import org.jetbrains.kotlin.generators.tree.config.AbstractElementConfigurator
import org.jetbrains.kotlin.generators.tree.withArgs

object TreeBuilder : AbstractElementConfigurator<Element, Field, Element.Kind>() {
    override val rootElement: Element by element(Element.Kind.Other, name = "Element") {
        hasAcceptChildrenMethod = true
        hasTransformChildrenMethod = true
    }

    val program: Element by element(Element.Kind.Other, name = "Program") {
        kind = ImplementationKind.Interface
        +listField("graphs", graph)
        +field("metadata", metadataMap, isChild = false)
    }

    val graph: Element by element(Element.Kind.Other, name = "Graph") {
        parent(namedElement)
        +listField("nodes", node)
        +listField("inputs", valueRef)
        +listField("outputs", valueRef)
    }

    val node: Element by element(Element.Kind.Other, name = "Node") {
        parent(namedElement)
        +field("op", StandardTypes.string, isChild = false)
        +listField("inputs", valueRef)
        +listField("outputs", valueRef)
        +field("attributes", attributeMap, isChild = false)
    }

    val valueRef: Element by element(Element.Kind.Other, name = "ValueRef") {
        +field("valueId", StandardTypes.string, isChild = false)
        +field("ndim", StandardTypes.int, isChild = false, isMutable = true) {
            defaultValueInBuilder = "1"
        }
    }

    val namedElement: Element by element(Element.Kind.Other, name = "NamedElement") {
        kind = ImplementationKind.Interface
        +field("name", StandardTypes.string, isChild = false)
    }

    // 类型系统（简化）
    val type: Element by element(Element.Kind.Type, name = "Type") {
        +field("typeKind", typeKindType, withReplace = false, withTransform = false,
            isChild = false, isMutable = false)
    }

    val tensorType: Element by element(Element.Kind.Type, name = "TensorType") {
        parent(type)
        +field("shape", shape, isChild = false)
        +field("dtype", dataType, isChild = false)
    }

    val shape: Element by element(Element.Kind.Type, name = "Shape") {
        +listField("dims", dim)
    }

    val dim: Element by element(Element.Kind.Type, name = "Dim") {
        +field("dimKind", dimKindType, isChild = false)
        +field("value", StandardTypes.int, nullable = true, isChild = false)
    }

    val dataType: Element by element(Element.Kind.Type, name = "DataType") {
        +field("name", StandardTypes.string, isChild = false)
        +field("bits", StandardTypes.int, isChild = false)
    }

    // 属性
    val attribute: Element by element(Element.Kind.Type, name = "Attribute") {
        +field("attrKind", attrKindType, withReplace = false, withTransform = false,
            isChild = false, isMutable = false)
    }

    val intAttr: Element by element(Element.Kind.Type, name = "IntAttr") {
        parent(attribute)
        +field("value", StandardTypes.int, isChild = false)
    }

    val stringAttr: Element by element(Element.Kind.Type, name = "StringAttr") {
        parent(attribute)
        +field("value", StandardTypes.string, isChild = false)
    }

    // 辅助类型
    val metadataMap = org.jetbrains.kotlin.generators.tree.type("kotlin.collections", "MutableMap")
        .withArgs(StandardTypes.string, StandardTypes.string)

    val attributeMap = org.jetbrains.kotlin.generators.tree.type("kotlin.collections", "MutableMap")
        .withArgs(StandardTypes.string, generatedType("Attribute"))

    fun field(
        name: String,
        type: TypeRefWithNullability,
        nullable: Boolean = false,
        isMutable: Boolean = true,
        withReplace: Boolean = false,
        withTransform: Boolean = false,
        isChild: Boolean = true,
        initializer: SimpleField.() -> Unit = {},
    ): SimpleField {
        return SimpleField(
            name,
            type.copy(nullable),
            isChild = isChild,
            isMutable = isMutable,
            withReplace = withReplace,
            withTransform = withTransform
        ).apply(initializer).also {
            if (name == "ndim") {
                println("DEBUG: ndim defaultValueInBuilder = ${it.defaultValueInBuilder}")
            }
        }
    }

    fun listField(
        name: String,
        baseType: TypeRef,
        withReplace: Boolean = false,
        withTransform: Boolean = false,
        useMutableOrEmpty: Boolean = true,
        isChild: Boolean = true,
        initializer: ListField.() -> Unit = {},
    ): Field {
        return ListField(
            name,
            baseType,
            withReplace = withReplace,
            isChild = isChild,
            isMutableOrEmptyList = useMutableOrEmpty,
            withTransform = withTransform,
        ).apply(initializer)
    }

    override fun createElement(
        name: String,
        propertyName: String,
        category: Element.Kind
    ): Element {
        return Element(name, propertyName, category)
    }
}