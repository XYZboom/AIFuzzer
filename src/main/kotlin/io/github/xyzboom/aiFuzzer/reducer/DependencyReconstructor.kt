package io.github.xyzboom.aiFuzzer.reducer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.UirTypeKind
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
                    // wire-aroundable 算子：同图消费者已通过 WIRE_AROUND 重定向，
                    // 无需 DEFAULT_VALUE 节点；仅跨图引用仍需 FULL 节点替代
                    if (hasCrossRef) {
                        repairs.add(RepairAction(
                            type = RepairType.DEFAULT_VALUE,
                            oldValueId = outputRef.valueId,
                            oldType = outputRef.type,
                            survivingConsumers = survivingConsumers,
                        ))
                    }
                }
                // 非 wire-aroundable 算子：有消费者（或跨图引用）时创建 FULL 节点替代
                else if (hasCrossRef || survivingConsumers.isNotEmpty()) {
                    // shape 变换算子且输入直接来自 graph input → 吸收 shape 到图边界
                    // 注意：输入必须是本图真正的 fresh input（不在跨图引用中）。
                    // 若输入是跨图 input（来自其他图输出），改 shape 声明只改了本图视角，
                    // 上游实际产出的 shape 未变 → 运行时 shape 不匹配（IndexError/size mismatch）。
                    val inputIsFresh = removedNode.inputs.isNotEmpty()
                        && removedNode.inputs[0].valueId in graph.inputs.map { it.valueId }
                        && removedNode.inputs[0].valueId !in crossGraphRefs
                    if (!hasCrossRef && removedNode.op in SHAPE_TRANSFORM_OPS && inputIsFresh) {
                        repairs.add(RepairAction(
                            type = RepairType.SHAPE_ABSORB,
                            oldValueId = outputRef.valueId,
                            oldType = outputRef.type,
                            survivingConsumers = survivingConsumers,
                            targetInputValueId = removedNode.inputs[0].valueId,
                        ))
                    }
                    // 常量算子（FULL/ZEROS/ONES/ARANGE）且无跨图引用 → 提升为 graph input
                    else if (!hasCrossRef && removedNode.op in CONSTANT_OPS) {
                        repairs.add(RepairAction(
                            type = RepairType.CONSTANT_TO_INPUT,
                            oldValueId = outputRef.valueId,
                            oldType = outputRef.type,
                            survivingConsumers = survivingConsumers,
                            newInputValueId = "${outputRef.valueId}_as_input",
                        ))
                    }
                    // shape 变换算子无法 SHAPE_ABSORB（输入不贴 graph input）：不生成修复，
                    // 删除后下游 shape 不匹配 → validateGraph/propertyCheck 失败 → DDMin 回滚。
                    // 不能用 DEFAULT_VALUE（FULL 常量）替代——UNSQUEEZE 等是升维语义，
                    // FULL 常量替代后 shape 对不上（2D 常量进 conv2d 会 IndexError）。
                    else if (removedNode.op in SHAPE_TRANSFORM_OPS) {
                        // 不添加修复；删除后该输出无来源，validateGraph 失败使 DDMin 放弃该子集
                    }
                    else {
                        repairs.add(RepairAction(
                            type = RepairType.DEFAULT_VALUE,
                            oldValueId = outputRef.valueId,
                            oldType = outputRef.type,
                            survivingConsumers = survivingConsumers,
                        ))
                    }
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
                            // 保留被删节点输出的 type/shape，而非输入的 type/shape：
                            // 下游消费者期望的是被删节点输出的形状，改成输入形状会导致 size mismatch
                            input.type = repair.oldType ?: repair.newType!!
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
                RepairType.SHAPE_ABSORB -> {
                    val targetId = repair.targetInputValueId
                    if (targetId != null && repair.oldType != null) {
                        // 1. 修改 graph input 的 shape 为 shape 变换后的形状
                        for (graphInput in graph.inputs) {
                            if (graphInput.valueId == targetId) {
                                graphInput.type = repair.oldType
                            }
                        }
                        // 2. 重连消费者：指向 graph input（现在 shape 已匹配）
                        for (consumer in repair.survivingConsumers ?: emptyList()) {
                            for (consumerInput in consumer.inputs) {
                                if (consumerInput.valueId == repair.oldValueId) {
                                    consumerInput.valueId = targetId
                                    consumerInput.type = repair.oldType
                                }
                            }
                        }
                    }
                }
                RepairType.CONSTANT_TO_INPUT -> {
                    val newInputId = repair.newInputValueId
                    if (newInputId != null && repair.oldType != null) {
                        // 1. 新增 graph input（形状与被删常量一致）
                        graph.inputs.add(buildValueRef {
                            valueId = newInputId
                            type = repair.oldType
                        })
                        // 2. 重连消费者：指向新 graph input
                        for (consumer in repair.survivingConsumers ?: emptyList()) {
                            for (consumerInput in consumer.inputs) {
                                if (consumerInput.valueId == repair.oldValueId) {
                                    consumerInput.valueId = newInputId
                                    consumerInput.type = repair.oldType
                                }
                            }
                        }
                    }
                }
            }
        }
        graph.nodes.addAll(0, newNodes)
        return newNodes
    }

    private fun createZerosNode(originalValueId: String, originalType: UirTensorType): UirNode {
        val newValueId = "${originalValueId}_default"
        val outputType = buildTensorType {
            typeKind = UirTypeKind.TENSOR
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
            op = UirOpKind.FULL
            attributes["fill_value"] = buildStringAttr {
                value = "0.5"
            }
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

        val SHAPE_TRANSFORM_OPS = setOf(
            UirOpKind.EXPAND_DIMS,
            UirOpKind.SQUEEZE,
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
    /** SHAPE_ABSORB 专用：需要改 shape 的 graph input 的 valueId */
    val targetInputValueId: String? = null,
    /** CONSTANT_TO_INPUT 专用：新增 graph input 的 valueId */
    val newInputValueId: String? = null,
)

enum class RepairType {
    WIRE_AROUND,
    DEFAULT_VALUE,
    SHAPE_ABSORB,
    CONSTANT_TO_INPUT,
}