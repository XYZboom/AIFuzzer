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
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
        val severity = obj["severity"]?.jsonPrimitive?.contentOrNull

        val nodes = parseNodes(obj["nodes"]?.jsonArray ?: error("Missing nodes array"))
        val values = parseValues(obj["values"]?.jsonArray ?: JsonArray(emptyList()))

        return PatternDef(
            id = id,
            compiler = compiler,
            target = target,
            description = description,
            severity = severity,
            nodes = nodes,
            values = values,
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
            
            result[id] = PatternValueDef(
                id = id,
                ndim = ndim,
                shape = shape,
                dtype = dtype,
            )
        }
        return result
    }

    private fun parseShape(shapeArray: JsonArray?): List<DimMatcher> {
        if (shapeArray == null) return emptyList()
        return shapeArray.map { DimMatcher.fromJson(it) }
    }
}