package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.UirOpKind
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
        val validOps = UirOpKind.values().toSet()

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

    @Test
    fun `avoidExtremeOps should filter out extreme ops`() {
        // Run 100 seeds with avoidExtremeOps=true — should NEVER see extreme ops
        for (seed in 0L until 100L) {
            val gen = UirGenerator(GeneratorConfig(
                seed = seed,
                avoidNaNInf = false,  // isolate the extreme filter
                avoidExtremeOps = true,
            ))
            val program = gen.generate()
            for (graph in program.graphs) {
                for (node in graph.nodes) {
                    assertFalse(
                        node.op in UirGenerator.extremeOps,
                        "avoidExtremeOps=true but seed=$seed generated ${node.op}"
                    )
                }
            }
        }
    }

    @Test
    fun `avoidExtremeOps disabled should allow extreme ops`() {
        // With avoidExtremeOps=false, extreme ops should appear in at least 1 of 100 runs
        var foundExtreme = false
        for (seed in 0L until 100L) {
            val gen = UirGenerator(GeneratorConfig(
                seed = seed,
                avoidNaNInf = false,
                avoidExtremeOps = false,
            ))
            val program = gen.generate()
            for (graph in program.graphs) {
                for (node in graph.nodes) {
                    if (node.op in UirGenerator.extremeOps) {
                        foundExtreme = true
                        break
                    }
                }
                if (foundExtreme) break
            }
            if (foundExtreme) break
        }
        assertTrue(foundExtreme, "avoidExtremeOps=false but extreme ops never appeared in 100 seeds")
    }

    @Test
    fun `avoidExtremeOps and avoidNaNInf should work independently`() {
        // Both enabled: neither extreme nor NaN-inf ops should appear
        var foundExtreme = false
        var foundNanInf = false
        for (seed in 0L until 50L) {
            val gen = UirGenerator(GeneratorConfig(
                seed = seed,
                avoidNaNInf = true,
                avoidExtremeOps = true,
            ))
            val program = gen.generate()
            for (graph in program.graphs) {
                for (node in graph.nodes) {
                    if (node.op in UirGenerator.extremeOps) foundExtreme = true
                    if (node.op in UirGenerator.nanInfProneOps) foundNanInf = true
                }
            }
        }
        assertFalse(foundExtreme, "Both filters enabled but extreme ops appeared")
        assertFalse(foundNanInf, "Both filters enabled but nan-inf ops appeared")
    }
}