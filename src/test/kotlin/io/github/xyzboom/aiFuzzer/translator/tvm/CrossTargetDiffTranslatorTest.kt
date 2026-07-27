package io.github.xyzboom.aiFuzzer.translator.tvm

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * 跨目标差分测试的翻译器验证。
 * 验证 TvmRelaxTranslator(crossTargetDifferential=true) 生成的 Python 代码
 * 包含正确的 CPU/GPU 构建和执行逻辑。
 */
class CrossTargetDiffTranslatorTest {

    private val translator = TvmRelaxTranslator(crossTargetDifferential = true)

    private fun shapeOf(vararg dims: Int): UirShape = buildShape {
        dims.forEach { v ->
            this.dims.add(buildDim {
                dimKind = UirDimKind.CONSTANT
                value = v
            })
        }
    }

    private fun tensorType(vararg dims: Int): UirTensorType = buildTensorType {
        typeKind = UirTypeKind.TENSOR
        shape = shapeOf(*dims)
        dtype = buildDataType { name = "float32"; bits = 32 }
    }

    @Test
    fun `cross target diff mode generates cpu build code`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "main"
                inputs.add(buildValueRef { valueId = "x"; type = tensorType(16, 16) })
                outputs.add(buildValueRef { valueId = "y"; type = tensorType(16, 16) })
                nodes.add(buildNode {
                    name = "relu"; op = UirOpKind.RELU
                    inputs.add(buildValueRef { valueId = "x"; type = tensorType(16, 16) })
                    outputs.add(buildValueRef { valueId = "y"; type = tensorType(16, 16) })
                })
            })
        }
        val result = translator.translate(program)

        // 验证 CPU 构建和执行
        assertTrue(result.contains("ex_cpu = relax.build(mod, target=\"llvm\")"),
            "缺少 CPU build: $result")
        assertTrue(result.contains("vm_cpu = relax.VirtualMachine(ex_cpu, tvm.cpu())"),
            "缺少 CPU VM: $result")
        assertTrue(result.contains("tvm_result_cpu_0"),
            "缺少 CPU 结果变量: $result")
    }

    @Test
    fun `cross target diff mode generates gpu build code`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "main"
                inputs.add(buildValueRef { valueId = "x"; type = tensorType(16, 16) })
                outputs.add(buildValueRef { valueId = "y"; type = tensorType(16, 16) })
                nodes.add(buildNode {
                    name = "relu"; op = UirOpKind.RELU
                    inputs.add(buildValueRef { valueId = "x"; type = tensorType(16, 16) })
                    outputs.add(buildValueRef { valueId = "y"; type = tensorType(16, 16) })
                })
            })
        }
        val result = translator.translate(program)

        // 验证 GPU 构建和执行
        assertTrue(result.contains("ex_gpu = relax.build(mod, target=\"cuda\")"),
            "缺少 GPU build: $result")
        assertTrue(result.contains("vm_gpu = relax.VirtualMachine(ex_gpu, tvm.cuda())"),
            "缺少 GPU VM: $result")
        assertTrue(result.contains("tvm_result_gpu_0"),
            "缺少 GPU 结果变量: $result")
    }

    @Test
    fun `cross target diff mode generates comparison code`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "main"
                inputs.add(buildValueRef { valueId = "x"; type = tensorType(16, 16) })
                outputs.add(buildValueRef { valueId = "y"; type = tensorType(16, 16) })
                nodes.add(buildNode {
                    name = "relu"; op = UirOpKind.RELU
                    inputs.add(buildValueRef { valueId = "x"; type = tensorType(16, 16) })
                    outputs.add(buildValueRef { valueId = "y"; type = tensorType(16, 16) })
                })
            })
        }
        val result = translator.translate(program)

        // 验证比较逻辑
        assertTrue(result.contains("[DIFF-MISMATCH]"),
            "缺少 DIFF-MISMATCH: $result")
        assertTrue(result.contains("np.max(np.abs("),
            "缺少 max-abs 比较: $result")
        assertTrue(result.contains("1e-3"),
            "缺少 1e-3 阈值: $result")
    }

    @Test
    fun `normal mode does not contain cross target diff code`() {
        val normalTranslator = TvmRelaxTranslator(crossTargetDifferential = false)
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "main"
                inputs.add(buildValueRef { valueId = "x"; type = tensorType(16, 16) })
                outputs.add(buildValueRef { valueId = "y"; type = tensorType(16, 16) })
                nodes.add(buildNode {
                    name = "relu"; op = UirOpKind.RELU
                    inputs.add(buildValueRef { valueId = "x"; type = tensorType(16, 16) })
                    outputs.add(buildValueRef { valueId = "y"; type = tensorType(16, 16) })
                })
            })
        }
        val result = normalTranslator.translate(program)

        // 验证普通模式没有交叉目标代码
        assertFalse(result.contains("ex_cpu = relax.build"),
            "普通模式不应包含 ex_cpu: $result")
        assertFalse(result.contains("ex_gpu = relax.build"),
            "普通模式不应包含 ex_gpu: $result")
        assertFalse(result.contains("[DIFF-MISMATCH]"),
            "普通模式不应包含 DIFF-MISMATCH: $result")
        assertTrue(result.contains("ex = relax.build(mod, target=\"llvm\")"),
            "缺少普通 build: $result")
    }

    @Test
    fun `cross target diff handles multiple graphs`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "func1"
                inputs.add(buildValueRef { valueId = "x"; type = tensorType(16) })
                outputs.add(buildValueRef { valueId = "y"; type = tensorType(16) })
                nodes.add(buildNode {
                    name = "relu"; op = UirOpKind.RELU
                    inputs.add(buildValueRef { valueId = "x"; type = tensorType(16) })
                    outputs.add(buildValueRef { valueId = "y"; type = tensorType(16) })
                })
            })
            graphs.add(buildGraph {
                name = "func2"
                inputs.add(buildValueRef { valueId = "a"; type = tensorType(16) })
                outputs.add(buildValueRef { valueId = "b"; type = tensorType(16) })
                nodes.add(buildNode {
                    name = "sigmoid"; op = UirOpKind.SIGMOID
                    inputs.add(buildValueRef { valueId = "a"; type = tensorType(16) })
                    outputs.add(buildValueRef { valueId = "b"; type = tensorType(16) })
                })
            })
        }
        val result = translator.translate(program)

        // 两个图都应该有 cpu+gpu 结果变量
        assertTrue(result.contains("tvm_result_cpu_0"), "缺少 graph_0 cpu: $result")
        assertTrue(result.contains("tvm_result_gpu_0"), "缺少 graph_0 gpu: $result")
        assertTrue(result.contains("tvm_result_cpu_1"), "缺少 graph_1 cpu: $result")
        assertTrue(result.contains("tvm_result_gpu_1"), "缺少 graph_1 gpu: $result")
        // 比较应该覆盖所有图
        assertTrue(result.contains("func1"), "缺少 func1 比较: $result")
        assertTrue(result.contains("func2"), "缺少 func2 比较: $result")
    }
}