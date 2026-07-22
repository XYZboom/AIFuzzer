package io.github.xyzboom.aiFuzzer.reducer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
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
 */
class DependencyReconstructor(
    private val graph: UirGraph,
    /** 跨图引用的 valueId 集合——即使同图没有消费者也要创建 ZEROS */
    private val crossGraphRefs: Set<String> = emptySet(),
) {

    fun prepare(nodesToRemove: Set<UirNode>): DependencyRepairPlan {
        val repairs = mutableListOf<RepairAction>()
        val fullGraph = UirDependencyGraph(graph)

        for (removedNode in nodesToRemove) {
            for (outputRef in removedNode.outputs) {
                val consumers = fullGraph.consumersOf(outputRef.valueId)
                val survivingConsumers = consumers.filter { it !in nodesToRemove }
                val hasCrossRef = outputRef.valueId in crossGraphRefs
                if (survivingConsumers.isEmpty() && !hasCrossRef) continue

                if (removedNode.inputs.isNotEmpty() && isWireAroundable(removedNode.op)) {
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
                }
                // 只要有跨图引用或同图消费者，就创建 ZEROS 替代
                // wire-around 只修复同图消费者，跨图引用需要 ZEROS 节点
                if (hasCrossRef || survivingConsumers.isNotEmpty()) {
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
    WIRE_AROUND,
    DEFAULT_VALUE,
}