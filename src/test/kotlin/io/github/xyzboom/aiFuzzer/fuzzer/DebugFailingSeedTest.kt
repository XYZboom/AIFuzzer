package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import org.junit.jupiter.api.Test
import java.io.File

class DebugFailingSeedTest {

    @Test
    fun `save failing program seed 0 for manual inspection`() {
        val gen = UirGenerator(GeneratorConfig(
            seed = 0,
            minNodesPerGraph = 4,
            maxNodesPerGraph = 12,
            graphCount = 1..2,
            avoidNaNInf = false,
            avoidExtremeOps = false,
        ))
        val program = gen.generate()
        
        println("=== UIR Nodes ===")
        for (g in program.graphs) {
            println("Graph: ${g.name}")
            for ((i, n) in g.nodes.withIndex()) {
                println("  [$i] op=${n.op} attrs=${n.attributes}")
                println("       inputs: ${n.inputs.map { it.valueId }}")
                println("       outputs: ${n.outputs.map { "${it.valueId} shape=${it.type.shape.dims.map { d -> d.value?.toString() ?: "?" }}" }}")
            }
        }
        
        val translator = TvmRelaxTranslator(target = "llvm", device = "cpu")
        val code = translator.translate(program)
        File("/tmp/debug_seed_0.py").writeText(code)
        println("\nSaved to /tmp/debug_seed_0.py")
    }
}
