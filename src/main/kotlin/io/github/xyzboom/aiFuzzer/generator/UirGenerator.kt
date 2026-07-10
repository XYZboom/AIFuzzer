package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.*

/** 默认算子列表（所有已实现算子，除外适配算子） */
val DefaultOps: List<UirOpKind> = UirOpKind.entries.filter { it !in UirOpKind.adapterOps }

/**
 * 生成合法 UIR 程序的配置。
 */
data class GeneratorConfig(
    val seed: Long = System.currentTimeMillis(),
    val minNodesPerGraph: Int = 3,
    val maxNodesPerGraph: Int = 12,
    val minInputs: Int = 1,
    val maxInputs: Int = 4,
    val branchProbability: Double = 0.3,
    val ops: List<String> = DefaultOps.map { it.name },
    val graphCount: Int = 1,
    val dtype: String = "float32",
    val dtypeBits: Int = 32,
)

/**
 * UIR 程序生成器。
 * 
 * 一阶段生成：LogicGenerator 直接生成形状兼容的图。
 */
class UirGenerator(private val config: GeneratorConfig = GeneratorConfig()) {

    private val opsEnum: List<UirOpKind> = config.ops.mapNotNull {
        try { UirOpKind.valueOf(it) } catch (_: IllegalArgumentException) { null }
    }.ifEmpty { DefaultOps }

    private val logicGen = LogicGenerator(
        LogicGraphConfig(
            seed = config.seed,
            minNodesPerGraph = config.minNodesPerGraph,
            maxNodesPerGraph = config.maxNodesPerGraph,
            minInputs = config.minInputs,
            maxInputs = config.maxInputs,
            branchProbability = config.branchProbability,
            ops = opsEnum,
            graphCount = config.graphCount,
            dtype = config.dtype,
            dtypeBits = config.dtypeBits,
        )
    )

    fun generate(): UirProgram {
        return logicGen.generate()
    }
}