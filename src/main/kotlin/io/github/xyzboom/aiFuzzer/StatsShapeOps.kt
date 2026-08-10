package io.github.xyzboom.aiFuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.ir.UirOpKind

/**
 * 统计 aifuzzer 生成程序中 shape-only 算子的比例。
 */
object StatsShapeOps {
    @JvmStatic
    fun main(args: Array<String>) {
        // shape-only 算子（只变形状，不改变数值语义）
        val shapeOnlyOps = setOf(
            UirOpKind.RESHAPE,
            UirOpKind.TRANSPOSE,
            UirOpKind.SQUEEZE,
            UirOpKind.UNSQUEEZE,
            UirOpKind.CONCAT,
            UirOpKind.SPLIT,
            UirOpKind.STRIDED_SLICE,
            UirOpKind.BROADCAST_TO,
            UirOpKind.TILE,
            UirOpKind.EXPAND_DIMS,
            UirOpKind.CAST,
        )

        // adapter ops（ShapeAdapter 插入的适配算子）
        val adapterOps = setOf(
            UirOpKind.EXPAND_DIMS,
            UirOpKind.SQUEEZE,
            UirOpKind.BROADCAST_TO,
            UirOpKind.CONCAT,
            UirOpKind.SPLIT,
        )

        var totalGraphs = 0
        var totalNodes = 0
        var totalShapeOnly = 0
        var totalAdapter = 0
        var graphsWithShapeOnly = 0
        var graphsWithAdapter = 0

        repeat(2000) { i ->
            val gen = UirGenerator(GeneratorConfig(
                seed = i.toLong(),
                graphCount = 1..1,
                minNodesPerGraph = 3,
                maxNodesPerGraph = 6,
            ))
            val program = gen.generate()
            for (graph in program.graphs) {
                totalGraphs++
                var nShapeOnly = 0
                var nAdapter = 0
                for (node in graph.nodes) {
                    totalNodes++
                    if (node.op in shapeOnlyOps) {
                        nShapeOnly++
                        totalShapeOnly++
                    }
                    if (node.op in adapterOps) {
                        nAdapter++
                        totalAdapter++
                    }
                }
                if (nShapeOnly > 0) graphsWithShapeOnly++
                if (nAdapter > 0) graphsWithAdapter++
            }
        }

        println("=== aifuzzer 统计 ($totalGraphs graphs) ===")
        println("总计算算子数: $totalNodes")
        println("shape-only 算子数: $totalShapeOnly")
        println("shape-only 占比: ${"%.2f".format(totalShapeOnly.toDouble() / totalNodes * 100)}%  ($totalShapeOnly/$totalNodes)")
        println("含 shape-only 算子的图占比: ${"%.1f".format(graphsWithShapeOnly.toDouble() / totalGraphs * 100)}%  ($graphsWithShapeOnly/$totalGraphs)")
        println()
        println("adapter 算子数: $totalAdapter")
        println("adapter 占比: ${"%.2f".format(totalAdapter.toDouble() / totalNodes * 100)}%  ($totalAdapter/$totalNodes)")
        println("含 adapter 算子的图占比: ${"%.1f".format(graphsWithAdapter.toDouble() / totalGraphs * 100)}%  ($graphsWithAdapter/$totalGraphs)")
    }
}