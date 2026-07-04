package io.github.xyzboom.aiFuzzer.generator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class UirGeneratorTest {

    @Test
    fun `generated program should have correct number of graphs`() {
        val gen = UirGenerator(GeneratorConfig(graphCount = 2, seed = 42))
        val program = gen.generate()
        assertEquals(2, program.graphs.size)
    }

    @Test
    fun `generated graph should have all value refs connected`() {
        val gen = UirGenerator(GeneratorConfig(seed = 42))
        val program = gen.generate()
        val graph = program.graphs.first()

        // 收集所有被引用的 valueId
        val referencedValues = mutableSetOf<String>()
        referencedValues.addAll(graph.inputs.map { it.valueId })
        graph.nodes.forEach { node ->
            referencedValues.addAll(node.inputs.map { it.valueId })
            referencedValues.addAll(node.outputs.map { it.valueId })
        }
        referencedValues.addAll(graph.outputs.map { it.valueId })

        // 每个引用要么是输入，要么是某个节点的输出
        val definedValues = mutableSetOf<String>()
        definedValues.addAll(graph.inputs.map { it.valueId })
        graph.nodes.forEach { node ->
            definedValues.addAll(node.outputs.map { it.valueId })
        }

        for (ref in referencedValues) {
            assertTrue(ref in definedValues, "Value '$ref' is referenced but never defined")
        }
    }

    @Test
    fun `generated value ids should be unique`() {
        val gen = UirGenerator(GeneratorConfig(seed = 123))
        val program = gen.generate()
        val graph = program.graphs.first()

        val allValueIds = mutableSetOf<String>()
        val inputs = graph.inputs.map { it.valueId }
        val outputs = graph.nodes.flatMap { it.outputs.map { it.valueId } }

        // 所有 valueId 不能重复
        for (id in inputs + outputs) {
            assertTrue(allValueIds.add(id), "Duplicate valueId: $id")
        }
    }

    @Test
    fun `generated nodes should have valid op names`() {
        val gen = UirGenerator(GeneratorConfig(seed = 99))
        val program = gen.generate()
        val graph = program.graphs.first()
        val validOps = GeneratorConfig().ops.toSet()

        for (node in graph.nodes) {
            assertTrue(node.op in validOps, "Unknown op: ${node.op}")
        }
    }

    @Test
    fun `generator should produce deterministic output with same seed`() {
        val gen1 = UirGenerator(GeneratorConfig(seed = 42))
        val program1 = gen1.generate()

        val gen2 = UirGenerator(GeneratorConfig(seed = 42))
        val program2 = gen2.generate()

        assertEquals(program1.graphs.size, program2.graphs.size)
        // 检查第一个 graph 的节点数
        assertEquals(
            program1.graphs.first().nodes.size,
            program2.graphs.first().nodes.size
        )
        // 检查第一个 graph 的 op
        assertEquals(
            program1.graphs.first().nodes.first().op,
            program2.graphs.first().nodes.first().op
        )
    }

    @Test
    fun `default generator should not crash with any seed`() {
        for (seed in 1L..50L) {
            val gen = UirGenerator(GeneratorConfig(seed = seed))
            val program = gen.generate()
            assertNotNull(program)
        }
    }
}