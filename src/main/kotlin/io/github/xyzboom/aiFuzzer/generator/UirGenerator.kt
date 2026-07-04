package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import kotlin.random.Random

/**
 * 生成合法 UIR 程序的配置。
 */
data class GeneratorConfig(
    val seed: Long = System.currentTimeMillis(),
    /** 每个 graph 的最小节点数 */
    val minNodesPerGraph: Int = 3,
    /** 每个 graph 的最大节点数 */
    val maxNodesPerGraph: Int = 8,
    /** 每个 graph 的最小输入数 */
    val minInputs: Int = 1,
    /** 每个 graph 的最大输入数 */
    val maxInputs: Int = 3,
    /** 参与生成的算子列表 */
    val ops: List<String> = listOf(
        "add", "subtract", "multiply", "divide",
        "matmul",
        "relu", "sigmoid", "tanh",
        "softmax",
        "abs", "exp", "log", "sqrt",
        "reshape", "transpose", "concat",
        "reduce_sum", "reduce_mean",
        "max", "min",
    ),
    /** 生成的 graph 数量 */
    val graphCount: Int = 1,
)

/**
 * 合法的 UIR 程序生成器。
 *
 * 生成的 IR 程序保证：
 * - 图是 DAG（拓扑顺序构建）
 * - 每个节点引用之前已定义的 value
 * - 输出节点引用已知的 value
 */
class UirGenerator(private val config: GeneratorConfig = GeneratorConfig()) {

    private val rand = Random(config.seed)

    fun generate(): UirProgram = buildProgram {
        repeat(config.graphCount) { graphIdx ->
            graphs.add(generateGraph("graph_$graphIdx"))
        }
    }

    private fun generateGraph(name: String): UirGraph = buildGraph {
        this.name = name
        var valueIdCounter = 0
        fun nextValueId(): String = "v_${valueIdCounter++}"

        // 1. 生成输入
        val inputCount = config.minInputs + rand.nextInt(config.maxInputs - config.minInputs + 1)
        val availableValues = mutableListOf<String>()
        repeat(inputCount) {
            val vid = nextValueId()
            inputs.add(buildValueRef { valueId = vid })
            availableValues.add(vid)
        }

        // 2. 生成节点
        val nodeCount = config.minNodesPerGraph + rand.nextInt(config.maxNodesPerGraph - config.minNodesPerGraph + 1)
        repeat(nodeCount) {
            val op = config.ops[rand.nextInt(config.ops.size)]

            // 确定输入数量。单输入 op 始终 1 个输入；二元 op 至少 2 个
            val minInputForOp = if (op in singleInputOps) 1 else 2
            val maxInputForOp = if (op in singleInputOps) 1 else 2.coerceAtMost(availableValues.size)
            val actualInputCount = if (maxInputForOp < minInputForOp) {
                // 可用值不够，重复使用同一个值
                minInputForOp
            } else {
                minInputForOp + rand.nextInt(maxInputForOp - minInputForOp + 1)
            }
            val chosenInputs = availableValues.shuffled(rand).let { list ->
                val result = mutableListOf<String>()
                result.addAll(list.take(actualInputCount))
                // 如果不够，重复取第一个
                while (result.size < actualInputCount) {
                    result.add(list[0])
                }
                result
            }

            // 生成输出
            val outputCount = if (op in multiOutputOps) 2 else 1
            val outputIds = (1..outputCount).map { nextValueId() }

            // 生成 attributes
            val attrs = generateAttributes(op)

            val nodeOp = op
            nodes.add(buildNode {
                this.name = nodeOp
                this.op = nodeOp
                chosenInputs.forEach { inputs.add(buildValueRef { valueId = it }) }
                outputIds.forEach { outputs.add(buildValueRef { valueId = it }) }
                if (attrs.isNotEmpty()) {
                    attributes = attrs.toMutableMap()
                }
            })

            // 新 value 加入可用池
            availableValues.addAll(outputIds)
        }

        // 3. 选择输出（从可用值中随机选 1-3 个）
        val outputCount = 1.coerceAtLeast(rand.nextInt(availableValues.size).coerceAtLeast(1))
        val chosenOutputs = availableValues.shuffled(rand).take(outputCount)
        chosenOutputs.forEach { outputs.add(buildValueRef { valueId = it }) }
    }

    private fun generateAttributes(op: String): Map<String, Attribute> {
        return when (op) {
            "softmax", "concat", "split" -> mapOf(
                "axis" to buildIntAttr { value = -1 }
            )
            "reshape" -> mapOf(
                "shape" to buildIntAttr { value = -1 }
            )
            else -> emptyMap()
        }
    }

    companion object {
        /** 单输入算子 */
        val singleInputOps = setOf(
            "relu", "sigmoid", "tanh", "gelu", "silu",
            "abs", "exp", "log", "sqrt", "neg",
            "softmax",
            "reshape", "squeeze", "unsqueeze",
        )

        /** 多输出算子 */
        val multiOutputOps = setOf("topk", "split")

        /** 默认算子列表 */
        val defaultOps = listOf(
            "add", "subtract", "multiply", "divide",
            "matmul",
            "relu", "sigmoid", "tanh",
            "softmax",
            "abs", "exp", "log", "sqrt",
            "reshape", "transpose", "concat",
            "reduce_sum", "reduce_mean",
            "max", "min",
        )
    }
}