package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * 临时审计脚本 — 生成多个程序，检查关键 op 的语义完整性。
 */
class SemanticAuditTest {

    data class AuditResult(
        val totalGraphs: Int,
        val totalNodes: Int,
        val stridedSliceCount: Int,
        val stridedSliceWithBegin: Int,
        val splitCount: Int,
        val splitWithSplits: Int,
        val gatherCount: Int,
        val gatherWithIndices: Int,
        val reshapeCount: Int,
        val reshapeWithTarget: Int,
        val transposeCount: Int,
        val transposeWithAxes: Int,
        val castCount: Int,
        val concatCount: Int,
        val conv2dCount: Int,
    )

    fun audit(nRuns: Int = 100): AuditResult {
        var totalGraphs = 0
        var totalNodes = 0
        var stridedSliceCount = 0
        var stridedSliceWithBegin = 0
        var splitCount = 0
        var splitWithSplits = 0
        var gatherCount = 0
        var gatherWithIndices = 0
        var reshapeCount = 0
        var reshapeWithTarget = 0
        var transposeCount = 0
        var transposeWithAxes = 0
        var castCount = 0
        var concatCount = 0
        var conv2dCount = 0

        val sampleStridedSlice = StringBuilder()
        val sampleSplit = StringBuilder()
        val sampleGather = StringBuilder()

        for (seed in 0L until nRuns) {
            val gen = UirGenerator(GeneratorConfig(
                seed = seed,
                minNodesPerGraph = 3,
                maxNodesPerGraph = 12,
                graphCount = 1..1,
            ))
            val program = gen.generate()
            totalGraphs += program.graphs.size
            for (graph in program.graphs) {
                for (node in graph.nodes) {
                    totalNodes++
                    val attrs = node.attributes
                    when (node.op) {
                        UirOpKind.STRIDED_SLICE -> {
                            stridedSliceCount++
                            if (attrs.containsKey("begin")) stridedSliceWithBegin++
                            if (sampleStridedSlice.isEmpty()) {
                                sampleStridedSlice.append("STRIDED_SLICE attrs: ${attrs.keys}\n")
                                sampleStridedSlice.append("  inputs: ${node.inputs.map { "${it.valueId}: dims=${it.type.shape.dims.size}" }}\n")
                            }
                        }
                        UirOpKind.SPLIT -> {
                            splitCount++
                            if (attrs.containsKey("indices_or_sections") || attrs.containsKey("splits")) splitWithSplits++
                            if (sampleSplit.isEmpty()) {
                                sampleSplit.append("SPLIT attrs: ${attrs.keys}\n")
                                sampleSplit.append("  outputs=${node.outputs.size}\n")
                            }
                        }
                        UirOpKind.GATHER -> {
                            gatherCount++
                            if (attrs.containsKey("indices")) gatherWithIndices++
                            if (sampleGather.isEmpty()) {
                                sampleGather.append("GATHER attrs: ${attrs.keys}\n")
                                sampleGather.append("  inputs=${node.inputs.size}\n")
                            }
                        }
                        UirOpKind.RESHAPE -> {
                            reshapeCount++
                            if (attrs.containsKey("shape") || attrs.containsKey("target_shape")) reshapeWithTarget++
                        }
                        UirOpKind.TRANSPOSE -> {
                            transposeCount++
                            if (attrs.containsKey("axes")) transposeWithAxes++
                        }
                        UirOpKind.CAST -> castCount++
                        UirOpKind.CONCAT -> concatCount++
                        UirOpKind.CONV2D -> conv2dCount++
                        else -> {}
                    }
                }
            }
        }

        println("=== AUDIT: ${nRuns} runs ===")
        println("Graphs: $totalGraphs, Nodes: $totalNodes")
        println()
        println("--- P0 candidates ---")
        println("STRIDED_SLICE: $stridedSliceCount total, $stridedSliceWithBegin with begin (${pct(stridedSliceWithBegin, stridedSliceCount)})")
        println("SPLIT:         $splitCount total, $splitWithSplits with splits (${pct(splitWithSplits, splitCount)})")
        println("GATHER:        $gatherCount total, $gatherWithIndices with indices (${pct(gatherWithIndices, gatherCount)})")
        println()
        println("--- P1 candidates ---")
        println("RESHAPE:       $reshapeCount total, $reshapeWithTarget with target (${pct(reshapeWithTarget, reshapeCount)})")
        println("TRANSPOSE:     $transposeCount total, $transposeWithAxes with axes (${pct(transposeWithAxes, transposeCount)})")
        println("CAST:          $castCount total")
        println()
        println("--- Operation distribution ---")
        println("CONCAT: $concatCount, CONV2D: $conv2dCount")

        // Print sample if empty attrs
        if (stridedSliceWithBegin == 0 && stridedSliceCount > 0) {
            println("\n--- Sample STRIDED_SLICE ---")
            println(sampleStridedSlice)
        }
        if (splitWithSplits == 0 && splitCount > 0) {
            println("\n--- Sample SPLIT ---")
            println(sampleSplit)
        }
        if (gatherWithIndices == 0 && gatherCount > 0) {
            println("\n--- Sample GATHER ---")
            println(sampleGather)
        }

        return AuditResult(
            totalGraphs, totalNodes,
            stridedSliceCount, stridedSliceWithBegin,
            splitCount, splitWithSplits,
            gatherCount, gatherWithIndices,
            reshapeCount, reshapeWithTarget,
            transposeCount, transposeWithAxes,
            castCount, concatCount, conv2dCount,
        )
    }

    private fun pct(n: Int, d: Int): String = if (d == 0) "N/A" else "${(n * 100.0 / d).toInt()}%"

    @Test
    fun `audit generated programs`() {
        val result = audit(100)
        // No assertions — informational only
    }
}
