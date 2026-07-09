package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.*
import kotlin.random.Random

/** 默认算子列表（所有已实现算子，除外适配算子） */
val DefaultOps: List<UirOpKind> = UirOpKind.entries.filter { it !in UirOpKind.adapterOps }

/**
 * 生成合法 UIR 程序的配置。
 * 
 * 注意：不再包含 ndim 相关配置，因为形状由 ShapeInferer 统一推导，
 * 生成器只负责逻辑拓扑。
 */
data class GeneratorConfig(
    val seed: Long = System.currentTimeMillis(),
    val minNodesPerGraph: Int = 3,
    val maxNodesPerGraph: Int = 12,
    val minInputs: Int = 1,
    val maxInputs: Int = 4,
    val minInputNdim: Int = 1,
    val maxInputNdim: Int = 4,
    val branchProbability: Double = 0.3,
    val ops: List<String> = DefaultOps.map { it.name },
    val graphCount: Int = 1,
)

/**
 * 合法的 UIR 程序生成器。
 * 
 * 两阶段生成：
 * 1. [LogicGenerator] 生成逻辑图（DAG 拓扑、算子类型、依赖关系）
 * 2. [ShapeAdapter] 使用 ShapeInferer 做形状推导和适配（插入 expand_dims）
 * 
 * 形状推导单一源头：
 * - 形状推导逻辑只在 ShapeInferer 中实现
 * - 生成器不负责形状计算
 * - 翻译器也不应独立推导形状
 */
class UirGenerator(private val config: GeneratorConfig = GeneratorConfig()) {

    private val rand = Random(config.seed)
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
        )
    )
    private val shapeAdapter = ShapeAdapter()

    fun generate(): UirProgram {
        val program = logicGen.generate()
        for (graph in program.graphs) {
            shapeAdapter.adapt(graph, rand)
        }
        return program
    }
}