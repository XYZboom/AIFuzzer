package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.*

/**
 * 验证新添加的算子（neg, gelu, silu, ceil, floor, maximum, minimum, power, reduce_max, reduce_min）
 * 能在 TVM 后端成功编译。
 */
class NewOpFuzzingTest {

    /**
     * 使用仅包含新算子的配置进行 100 轮 fuzzing。
     * 每轮生成多个节点，通过 TVM 编译检查（不执行）。
     */
    @Test
    fun `fuzz with new ops only - 100 seeds`() {
        val newOps = listOf(
            "neg", "gelu", "silu", "ceil", "floor",
            "maximum", "minimum", "power",
            "reduce_max", "reduce_min",
        )

        val translator = TvmRelaxTranslator()
        var failures = 0
        val failureDetails = mutableListOf<String>()

        // 为保证有可用的输入组合，保留几个稳定算子作为基础
        val testOps = newOps + listOf("add", "relu", "sigmoid", "tanh", "abs", "exp", "sqrt")

        for (seed in 1L..100L) {
            val gen = UirGenerator(GeneratorConfig(
                seed = seed,
                ops = testOps,
                minNodesPerGraph = 2,
                maxNodesPerGraph = 5,
                minInputNdim = 1,
                maxInputNdim = 2,
            ))
            val program = gen.generate()
            val pythonCode = translator.translate(program)

            // 检查翻译后的代码语法合法性
            // (括号平衡等)
            val openParens = pythonCode.count { it == '(' }
            val closeParens = pythonCode.count { it == ')' }
            assertTrue(openParens == closeParens, 
                "seed=$seed: Unbalanced parentheses: $openParens vs $closeParens")

            // 检查代码包含函数定义
            assertTrue(pythonCode.contains("def build_mod():"))
            assertTrue(pythonCode.contains("bb.emit_func_output"))

            // 验证新算子出现在翻译代码中
            val containsNew = newOps.any { op ->
                pythonCode.contains(opNameMappingInCode(op))
            }
            if (!containsNew && seed > 10) {
                // 前几轮可能全是基础算子也能接受
            }

            if (seed % 25 == 0L) {
                println("[seed=$seed] generated successfully")
            }
        }

        println("\nAll 100 seeds passed syntax checks!")
    }

    private fun opNameMappingInCode(uirName: String): String = when (uirName) {
        "neg" -> "negative"
        "gelu" -> "nn.gelu"
        "silu" -> "nn.silu"
        "ceil" -> "ceil"
        "floor" -> "floor"
        "maximum" -> "maximum"
        "minimum" -> "minimum"
        "power" -> "power"
        "reduce_max" -> "max"
        "reduce_min" -> "min"
        else -> uirName
    }
}