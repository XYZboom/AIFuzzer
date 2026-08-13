package io.github.xyzboom.aiFuzzer.reducer

import io.github.xyzboom.aiFuzzer.cli.ReduceCommand
import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * 缩减器组件单元测试。
 */
class ReducerTest {

    // =====================================================
    // matchesBugSignature 测试
    // =====================================================

    @Test
    fun `matchesBugSignature should match VERIFY FAIL`() {
        assertTrue(ReduceCommand.matchesBugSignature("VERIFY: FAIL", "some\nVERIFY: FAIL\nother"))
        assertFalse(ReduceCommand.matchesBugSignature("verify: fail", "some\nVERIFY: FAIL\nother"))
        assertFalse(ReduceCommand.matchesBugSignature("", "some\nVERIFY: FAIL\nother"))
    }

    @Test
    fun `matchesBugSignature should match ONNXRuntimeError by error code`() {
        val orig = "E: [ONNXRuntimeError] : 1234 : Common : 'node_X' shape mismatch"
        assertTrue(ReduceCommand.matchesBugSignature(
            "E: [ONNXRuntimeError] : 1234 : Common : 'node_Y' shape mismatch",
            orig))
        assertFalse(ReduceCommand.matchesBugSignature(
            "E: [ONNXRuntimeError] : 5678 : Common : shape mismatch",
            orig))
    }

    @Test
    fun `matchesBugSignature should match ONNX-DIFF output mismatch`() {
        val orig = "[ONNX-DIFF] graph_0[23]: opt=[0. 0.] ref=[2147483647 2147483647]"
        assertTrue(ReduceCommand.matchesBugSignature(
            "[ONNX-DIFF] graph_0[0]: opt=[0.] ref=[2147483647]",
            orig))
        assertFalse(ReduceCommand.matchesBugSignature("no diff", orig))
        assertFalse(ReduceCommand.matchesBugSignature("", orig))
    }

    @Test
    fun `matchesBugSignature should match tvm errors`() {
        assertTrue(ReduceCommand.matchesBugSignature(
            "tvm.error.InternalError: some error",
            "tvm.error.InternalError: orig error"))
        assertTrue(ReduceCommand.matchesBugSignature(
            "ScheduleError: cannot schedule",
            "ScheduleError: different schedule"))
    }

    @Test
    fun `matchesBugSignature should match AttributeError with key fragment`() {
        val orig = "AttributeError: 'NoneType' object has no attribute 'shape'"
        assertTrue(ReduceCommand.matchesBugSignature(
            "AttributeError: 'NoneType' object has no attribute 'shape'",
            orig))
    }

    @Test
    fun `matchesBugSignature should fallback to known error types`() {
        val orig = "Error: something\nRuntimeError: Expected all tensors to be on the same device"
        assertTrue(ReduceCommand.matchesBugSignature(
            "RuntimeError: Expected all tensors to be on the same device",
            orig))
        assertFalse(ReduceCommand.matchesBugSignature("no error here", orig))
    }

    // =====================================================
    // DependencyReconstructor 测试
    // =====================================================

    private fun tensorType(vararg dimValues: Int): UirTensorType = buildTensorType {
        typeKind = UirTypeKind.TENSOR
        shape = buildShape {
            dimValues.forEach { v ->
                dims.add(buildDim {
                    dimKind = UirDimKind.CONSTANT
                    value = v
                })
            }
        }
        dtype = buildDataType { name = "float32"; bits = 32 }
    }

    @Test
    fun `DependencyReconstructor should wire-around cast node`() {
        val graph = buildGraph {
            name = "test_graph"
            inputs.add(buildValueRef { valueId = "v_0"; type = tensorType(16) })
            nodes.add(buildNode {
                name = "cast"
                op = UirOpKind.CAST
                inputs.add(buildValueRef { valueId = "v_0"; type = tensorType(16) })
                outputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16) })
            })
            nodes.add(buildNode {
                name = "relu"
                op = UirOpKind.RELU
                inputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16) })
                outputs.add(buildValueRef { valueId = "v_2"; type = tensorType(16) })
            })
            outputs.add(buildValueRef { valueId = "v_2"; type = tensorType(16) })
        }

        val reconstructor = DependencyReconstructor(graph)
        val castNode = graph.nodes[0]
        val plan = reconstructor.prepare(setOf(castNode))
        reconstructor.apply(plan)
        graph.nodes.removeAll { it.name == "cast" || it.name.startsWith("default_") }

        assertEquals(1, graph.nodes.size)
        val reluNode = graph.nodes[0]
        assertEquals(UirOpKind.RELU, reluNode.op)
        assertEquals("v_0", reluNode.inputs[0].valueId)
    }

    @Test
    fun `DependencyReconstructor should create FULL half fill for removed non-wire-aroundable node`() {
        val graph = buildGraph {
            name = "test_graph"
            inputs.add(buildValueRef { valueId = "v_0"; type = tensorType(16) })
            nodes.add(buildNode {
                name = "zeros_src"
                op = UirOpKind.ZEROS
                outputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16) })
            })
            nodes.add(buildNode {
                name = "add"
                op = UirOpKind.ADD
                inputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16) })
                inputs.add(buildValueRef { valueId = "v_0"; type = tensorType(16) })
                outputs.add(buildValueRef { valueId = "v_2"; type = tensorType(16) })
            })
            outputs.add(buildValueRef { valueId = "v_2"; type = tensorType(16) })
        }

        val reconstructor = DependencyReconstructor(graph)
        val zerosNode = graph.nodes.find { it.op == UirOpKind.ZEROS }!!
        val plan = reconstructor.prepare(setOf(zerosNode))
        val newNodes = reconstructor.apply(plan)
        graph.nodes.removeAll { it.name == "zeros_src" }

        // ZEROS 是常量算子 → CONSTANT_TO_INPUT：提升为 graph input，而非创建 FULL 节点
        assertTrue(newNodes.isEmpty())
        val addNode = graph.nodes.find { it.name == "add" }
        assertNotNull(addNode)
        assertTrue(graph.inputs.any { it.valueId == "v_1_as_input" })
        assertTrue(addNode!!.inputs[0].valueId == "v_1_as_input")
    }

    // =====================================================
    // DeadCodeEliminator 测试
    // =====================================================

    @Test
    fun `DeadCodeEliminator should remove nodes not reachable from outputs`() {
        val graph = buildGraph {
            name = "test_graph"
            inputs.add(buildValueRef { valueId = "v_0"; type = tensorType(16) })
            nodes.add(buildNode {
                name = "relu"
                op = UirOpKind.RELU
                inputs.add(buildValueRef { valueId = "v_0"; type = tensorType(16) })
                outputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16) })
            })
            outputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16) })
            // EXP consumes v_1 (RELU output) — v_1 is graph output, so backwards traversal
            // finds RELU (producer of v_1). EXP is a consumer of v_1, not a producer,
            // so EXP is NOT reachable.
            nodes.add(buildNode {
                name = "exp_downstream"
                op = UirOpKind.EXP
                inputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16) })
                outputs.add(buildValueRef { valueId = "v_2"; type = tensorType(16) })
            })
        }

        val removed = DeadCodeEliminator.eliminateToFixpoint(graph)
        assertEquals(1, removed.size)
        assertTrue(removed.any { it.name == "exp_downstream" })
        assertEquals(1, graph.nodes.size)
        assertTrue(graph.nodes.any { it.op == UirOpKind.RELU })
    }

    @Test
    fun `DeadCodeEliminator should cascade delete orphan chain`() {
        val graph = buildGraph {
            name = "test_graph"
            inputs.add(buildValueRef { valueId = "v_0"; type = tensorType(16) })
            nodes.add(buildNode {
                name = "relu"
                op = UirOpKind.RELU
                inputs.add(buildValueRef { valueId = "v_0"; type = tensorType(16) })
                outputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16) })
            })
            nodes.add(buildNode {
                name = "ceil"
                op = UirOpKind.CEIL
                inputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16) })
                outputs.add(buildValueRef { valueId = "v_2"; type = tensorType(16) })
            })
            // EXP consumes v_1 (RELU output), NOT v_0 (graph input)
            nodes.add(buildNode {
                name = "exp"
                op = UirOpKind.EXP
                inputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16) })
                outputs.add(buildValueRef { valueId = "v_3"; type = tensorType(16) })
            })
            nodes.add(buildNode {
                name = "neg"
                op = UirOpKind.NEG
                inputs.add(buildValueRef { valueId = "v_3"; type = tensorType(16) })
                outputs.add(buildValueRef { valueId = "v_4"; type = tensorType(16) })
            })
            outputs.add(buildValueRef { valueId = "v_2"; type = tensorType(16) })
        }

        val removed = DeadCodeEliminator.eliminateToFixpoint(graph)
        assertTrue(removed.any { it.name == "exp" })
        assertTrue(removed.any { it.name == "neg" })
        assertEquals(2, removed.size)
        assertEquals(2, graph.nodes.size)
    }

    // =====================================================
    // AutoReducer 基础测试
    // =====================================================

    @Test
    fun `AutoReducer should not crash on empty program`() {
        val program = buildProgram { }
        val checker = object : PropertyChecker {
            override fun check(program: UirProgram): Boolean = false
            override fun bugSignature(): String = "empty"
        }
        val reducer = AutoReducer(AutoReducer.ReducerConfig(enabled = true))
        val result = reducer.reduce(program, checker)
        assertFalse(result.propertyPreserved)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `AutoReducer should keep program when property holds`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "graph_0"
                inputs.add(buildValueRef { valueId = "v_0"; type = tensorType(16) })
                nodes.add(buildNode {
                    name = "relu"
                    op = UirOpKind.RELU
                    inputs.add(buildValueRef { valueId = "v_0"; type = tensorType(16) })
                    outputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16) })
                })
                outputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16) })
            })
        }

        val checker = object : PropertyChecker {
            override fun check(program: UirProgram): Boolean {
                if (program.graphs.isEmpty()) return false
                val g = program.graphs[0]
                if (g.nodes.isEmpty()) return false
                val allOutputIds = g.inputs.map { it.valueId }.toSet() +
                    g.nodes.flatMap { it.outputs.map { o -> o.valueId } }
                return g.nodes.all { node ->
                    node.inputs.all { it.valueId in allOutputIds }
                }
            }
            override fun bugSignature(): String = "test_bug"
        }

        val reducer = AutoReducer(AutoReducer.ReducerConfig(enabled = true))
        val result = reducer.reduce(program, checker)
        assertTrue(result.propertyPreserved)
        assertNotNull(result.minifiedProgram)
        assertTrue(result.minifiedProgram!!.graphs.sumOf { it.nodes.size } >= 1)
    }
}