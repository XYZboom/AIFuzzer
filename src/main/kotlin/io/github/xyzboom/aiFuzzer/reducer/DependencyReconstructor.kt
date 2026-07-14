package io.github.xyzboom.aiFuzzer.reducer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.builder.buildNode
import io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef
import io.github.xyzboom.aiFuzzer.ir.types.UirTensorType
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildShape
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDim
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDataType
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildTensorType
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildStringAttr

private val log = KotlinLogging.logger {}

/**
 * 依赖重建器：删除节点后修复依赖，保持中间程序合法。
 *
 * 策略（按优先级）：
 * - Wire-Around: 对有输入的节点，将下游引用重连到其第一个输入（语义变化最小）
 * - Default Value: 对无输入的节点（常量等），插入 ZEROS 节点作为替代生产者
 *
 * 注意：wire-around 对非 identity-like 算子会改变语义，
 * 但这正是缩减想要的——用更简单的值替代复杂计算的结果。
 * 如果属性丢失，DDMin 会拒绝这个缩减。
 */
class DependencyReconstructor(private val graph: UirGraph) {

    /**
     * 删除一组节点前的准备工作。
     *
     * 策略：
     * - 有输入的节点 → wire-around（重连到第一个输入）
     * - 无输入的节点 → 插入 ZEROS 替代
     */
    fun prepare(nodesToRemove: Set<UirNode>): DependencyRepairPlan {
        val repairs = mutableListOf<RepairAction>()
        val fullGraph = UirDependencyGraph(graph)

        for (removedNode in nodesToRemove) {
            for (outputRef in removedNode.outputs) {
                val consumers = fullGraph.consumersOf(outputRef.valueId)
                val survivingConsumers = consumers.filter { it !in nodesToRemove }
                if (survivingConsumers.isEmpty()) continue

                if (removedNode.inputs.isNotEmpty()) {
                    // Wire-around: 重连到第一个输入（无论是否 identity-like）
                    val sourceRef = removedNode.inputs[0]
                    for (consumer in survivingConsumers) {
                        for (input in consumer.inputs) {
                            if (input.valueId == outputRef.valueId) {
                                repairs.add(RepairAction(
                                    type = RepairType.WIRE_AROUND,
                                    targetNode = consumer,
                                    oldValueId = outputRef.valueId,
                                    newValueId = sourceRef.valueId,
                                    newType = sourceRef.type,
                                ))
                            }
                        }
                    }
                } else {
                    // 无输入节点（常量生成等）: 插入 ZEROS 替代
                    repairs.add(RepairAction(
                        type = RepairType.DEFAULT_VALUE,
                        oldValueId = outputRef.valueId,
                        oldType = outputRef.type,
                        survivingConsumers = survivingConsumers,
                    ))
                }
            }
        }

        return DependencyRepairPlan(repairs)
    }

    /**
     * 执行依赖重建：应用 [plan] 中的所有修复操作。
     * 返回新插入的 ZEROS 节点列表。
     */
    fun apply(plan: DependencyRepairPlan): List<UirNode> {
        val newNodes = mutableListOf<UirNode>()

        for (repair in plan.repairs) {
            when (repair.type) {
                RepairType.WIRE_AROUND -> {
                    for (input in repair.targetNode!!.inputs) {
                        if (input.valueId == repair.oldValueId) {
                            input.valueId = repair.newValueId!!
                            input.type = repair.newType!!
                        }
                    }
                }
                RepairType.DEFAULT_VALUE -> {
                    val zerosNode = createZerosNode(repair.oldValueId, repair.oldType!!)
                    newNodes.add(zerosNode)
                    val zerosOutput = zerosNode.outputs[0]
                    for (consumer in repair.survivingConsumers!!) {
                        for (input in consumer.inputs) {
                            if (input.valueId == repair.oldValueId) {
                                input.valueId = zerosOutput.valueId
                                input.type = zerosOutput.type
                            }
                        }
                    }
                }
            }
        }

        graph.nodes.addAll(newNodes)
        return newNodes
    }

    private fun createZerosNode(originalValueId: String, originalType: UirTensorType): UirNode {
        val newValueId = "${originalValueId}_default"
        val outputType = buildTensorType {
            shape = buildShape {
                for (dim in originalType.shape.dims) {
                    dims.add(buildDim {
                        dimKind = dim.dimKind
                        value = dim.value
                    })
                }
            }
            dtype = buildDataType {
                name = originalType.dtype.name
                bits = originalType.dtype.bits
            }
        }

        return buildNode {
            name = "default_${originalValueId}"
            op = UirOpKind.ZEROS
            attributes["shape"] = buildStringAttr {
                value = originalType.shape.dims.map { it.value?.toString() ?: "?" }.joinToString(",")
            }
            outputs.add(buildValueRef {
                valueId = newValueId
                type = outputType
            })
        }
    }

    companion object {
        @JvmStatic
        fun isWireAroundable(op: UirOpKind): Boolean = op in WIRE_AROUNDABLE_OPS

        val WIRE_AROUNDABLE_OPS = setOf(
            UirOpKind.RESHAPE, UirOpKind.SQUEEZE, UirOpKind.UNSQUEEZE,
            UirOpKind.EXPAND_DIMS, UirOpKind.BROADCAST_TO, UirOpKind.TRANSPOSE, UirOpKind.TILE,
            UirOpKind.CAST,
            UirOpKind.RELU, UirOpKind.LEAKY_RELU, UirOpKind.ELU,
            UirOpKind.SELU, UirOpKind.MISH, UirOpKind.HARDTANH,
            UirOpKind.GELU, UirOpKind.SILU, UirOpKind.SIGMOID, UirOpKind.TANH,
            UirOpKind.SOFTMAX, UirOpKind.LOG_SOFTMAX,
            UirOpKind.NEG, UirOpKind.ABS, UirOpKind.SIGN,
            UirOpKind.EXP, UirOpKind.LOG, UirOpKind.LOG2,
            UirOpKind.SQRT, UirOpKind.RSQRT, UirOpKind.RECIPROCAL,
            UirOpKind.CEIL, UirOpKind.FLOOR, UirOpKind.ROUND, UirOpKind.CLAMP,
        )

        val CONSTANT_OPS = setOf(
            UirOpKind.ONES, UirOpKind.ZEROS, UirOpKind.FULL, UirOpKind.ARANGE,
        )
    }
}

data class DependencyRepairPlan(
    val repairs: List<RepairAction>,
) {
    companion object {
        val EMPTY = DependencyRepairPlan(emptyList())
    }
}

data class RepairAction(
    val type: RepairType,
    val targetNode: UirNode? = null,
    val oldValueId: String,
    val newValueId: String? = null,
    val newType: UirTensorType? = null,
    val oldType: UirTensorType? = null,
    val survivingConsumers: List<UirNode>? = null,
)

enum class RepairType {
    /** 将下游引用从旧 valueId 改为新 valueId（直通替换） */
    WIRE_AROUND,
    /** 插入 ZEROS 节点作为替代生产者 */
    DEFAULT_VALUE,
}
