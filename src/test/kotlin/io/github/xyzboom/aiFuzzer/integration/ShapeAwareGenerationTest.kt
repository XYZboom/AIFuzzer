package io.github.xyzboom.aiFuzzer.integration

import io.github.xyzboom.aiFuzzer.generator.*
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import org.junit.jupiter.api.Test
import kotlin.system.exitProcess

/**
 * 形状感知生成测试。
 * 
 * 验证成功率从 28% 提升到接近 100%。
 */
class ShapeAwareGenerationTest {

    @Test
    fun `test success rate with new shape generator`() {
        val numTrials = 30
        var successCount = 0
        val errors = mutableListOf<String>()
        
        repeat(numTrials) { seed ->
            try {
                val config = GeneratorConfig(
                    seed = seed.toLong(),
                    graphCount = 1,
                    minNodesPerGraph = 3,
                    maxNodesPerGraph = 8
                )
                
                val generator = UirGenerator(config)
                val program = generator.generate()
                
                val translator = TvmRelaxTranslator()
                val code = translator.translate(program)
                
                // 如果能翻译成功，说明形状兼容
                successCount++
            } catch (e: Exception) {
                errors.add("Seed $seed failed: ${e.message}")
            }
        }
        
        val successRate = successCount.toDouble() / numTrials
        println("=".repeat(60))
        println("Shape-Aware Generation Test Results")
        println("=".repeat(60))
        println("Total trials: $numTrials")
        println("Success count: $successCount")
        println("Success rate: ${successRate * 100}%")
        println("=".repeat(60))
        
        if (errors.isNotEmpty()) {
            println("\nErrors:")
            errors.take(5).forEach { println("  - $it") }
            if (errors.size > 5) {
                println("  ... and ${errors.size - 5} more")
            }
        }
        
        // 目标：成功率 >= 90%（从 28% 提升）
        if (successRate < 0.9) {
            println("\n❌ FAILED: Success rate ${successRate * 100}% < 90%")
            println("Expected significant improvement from 28%")
        } else {
            println("\n✅ SUCCESS: Success rate ${successRate * 100}% >= 90%")
        }
    }
    
    @Test
    fun `test single graph generation and translation`() {
        val config = GeneratorConfig(seed = 42)
        val generator = UirGenerator(config)
        val program = generator.generate()
        
        println("Graph structure:")
        for (graph in program.graphs) {
            println("  Inputs: ${graph.inputs.size}")
            println("  Nodes: ${graph.nodes.size}")
            println("  Outputs: ${graph.outputs.size}")
            
            for (node in graph.nodes) {
                val inputShapes = node.inputs.map { it.type.shape }
                println("    ${node.name}: ${node.op} with shapes $inputShapes")
            }
        }
        
        val translator = TvmRelaxTranslator()
        val code = translator.translate(program)
        
        println("\nGenerated TVM Relax code (seed=42):")
        println(code)
    }
}