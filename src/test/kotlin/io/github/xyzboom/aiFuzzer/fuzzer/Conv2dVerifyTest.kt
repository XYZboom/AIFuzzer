package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import io.github.xyzboom.aiFuzzer.translator.pytorch.PytorchTranslator
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import org.junit.jupiter.api.Test
import java.io.PrintStream

/**
 * 验证 CONV2D 算子的生成和翻译。
 */
class Conv2dVerifyTest {

    @Test
    fun `verify conv2d code generation`() {
        val out = PrintStream(System.out, true)

        // 使用只有 CONV2D 的算子列表，确保生成 CONV2D
        val gen = UirGenerator(GeneratorConfig(
            seed = 42,
            ops = listOf("conv2d"),
            minNodesPerGraph = 1,
            maxNodesPerGraph = 3,
            minInputs = 2,
            maxInputs = 4,
        ))
        val program = gen.generate()
        val graph = program.graphs[0]

        out.println("=== 生成的图 ===")
        out.println("输入: ${graph.inputs.size}")
        out.println("节点: ${graph.nodes.size}")

        for (node in graph.nodes) {
            out.println("\n节点: ${node.name}")
            out.println("  op: ${node.op}")
            out.println("  inputs: ${node.inputs.map { it.valueId }}")
            out.println("  outputs: ${node.outputs.map { it.valueId }}")
            out.println("  attributes: ${node.attributes}")
        }

        // TVM 翻译
        val tvmTranslator = TvmRelaxTranslator()
        val tvmCode = tvmTranslator.translate(program)
        out.println("\n=== TVM 代码 ===")
        out.println(tvmCode)

        // PyTorch 翻译
        val pytorchTranslator = PytorchTranslator()
        val pytorchCode = pytorchTranslator.translate(program)
        out.println("\n=== PyTorch 代码 ===")
        out.println(pytorchCode)

        // 验证
        assert(tvmCode.contains("conv2d") || graph.nodes.none { it.op == UirOpKind.CONV2D }) {
            "TVM 代码应包含 conv2d"
        }
        assert(pytorchCode.contains("conv2d") || graph.nodes.none { it.op == UirOpKind.CONV2D }) {
            "PyTorch 代码应包含 conv2d"
        }
    }
}