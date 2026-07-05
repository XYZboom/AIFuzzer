package io.github.xyzboom.aiFuzzer.ir.serialize

import io.github.xyzboom.aiFuzzer.ir.*

/**
 * 将 [UirProgram] 序列化为 JSON Lines (JSONL) 格式。
 *
 * 每行一个 JSON 对象，表示 IR 图的一个实体（graph / node / valueRef）。
 *
 * 格式：
 *   {"kind":"graph","name":"graph_0","inputIds":["v_0","v_1"],"outputIds":["v_2"]}
 *   {"kind":"node","name":"relu","op":"relu","inputIds":["v_0"],"outputIds":["v_2"],"attrs":{}}
 *   {"kind":"valueRef","id":"v_0","ndim":3}
 *
 * 这种格式比单一嵌套 JSON 更易增量追加、grep 和 diff。
 */
object UirSerializer {

    /**
     * 将 UirProgram 序列化为 JSONL 字符串（每行一个 JSON 对象）。
     */
    fun toJsonl(program: UirProgram): String {
        val sb = StringBuilder()

        // Metadata header
        sb.appendLine("""{"kind":"metadata","graphCount":${program.graphs.size},"graphNames":${jsonArray(program.graphs.map { it.name })}}""")

        for (graph in program.graphs) {
            // Graph entry
            val graphInputIds = jsonArray(graph.inputs.map { it.valueId })
            val graphOutputIds = jsonArray(graph.outputs.map { it.valueId })
            sb.appendLine("""{"kind":"graph","name":${jsonString(graph.name)},"inputIds":$graphInputIds,"outputIds":$graphOutputIds}""")

            // All value refs in this graph
            val seenIds = mutableSetOf<String>()
            for (valueRef in graph.inputs) {
                if (seenIds.add(valueRef.valueId)) {
                    sb.appendLine("""{"kind":"valueRef","id":${jsonString(valueRef.valueId)},"ndim":${valueRef.ndim}}""")
                }
            }
            for (valueRef in graph.outputs) {
                if (seenIds.add(valueRef.valueId)) {
                    sb.appendLine("""{"kind":"valueRef","id":${jsonString(valueRef.valueId)},"ndim":${valueRef.ndim}}""")
                }
            }

            // Nodes
            for (node in graph.nodes) {
                val inputIds = jsonArray(node.inputs.map { it.valueId })
                val outputIds = jsonArray(node.outputs.map { it.valueId })
                val attrs = serializeAttributes(node.attributes)
                sb.appendLine("""{"kind":"node","name":${jsonString(node.name)},"op":${jsonString(node.op)},"inputIds":$inputIds,"outputIds":$outputIds,"attrs":$attrs}""")

                // Value refs inside the node
                for (valueRef in node.inputs) {
                    if (seenIds.add(valueRef.valueId)) {
                        sb.appendLine("""{"kind":"valueRef","id":${jsonString(valueRef.valueId)},"ndim":${valueRef.ndim}}""")
                    }
                }
                for (valueRef in node.outputs) {
                    if (seenIds.add(valueRef.valueId)) {
                        sb.appendLine("""{"kind":"valueRef","id":${jsonString(valueRef.valueId)},"ndim":${valueRef.ndim}}""")
                    }
                }
            }
        }

        return sb.toString()
    }

    private fun serializeAttributes(attrs: Map<String, Attribute>): String {
        val entries = attrs.entries.joinToString(",") { (key, value) ->
            val jsonValue = when (value) {
                is io.github.xyzboom.aiFuzzer.ir.types.UirIntAttr -> value.value.toString()
                is io.github.xyzboom.aiFuzzer.ir.types.UirStringAttr -> jsonString(value.value)
                else -> jsonString(value.toString())
            }
            "${jsonString(key)}:$jsonValue"
        }
        return "{$entries}"
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""

    private fun jsonArray(items: List<String>): String =
        "[" + items.joinToString(",") { jsonString(it) } + "]"
}