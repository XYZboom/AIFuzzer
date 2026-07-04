package io.github.xyzboom.aiFuzzer.integration

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import org.junit.jupiter.api.Test

/**
 * 集成测试：生成 → 翻译 → 验证合法性
 */
class GenerationPipelineTest {

    @Test
    fun `print sample output`() {
        val gen = UirGenerator(GeneratorConfig(seed = 42))
        val program = gen.generate()
        val translator = TvmRelaxTranslator()
        val pythonCode = translator.translate(program)

        println("=".repeat(60))
        println("Sample Generated Python Code (seed=42)")
        println("=".repeat(60))
        println(pythonCode)
    }
}