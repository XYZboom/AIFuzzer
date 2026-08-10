package io.github.xyzboom.aiFuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator

/**
 * 生成 seed=32 的 UIR 程序，检查 SUB 节点的输入数量。
 */
object InspectSubInputs {
    @JvmStatic
    fun main(args: Array<String>) {
        val gen = UirGenerator(GeneratorConfig(
            seed = 32,
            graphCount = 1..1,
            minNodesPerGraph = 3,
            maxNodesPerGraph = 6,
            fallbackConstProbability = 0.3,
            shapePreservingChainRange = 0..3,
        ))
        val program = gen.generate()
        val graph = program.graphs.first()
        
        for (node in graph.nodes) {
            if (node.op == io.github.xyzboom.aiFuzzer.ir.UirOpKind.SUBTRACT) {
                println("=== SUB node '${node.name}' ===")
                println("输入数量: ${node.inputs.size}")
                node.inputs.forEachIndexed { i, ref ->
                    println("  input[$i]: valueId=${ref.valueId}")
                }
                println("输出: ${node.outputs.map { it.valueId }}")
                
                // 打印所有节点的输入
                println("\n=== 全部节点 ===")
                graph.nodes.forEach { n ->
                    println("  ${n.name}: op=${n.op}, inputs=${n.inputs.map { it.valueId }}, outputs=${n.outputs.map { it.valueId }}")
                }
            }
        }
    }
}