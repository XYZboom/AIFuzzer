package io.github.xyzboom.aiFuzzer.translator.tvm

import io.github.xyzboom.aiFuzzer.ir.builder.buildProgram
import io.github.xyzboom.aiFuzzer.ir.builder.buildGraph
import io.github.xyzboom.aiFuzzer.ir.builder.buildNode
import io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildIntAttr
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class TvmRelaxTranslatorTest {

    private val translator = TvmRelaxTranslator()

    @Test
    fun `translate empty program`() {
        val program = buildProgram { }
        val result = translator.translate(program)
        assertTrue(result.contains("import tvm"))
        assertTrue(result.contains("build_mod"))
        assertTrue(result.contains("bb = relax.BlockBuilder()"))
        assertTrue(result.contains("mod = build_mod()"))
    }

    @Test
    fun `translate program with one graph and relu node`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "main"
                inputs.add(buildValueRef { valueId = "input_0"; ndim = 2 })
                outputs.add(buildValueRef { valueId = "relu_out"; ndim = 2 })
                nodes.add(buildNode {
                    name = "relu"
                    op = "relu"
                    inputs.add(buildValueRef { valueId = "input_0"; ndim = 2 })
                    outputs.add(buildValueRef { valueId = "relu_out"; ndim = 2 })
                })
            })
        }
        val result = translator.translate(program)
        assertTrue(result.contains("with bb.function(\"main\", [input_0_var]):")) {
            "Expected function with input_0_var, got:\n$result"
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
                inputs.add(buildValueRef { valueId = "a"; ndim = 2 })
                inputs.add(buildValueRef { valueId = "b"; ndim = 2 })
                outputs.add(buildValueRef { valueId = "c"; ndim = 2 })
                nodes.add(buildNode {
                    name = "add"
                    op = "add"
                    inputs.add(buildValueRef { valueId = "a"; ndim = 2 })
                    inputs.add(buildValueRef { valueId = "b"; ndim = 2 })
                    outputs.add(buildValueRef { valueId = "c"; ndim = 2 })
                })
            })
        }
        val result = translator.translate(program)
        assertTrue(result.contains("c = bb.emit(relax.op.add(a_var, b_var))")) {
            "Expected c = bb.emit(relax.op.add(a_var, b_var)), got:\n$result"
        }
    }

    @Test
    fun `translate node with int attribute`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "main"
                inputs.add(buildValueRef { valueId = "x"; ndim = 2 })
                outputs.add(buildValueRef { valueId = "y"; ndim = 2 })
                nodes.add(buildNode {
                    name = "softmax"
                    op = "softmax"
                    inputs.add(buildValueRef { valueId = "x"; ndim = 2 })
                    outputs.add(buildValueRef { valueId = "y"; ndim = 2 })
                    attributes = mutableMapOf("axis" to buildIntAttr { value = -1 })
                })
            })
        }
        val result = translator.translate(program)
        assertTrue(result.contains("y = bb.emit(relax.op.nn.softmax(x_var, axis=-1))")) {
            "Expected y = bb.emit(relax.op.nn.softmax(x_var, axis=-1)), got:\n$result"
        }
    }

    @Test
    fun `translate multi-output node`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "main"
                inputs.add(buildValueRef { valueId = "x"; ndim = 2 })
                outputs.add(buildValueRef { valueId = "values"; ndim = 2 })
                outputs.add(buildValueRef { valueId = "indices"; ndim = 2 })
                nodes.add(buildNode {
                    name = "topk"
                    op = "topk"
                    inputs.add(buildValueRef { valueId = "x"; ndim = 2 })
                    outputs.add(buildValueRef { valueId = "values"; ndim = 2 })
                    outputs.add(buildValueRef { valueId = "indices"; ndim = 2 })
                })
            })
        }
        val result = translator.translate(program)
        assertTrue(result.contains("values, indices = bb.emit(relax.op.topk(x_var))")) {
            "Expected topk with x_var, got:\n$result"
        }
    }
}