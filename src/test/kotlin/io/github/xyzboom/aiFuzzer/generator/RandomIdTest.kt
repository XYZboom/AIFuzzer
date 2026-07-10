package io.github.xyzboom.aiFuzzer.generator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RandomIdTest {

    @Test
    fun `value ids should contain random suffix`() {
        val gen = UirGenerator(GeneratorConfig(seed = 42))
        val program = gen.generate()
        val graph = program.graphs.first()

        // 检查所有 valueId 都包含随机后缀（8个字符）
        for (input in graph.inputs) {
            println("Input valueId: ${input.valueId}")
            assertTrue(input.valueId.matches(Regex("v_\\d+_\\w{8}")), 
                "ValueId '${input.valueId}' should match pattern 'v_<number>_<8-char-random>'")
        }

        for (node in graph.nodes) {
            for (output in node.outputs) {
                println("Output valueId: ${output.valueId}")
                assertTrue(output.valueId.matches(Regex("v_\\d+_\\w{8}")),
                    "ValueId '${output.valueId}' should match pattern 'v_<number>_<8-char-random>'")
            }
        }
    }

    @Test
    fun `node names should contain random suffix`() {
        val gen = UirGenerator(GeneratorConfig(seed = 42))
        val program = gen.generate()
        val graph = program.graphs.first()

        // 检查所有节点名称都包含随机后缀
        for (node in graph.nodes) {
            println("Node name: ${node.name}")
            // 节点名称格式：<op>_<index>_<random> 或 <op>_<index>_<random>（转换节点）
            assertTrue(node.name.contains("_") && node.name.split("_").last().length == 8,
                "Node name '${node.name}' should contain 8-char random suffix")
        }
    }

    @Test
    fun `random suffixes should be unique within one generation`() {
        val gen = UirGenerator(GeneratorConfig(seed = 42))
        val program = gen.generate()
        val graph = program.graphs.first()

        val suffixes = mutableSetOf<String>()
        
        // 从 valueId 提取后缀
        for (input in graph.inputs) {
            val suffix = input.valueId.split("_").last()
            assertTrue(suffixes.add(suffix), "Suffix '$suffix' should be unique")
        }

        for (node in graph.nodes) {
            for (output in node.outputs) {
                val suffix = output.valueId.split("_").last()
                assertTrue(suffixes.add(suffix), "Suffix '$suffix' should be unique")
            }
        }
    }
}
