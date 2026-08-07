package io.github.xyzboom.aiFuzzer.pattern

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 值域表示——轻量级 [min, max] 区间，带 NaN/Inf 标志。
 * 精度放宽（over-approximate），与编译器一致：只判断"值域可能满足条件"。
 */
data class ValueRange(
    val min: Double,
    val max: Double,
    val hasNaN: Boolean = false,
    val hasInf: Boolean = false,
) {
    /** 值域是否完全确定（无 NaN/Inf/未知） */
    val isKnown: Boolean get() = min.isFinite() && max.isFinite() && !hasNaN && !hasInf

    /** 值域包含 0 */
    fun containsZero(): Boolean = min <= 0.0 && max >= 0.0

    /** 值域完全为负 */
    val isNegative: Boolean get() = max < 0.0

    /** 值域完全为正 */
    val isPositive: Boolean get() = min > 0.0

    /** 值域非负（>=0） */
    val isNonNegative: Boolean get() = min >= 0.0

    /** 值域非正（<=0） */
    val isNonPositive: Boolean get() = max <= 0.0

    /** 值域包含负数 */
    val containsNegative: Boolean get() = min < 0.0

    /** 值域包含正数 */
    val containsPositive: Boolean get() = max > 0.0

    /** 合并两个值域（取并集，over-approximate） */
    fun union(other: ValueRange): ValueRange = ValueRange(
        min = minOf(min, other.min),
        max = maxOf(max, other.max),
        hasNaN = hasNaN || other.hasNaN,
        hasInf = hasInf || other.hasInf,
    )

    companion object {
        /** 完全未知的值域——最宽的 over-approximation */
        val UNKNOWN = ValueRange(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, hasNaN = true, hasInf = true)

        /** 精确值 */
        fun exact(v: Double) = ValueRange(v, v)

        /** 区间 */
        fun range(min: Double, max: Double) = ValueRange(min, max)
    }
}

/**
 * 值域匹配器——判断值域是否满足条件。
 */
sealed class ValueRangeMatcher {
    abstract fun matches(range: ValueRange): Boolean

    /** 值域下界 < value（值域可能小于某值） */
    data class Less(val value: Double) : ValueRangeMatcher() {
        override fun matches(range: ValueRange): Boolean = range.min < value
    }

    /** 值域上界 > value（值域可能大于某值） */
    data class Greater(val value: Double) : ValueRangeMatcher() {
        override fun matches(range: ValueRange): Boolean = range.max > value
    }

    /** 值域包含 0 */
    data object ContainsZero : ValueRangeMatcher() {
        override fun matches(range: ValueRange): Boolean = range.containsZero()
    }

    /** 值域非负 */
    data object NonNegative : ValueRangeMatcher() {
        override fun matches(range: ValueRange): Boolean = range.isNonNegative
    }

    /** 值域非正 */
    data object NonPositive : ValueRangeMatcher() {
        override fun matches(range: ValueRange): Boolean = range.isNonPositive
    }

    /** 精确值 */
    data class Exact(val value: Double) : ValueRangeMatcher() {
        override fun matches(range: ValueRange): Boolean =
            range.isKnown && range.min == value && range.max == value
    }

    /** 无条件通过 */
    data object Any : ValueRangeMatcher() {
        override fun matches(range: ValueRange): Boolean = true
    }

    /** 值域是否已知（非 UNKNOWN） */
    data object Known : ValueRangeMatcher() {
        override fun matches(range: ValueRange): Boolean = range.isKnown
    }

    /** 多个约束 AND 组合 */
    data class And(val matchers: List<ValueRangeMatcher>) : ValueRangeMatcher() {
        override fun matches(range: ValueRange): Boolean = matchers.all { it.matches(range) }
    }

    companion object {
        fun fromJson(json: kotlinx.serialization.json.JsonElement): ValueRangeMatcher {
            return when {
                json is kotlinx.serialization.json.JsonPrimitive && json.isString -> {
                    when (json.content) {
                        "any" -> Any
                        "contains_zero" -> ContainsZero
                        "non_negative" -> NonNegative
                        "non_positive" -> NonPositive
                        else -> Any
                    }
                }
                json is kotlinx.serialization.json.JsonObject -> {
                    val obj = json.jsonObject
                    // 解析多个约束条件（AND 组合）
                    val matchers = mutableListOf<ValueRangeMatcher>()
                    if (obj.containsKey("\$lt")) matchers.add(Less((obj["\$lt"]!! as JsonPrimitive).content.toDouble()))
                    if (obj.containsKey("\$gt")) matchers.add(Greater((obj["\$gt"]!! as JsonPrimitive).content.toDouble()))
                    if (obj.containsKey("\$eq")) matchers.add(Exact((obj["\$eq"]!! as JsonPrimitive).content.toDouble()))
                    if (obj.containsKey("\$contains_zero")) matchers.add(ContainsZero)
                    if (obj.containsKey("\$non_negative")) matchers.add(NonNegative)
                    if (obj.containsKey("\$non_positive")) matchers.add(NonPositive)
                    if (obj.containsKey("\$known")) matchers.add(Known)
                    when {
                        matchers.isEmpty() -> Any
                        matchers.size == 1 -> matchers[0]
                        else -> And(matchers)
                    }
                }
                else -> Any
            }
        }
    }
}

/**
 * 值域分析器——根据 op 类型和输入值域推断输出值域。
 * 精度放宽（over-approximate），只做简单区间运算。
 */
object ValueRangeAnalyzer {

    /**
     * 计算给定 op 的输出值域。
     * @param op op 类型
     * @param inputRanges 输入值域列表（按位置对应）
     * @param attrs 节点属性（用于常量节点取 fill_value 等）
     * @return 输出值域
     */
    fun outputRange(
        op: String,
        inputRanges: List<ValueRange>,
        attrs: Map<String, Any>
    ): ValueRange {
        return when (op) {
            // === 常量节点 ===
            "ZEROS" -> ValueRange.exact(0.0)
            "ONES" -> ValueRange.exact(1.0)
            "FULL" -> {
                val fillStr = attrs["fill_value"]?.toString()
                if (fillStr != null) {
                    try { ValueRange.exact(fillStr.toDouble()) }
                    catch (_: NumberFormatException) { ValueRange.UNKNOWN }
                } else ValueRange.UNKNOWN
            }
            "ARANGE" -> {
                // ARANGE 生成 1-D 张量，值范围 [start, stop)
                // 没有 attrs 记录 start/stop，用 UNKNOWN
                ValueRange.UNKNOWN
            }

            // === 一元算子 ===
            "ABS" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                ValueRange(0.0, maxOf(abs(r.min), abs(r.max)), hasNaN = r.hasNaN, hasInf = r.hasInf)
            }
            "NEG" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                ValueRange(-r.max, -r.min, hasNaN = r.hasNaN, hasInf = r.hasInf)
            }
            "EXP" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                val eMin = exp(r.min)
                val eMax = exp(r.max)
                // 只有实际溢出时才标记 hasInf
                val hasInf = r.hasInf || !eMax.isFinite()
                ValueRange(eMin, if (eMax.isFinite()) eMax else Double.MAX_VALUE, hasInf = hasInf)
            }
            "LOG" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                if (r.min <= 0.0) ValueRange.UNKNOWN  // 输入可能 <=0 → NaN
                else ValueRange(log(r.min), log(r.max))
            }
            "LOG2" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                if (r.min <= 0.0) ValueRange.UNKNOWN  // 输入可能 <=0 → -inf/NaN
                else ValueRange(log2(r.min), log2(r.max))
            }
            "SQRT" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                if (r.min < 0.0) ValueRange.UNKNOWN  // 输入可能为负 → NaN
                else ValueRange(sqrt(r.min), sqrt(r.max))
            }
            "RSQRT" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                if (r.min <= 0.0) ValueRange.UNKNOWN  // 输入可能 <=0 → NaN/Inf
                else ValueRange(1.0 / sqrt(r.max), 1.0 / sqrt(r.min))
            }
            "RECIPROCAL" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                if (r.containsZero()) ValueRange.UNKNOWN  // 包含0 → ±inf
                else ValueRange(1.0 / r.max, 1.0 / r.min)
            }
            "SIGN" -> ValueRange(-1.0, 1.0)
            "FLOOR" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                ValueRange(floor(r.min), floor(r.max), hasNaN = r.hasNaN, hasInf = r.hasInf)
            }
            "CEIL" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                ValueRange(ceil(r.min), ceil(r.max), hasNaN = r.hasNaN, hasInf = r.hasInf)
            }
            "ROUND" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                ValueRange(floor(r.min), ceil(r.max), hasNaN = r.hasNaN, hasInf = r.hasInf)
            }
            "RELU" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                ValueRange(maxOf(0.0, r.min), maxOf(0.0, r.max), hasNaN = r.hasNaN, hasInf = r.hasInf)
            }
            "LEAKY_RELU" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                // leaky_relu(x) = x if x>0 else alpha*x (alpha≈0.01)
                // 负值被缩小到 alpha 倍，更接近 0
                val alpha = 0.01
                val newMin = if (r.min < 0.0) alpha * r.min else r.min
                val newMax = if (r.max < 0.0) alpha * r.max else r.max
                ValueRange(newMin, newMax, hasNaN = r.hasNaN, hasInf = r.hasInf)
            }
            "SELU" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                // selu(x) = scale * (max(0,x) + min(0, alpha*(exp(x)-1)))
                // scale≈1.05, alpha≈1.67, 负值输出范围 ≈ [-1.05*alpha, 0) ≈ [-1.76, 0)
                val newMin = minOf(r.min, -1.76)
                val newMax = maxOf(r.max, 0.0)
                ValueRange(newMin, newMax, hasNaN = r.hasNaN, hasInf = r.hasInf)
            }
            "MISH" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                // mish(x) = x * tanh(softplus(x)), range ≈ [-0.31, +inf)
                val newMin = minOf(r.min, -0.31)
                ValueRange(newMin, r.max, hasNaN = r.hasNaN, hasInf = r.hasInf)
            }
            "HARDTANH" -> {
                // hardtanh(x) = clamp(x, -1, 1)
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                ValueRange(
                    min = maxOf(r.min, -1.0),
                    max = minOf(r.max, 1.0),
                    hasNaN = r.hasNaN
                )
            }
            "CLAMP" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                val minVal = attrs["min"]?.toString()?.toDoubleOrNull()
                val maxVal = attrs["max"]?.toString()?.toDoubleOrNull()
                if (minVal != null && maxVal != null) {
                    ValueRange(
                        min = maxOf(r.min, minVal),
                        max = minOf(r.max, maxVal),
                        hasNaN = r.hasNaN
                    )
                } else ValueRange(r.min, r.max, hasNaN = r.hasNaN, hasInf = r.hasInf)
            }
            "ELU" -> {
                // elu(x) = alpha*(exp(x)-1) for x<0, x for x>=0, alpha≈1
                // 输出范围 ≈ [min(-1, r.min), r.max]，受 alpha 影响
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                // 放宽：负值输出范围 [-1, 0)，正值不变
                val newMin = minOf(r.min, -1.0)
                ValueRange(newMin, r.max, hasNaN = r.hasNaN, hasInf = r.hasInf)
            }
            "SILU" -> {
                // silu(x) = x * sigmoid(x), range ≈ [-0.28, +inf)
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                val newMin = minOf(r.min, -0.28)
                ValueRange(newMin, r.max, hasNaN = r.hasNaN, hasInf = r.hasInf)
            }
            "GELU" -> {
                // gelu(x) ≈ x*Φ(x), range ≈ [-0.17, +inf)
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                val r = inputRanges[0]
                val newMin = minOf(r.min, -0.17)
                ValueRange(newMin, r.max, hasNaN = r.hasNaN, hasInf = r.hasInf)
            }
            "TANH" -> ValueRange(-1.0, 1.0)
            "SIGMOID" -> ValueRange(0.0, 1.0)
            "SOFTMAX" -> ValueRange(0.0, 1.0)
            "LOG_SOFTMAX" -> ValueRange(Double.NEGATIVE_INFINITY, 0.0, hasInf = true)

            // === 二元算子 ===
            "ADD" -> {
                if (inputRanges.size < 2) return ValueRange.UNKNOWN
                val a = inputRanges[0]; val b = inputRanges[1]
                ValueRange(a.min + b.min, a.max + b.max, hasNaN = a.hasNaN || b.hasNaN, hasInf = a.hasInf || b.hasInf)
            }
            "SUBTRACT" -> {
                if (inputRanges.size < 2) return ValueRange.UNKNOWN
                val a = inputRanges[0]; val b = inputRanges[1]
                ValueRange(a.min - b.max, a.max - b.min, hasNaN = a.hasNaN || b.hasNaN, hasInf = a.hasInf || b.hasInf)
            }
            "MULTIPLY" -> {
                if (inputRanges.size < 2) return ValueRange.UNKNOWN
                val a = inputRanges[0]; val b = inputRanges[1]
                val products = listOf(a.min * b.min, a.min * b.max, a.max * b.min, a.max * b.max)
                ValueRange(products.min(), products.max(), hasNaN = a.hasNaN || b.hasNaN, hasInf = a.hasInf || b.hasInf)
            }
            "DIVIDE" -> {
                if (inputRanges.size < 2) return ValueRange.UNKNOWN
                val a = inputRanges[0]; val b = inputRanges[1]
                if (b.containsZero()) ValueRange.UNKNOWN  // 分母可能为0 → ±inf/NaN
                else {
                    val quotients = listOf(a.min / b.min, a.min / b.max, a.max / b.min, a.max / b.max)
                    ValueRange(quotients.min(), quotients.max(), hasNaN = a.hasNaN || b.hasNaN, hasInf = a.hasInf || b.hasInf)
                }
            }
            "MAXIMUM" -> {
                if (inputRanges.size < 2) return ValueRange.UNKNOWN
                val a = inputRanges[0]; val b = inputRanges[1]
                ValueRange(
                    min = maxOf(a.min, b.min),
                    max = maxOf(a.max, b.max),
                    hasNaN = a.hasNaN || b.hasNaN,
                    hasInf = a.hasInf || b.hasInf,
                )
            }
            "MINIMUM" -> {
                if (inputRanges.size < 2) return ValueRange.UNKNOWN
                val a = inputRanges[0]; val b = inputRanges[1]
                ValueRange(
                    min = minOf(a.min, b.min),
                    max = minOf(a.max, b.max),
                    hasNaN = a.hasNaN || b.hasNaN,
                    hasInf = a.hasInf || b.hasInf,
                )
            }
            "MATMUL" -> {
                // 矩阵乘法：输出范围取决于输入元素范围
                // 对 over-approximate 而言，元素范围相乘足够
                if (inputRanges.size < 2) return ValueRange.UNKNOWN
                val a = inputRanges[0]; val b = inputRanges[1]
                val products = listOf(a.min * b.min, a.min * b.max, a.max * b.min, a.max * b.max)
                ValueRange(products.min(), products.max(), hasNaN = a.hasNaN || b.hasNaN, hasInf = a.hasInf || b.hasInf)
            }
            "POWER" -> ValueRange.UNKNOWN  // 幂运算值域复杂，放宽

            // === 一元三角/杂项 ===
            "SIN", "COS" -> ValueRange(-1.0, 1.0)

            // === 汇总/归约 ===
            "REDUCE_MAX" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                // reduce_max 的最大值 = 输入 max
                inputRanges[0]
            }
            "REDUCE_MIN" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                // reduce_min 的最小值 = 输入 min
                inputRanges[0]
            }
            "REDUCE_MEAN" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                // mean 在输入范围内
                inputRanges[0]
            }
            "REDUCE_SUM" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                // sum 的范围可能比输入宽（多元素累加），保守用输入范围
                inputRanges[0]
            }
            "ARGMIN", "ARGMAX" -> ValueRange.UNKNOWN  // 索引值，放宽

            // === 形状变换（不改变元素值） ===
            "RESHAPE", "TRANSPOSE", "SQUEEZE", "UNSQUEEZE",
            "BROADCAST_TO", "TILE", "EXPAND_DIMS",
            "CAST", "STRIDED_SLICE", "GATHER" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                inputRanges[0]
            }
            // CONCAT 拼接多个输入，取并集
            "CONCAT", "SPLIT" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                inputRanges.reduce { a, b -> a.union(b) }
            }

            // === 池化（对元素做统计，范围不超出输入） ===
            "MAX_POOL2D", "AVG_POOL2D" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                inputRanges[0]
            }

            // === 其他（不变换值） ===
            "INTERPOLATE", "RESIZE2D" -> {
                if (inputRanges.isEmpty()) return ValueRange.UNKNOWN
                inputRanges[0]
            }

            // === 其他 ===
            else -> ValueRange.UNKNOWN
        }
    }

    private fun abs(v: Double) = if (v < 0) -v else v
    private fun exp(v: Double) = kotlin.math.exp(v)
    private fun log(v: Double) = kotlin.math.log(v, kotlin.math.E)
    private fun log2(v: Double) = kotlin.math.log2(v)
    private fun sqrt(v: Double) = kotlin.math.sqrt(v)
    private fun floor(v: Double) = kotlin.math.floor(v)
    private fun ceil(v: Double) = kotlin.math.ceil(v)
    private fun maxOf(a: Double, b: Double) = if (a > b) a else b
    private fun minOf(a: Double, b: Double) = if (a < b) a else b
}