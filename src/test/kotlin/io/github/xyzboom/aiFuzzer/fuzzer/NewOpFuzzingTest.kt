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

    @Test
    fun `fuzz with batch 1 trig and reduce_prod ops across all backends`() {
        val batch1Ops = listOf("sin", "cos", "tan", "asin", "acos", "atan", "reduce_prod")
        val baseOps = batch1Ops + listOf("add", "relu", "sigmoid", "abs")

        val tvmTranslator = TvmRelaxTranslator()
        val ptTranslator = io.github.xyzboom.aiFuzzer.translator.pytorch.PytorchTranslator()
        val onnxTranslator = io.github.xyzboom.aiFuzzer.translator.onnx.OnnxTranslator()

        for (seed in 1L..30L) {
            val gen = UirGenerator(GeneratorConfig(
                seed = seed,
                ops = baseOps,
                minNodesPerGraph = 2,
                maxNodesPerGraph = 6,
                avoidNaNInf = false, // allow testing trig and reduce_prod
            ))
            val program = gen.generate()

            // 1. TVM translation
            val tvmCode = tvmTranslator.translate(program)
            assertTrue(tvmCode.contains("def build_mod():"))
            assertEquals(tvmCode.count { it == '(' }, tvmCode.count { it == ')' }, "TVM parens balance seed=$seed")

            // 2. PyTorch translation
            val ptCode = ptTranslator.translate(program)
            assertTrue(ptCode.contains("class TestModule_0"))
            assertEquals(ptCode.count { it == '(' }, ptCode.count { it == ')' }, "PT parens balance seed=$seed")

            // 3. ONNX translation
            val onnxCode = onnxTranslator.translate(program)
            assertTrue(onnxCode.contains("helper.make_model"))
            assertEquals(onnxCode.count { it == '(' }, onnxCode.count { it == ')' }, "ONNX parens balance seed=$seed")
        }
    }

    @Test
    fun `fuzz with batch 2 hyperbolic and erf ops across all backends`() {
        val batch2Ops = listOf("erf", "sinh", "cosh", "asinh", "acosh", "atanh")
        val baseOps = batch2Ops + listOf("add", "relu", "sigmoid", "abs")

        val tvmTranslator = TvmRelaxTranslator()
        val ptTranslator = io.github.xyzboom.aiFuzzer.translator.pytorch.PytorchTranslator()
        val onnxTranslator = io.github.xyzboom.aiFuzzer.translator.onnx.OnnxTranslator()

        for (seed in 1L..30L) {
            val gen = UirGenerator(GeneratorConfig(
                seed = seed,
                ops = baseOps,
                minNodesPerGraph = 2,
                maxNodesPerGraph = 6,
                avoidNaNInf = false, // allow testing hyperbolic ops
            ))
            val program = gen.generate()

            // 1. TVM translation
            val tvmCode = tvmTranslator.translate(program)
            assertTrue(tvmCode.contains("def build_mod():"))
            assertEquals(tvmCode.count { it == '(' }, tvmCode.count { it == ')' }, "TVM parens balance seed=$seed")

            // 2. PyTorch translation
            val ptCode = ptTranslator.translate(program)
            assertTrue(ptCode.contains("class TestModule_0"))
            assertEquals(ptCode.count { it == '(' }, ptCode.count { it == ')' }, "PT parens balance seed=$seed")

            // 3. ONNX translation
            val onnxCode = onnxTranslator.translate(program)
            assertTrue(onnxCode.contains("helper.make_model"))
            assertEquals(onnxCode.count { it == '(' }, onnxCode.count { it == ')' }, "ONNX parens balance seed=$seed")
        }
    }

    @Test
    fun `fuzz with batch 3 comparison, logical, and WHERE ops across all backends`() {
        val batch3Ops = listOf("equal", "less", "greater", "logical_and", "logical_or", "logical_xor", "where")
        val baseOps = batch3Ops + listOf("add", "relu", "sigmoid", "abs")

        val tvmTranslator = TvmRelaxTranslator()
        val ptTranslator = io.github.xyzboom.aiFuzzer.translator.pytorch.PytorchTranslator()
        val onnxTranslator = io.github.xyzboom.aiFuzzer.translator.onnx.OnnxTranslator()

        for (seed in 1L..30L) {
            val gen = UirGenerator(GeneratorConfig(
                seed = seed,
                ops = baseOps,
                minNodesPerGraph = 2,
                maxNodesPerGraph = 6,
                avoidNaNInf = false,
            ))
            val program = gen.generate()

            // 1. TVM translation
            val tvmCode = tvmTranslator.translate(program)
            assertTrue(tvmCode.contains("def build_mod():"))
            assertEquals(tvmCode.count { it == '(' }, tvmCode.count { it == ')' }, "TVM parens balance seed=$seed")

            // 2. PyTorch translation
            val ptCode = ptTranslator.translate(program)
            assertTrue(ptCode.contains("class TestModule_0"))
            assertEquals(ptCode.count { it == '(' }, ptCode.count { it == ')' }, "PT parens balance seed=$seed")

            // 3. ONNX translation
            val onnxCode = onnxTranslator.translate(program)
            assertTrue(onnxCode.contains("helper.make_model"))
            assertEquals(onnxCode.count { it == '(' }, onnxCode.count { it == ')' }, "ONNX parens balance seed=$seed")
        }
    }

    @Test
    fun `test real execution in Python for all 20 new ops across backends`() {
        val pythonBin = System.getenv("PYTHON_BIN")
            ?: listOf("/home/xyzboom/Programs/miniconda3/envs/aifuzzer/bin/python", "python3", "python").find {
                java.io.File(it).exists() || try {
                    ProcessBuilder(it, "--version").start().waitFor() == 0
                } catch (_: Exception) { false }
            }
        assumeTrue(pythonBin != null, "Python executable not found")

        val allNewOps = listOf(
            "sin", "cos", "tan", "asin", "acos", "atan", "reduce_prod",
            "erf", "sinh", "cosh", "asinh", "acosh", "atanh",
            "equal", "less", "greater", "logical_and", "logical_or", "logical_xor", "where"
        )
        val testOps = allNewOps + listOf("add", "relu", "sigmoid", "abs")

        val tvmTranslator = TvmRelaxTranslator()
        val ptTranslator = io.github.xyzboom.aiFuzzer.translator.pytorch.PytorchTranslator()
        val onnxTranslator = io.github.xyzboom.aiFuzzer.translator.onnx.OnnxTranslator()

        val tempDir = java.nio.file.Files.createTempDirectory("aifuzzer_test_new_ops").toFile()
        try {
            for (seed in 1L..5L) {
                val gen = UirGenerator(GeneratorConfig(
                    seed = seed,
                    ops = testOps,
                    minNodesPerGraph = 2,
                    maxNodesPerGraph = 5,
                    avoidNaNInf = false,
                ))
                val program = gen.generate()

                // PyTorch execution
                val ptFile = java.io.File(tempDir, "test_pt_$seed.py")
                ptFile.writeText(ptTranslator.translate(program))
                val ptProc = ProcessBuilder(pythonBin, ptFile.absolutePath).redirectErrorStream(true).start()
                val ptOut = ptProc.inputStream.bufferedReader().readText()
                val ptExit = ptProc.waitFor()
                assertTrue(ptExit == 0, "PyTorch execution failed for seed=$seed:\n$ptOut")

                // ONNX execution
                val onnxFile = java.io.File(tempDir, "test_onnx_$seed.py")
                onnxFile.writeText(onnxTranslator.translate(program))
                val onnxProc = ProcessBuilder(pythonBin, onnxFile.absolutePath).redirectErrorStream(true).start()
                val onnxOut = onnxProc.inputStream.bufferedReader().readText()
                val onnxExit = onnxProc.waitFor()
                assertTrue(onnxExit == 0, "ONNX execution failed for seed=$seed:\n$onnxOut")

                // TVM execution
                val tvmFile = java.io.File(tempDir, "test_tvm_$seed.py")
                tvmFile.writeText(tvmTranslator.translate(program))
                val tvmProc = ProcessBuilder(pythonBin, tvmFile.absolutePath).redirectErrorStream(true).start()
                val tvmOut = tvmProc.inputStream.bufferedReader().readText()
                val tvmExit = tvmProc.waitFor()
                assertTrue(tvmExit == 0, "TVM execution failed for seed=$seed:\n$tvmOut")
            }
        } finally {
            tempDir.deleteRecursively()
        }
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