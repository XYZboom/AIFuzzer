package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.builder.buildGraph
import io.github.xyzboom.aiFuzzer.ir.builder.buildNode
import io.github.xyzboom.aiFuzzer.ir.builder.buildProgram
import io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDataType
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDim
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildIntAttr
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildShape
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildTensorType
import io.github.xyzboom.aiFuzzer.ir.UirDimKind
import io.github.xyzboom.aiFuzzer.ir.UirTypeKind
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import io.github.xyzboom.aiFuzzer.translator.pytorch.PytorchTranslator
import org.junit.jupiter.api.Test
import java.io.PrintStream

/**
 * 直接构造包含 CONV2D 的 UIR 程序，验证翻译器。
 */
class Conv2dDirectTest {

    @Test
    fun `translate conv2d manually`() {
        val out = PrintStream(System.out, true)

        // 手动构造一个包含 CONV2D 的程序
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "test_conv2d"

                // 输入: [N, C_in, H, W] = [1, 3, 32, 32]
                inputs.add(buildValueRef {
                    valueId = "input"
                    type = buildTensorType {
                        typeKind = UirTypeKind.TENSOR
                        shape = buildShape {
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 1 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                        }
                        dtype = buildDataType { name = "float32"; bits = 32 }
                    }
                })

                // 权重: [C_out, C_in/groups, kH, kW] = [16, 3, 3, 3]
                inputs.add(buildValueRef {
                    valueId = "weight"
                    type = buildTensorType {
                        typeKind = UirTypeKind.TENSOR
                        shape = buildShape {
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 16 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
                        }
                        dtype = buildDataType { name = "float32"; bits = 32 }
                    }
                })

                // CONV2D 节点
                nodes.add(buildNode {
                    name = "conv2d_0"
                    op = UirOpKind.CONV2D
                    inputs.add(buildValueRef {
                        valueId = "input"
                        type = buildTensorType {
                            typeKind = UirTypeKind.TENSOR
                            shape = buildShape {
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 1 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 32 })
                            }
                            dtype = buildDataType { name = "float32"; bits = 32 }
                        }
                    })
                    inputs.add(buildValueRef {
                        valueId = "weight"
                        type = buildTensorType {
                            typeKind = UirTypeKind.TENSOR
                            shape = buildShape {
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 16 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
                            }
                            dtype = buildDataType { name = "float32"; bits = 32 }
                        }
                    })
                    // 输出: [1, 16, 30, 30] (stride=1, padding=0, dilation=1)
                    outputs.add(buildValueRef {
                        valueId = "output"
                        type = buildTensorType {
                            typeKind = UirTypeKind.TENSOR
                            shape = buildShape {
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 1 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 16 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 30 })
                                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 30 })
                            }
                            dtype = buildDataType { name = "float32"; bits = 32 }
                        }
                    })
                    attributes = mutableMapOf(
                        "stride" to buildIntAttr { value = 1 },
                        "padding" to buildIntAttr { value = 0 },
                        "dilation" to buildIntAttr { value = 1 },
                        "groups" to buildIntAttr { value = 1 }
                    )
                })

                outputs.add(buildValueRef {
                    valueId = "output"
                    type = buildTensorType {
                        typeKind = UirTypeKind.TENSOR
                        shape = buildShape {
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 1 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 16 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 30 })
                            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 30 })
                        }
                        dtype = buildDataType { name = "float32"; bits = 32 }
                    }
                })
            })
        }

        out.println("=== 手动构造的 CONV2D 程序 ===")
        out.println("Graph: ${program.graphs[0].name}")
        out.println("Inputs: ${program.graphs[0].inputs.map { it.valueId }}")
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
        assert(tvmCode.contains("conv2d")) { "TVM 代码应包含 conv2d" }
        assert(pytorchCode.contains("conv2d")) { "PyTorch 代码应包含 conv2d" }
    }
}