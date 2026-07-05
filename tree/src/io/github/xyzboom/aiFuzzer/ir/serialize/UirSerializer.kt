package io.github.xyzboom.aiFuzzer.ir.serialize

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import kotlinx.serialization.json.*

/**
 * UIR 程序的 JSON Lines (JSONL) 序列化与反序列化。
 *
 * 依赖三方库 [kotlinx-serialization] 处理 JSON 的构建和解析，
 * 手动处理 IR 树的遍历和循环引用解除。
 *
 * ### 格式
 * 每行一个 JSON 对象，通过 visit* 前缀区分实体类型：
 *
 *   {"kind":"visitMetadata","graphCount":1,"graphNames":["graph_0"]}
 *   {"kind":"visitGraph","graph":0,"name":"graph_0","inputIds":["v_0"],"outputIds":["v_1"]}
 *   {"kind":"visitNode","graph":0,"name":"relu","op":"relu","inputIds":["v_0"],"outputIds":["v_2"],"attrs":{}}
 *   {"kind":"visitValue","id":"v_0","ndim":3}
 *
 * 使用 visitValue 将 valueRef 平面化，visitMetadata/visitGraph 作为结构标记，
 * 反序列化时按此结构重建。
 *
 * ### 循环引用处理
 * 序列化阶段通过 [visitedIds] 集合跟踪已输出的 visitValue，
 * 去重后避免无限循环。反序列化阶段不存在循环引用问题。
 */
object UirSerializer {

    private val json = Json { prettyPrint = false }

    // =========================================================================
    // 序列化
    // =========================================================================

    /**
     * 将 [UirProgram] 序列化为 JSONL 字符串。
     */
    fun toJsonl(program: UirProgram): String {
        val lines = mutableListOf<String>()

        // —— metadata ——
        lines.add(encode(buildJsonObject {
            put("kind", "visitMetadata")
            put("graphCount", program.graphs.size)
            put("graphNames", buildJsonArray {
                program.graphs.forEach { add(it.name) }
            })
        }))

        // 循环引用解除集：跟踪已输出的 visitValue
        val visitedIds = mutableSetOf<String>()

        fun emitValueRef(vr: UirValueRef) {
            if (visitedIds.add(vr.valueId)) {
                lines.add(encode(buildJsonObject {
                    put("kind", "visitValue")
                    put("id", vr.valueId)
                    put("ndim", vr.ndim)
                }))
            }
        }

        for ((gidx, graph) in program.graphs.withIndex()) {
            // —— graph ——
            lines.add(encode(buildJsonObject {
                put("kind", "visitGraph")
                put("graph", gidx)
                put("name", graph.name)
                put("inputIds", buildJsonArray { graph.inputs.forEach { add(it.valueId) } })
                put("outputIds", buildJsonArray { graph.outputs.forEach { add(it.valueId) } })
            }))

            // —— graph 层面的 valueRef ——
            graph.inputs.forEach { emitValueRef(it) }
            graph.outputs.forEach { emitValueRef(it) }

            // —— nodes ——
            for (node in graph.nodes) {
                lines.add(encode(buildJsonObject {
                    put("kind", "visitNode")
                    put("graph", gidx)
                    put("name", node.name)
                    put("op", node.op)
                    put("inputIds", buildJsonArray { node.inputs.forEach { add(it.valueId) } })
                    put("outputIds", buildJsonArray { node.outputs.forEach { add(it.valueId) } })
                    put("attrs", buildJsonObject {
                        node.attributes.forEach { (key, attr) ->
                            when (attr) {
                                is UirIntAttr -> put(key, attr.value)
                                is UirStringAttr -> put(key, attr.value)
                                else -> put(key, attr.toString())
                            }
                        }
                    })
                }))

                // 节点内部的 valueRef
                node.inputs.forEach { emitValueRef(it) }
                node.outputs.forEach { emitValueRef(it) }
            }
        }

        return lines.joinToString("\n") + "\n"
    }

    private fun encode(obj: JsonObject): String =
        json.encodeToString(JsonObject.serializer(), obj)

    // =========================================================================
    // 反序列化
    // =========================================================================

    /**
     * 从 JSONL 字符串反序列化为 [UirProgram]。
     */
    fun fromJsonl(jsonl: String): UirProgram {
        val lines = jsonl.trimEnd().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) error("Empty JSONL input")

        data class NodeEntry(
            val name: String,
            val op: String,
            val inputIds: List<String>,
            val outputIds: List<String>,
            val attrs: JsonObject,
        )
        data class GraphEntry(
            val name: String,
            val inputIds: List<String>,
            val outputIds: List<String>,
            val nodes: MutableList<NodeEntry>,
        )

        // 第一遍扫描：提取所有 visitValue
        val valueMap = mutableMapOf<String, Int>() // id → ndim
        // 按 graph 分组的 node 和 graph 元数据
        val graphEntries = mutableListOf<GraphEntry>()

        var currentGraph: GraphEntry? = null

        for (line in lines) {
            val obj = json.decodeFromString(JsonObject.serializer(), line)
            val kind = obj["kind"]?.jsonPrimitive?.content ?: error("Missing 'kind' in: $line")

            when (kind) {
                "visitValue" -> {
                    val id = obj["id"]?.jsonPrimitive?.content ?: error("Missing 'id' in visitValue: $line")
                    val ndim = obj["ndim"]?.jsonPrimitive?.int ?: 1
                    valueMap[id] = ndim
                }
                "visitGraph" -> {
                    val ge = GraphEntry(
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        inputIds = (obj["inputIds"]?.jsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                        outputIds = (obj["outputIds"]?.jsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                        nodes = mutableListOf(),
                    )
                    graphEntries.add(ge)
                    currentGraph = ge
                }
                "visitNode" -> {
                    val cg = currentGraph ?: error("visitNode without preceding visitGraph: $line")
                    cg.nodes.add(NodeEntry(
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        op = obj["op"]?.jsonPrimitive?.content ?: "",
                        inputIds = (obj["inputIds"]?.jsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                        outputIds = (obj["outputIds"]?.jsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                        attrs = obj["attrs"]?.jsonObject ?: buildJsonObject { },
                    ))
                }
                "visitMetadata" -> { /* metadata is informational; not needed for reconstruction */ }
            }
        }

        // 第二遍：重建 UirProgram
        return buildProgram {
            for (ge in graphEntries) {
                // 构建 graph 的 input / output valueRef
                val graphInputs = ge.inputIds.map { id ->
                    buildValueRef {
                        valueId = id
                        ndim = valueMap[id] ?: 1
                    }
                }
                val graphOutputs = ge.outputIds.map { id ->
                    buildValueRef {
                        valueId = id
                        ndim = valueMap[id] ?: 1
                    }
                }

                // 构建 nodes
                val uirNodes = ge.nodes.map { ne ->
                    val nodeInputs = ne.inputIds.map { id ->
                        buildValueRef {
                            valueId = id
                            ndim = valueMap[id] ?: error("Unknown valueRef '$id' referenced by node '${ne.name}'")
                        }
                    }
                    val nodeOutputs = ne.outputIds.map { id ->
                        buildValueRef {
                            valueId = id
                            ndim = valueMap[id] ?: error("Unknown valueRef '$id' referenced by node '${ne.name}'")
                        }
                    }
                    val attrs = mutableMapOf<String, Attribute>()
                    for ((key, value) in ne.attrs) {
                        attrs[key] = when (value) {
                            is JsonPrimitive -> {
                                if (value.isString) {
                                    buildStringAttr { this.value = value.content }
                                } else {
                                    buildIntAttr { this.value = value.int }
                                }
                            }
                            else -> buildStringAttr { this.value = value.toString() }
                        }
                    }

                    buildNode {
                        name = ne.name
                        op = ne.op
                        nodeInputs.forEach { inputs.add(it) }
                        nodeOutputs.forEach { outputs.add(it) }
                        this.attributes = attrs
                    }
                }

                graphs.add(buildGraph {
                    name = ge.name
                    graphInputs.forEach { inputs.add(it) }
                    graphOutputs.forEach { outputs.add(it) }
                    uirNodes.forEach { nodes.add(it) }
                })
            }
        }
    }
}