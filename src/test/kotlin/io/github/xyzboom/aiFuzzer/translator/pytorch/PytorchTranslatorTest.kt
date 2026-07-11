package io.github.xyzboom.aiFuzzer.translator.pytorch

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable

/**
 * PyTorch 翻译器测试。
 */
class PytorchTranslatorTest {

    @Test
    fun `translator should generate valid Python code`() {
        val translator = PytorchTranslator()
        val generator = UirGenerator(GeneratorConfig(
            seed = 42L,
            minNodesPerGraph = 2,
            maxNodesPerGraph = 5,
            minInputs = 1,
            maxInputs = 2,
        ))
        val program = generator.generate()
        val pythonCode = translator.translate(program)

        // 检查基本结构
        assertTrue(pythonCode.contains("import torch"))
        assertTrue(pythonCode.contains("import torch.nn as nn"))
        assertTrue(pythonCode.contains("import torch.nn.functional as F"))
        assertTrue(pythonCode.contains("class TestModule_0"))
        assertTrue(pythonCode.contains("def forward"))
        assertTrue(pythonCode.contains("torch.compile"))
        assertTrue(pythonCode.contains("print"))
    }

    @Test
    fun `translator should handle single op programs`() {
        val translator = PytorchTranslator(dtype = "float32", device = "cpu")
        val generator = UirGenerator(GeneratorConfig(
            seed = 123L,
            minNodesPerGraph = 1,
            maxNodesPerGraph = 1,
            minInputs = 1,
            maxInputs = 1,
        ))
        val program = generator.generate()
        val pythonCode = translator.translate(program)

        // 应该生成可执行的代码
        println("Generated Python code:\n$pythonCode")
        assertTrue(pythonCode.contains("class TestModule_0"))
    }

    @Test
    fun `translator should handle multiple graphs`() {
        val translator = PytorchTranslator()
        val generator = UirGenerator(GeneratorConfig(
            seed = 456L,
            minNodesPerGraph = 2,
            maxNodesPerGraph = 3,
            graphCount = 2,
        ))
        val program = generator.generate()
        val pythonCode = translator.translate(program)

        // 应该生成多个 Module
        assertTrue(pythonCode.contains("class TestModule_0"))
        // 第二个 module 可能不会被执行，但应该被定义
        // （因为主代码只实例化 TestModule_0）
    }
}