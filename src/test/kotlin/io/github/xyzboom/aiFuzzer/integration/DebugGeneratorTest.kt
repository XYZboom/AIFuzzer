package io.github.xyzboom.aiFuzzer.integration

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.ir.visitors.UirDefaultVisitor
import io.github.xyzboom.aiFuzzer.ir.*
import org.junit.jupiter.api.Test

class DebugGeneratorTest {

    @Test
    fun `debug generator input counts`() {
        val config = GeneratorConfig(
            seed = 10002,
            minNodesPerGraph = 3,
            maxNodesPerGraph = 5,
            graphCount = 1..1,
            minInputs = 1,
            maxInputs = 2,
        )
        val generator = UirGenerator(config)
        val program = generator.generate()

        val visitor = object : UirDefaultVisitor<Unit, Unit>() {
            override fun visitElement(element: UirElement, data: Unit) {
                element.acceptChildren(this, data)
            }
            override fun visitNode(node: UirNode, data: Unit) {
                println("  op=${node.op}, inputs=${node.inputs.size}")
                node.acceptChildren(this, data)
            }
        }
        program.accept(visitor, Unit)
    }
}