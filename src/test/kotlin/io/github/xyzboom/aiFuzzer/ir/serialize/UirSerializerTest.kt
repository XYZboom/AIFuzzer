package io.github.xyzboom.aiFuzzer.ir.serialize

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class UirSerializerTest {
    
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
    fun `serialize and deserialize empty program`() {
        val program = buildProgram { }
        val jsonl = UirSerializer.toJsonl(program)
        assertFalse(jsonl.isBlank(), "JSONL should not be blank for empty program")

        // 即使空 program 也应该能 round-trip
        val restored = UirSerializer.fromJsonl(jsonl)
        assertEquals(0, restored.graphs.size)
        assertTrue(restored.metadata.isEmpty())
    }

    @Test
    fun `serialize and deserialize single graph program`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "graph_0"
                inputs.add(buildValueRef { valueId = "v_0"; type = tensorType(16, 16, 16) })
                inputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16, 16) })
                outputs.add(buildValueRef { valueId = "v_3"; type = tensorType(16, 16) })

                nodes.add(buildNode {
                    name = "relu"
                    op = UirOpKind.RELU
                    inputs.add(buildValueRef { valueId = "v_0"; type = tensorType(16, 16, 16) })
                    outputs.add(buildValueRef { valueId = "v_2"; type = tensorType(16, 16, 16) })
                    attributes = mutableMapOf()
                })

                nodes.add(buildNode {
                    name = "add"
                    op = UirOpKind.ADD
                    inputs.add(buildValueRef { valueId = "v_2"; type = tensorType(16, 16, 16) })
                    inputs.add(buildValueRef { valueId = "v_1"; type = tensorType(16, 16) })
                    outputs.add(buildValueRef { valueId = "v_3"; type = tensorType(16, 16) })
                    attributes = mutableMapOf(
                        "axis" to buildIntAttr { value = 0 },
                    )
                })
            })
        }

        val jsonl = UirSerializer.toJsonl(program)
        println("=== JSONL ===")
        println(jsonl)

        // 验证 JSONL 包含预期内容
        assertTrue(jsonl.contains("visitMetadata"))
        assertTrue(jsonl.contains("visitGraph"))
        assertTrue(jsonl.contains("visitNode"))
        assertTrue(jsonl.contains("visitValue"))

        // Round-trip
        val restored = UirSerializer.fromJsonl(jsonl)
        assertEquals(1, restored.graphs.size)
        
        val restoredGraph = restored.graphs[0]
        assertEquals("graph_0", restoredGraph.name)
        assertEquals(2, restoredGraph.inputs.size)
        assertEquals(1, restoredGraph.outputs.size)
        assertEquals(2, restoredGraph.nodes.size)
        
        // 验证节点
        val reluNode = restoredGraph.nodes[0]
        assertEquals("relu", reluNode.name)
        assertEquals(UirOpKind.RELU, reluNode.op)
        
        val addNode = restoredGraph.nodes[1]
        assertEquals("add", addNode.name)
        assertEquals(UirOpKind.ADD, addNode.op)
    }

    @Test
    fun `round-trip file I-O and remove duplicate visitValue`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "test_graph"
                inputs.add(buildValueRef { valueId = "in"; type = tensorType(16, 16) })
                outputs.add(buildValueRef { valueId = "out"; type = tensorType(16, 16) })
                nodes.add(buildNode {
                    name = "matmul"
                    op = UirOpKind.MATMUL
                    inputs.add(buildValueRef { valueId = "in"; type = tensorType(16, 16) })
                    inputs.add(buildValueRef { valueId = "in"; type = tensorType(16, 16) })
                    outputs.add(buildValueRef { valueId = "out"; type = tensorType(16, 16) })
                    attributes = mutableMapOf(
                        "transpose_a" to buildIntAttr { value = 0 },
                        "transpose_b" to buildIntAttr { value = 1 },
                    )
                })
            })
        }

        val jsonl = UirSerializer.toJsonl(program)
        println("=== JSONL with dedup ===")
        println(jsonl)

        // 验证 visitValue "in" 只出现一次（去重）
        val visitValueLines = jsonl.lines().filter { it.contains("visitValue") }
        val idLines = visitValueLines.filter { it.contains("\"in\"") }
        assertEquals(1, idLines.size, "valueRef 'in' should only be serialized once")

        // 文件 round-trip
        val tmpFile = File.createTempFile("uir_roundtrip_", ".jsonl")
        tmpFile.deleteOnExit()
        tmpFile.writeText(jsonl)

        val restored = UirSerializer.fromJsonl(tmpFile.readText())
        assertEquals(1, restored.graphs.size)
        assertEquals("test_graph", restored.graphs[0].name)
    }

    @Test
    fun `serialize multi-graph program`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "func1"
                inputs.add(buildValueRef { valueId = "x"; type = tensorType(16) })
                outputs.add(buildValueRef { valueId = "y"; type = tensorType(16) })
                nodes.add(buildNode {
                    name = "relu"
                    op = UirOpKind.RELU
                    inputs.add(buildValueRef { valueId = "x"; type = tensorType(16) })
                    outputs.add(buildValueRef { valueId = "y"; type = tensorType(16) })
                })
            })
            graphs.add(buildGraph {
                name = "func2"
                inputs.add(buildValueRef { valueId = "a"; type = tensorType(16) })
                inputs.add(buildValueRef { valueId = "b"; type = tensorType(16) })
                outputs.add(buildValueRef { valueId = "c"; type = tensorType(16) })
                nodes.add(buildNode {
                    name = "add"
                    op = UirOpKind.ADD
                    inputs.add(buildValueRef { valueId = "a"; type = tensorType(16) })
                    inputs.add(buildValueRef { valueId = "b"; type = tensorType(16) })
                    outputs.add(buildValueRef { valueId = "c"; type = tensorType(16) })
                })
            })
        }

        val jsonl = UirSerializer.toJsonl(program)
        val restored = UirSerializer.fromJsonl(jsonl)
        
        assertEquals(2, restored.graphs.size)
        assertEquals("func1", restored.graphs[0].name)
        assertEquals("func2", restored.graphs[1].name)
    }

    @Test
    fun `preserve metadata`() {
        val program = buildProgram {
            metadata["key1"] = "value1"
            metadata["key2"] = "value2"
        }

        val jsonl = UirSerializer.toJsonl(program)
        val restored = UirSerializer.fromJsonl(jsonl)
        
        assertEquals(2, restored.metadata.size)
        assertEquals("value1", restored.metadata["key1"])
        assertEquals("value2", restored.metadata["key2"])
    }
}