package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.UirOpKind

/** 默认算子列表（所有已实现算子，除外适配算子） */
val DefaultOps: List<UirOpKind> = UirOpKind.entries.filter { it !in UirOpKind.adapterOps }

/**
 * 形状档位配置：控制形状大小范围，确保可执行性。
 *
 * @param minDim 每个维度的最小值
 * @param maxDim 每个维度的最大值（关键参数——设小可避免 OOM）
 * @param minNdim 最小维度数
 * @param maxNdim 最大维度数
 * @param maxTotalElements 单个图所有张量的总元素上限
 * @param label 人类可读标签
 */
data class ShapeTier(
    val minDim: Int = 1,
    val maxDim: Int = 6,
    val minNdim: Int = 1,
    val maxNdim: Int = 3,
    val maxTotalElements: Long = 8_000,
    val label: String = "tiny",
)

/** 预定义形状档位注册表 */
object ShapeTiers {
    val TIERS: Map<String, ShapeTier> = mapOf(
        "tiny" to ShapeTier(1, 6, 1, 3, 8_000, "tiny"),
        "small" to ShapeTier(1, 16, 1, 4, 64_000, "small"),
        "medium" to ShapeTier(1, 32, 1, 4, 256_000, "medium"),
        "conv" to ShapeTier(2, 8, 4, 4, 16_384, "conv"),
        "extreme" to ShapeTier(0, 1, 0, 5, 1_000, "extreme"),
    )

    fun resolve(name: String): ShapeTier = TIERS[name] ?: TIERS["tiny"]!!
}

/**
 * UIR 程序生成器配置。
 */
data class GeneratorConfig(
    val seed: Long = System.currentTimeMillis(),
    val minNodesPerGraph: Int = 3,
    val maxNodesPerGraph: Int = 12,
    val minInputs: Int = 1,
    val maxInputs: Int = 4,
    val branchProbability: Double = 0.3,
    val ops: List<String> = DefaultOps.map { it.name },
    val graphCount: IntRange = 3..5,
    val minNdim: Int = 2,  // 至少 2D
    val maxNdim: Int = 4,
    val dtype: String = "float32",
    val dtypeBits: Int = 32,
    /** 形状档位名称，控制形状大小以避免 OOM */
    val shapeTier: String = "tiny",
    /** 避免生成可能导致 NaN/Inf 的算子。默认开启。
     * 开启后排除 LOG, LOG2, SQRT, RSQRT, RECIPROCAL, DIVIDE, POWER, EXP, CUMPROD */
    val avoidNaNInf: Boolean = true,
    /**
     * 避免生成向上/向下取整、argmin/argmax 等极端算子。
     * 这些算子会放大极其微小的浮点精度误差（如 1.0000001 vs 0.9999999 → 取整后 1 vs 0）。
     * 默认开启，排除 CEIL, FLOOR, ROUND, ARGMAX, ARGMIN。
     */
    val avoidExtremeOps: Boolean = true,
    /** CONCAT 最小输入数量（随机选择输入个数的下限） */
    val concatMinInputs: Int = 2,
    /** CONCAT 最大输入数量（随机选择输入个数的上限） */
    val concatMaxInputs: Int = 5,
    /** 去重配置：在生成阶段规避已知 bug pattern */
    val dedup: DedupConfig = DedupConfig(),
    /** 变异配置 */
    val mutationConfig: io.github.xyzboom.aiFuzzer.config.MutationConfig = io.github.xyzboom.aiFuzzer.config.MutationConfig(),
    /**
     * 无兼容输入时的回退策略：生成常量+形状保持链的概率。
     * 0.0 = 始终用原有适配（从已有值中随便选一个，ShapeAdapter 适配）
     * 1.0 = 始终生成所需形状的常量，插入形状保持算子链
     * 默认 0.3 = 30% 概率走常量策略，70% 走原有适配
     */
    val fallbackConstProbability: Double = 0.3,
    /**
     * 常量策略中形状保持算子链的长度范围（随机取）。
     * 例如 0..3 表示插入 0-3 个形状保持算子。
     */
    val shapePreservingChainRange: IntRange = 0..3,
)

/** 去重配置 */
data class DedupConfig(
    val enabled: Boolean = false,
    val patternDatabase: io.github.xyzboom.aiFuzzer.pattern.PatternDatabase? = null,
    val compiler: String = "tvm",
    val target: String? = "llvm",
    val frontend: String? = null,
    val maxRetries: Int = 10,
    /** 值域分析开关：启用后 pattern 可匹配值的范围（如含有零、负数等） */
    val valueRangeAnalysis: Boolean = false,
)