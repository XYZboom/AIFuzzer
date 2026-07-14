package io.github.xyzboom.aiFuzzer.translator.tvm

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TvmRelaxTranslatorTest {

    private val translator = TvmRelaxTranslator()
    
    // ===== 辅助函数 =====
    
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
    fun `translate empty program`() {
        val program = buildProgram { }
        val result = translator.translate(program)
        assertTrue(result.contains("import tvm"))
        assertTrue(result.contains("def build_mod():"))
        assertTrue(result.contains("bb = relax.BlockBuilder()"))
        assertTrue(result.contains("mod = bb.get()"))
    }

    @Test
    fun `translate program with one graph and relu node`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "main"
                inputs.add(buildValueRef { 
                    valueId = "input_0"
                    type = tensorType(16, 16)
                })
                outputs.add(buildValueRef { 
                    valueId = "relu_out"
                    type = tensorType(16, 16)
                })
                nodes.add(buildNode {
                    name = "relu"
                    op = UirOpKind.RELU
                    inputs.add(buildValueRef { 
                        valueId = "input_0"
                        type = tensorType(16, 16)
                    })
                    outputs.add(buildValueRef { 
                        valueId = "relu_out"
                        type = tensorType(16, 16)
                    })
                })
            })
        }
        val result = translator.translate(program)
        assertTrue(result.contains("with bb.function(\"main\"")) {
            "Expected function 'main', got:\n$result"
        }
        assertTrue(result.contains("relu_out = bb.emit(relax.op.nn.relu(input_0_var))")) {
            "Expected relu_out = bb.emit(relax.op.nn.relu(input_0_var)), got:\n$result"
        }
        assertTrue(result.contains("bb.emit_func_output(relu_out)")) {
            "Expected emit_func_output with relu_out, got:\n$result"
        }
    }

    @Test
    fun `translate add node with two inputs`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "main"
                inputs.add(buildValueRef { 
                    valueId = "a"
                    type = tensorType(16, 16)
                })
                inputs.add(buildValueRef { 
                    valueId = "b"
                    type = tensorType(16, 16)
                })
                outputs.add(buildValueRef { 
                    valueId = "c"
                    type = tensorType(16, 16)
                })
                nodes.add(buildNode {
                    name = "add"
                    op = UirOpKind.ADD
                    inputs.add(buildValueRef { 
                        valueId = "a"
                        type = tensorType(16, 16)
                    })
                    inputs.add(buildValueRef { 
                        valueId = "b"
                        type = tensorType(16, 16)
                    })
                    outputs.add(buildValueRef { 
                        valueId = "c"
                        type = tensorType(16, 16)
                    })
                })
            })
        }
        val result = translator.translate(program)
        assertTrue(result.contains("relax.op.add(relax.op.astype(a_var, dtype=\"float32\"), relax.op.astype(b_var, dtype=\"float32\"))")) {
            "Expected relax.op.add with astype(float32), got:\n$result"
        }
    }

    @Test
    fun `translate node with int attribute`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "main"
                inputs.add(buildValueRef { 
                    valueId = "x"
                    type = tensorType(16, 16)
                })
                outputs.add(buildValueRef { 
                    valueId = "y"
                    type = tensorType(16, 16)
                })
                nodes.add(buildNode {
                    name = "softmax"
                    op = UirOpKind.SOFTMAX
                    inputs.add(buildValueRef { 
                        valueId = "x"
                        type = tensorType(16, 16)
                    })
                    outputs.add(buildValueRef { 
                        valueId = "y"
                        type = tensorType(16, 16)
                    })
                    attributes = mutableMapOf("axis" to buildIntAttr { value = -1 })
                })
            })
        }
        val result = translator.translate(program)
        assertTrue(result.contains("relax.op.nn.softmax(relax.op.astype(x_var, dtype=\"float32\"), axis=-1)")) {
            "Expected relax.op.nn.softmax with astype(float32), got:\n$result"
        }
    }

    @Test
    fun `translate matmul node`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "main"
                inputs.add(buildValueRef { 
                    valueId = "a"
                    type = tensorType(16, 16)
                })
                inputs.add(buildValueRef { 
                    valueId = "b"
                    type = tensorType(16, 16)
                })
                outputs.add(buildValueRef { 
                    valueId = "c"
                    type = tensorType(16, 16)
                })
                nodes.add(buildNode {
                    name = "matmul"
                    op = UirOpKind.MATMUL
                    inputs.add(buildValueRef { 
                        valueId = "a"
                        type = tensorType(16, 16)
                    })
                    inputs.add(buildValueRef { 
                        valueId = "b"
                        type = tensorType(16, 16)
                    })
                    outputs.add(buildValueRef { 
                        valueId = "c"
                        type = tensorType(16, 16)
                    })
                })
            })
        }
        val result = translator.translate(program)
        assertTrue(result.contains("c = bb.emit(relax.op.matmul(a_var, b_var))")) {
            "Expected c = bb.emit(relax.op.matmul(a_var, b_var)), got:\n$result"
        }
    }

    @Test
    fun `translate reduce_sum node`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "main"
                inputs.add(buildValueRef { 
                    valueId = "x"
                    type = tensorType(16, 16, 16)
                })
                outputs.add(buildValueRef { 
                    valueId = "y"
                    type = tensorType(16, 16)
                })
                nodes.add(buildNode {
                    name = "reduce_sum"
                    op = UirOpKind.REDUCE_SUM
                    inputs.add(buildValueRef { 
                        valueId = "x"
                        type = tensorType(16, 16, 16)
                    })
                    outputs.add(buildValueRef { 
                        valueId = "y"
                        type = tensorType(16, 16)
                    })
                    attributes = mutableMapOf(
                        "axis" to buildIntAttr { value = 2 },
                        "keepdims" to buildIntAttr { value = 0 }
                    )
                })
            })
        }
        val result = translator.translate(program)
        assertTrue(result.contains("y = bb.emit(relax.op.sum(x_var, axis=[2], keepdims=False))")) {
            "Expected reduce_sum with axis=2, got:\n$result"
        }
    }

    @Test
    fun `translate multiple graphs`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "func1"
                inputs.add(buildValueRef { 
                    valueId = "x"
                    type = tensorType(16)
                })
                outputs.add(buildValueRef { 
                    valueId = "y"
                    type = tensorType(16)
                })
                nodes.add(buildNode {
                    name = "relu"
                    op = UirOpKind.RELU
                    inputs.add(buildValueRef { valueId = "x"; type = tensorType(16) })
                    outputs.add(buildValueRef { valueId = "y"; type = tensorType(16) })
                })
            })
            graphs.add(buildGraph {
                name = "func2"
                inputs.add(buildValueRef { 
                    valueId = "a"
                    type = tensorType(16)
                })
                outputs.add(buildValueRef { 
                    valueId = "b"
                    type = tensorType(16)
                })
                nodes.add(buildNode {
                    name = "sigmoid"
                    op = UirOpKind.SIGMOID
                    inputs.add(buildValueRef { valueId = "a"; type = tensorType(16) })
                    outputs.add(buildValueRef { valueId = "b"; type = tensorType(16) })
                })
            })
        }
        val result = translator.translate(program)
        assertTrue(result.contains("with bb.function(\"func1\"")) {
            "Expected function 'func1', got:\n$result"
        }
        assertTrue(result.contains("with bb.function(\"func2\"")) {
            "Expected function 'func2', got:\n$result"
        }
    }
}