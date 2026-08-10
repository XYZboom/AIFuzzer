package io.github.xyzboom.aiFuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.ir.UirOpKind

/**
 * 验证策略b（常量+形状保持链）是否生效。
 */
object VerifyFallbackStrategy {
    @JvmStatic
    fun main(args: Array<String>) {
        // 强制 100% 走策略b，且只用需要精确 4D 输入的算子
        // 同时设置 minNdim=5 让初始输入 >4D，强制触发适配
        val gen = UirGenerator(GeneratorConfig(
            seed = 42,
            graphCount = 1..1,
            minNodesPerGraph = 1,
            maxNodesPerGraph = 3,
            ops = listOf("CONV2D", "MAX_POOL2D", "AVG_POOL2D"),
            fallbackConstProbability = 1.0,
            shapePreservingChainRange = 0..3,
            minNdim = 5,  // 初始输入 5D，Pool2d 需要精确 4D → 强制触发适配
            maxNdim = 5,
        ))
        val program = gen.generate()
        val graph = program.graphs.first()

        var fallbackConst = 0
        var shapePreservingNodes = 0
        val ops = mutableMapOf<UirOpKind, Int>()

        for (node in graph.nodes) {
            ops[node.op] = (ops[node.op] ?: 0) + 1
            if (node.name.startsWith("fallback_const")) fallbackConst++
            if (node.name.startsWith("fallback_")) shapePreservingNodes++
        }

        println("=== 策略b 验证 (fallbackConstProbability=1.0) ===")
        println("总节点数: ${graph.nodes.size}")
        println("fallback_const 节点: $fallbackConst")
        println("形状保持链节点: ${shapePreservingNodes - fallbackConst}")
        println()
        println("算子分布:")
        for ((op, cnt) in ops.entries.sortedByDescending { it.value }) {
            println("  $op: $cnt")
        }
        println()
        if (fallbackConst > 0) {
            println("✓ 策略b 生效：生成了常量 fallback_const 节点")
        } else {
            println("✗ 策略b 未触发！")
        }
    }
}