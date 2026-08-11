package io.github.xyzboom.aiFuzzer.pattern

import kotlinx.serialization.json.*

/**
 * 从 JSON 解析 PatternDatabase。
 * 复用 kotlinx-serialization 的 JSON 解析器。
 */
object PatternParser {

    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 从 JSON 字符串解析 PatternDatabase。
     */
    fun parse(jsonString: String): PatternDatabase {
        val root = json.decodeFromString(JsonObject.serializer(), jsonString)
        return parseDatabase(root)
    }

    /**
     * 从 JSON 元素解析。
     */
    fun parse(jsonElement: JsonElement): PatternDatabase {
        return parseDatabase(jsonElement.jsonObject)
    }

    private fun parseDatabase(obj: JsonObject): PatternDatabase {
        val formatVersion = obj["format_version"]?.jsonPrimitive?.content ?: "1.0"
        val patternsArray = obj["patterns"]?.jsonArray ?: error("Missing 'patterns' array")
        
        val patterns = patternsArray.map { parsePattern(it.jsonObject) }
        return PatternDatabase(
            formatVersion = formatVersion,
            patterns = patterns,
        )
    }

    private fun parsePattern(obj: JsonObject): PatternDef {
        val id = obj["id"]?.jsonPrimitive?.content ?: error("Missing pattern id")
        val compiler = obj["compiler"]?.jsonPrimitive?.content ?: error("Missing pattern compiler")
        val target = obj["target"]?.jsonPrimitive?.contentOrNull
        val frontend = obj["frontend"]?.jsonPrimitive?.contentOrNull
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
        val severity = obj["severity"]?.jsonPrimitive?.contentOrNull

        val nodes = parseNodes(obj["nodes"]?.jsonArray ?: error("Missing nodes array"))
        val values = parseValues(obj["values"]?.jsonArray ?: JsonArray(emptyList()))
        val graphConstraints = parseGraphConstraints(obj["graphConstraints"]?.jsonObject)
        val flowConstraints = parseFlowConstraints(obj["flowConstraints"]?.jsonArray)

        return PatternDef(
            id = id,
            compiler = compiler,
            target = target,
            frontend = frontend,
            description = description,
            severity = severity,
            nodes = nodes,
            values = values,
            graphConstraints = graphConstraints,
            flowConstraints = flowConstraints,
        )
    }

    private fun parseNodes(nodesArray: JsonArray): List<PatternNodeDef> {
        return nodesArray.map { parseNode(it.jsonObject) }
    }

    private fun parseNode(obj: JsonObject): PatternNodeDef {
        val id = obj["id"]?.jsonPrimitive?.content ?: error("Missing node id")
        val op = obj["op"]?.jsonPrimitive?.content ?: error("Missing node op")
        val inputs = obj["inputs"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val outputs = obj["outputs"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        
        val attrs = mutableMapOf<String, AttrMatcher>()
        val attrsObj = obj["attrs"]?.jsonObject
        if (attrsObj != null) {
            for ((key, value) in attrsObj) {
                attrs[key] = AttrMatcher.fromJson(value)
            }
        }

        return PatternNodeDef(
            id = id,
            op = op,
            inputs = inputs,
            outputs = outputs,
            attrs = attrs,
        )
    }

    private fun parseValues(valuesArray: JsonArray): Map<String, PatternValueDef> {
        val result = mutableMapOf<String, PatternValueDef>()
        for (element in valuesArray) {
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: error("Missing value id")
            val ndim = obj["ndim"]?.let { DimMatcher.fromJson(it) }
            val shape = parseShape(obj["shape"]?.jsonArray)
            val dtype = DtypeMatcher.fromJson(obj["dtype"])
            val expressionConstraints = parseExpressionConstraints(obj["expressionConstraints"]?.jsonArray)
            if (expressionConstraints.isNotEmpty() && System.getProperty("pattern.debug") == "true") {
                println("[PatternParser] 值 '$id' 解析到 ${expressionConstraints.size} 个表达式约束: ${expressionConstraints}")
            }
            result[id] = PatternValueDef(
                id = id,
                ndim = ndim,
                shape = shape,
                dtype = dtype,
                expressionConstraints = expressionConstraints,
                range = obj["range"]?.let { ValueRangeMatcher.fromJson(it) } ?: ValueRangeMatcher.Any,
            )
        }
        return result
    }

    private fun parseExpressionConstraints(arr: JsonArray?): List<ExpressionConstraint> {
        if (arr == null) return emptyList()
        return arr.map { elem ->
            val obj = elem.jsonObject
            val dimIndices = obj["dimIndices"]!!.jsonArray.map { it.jsonPrimitive.int }
            val op = obj["op"]!!.jsonPrimitive.content
            val allowedValues = obj["allowedValues"]!!.jsonArray.map { it.jsonPrimitive.int }.toSet()
            // 兼容新旧格式：优先使用 divisors 数组，回退到旧 divisor（作用于所有维度）
            val divisors = obj["divisors"]?.jsonArray?.map { it.jsonPrimitive.int }
                ?: obj["divisor"]?.jsonPrimitive?.intOrNull?.let { d ->
                    dimIndices.map { d }  // 旧格式：单个 divisor 作用于所有维度
                }
            val excludeWhen = obj["excludeWhen"]?.jsonArray?.map { parseExcludeConstraint(it.jsonObject) }
            ExpressionConstraint(
                dimIndices = dimIndices,
                op = op,
                allowedValues = allowedValues,
                divisors = divisors,
                excludeWhen = excludeWhen,
            )
        }
    }

    private fun parseExcludeConstraint(obj: JsonObject): ExpressionConstraint {
        val dimIndices = obj["dimIndices"]!!.jsonArray.map { it.jsonPrimitive.int }
        val op = obj["op"]!!.jsonPrimitive.content
        val allowedValues = obj["allowedValues"]!!.jsonArray.map { it.jsonPrimitive.int }.toSet()
        val divisors = obj["divisors"]?.jsonArray?.map { it.jsonPrimitive.int }
            ?: obj["divisor"]?.jsonPrimitive?.intOrNull?.let { d ->
                dimIndices.map { d }
            }
        return ExpressionConstraint(
            dimIndices = dimIndices,
            op = op,
            allowedValues = allowedValues,
            divisors = divisors,
        )
    }

    private fun parseShape(shapeArray: JsonArray?): List<DimMatcher> {
        if (shapeArray == null) return emptyList()
        return shapeArray.map { DimMatcher.fromJson(it) }
    }

    private fun parseGraphConstraints(obj: JsonObject?): GraphConstraints? {
        if (obj == null) return null
        val minNodes = obj["minNodes"]?.jsonPrimitive?.intOrNull
        val maxNodes = obj["maxNodes"]?.jsonPrimitive?.intOrNull
        val requiredOps = obj["requiredOps"]?.jsonArray?.map { it.jsonPrimitive.content }
        if (minNodes == null && maxNodes == null && requiredOps == null) return null
        return GraphConstraints(
            minNodes = minNodes,
            maxNodes = maxNodes,
            requiredOps = requiredOps,
        )
    }

    private fun parseFlowConstraints(arr: JsonArray?): List<FlowConstraint>? {
        if (arr == null) return null
        val result = arr.map { parseFlowConstraint(it.jsonObject) }
        if (result.isEmpty()) return null
        return result
    }

    private fun parseFlowConstraint(obj: JsonObject): FlowConstraint {
        return FlowConstraint(
            fromNode = obj["fromNode"]?.jsonPrimitive?.content ?: error("Missing flowConstraint.fromNode"),
            fromOutput = obj["fromOutput"]?.jsonPrimitive?.intOrNull ?: 0,
            toNode = obj["toNode"]?.jsonPrimitive?.content ?: error("Missing flowConstraint.toNode"),
            toInput = obj["toInput"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }
}