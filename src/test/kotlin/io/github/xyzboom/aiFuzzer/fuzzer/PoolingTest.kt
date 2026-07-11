package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.UirDimKind
import io.github.xyzboom.aiFuzzer.ir.UirTypeKind
import io.github.xyzboom.aiFuzzer.ir.builder.buildGraph
import io.github.xyzboom.aiFuzzer.ir.builder.buildNode
import io.github.xyzboom.aiFuzzer.ir.builder.buildProgram
import io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDataType
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDim
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildIntAttr
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildShape
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildTensorType
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import io.github.xyzboom.aiFuzzer.translator.pytorch.PytorchTranslator
import org.junit.jupiter.api.Test
import java.io.PrintStream

/**
 * 验证池化算子（MAX_POOL2D, AVG_POOL2D）的翻译。
 */
class PoolingTest {

    @Test
    fun `translate max_pool2d manually`() {
        val out = PrintStream(System.out, true)

        // 手动构造一个包含 MAX_POOL2D 的程序
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "test_maxpool2d"

                // 输入: [N, C, H, W] = [1, 16, 32, 32]
                inputs.add(buildValueRef {
                    valueId = "input"
                    type = buildTensorType {
                        typeKind = UirTypeKind.TENSOR
                        shape = buildShape {
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 1 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 16 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                        }
                        dtype = buildDataType { name = "float32"; bits = 32 }
                    }
                })

                // MAX_POOL2D 节点
                nodes.add(buildNode {
                    name = "maxpool2d_0"
                    op = UirOpKind.MAX_POOL2D
                    inputs.add(buildValueRef {
                        valueId = "input"
                        type = buildTensorType {
                            typeKind = UirTypeKind.TENSOR
                            shape = buildShape {
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 1 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 16 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                            }
                            dtype = buildDataType { name = "float32"; bits = 32 }
                        }
                    })
                    // 输出: [1, 16, 16, 16] (kernel=2, stride=2, padding=0)
                    outputs.add(buildValueRef {
                        valueId = "output"
                        type = buildTensorType {
                            typeKind = UirTypeKind.TENSOR
                            shape = buildShape {
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 1 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 16 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 16 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 16 })
                            }
                            dtype = buildDataType { name = "float32"; bits = 32 }
                        }
                    })
                    attributes = mutableMapOf(
                        "kernel_size" to buildIntAttr { value = 2 },
                        "stride" to buildIntAttr { value = 2 },
                        "padding" to buildIntAttr { value = 0 }
                    )
                })

                outputs.add(buildValueRef {
                    valueId = "output"
                    type = buildTensorType {
                        typeKind = UirTypeKind.TENSOR
                        shape = buildShape {
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 1 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 16 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 16 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 16 })
                        }
                        dtype = buildDataType { name = "float32"; bits = 32 }
                    }
                })
            })
        }

        out.println("=== 手动构造的 MAX_POOL2D 程序 ===")
        out.println("Graph: ${program.graphs[0].name}")
        out.println("Nodes: ${program.graphs[0].nodes.map { it.op }}")

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
        assert(tvmCode.contains("max_pool2d")) { "TVM 代码应包含 max_pool2d" }
        assert(pytorchCode.contains("max_pool2d")) { "PyTorch 代码应包含 max_pool2d" }
    }

    @Test
    fun `translate avg_pool2d manually`() {
        val out = PrintStream(System.out, true)

        // 手动构造一个包含 AVG_POOL2D 的程序
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "test_avgpool2d"

                // 输入: [N, C, H, W] = [2, 32, 64, 64]
                inputs.add(buildValueRef {
                    valueId = "input"
                    type = buildTensorType {
                        typeKind = UirTypeKind.TENSOR
                        shape = buildShape {
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 2 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 64 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 64 })
                        }
                        dtype = buildDataType { name = "float32"; bits = 32 }
                    }
                })

                // AVG_POOL2D 节点
                nodes.add(buildNode {
                    name = "avgpool2d_0"
                    op = UirOpKind.AVG_POOL2D
                    inputs.add(buildValueRef {
                        valueId = "input"
                        type = buildTensorType {
                            typeKind = UirTypeKind.TENSOR
                            shape = buildShape {
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 2 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 64 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 64 })
                            }
                            dtype = buildDataType { name = "float32"; bits = 32 }
                        }
                    })
                    // 输出: [2, 32, 32, 32] (kernel=2, stride=2, padding=0)
                    outputs.add(buildValueRef {
                        valueId = "output"
                        type = buildTensorType {
                            typeKind = UirTypeKind.TENSOR
                            shape = buildShape {
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 2 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                            }
                            dtype = buildDataType { name = "float32"; bits = 32 }
                        }
                    })
                    attributes = mutableMapOf(
                        "kernel_size" to buildIntAttr { value = 2 },
                        "stride" to buildIntAttr { value = 2 },
                        "padding" to buildIntAttr { value = 0 }
                    )
                })

                outputs.add(buildValueRef {
                    valueId = "output"
                    type = buildTensorType {
                        typeKind = UirTypeKind.TENSOR
                        shape = buildShape {
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 2 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                        }
                        dtype = buildDataType { name = "float32"; bits = 32 }
                    }
                })
            })
        }

        out.println("=== 手动构造的 AVG_POOL2D 程序 ===")
        out.println("Graph: ${program.graphs[0].name}")
        out.println("Nodes: ${program.graphs[0].nodes.map { it.op }}")

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
        assert(tvmCode.contains("avg_pool2d")) { "TVM 代码应包含 avg_pool2d" }
        assert(pytorchCode.contains("avg_pool2d")) { "PyTorch 代码应包含 avg_pool2d" }
    }
}