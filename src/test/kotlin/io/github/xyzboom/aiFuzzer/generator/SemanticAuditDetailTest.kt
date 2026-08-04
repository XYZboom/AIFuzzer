package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Detailed IR dump — generates programs and prints serialized IR
 * for manual inspection of problematic nodes.
 */
class SemanticAuditDetailTest {

    /**
     * Run with specific seeds known to produce STRIDED_SLICE, GATHER, RESHAPE, TRANSPOSE
     * and dump their serialized IR.
     */
    @Test
    fun `dump programs with problematic ops`() {
        var stridedSliceFound = false
        var gatherFound = false
        var reshapeFound = false
        var transposeFound = false
        var splitFound = false

        for (seed in 0L until 500L) {
            if (stridedSliceFound && gatherFound && reshapeFound && transposeFound && splitFound) break
            
            val gen = UirGenerator(GeneratorConfig(
                seed = seed,
                minNodesPerGraph = 5,
                maxNodesPerGraph = 15,
                graphCount = 1..1,
                // Don't filter SPLIT/CONCAT out — let's force them
                ops = UirOpKind.entries.map { it.name },
                avoidNaNInf = false,
                avoidExtremeOps = false,
            ))
            val program = gen.generate()
            for (graph in program.graphs) {
                val hasStridedSlice = graph.nodes.any { it.op == UirOpKind.STRIDED_SLICE }
                val hasGather = graph.nodes.any { it.op == UirOpKind.GATHER }
                val hasReshape = graph.nodes.any { it.op == UirOpKind.RESHAPE && graph.nodes.any { n -> n.inputs.any { i -> graph.inputs.none { gi -> gi.valueId == i.valueId } } } }
                val hasTranspose = graph.nodes.any { it.op == UirOpKind.TRANSPOSE }
                val hasSplit = graph.nodes.any { it.op == UirOpKind.SPLIT }

                if (hasStridedSlice && !stridedSliceFound) {
                    stridedSliceFound = true
                    println("=" .repeat(60))
                    println("SEED=$seed — STRIDED_SLICE found")
                    println("=" .repeat(60))
                    dumpProgram(program, UirOpKind.STRIDED_SLICE)
                }
                if (hasGather && !gatherFound) {
                    gatherFound = true
                    println("=" .repeat(60))
                    println("SEED=$seed — GATHER found")
                    println("=" .repeat(60))
                    dumpProgram(program, UirOpKind.GATHER)
                }
                if (hasTranspose && !transposeFound) {
                    transposeFound = true
                    println("=" .repeat(60))
                    println("SEED=$seed — TRANSPOSE found")
                    println("=" .repeat(60))
                    dumpProgram(program, UirOpKind.TRANSPOSE)
                }
                if (hasSplit && !splitFound) {
                    splitFound = true
                    println("=" .repeat(60))
                    println("SEED=$seed — SPLIT found")
                    println("=" .repeat(60))
                    dumpProgram(program, UirOpKind.SPLIT)
                }
            }
        }

        // Summary
        println()
        println("=== Summary ===")
        println("STRIDED_SLICE sample: ${if (stridedSliceFound) "✓" else "✗ NOT FOUND"}")
        println("GATHER sample:        ${if (gatherFound) "✓" else "✗ NOT FOUND"}")
        println("TRANSPOSE sample:     ${if (transposeFound) "✓" else "✗ NOT FOUND"}")
        println("SPLIT sample:         ${if (splitFound) "✓" else "✗ NOT FOUND"}")
    }

    private fun shapeString(shape: io.github.xyzboom.aiFuzzer.ir.types.UirShape): String {
        return shape.dims.joinToString(",", "[", "]") { dim ->
            dim.value?.toString() ?: "?"
        }
    }

    private fun dumpProgram(program: UirProgram, targetOp: UirOpKind) {
        // Find and print the target op node in detail
        for (graph in program.graphs) {
            for (node in graph.nodes) {
                if (node.op == targetOp) {
                    println()
                    println("--- Target node: ${node.name} (op=${node.op}) ---")
                    println("  attributes: ${node.attributes}")
                    println("  inputs: ${node.inputs.map { "${it.valueId} shape=${shapeString(it.type.shape)}" }}")
                    println("  outputs: ${node.outputs.map { "${it.valueId} shape=${shapeString(it.type.shape)}" }}")
                }
            }
        }
        // Print surrounding context (all nodes with their ops)
        println()
        println("All nodes in graph:")
        for ((gi, graph) in program.graphs.withIndex()) {
            println("--- Graph $gi: ${graph.name} (${graph.nodes.size} nodes) ---")
            println("  Inputs: ${graph.inputs.map { "${it.valueId} shape=${shapeString(it.type.shape)}" }}")
            for ((ni, node) in graph.nodes.withIndex()) {
                val marker = if (node.op == targetOp) " *** TARGET ***" else ""
                println("  [$ni] op=${node.op}${marker}")
                println("       attrs=${node.attributes}")
                println("       inputs: ${node.inputs.map { it.valueId }}")
                println("       outputs: ${node.outputs.map { it.valueId }}")
            }
            println("  Outputs: ${graph.outputs.map { it.valueId }}")
        }
        println()
    }

    @Test
    fun `force SPLIT and CONCAT generation`() {
        // SPLIT is in adapterOps, so default filter excludes it.
        // Let's see if ops override can include it.
        val gen = UirGenerator(GeneratorConfig(
            seed = 0,
            minNodesPerGraph = 8,
            maxNodesPerGraph = 20,
            graphCount = 1..1,
            ops = UirOpKind.entries.filter { it != UirOpKind.EXPAND_DIMS }.map { it.name },
            avoidNaNInf = false,
            avoidExtremeOps = false,
        ))

        var splitSeen = 0
        var concatSeen = 0
        var reshapeSeen = 0
        for (seed in 0L until 200L) {
            val gen2 = UirGenerator(GeneratorConfig(
                seed = seed,
                minNodesPerGraph = 8,
                maxNodesPerGraph = 20,
                graphCount = 1..1,
                ops = UirOpKind.entries.filter { it != UirOpKind.EXPAND_DIMS }.map { it.name },
                avoidNaNInf = false,
                avoidExtremeOps = false,
            ))
            val p = gen2.generate()
            for (g in p.graphs) {
                for (n in g.nodes) {
                    when (n.op) {
                        UirOpKind.SPLIT -> splitSeen++
                        UirOpKind.CONCAT -> concatSeen++
                        UirOpKind.RESHAPE -> reshapeSeen++
                        else -> {}
                    }
                }
            }
        }

        println("=== Force include test (200 runs, adapterOps unfiltered) ===")
        println("SPLIT:   $splitSeen")
        println("CONCAT:  $concatSeen")
        println("RESHAPE: $reshapeSeen")
        
        // Now find a seed with SPLIT and dump it
        var foundSplit = false
        for (seed in 0L until 1000L) {
            if (foundSplit) break
            val gen3 = UirGenerator(GeneratorConfig(
                seed = seed,
                minNodesPerGraph = 8,
                maxNodesPerGraph = 20,
                graphCount = 1..1,
                ops = UirOpKind.entries.filter { it != UirOpKind.EXPAND_DIMS }.map { it.name },
                avoidNaNInf = false,
                avoidExtremeOps = false,
            ))
            val p = gen3.generate()
            for (g in p.graphs) {
                for (n in g.nodes) {
                    if (n.op == UirOpKind.SPLIT) {
                        foundSplit = true
                        println()
                        println("=" .repeat(60))
                        println("SEED=$seed — SPLIT found (with adapterOps unfiltered)")
                        println("=" .repeat(60))
                        dumpProgram(p, UirOpKind.SPLIT)
                        break
                    }
                }
            }
        }
        if (!foundSplit) {
            println("SPLIT: not found in 1000 seeds even with adapterOps unfiltered")
        }
    }

    @Test
    fun `check STRIDED_SLICE attrs in detail`() {
        // Check what the 19 STRIDED_SLICE nodes that DON'T have 'begin' actually have
        var withBegin = 0
        var withoutBegin = 0
        val sampleNoBegin = StringBuilder()
        
        for (seed in 0L until 200L) {
            val gen = UirGenerator(GeneratorConfig(
                seed = seed,
                minNodesPerGraph = 5,
                maxNodesPerGraph = 15,
                graphCount = 1..1,
                avoidNaNInf = false,
                avoidExtremeOps = false,
            ))
            val p = gen.generate()
            for (g in p.graphs) {
                for (n in g.nodes) {
                    if (n.op == UirOpKind.STRIDED_SLICE) {
                        if (n.attributes.containsKey("begin")) {
                            withBegin++
                        } else {
                            withoutBegin++
                            if (sampleNoBegin.isEmpty()) {
                                sampleNoBegin.append("Seed=$seed, node=${n.name}\n")
                                sampleNoBegin.append("  attrs: ${n.attributes}\n")
                                sampleNoBegin.append("  inputs: ${n.inputs.map { "${it.valueId} ndim=${it.type.shape.dims.size}" }}\n")
                            }
                        }
                    }
                }
            }
        }
        
        println("STRIDED_SLICE with begin:   $withBegin")
        println("STRIDED_SLICE without begin: $withoutBegin")
        if (sampleNoBegin.isNotEmpty()) {
            println("\n--- Sample STRIDED_SLICE without begin ---")
            println(sampleNoBegin)
        }
    }
}
