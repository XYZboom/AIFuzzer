package io.github.xyzboom.aiFuzzer.pattern

import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import kotlinx.serialization.json.*

/**
 * 维度匹配器：支持精确匹配、范围匹配、通配符等。
 * 从 JSON 节点解析，兼容多种格式。
 */
sealed class DimMatcher {
    /** 检查给定的维度值是否匹配 */
    abstract fun matches(dimValue: Int?): Boolean

    /** 精确等于 */
    data class Exact(val value: Int) : DimMatcher() {
        override fun matches(dimValue: Int?): Boolean = dimValue == value
    }

    /** 不等于 */
    data class NotEqual(val value: Int) : DimMatcher() {
        override fun matches(dimValue: Int?): Boolean = dimValue != null && dimValue != value
    }

    /** 大于 */
    data class Greater(val value: Int) : DimMatcher() {
        override fun matches(dimValue: Int?): Boolean = dimValue != null && dimValue > value
    }

    /** 大于等于 */
    data class GreaterOrEqual(val value: Int) : DimMatcher() {
        override fun matches(dimValue: Int?): Boolean = dimValue != null && dimValue >= value
    }

    /** 小于 */
    data class Less(val value: Int) : DimMatcher() {
        override fun matches(dimValue: Int?): Boolean = dimValue != null && dimValue < value
    }

    /** 小于等于 */
    data class LessOrEqual(val value: Int) : DimMatcher() {
        override fun matches(dimValue: Int?): Boolean = dimValue != null && dimValue <= value
    }

    /** 在列表中 */
    data class InList(val values: List<Int>) : DimMatcher() {
        override fun matches(dimValue: Int?): Boolean = dimValue != null && dimValue in values
    }

    /** 模数匹配（如偶数：$mod 2，奇数：$mod {"d":2, "r":1}） */
    data class Mod(val divisor: Int, val remainder: Int = 0) : DimMatcher() {
        override fun matches(dimValue: Int?): Boolean =
            dimValue != null && dimValue % divisor == remainder
    }

    /** 任意值（通配符） */
    data class Any(val wildcard: Boolean = true) : DimMatcher() {
        override fun matches(dimValue: Int?): Boolean = true
    }

    /** 组合匹配：所有子匹配器都必须通过（AND 逻辑） */
    data class And(val matchers: List<DimMatcher>) : DimMatcher() {
        override fun matches(dimValue: Int?): Boolean =
            matchers.all { it.matches(dimValue) }
    }

    companion object {
        /**
         * 从 JSON 元素解析 DimMatcher。
         * 支持格式：
         *   - 纯数字: 42 → Exact(42)
         *   - 对象: {"$gte": 2} → GreaterOrEqual(2)
         *   - 复合对象: {"$gte": 6, "$mod": {"d": 2, "r": 0}} → And([GreaterOrEqual(6), Mod(2, 0)])
         *   - 通配: {"$any": true} → Any(true)
         */
        fun fromJson(json: JsonElement): DimMatcher {
            return when {
                // 纯数字 → 精确匹配
                json is JsonPrimitive && json.contentOrNull?.toIntOrNull() != null -> {
                    Exact(json.jsonPrimitive.int)
                }
                // 对象 → 根据键名判断
                json is JsonObject -> {
                    val obj = json.jsonObject
                    // 检查是否有多个匹配键（复合条件）
                    val matchKeys = listOf("\$eq", "\$ne", "\$gt", "\$gte", "\$lt", "\$lte", "\$in", "\$mod", "\$any")
                    val presentKeys = matchKeys.filter { obj.containsKey(it) }

                    if (presentKeys.isEmpty()) {
                        Any(true)
                    } else if (presentKeys.size == 1) {
                        parseSingleMatcher(obj, presentKeys[0])
                    } else {
                        // 复合条件：用 AND 组合
                        And(presentKeys.map { parseSingleMatcher(obj, it) })
                    }
                }
                else -> Any(true) // 兜底
            }
        }

        private fun parseSingleMatcher(obj: JsonObject, key: String): DimMatcher {
            return when (key) {
                "\$eq" -> Exact(obj["\$eq"]!!.jsonPrimitive.int)
                "\$ne" -> NotEqual(obj["\$ne"]!!.jsonPrimitive.int)
                "\$gt" -> Greater(obj["\$gt"]!!.jsonPrimitive.int)
                "\$gte" -> GreaterOrEqual(obj["\$gte"]!!.jsonPrimitive.int)
                "\$lt" -> Less(obj["\$lt"]!!.jsonPrimitive.int)
                "\$lte" -> LessOrEqual(obj["\$lte"]!!.jsonPrimitive.int)
                "\$in" -> InList(obj["\$in"]!!.jsonArray.map { it.jsonPrimitive.int })
                "\$mod" -> {
                    val modVal = obj["\$mod"]!!
                    if (modVal is JsonPrimitive) {
                        Mod(modVal.int)
                    } else {
                        val modObj = modVal.jsonObject
                        Mod(modObj["d"]!!.jsonPrimitive.int, modObj["r"]?.jsonPrimitive?.int ?: 0)
                    }
                }
                "\$any" -> Any(true)
                else -> Any(true)
            }
        }
    }
}

/**
 * 属性匹配器：支持精确匹配、列表匹配等。
 */
sealed class AttrMatcher {
    abstract fun matches(actual: Any?): Boolean

    data class ExactInt(val value: Int) : AttrMatcher() {
        override fun matches(actual: Any?): Boolean = actual is Int && actual == value
    }

    data class ExactString(val value: String) : AttrMatcher() {
        override fun matches(actual: Any?): Boolean = actual is String && actual == value
    }

    /** 精确匹配整数列表，如 pool_size=[2,2] */
    data class ExactIntList(val values: List<Int>) : AttrMatcher() {
        override fun matches(actual: Any?): Boolean {
            if (actual !is List<*>) return false
            if (actual.size != values.size) return false
            return actual.zip(values).all { (a, v) -> a is Int && a == v }
        }
    }

    data class InList(val values: List<String>) : AttrMatcher() {
        override fun matches(actual: Any?): Boolean = actual is String && actual in values
    }

    data class AnyAttr(val wildcard: Boolean = true) : AttrMatcher() {
        override fun matches(actual: Any?): Boolean = true
    }

    /** 不等于整数 */
    data class NotInt(val value: Int) : AttrMatcher() {
        override fun matches(actual: Any?): Boolean = actual is Int && actual != value
    }

    /** 不等于整数列表 */
    data class NotIntList(val values: List<Int>) : AttrMatcher() {
        override fun matches(actual: Any?): Boolean {
            if (actual !is List<*>) return false
            if (actual.size != values.size) return false
            return actual.zip(values).any { (a, v) -> a is Int && a != v }
        }
    }

    companion object {
        fun fromJson(json: JsonElement): AttrMatcher {
            return when {
                json is JsonPrimitive && json.isString -> ExactString(json.content)
                json is JsonPrimitive && json.contentOrNull?.toIntOrNull() != null -> ExactInt(json.int)
                json is JsonObject -> {
                    val obj = json.jsonObject
                    when {
                        obj.containsKey("\$in") -> InList(obj["\$in"]!!.jsonArray.map { it.jsonPrimitive.content })
                        obj.containsKey("\$eq") -> {
                            val eqVal = obj["\$eq"]!!
                            when {
                                eqVal is JsonPrimitive && eqVal.contentOrNull?.toIntOrNull() != null -> ExactInt(eqVal.int)
                                eqVal is JsonPrimitive && eqVal.isString -> ExactString(eqVal.content)
                                eqVal is JsonArray -> ExactIntList(eqVal.map { it.jsonPrimitive.int })
                                else -> AnyAttr(true)
                            }
                        }
                        obj.containsKey("\$ne") -> {
                            val neVal = obj["\$ne"]!!
                            when {
                                neVal is JsonPrimitive && neVal.contentOrNull?.toIntOrNull() != null -> NotInt(neVal.int)
                                neVal is JsonArray -> NotIntList(neVal.map { it.jsonPrimitive.int })
                                else -> AnyAttr(true)
                            }
                        }
                        else -> AnyAttr(true)
                    }
                }
                json is JsonArray -> {
                    // Direct array: [2, 2] → ExactIntList
                    ExactIntList(json.map { it.jsonPrimitive.int })
                }
                else -> AnyAttr(true)
            }
        }
    }
}

/**
 * Dtype 匹配器
 */
sealed class DtypeMatcher {
    abstract fun matches(name: String, bits: Int): Boolean

    data class Exact(val name: String) : DtypeMatcher() {
        override fun matches(name: String, bits: Int): Boolean = this.name == name
    }

    data class InList(val names: List<String>) : DtypeMatcher() {
        override fun matches(name: String, bits: Int): Boolean = name in names
    }

    data class AnyDtype(val wildcard: Boolean = true) : DtypeMatcher() {
        override fun matches(name: String, bits: Int): Boolean = true
    }

    companion object {
        fun fromJson(json: JsonElement?): DtypeMatcher {
            if (json == null) return AnyDtype(true)
            return when {
                json is JsonPrimitive && json.isString -> Exact(json.content)
                json is JsonObject -> {
                    val obj = json.jsonObject
                    when {
                        obj.containsKey("\$in") -> InList(obj["\$in"]!!.jsonArray.map { it.jsonPrimitive.content })
                        else -> AnyDtype(true)
                    }
                }
                else -> AnyDtype(true)
            }
        }
    }
}

/**
 * Pattern 中的节点定义——匹配一个 UirNode。
 */
data class PatternNodeDef(
    val id: String,
    val op: String,                     // 精确匹配 op 名称，如 "CONV2D"
    val inputs: List<String>,           // 引用 value ID 列表
    val outputs: List<String>,          // 引用 value ID 列表
    val attrs: Map<String, AttrMatcher>, // 属性匹配
)

/**
 * Pattern 中的值定义——匹配一个 UirValueRef 的 shape/dtype。
 */
data class PatternValueDef(
    val id: String,
    val ndim: DimMatcher?,
    val shape: List<DimMatcher>,
    val dtype: DtypeMatcher,
)

/**
 * 单个 Pattern 定义。
 */
data class PatternDef(
    val id: String,
    val compiler: String,          // "tvm", "pytorch", "onnx"
    val target: String? = null,    // "cuda", "llvm", null = any
    val description: String? = null,
    val severity: String? = null,  // "crash", "silent_correctness", "error"
    val nodes: List<PatternNodeDef>,
    val values: Map<String, PatternValueDef>,
)

/**
 * Pattern 数据库。
 */
data class PatternDatabase(
    val formatVersion: String = "1.0",
    val patterns: List<PatternDef>,
) {
    /**
     * 按 compiler 和 target 筛选 pattern。
     */
    fun filter(compiler: String? = null, target: String? = null): List<PatternDef> {
        return patterns.filter { p ->
            (compiler == null || p.compiler == compiler) &&
            (target == null || p.target == null || p.target == target)
        }
    }
}