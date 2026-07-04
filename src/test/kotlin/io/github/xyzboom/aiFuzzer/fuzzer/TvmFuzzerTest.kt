package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class TvmFuzzerTest {

    @Test
    fun `fuzzer generate-translate should not crash`() {
        val generator = UirGenerator(GeneratorConfig(seed = 42, minNodesPerGraph = 2, maxNodesPerGraph = 5))
        val translator = TvmRelaxTranslator()
        val program = generator.generate()
        val pythonCode = translator.translate(program)
        assertFalse(pythonCode.isBlank())
        assertTrue(pythonCode.contains("import tvm"))
        assertTrue(pythonCode.contains("relax.op"))
    }

    @Test
    fun `generated python code should contain valid syntax patterns`() {
        val generator = UirGenerator(GeneratorConfig(seed = 42))
        val translator = TvmRelaxTranslator()
        val program = generator.generate()
        val pythonCode = translator.translate(program)

        // 检查基本模式
        assertTrue(pythonCode.contains("with bb.function"))
        assertTrue(pythonCode.contains("bb.emit_func_output"))
        assertTrue(pythonCode.contains("build_mod"))
        assertTrue(pythonCode.contains("def build_mod():"))
    }

    @Test
    fun `generated python should have balanced parentheses`() {
        val generator = UirGenerator(GeneratorConfig(seed = 42, minNodesPerGraph = 5, maxNodesPerGraph = 15))
        val translator = TvmRelaxTranslator()
        val program = generator.generate()
        val pythonCode = translator.translate(program)

        // 检查括号平衡
        val openParens = pythonCode.count { it == '(' }
        val closeParens = pythonCode.count { it == ')' }
        assertEquals(openParens, closeParens,
            "Unbalanced parentheses: $openParens vs $closeParens\n$pythonCode")
    }

    @Test
    fun `run batch on multiple seeds without TVM installed should still work for generate-translate`() {
        val generator = UirGenerator(GeneratorConfig(seed = 1, minNodesPerGraph = 2, maxNodesPerGraph = 5))
        val translator = TvmRelaxTranslator()

        for (s in 1L..20L) {
            val program = UirGenerator(GeneratorConfig(seed = s)).generate()
            val pythonCode = translator.translate(program)
            assertNotNull(pythonCode)
        }
    }

    @Test
    fun `generated python should not have duplicated value definitions`() {
        val generator = UirGenerator(GeneratorConfig(seed = 42))
        val translator = TvmRelaxTranslator()
        val program = generator.generate()
        val pythonCode = translator.translate(program)

        // 检查没有 Python 变量重复定义
        val lines = pythonCode.lines()
        val varAssignments = lines.map { it.trim() }
            .filter { it.contains("= bb.emit") }
            .map { it.substringBefore("=").trim() }

        // 如果复合赋值（多输出），需要分割
        val varNames = varAssignments.flatMap { it.split(",").map { v -> v.trim() } }
        val uniqueNames = varNames.toSet()

        assertEquals(varNames.size, uniqueNames.size,
            "Duplicate Python variable names: $varNames")
    }
}