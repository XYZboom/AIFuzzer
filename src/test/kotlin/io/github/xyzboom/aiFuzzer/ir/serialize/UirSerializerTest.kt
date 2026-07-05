package io.github.xyzboom.aiFuzzer.ir.serialize

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.UirIntAttr
import io.github.xyzboom.aiFuzzer.ir.types.UirStringAttr
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class UirSerializerTest {

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
                inputs.add(buildValueRef { valueId = "v_0"; ndim = 3 })
                inputs.add(buildValueRef { valueId = "v_1"; ndim = 2 })
                outputs.add(buildValueRef { valueId = "v_3"; ndim = 2 })

                nodes.add(buildNode {
                    name = "relu"
                    op = "relu"
                    inputs.add(buildValueRef { valueId = "v_0"; ndim = 3 })
                    outputs.add(buildValueRef { valueId = "v_2"; ndim = 3 })
                    attributes = mutableMapOf()
                })

                nodes.add(buildNode {
                    name = "add"
                    op = "add"
                    inputs.add(buildValueRef { valueId = "v_2"; ndim = 3 })
                    inputs.add(buildValueRef { valueId = "v_1"; ndim = 2 })
                    outputs.add(buildValueRef { valueId = "v_3"; ndim = 2 })
                    attributes = mutableMapOf(
                        "axis" to buildIntAttr { value = 0 },
                    )
                })
            })
        }

        val jsonl = UirSerializer.toJsonl(program)
        println("=== JSONL output ===")
        println(jsonl)

        val restored = UirSerializer.fromJsonl(jsonl)

        assertEquals(1, restored.graphs.size)
        val graph = restored.graphs[0]
        assertEquals("graph_0", graph.name)
        assertEquals(2, graph.inputs.size)
        assertEquals(1, graph.outputs.size)
        assertEquals(2, graph.nodes.size)

        // relu node
        val reluNode = graph.nodes.find { it.op == "relu" }
        assertNotNull(reluNode)
        assertEquals("relu", reluNode!!.name)
        assertEquals(1, reluNode.inputs.size)
        assertEquals(1, reluNode.outputs.size)
        assertEquals("v_0", reluNode.inputs[0].valueId)
        assertEquals(3, reluNode.inputs[0].ndim)

        // add node
        val addNode = graph.nodes.find { it.op == "add" }
        assertNotNull(addNode)
        assertEquals("add", addNode!!.name)
        assertEquals(2, addNode.inputs.size)
        assertEquals(1, addNode.outputs.size)
        assertEquals(0, (addNode.attributes["axis"] as UirIntAttr).value)

        val outputVr = addNode.outputs[0]
        assertEquals("v_3", outputVr.valueId)
        assertEquals(2, outputVr.ndim)
    }

    @Test
    fun `serialize and deserialize program with attributes`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "graph_0"
                inputs.add(buildValueRef { valueId = "v_0"; ndim = 2 })

                nodes.add(buildNode {
                    name = "conv2d"
                    op = "conv2d"
                    inputs.add(buildValueRef { valueId = "v_0"; ndim = 2 })
                    outputs.add(buildValueRef { valueId = "v_1"; ndim = 2 })
                    attributes = mutableMapOf(
                        "kernel_size" to buildIntAttr { value = 3 },
                        "strides" to buildIntAttr { value = 1 },
                        "padding" to buildIntAttr { value = 0 },
                    )
                })

                nodes.add(buildNode {
                    name = "cast"
                    op = "cast"
                    inputs.add(buildValueRef { valueId = "v_1"; ndim = 2 })
                    outputs.add(buildValueRef { valueId = "v_2"; ndim = 2 })
                    attributes = mutableMapOf(
                        "dtype" to buildStringAttr { value = "float16" },
                    )
                })

                outputs.add(buildValueRef { valueId = "v_2"; ndim = 2 })
            })
        }

        val jsonl = UirSerializer.toJsonl(program)
        println("=== JSONL with attributes ===")
        println(jsonl)

        val restored = UirSerializer.fromJsonl(jsonl)
        assertEquals(1, restored.graphs.size)
        val graph = restored.graphs[0]
        assertEquals(2, graph.nodes.size)

        val convNode = graph.nodes.find { it.op == "conv2d" }
        assertNotNull(convNode)
        assertEquals(3, (convNode!!.attributes["kernel_size"] as UirIntAttr).value)

        val castNode = graph.nodes.find { it.op == "cast" }
        assertNotNull(castNode)
        assertEquals("float16", (castNode!!.attributes["dtype"] as UirStringAttr).value)
    }

    @Test
    fun `serialize and deserialize multiple graphs`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "graph_a"
                inputs.add(buildValueRef { valueId = "x"; ndim = 3 })
                outputs.add(buildValueRef { valueId = "y"; ndim = 3 })
                nodes.add(buildNode {
                    name = "relu"; op = "relu"
                    inputs.add(buildValueRef { valueId = "x"; ndim = 3 })
                    outputs.add(buildValueRef { valueId = "y"; ndim = 3 })
                })
            })
            graphs.add(buildGraph {
                name = "graph_b"
                inputs.add(buildValueRef { valueId = "a"; ndim = 2 })
                outputs.add(buildValueRef { valueId = "b"; ndim = 2 })
                nodes.add(buildNode {
                    name = "sigmoid"; op = "sigmoid"
                    inputs.add(buildValueRef { valueId = "a"; ndim = 2 })
                    outputs.add(buildValueRef { valueId = "b"; ndim = 2 })
                })
            })
        }

        val jsonl = UirSerializer.toJsonl(program)
        val restored = UirSerializer.fromJsonl(jsonl)

        assertEquals(2, restored.graphs.size)
        assertEquals("graph_a", restored.graphs[0].name)
        assertEquals("graph_b", restored.graphs[1].name)
        assertEquals(1, restored.graphs[0].nodes.size)
        assertEquals(1, restored.graphs[1].nodes.size)
        assertEquals("relu", restored.graphs[0].nodes[0].op)
        assertEquals("sigmoid", restored.graphs[1].nodes[0].op)
    }

    @Test
    fun `round-trip file I-O and remove duplicate visitValue`() {
        val program = buildProgram {
            graphs.add(buildGraph {
                name = "test_graph"
                inputs.add(buildValueRef { valueId = "in"; ndim = 2 })
                outputs.add(buildValueRef { valueId = "out"; ndim = 2 })
                nodes.add(buildNode {
                    name = "matmul"; op = "matmul"
                    inputs.add(buildValueRef { valueId = "in"; ndim = 2 })
                    inputs.add(buildValueRef { valueId = "in"; ndim = 2 })
                    outputs.add(buildValueRef { valueId = "out"; ndim = 2 })
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
        try {
            tmpFile.writeText(jsonl)
            val readBack = tmpFile.readText()
            val restored = UirSerializer.fromJsonl(readBack)

            assertEquals(1, restored.graphs.size)
            val graph = restored.graphs[0]
            assertEquals("test_graph", graph.name)
            assertEquals("matmul", graph.nodes[0].op)
            assertEquals(1, graph.inputs.size)
            assertEquals(1, graph.outputs.size)

            val matmulNode = graph.nodes[0]
            assertEquals(2, matmulNode.inputs.size)
            assertEquals(0, (matmulNode.attributes["transpose_a"] as UirIntAttr).value)
            assertEquals(1, (matmulNode.attributes["transpose_b"] as UirIntAttr).value)
        } finally {
            tmpFile.delete()
        }
    }
}
