package io.github.xyzboom.aiFuzzer.tree.generator

import io.github.xyzboom.aiFuzzer.tree.generator.model.Element
import io.github.xyzboom.aiFuzzer.tree.generator.model.Field
import io.github.xyzboom.aiFuzzer.tree.generator.model.Implementation
import org.jetbrains.kotlin.generators.tree.config.AbstractBuilderConfigurator

class BuilderConfigurator(model: org.jetbrains.kotlin.generators.tree.Model<Element>) :
    AbstractBuilderConfigurator<Element, Implementation, Field>(model) {

    override val namePrefix: String get() = "Uir"
    override val defaultBuilderPackage: String get() = "$BASE_PACKAGE.builder"

    override fun configureBuilders() = with(TreeBuilder) {
        builder(program) { }
        builder(graph) { }
        builder(node) { }
        builder(valueRef) { }
        builder(namedElement) { }
        builder(type) { }
        builder(tensorType) { }
        builder(shape) { }
        builder(dim) { }
        builder(dataType) { }
        builder(attribute) { }
        builder(intAttr) { }
        builder(stringAttr) { }
    }
}