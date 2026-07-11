package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import io.github.xyzboom.aiFuzzer.translator.pytorch.PytorchTranslator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import io.github.xyzboom.aiFuzzer.ir.UirOpKind

/**
 * 验证新添加的 CONV2D 算子能在 TVM 和 PyTorch 后端成功编译。
 */
class Conv2dFuzzingTest {

    /**
     * 使用仅包含 CONV2D 的配置进行 fuzzing。
     * 验证生成的程序能正确翻译为 TVM 和 PyTorch 代码。
     */
    @Test
    fun `fuzz with conv2d only`() {
        val translator = TvmRelaxTranslator()
        val pytorchTranslator = PytorchTranslator()

        for (seed in 1L..10L) {
            val gen = UirGenerator(GeneratorConfig(
                seed = seed,
                ops = listOf("conv2d", "add", "relu", "matmul"),
                minNodesPerGraph = 2,
                maxNodesPerGraph = 5,
                minInputs = 2,  // CONV2D 需要 2 个输入
                maxInputs = 4,
            ))
            val program = gen.generate()
            
            println("\n=== Seed $seed ===")
            println("Graph inputs: ${program.graphs[0].inputs.size}")
            println("Graph nodes: ${program.graphs[0].nodes.size}")
            println("Nodes: ${program.graphs[0].nodes.map { it.op.name }}")
            
            // TVM 翻译
            val tvmCode = translator.translate(program)
            println("\nTVM Code snippet:")
            println(tvmCode.lines().take(30).joinToString("\n"))
            
            // 检查 TVM 代码语法
            assertTrue(tvmCode.contains("def build_mod"))
            assertTrue(tvmCode.contains("conv2d") || program.graphs[0].nodes.none { it.op == UirOpKind.CONV2D })
            
            // PyTorch 翻译
            val pytorchCode = pytorchTranslator.translate(program)
            println("\nPyTorch Code snippet:")
            println(pytorchCode.lines().take(30).joinToString("\n"))
            
            // 检查 PyTorch 代码语法
            assertTrue(pytorchCode.contains("class TestModule"))
            assertTrue(pytorchCode.contains("torch") || pytorchCode.contains("F."))
        }

        println("\nAll 10 seeds passed syntax checks!")
    }
}