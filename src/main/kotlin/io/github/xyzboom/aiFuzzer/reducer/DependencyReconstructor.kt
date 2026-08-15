package io.github.xyzboom.aiFuzzer.reducer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.UirTypeKind
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.UirDimKind
import io.github.xyzboom.aiFuzzer.ir.builder.buildNode
import io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef
import io.github.xyzboom.aiFuzzer.ir.types.UirTensorType
import io.github.xyzboom.aiFuzzer.ir.types.UirIntAttr
import io.github.xyzboom.aiFuzzer.ir.types.UirStringAttr
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
        val graphOutputIds = graph.outputs.map { it.valueId }.toSet()

        for (removedNode in nodesToRemove) {
            for (outputRef in removedNode.outputs) {
                val consumers = fullGraph.consumersOf(outputRef.valueId)
                val survivingConsumers = consumers.filter { it !in nodesToRemove }
                val hasCrossRef = outputRef.valueId in crossGraphRefs
                // graph output 是图边界：即使本图无消费者、无跨图引用（其他图 outputs），
                // 只要该值仍被声明为 graph output，删除 producer 后必须创建替代，
                // 否则翻译出的 return 引用悬空值 → NameError。
                // 判断只看本图自身，与其他图 inputs 无关。
                val isGraphOutput = outputRef.valueId in graphOutputIds
                if (survivingConsumers.isEmpty() && !hasCrossRef && !isGraphOutput) continue

                if (removedNode.inputs.isNotEmpty() && isWireAroundable(removedNode.op)) {
                    // 找输入链上第一个"不在被删集合中"的幸存值（producer 输出或 graph input）。
                    // 否则若 sourceRef 的 producer 也在 nodesToRemove（链式删除），
                    // WIRE_AROUND 指向的替代值本身断链 → validateGraph 失败。
                    val sourceRef = findSurvivingWireSource(removedNode, nodesToRemove)
                        ?: removedNode.inputs[0]
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
                    // 无需 DEFAULT_VALUE 节点；仅 graph output 或跨图引用仍需 FULL 节点替代
                    if (hasCrossRef || isGraphOutput) {
                        repairs.add(RepairAction(
                            type = RepairType.DEFAULT_VALUE,
                            oldValueId = outputRef.valueId,
                            oldType = outputRef.type,
                            survivingConsumers = survivingConsumers,
                        ))
                    }
                }
                // 非 wire-aroundable 算子：有消费者、graph output 或跨图引用时创建 FULL 节点替代
                else if (hasCrossRef || isGraphOutput || survivingConsumers.isNotEmpty()) {
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
                    // shape 变换算子无法 SHAPE_ABSORB（输入不贴 graph input）：用 DEFAULT_VALUE 修复。
                    // 之前刻意不生成修复（删除后 validateGraph 失败使 DDMin 放弃该子集），
                    // 但混合候选 DDMin 需要所有分支都能生成修复，否则 partition 链式断链。
                    // DEFAULT_VALUE 创建 FULL 常量，shape 从被删节点输出 type 复制，shape 合法。
                    else if (removedNode.op in SHAPE_TRANSFORM_OPS) {
                        repairs.add(RepairAction(
                            type = RepairType.DEFAULT_VALUE,
                            oldValueId = outputRef.valueId,
                            oldType = outputRef.type,
                            survivingConsumers = survivingConsumers,
                        ))
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
                    // 扫描全图所有仍引用被删 valueId 的 input，重定向到常量替代。
                    // 不限于 survivingConsumers：链式删除时（如同删 RESIZE2D+GELU），
                    // 上游的 WIRE_AROUND 会把下游 consumer 指向 RESIZE2D 的输出值Id，
                    // 该值Id 在 prepare 阶段不是 GELU 的幸存消费者，但 apply 阶段已被引用。
                    for (candidate in graph.nodes) {
                        if (candidate === zerosNode) continue
                        for (input in candidate.inputs) {
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

    /**
     * 沿 wire-aroundable 被删节点的输入链向上，找第一个"不在被删集合中"的幸存值。
     * 解决链式删除（如删 SILU+TRIU+TILE）时 WIRE_AROUND 指向的 producer 也在被删集合
     * → 替代值本身断链 → validateGraph 失败的问题。
     * 返回 null 时调用方用 input[0] 兜底（不影响现有行为）。
     */
    private fun findSurvivingWireSource(
        node: UirNode,
        nodesToRemove: Set<UirNode>,
    ): UirValueRef? {
        if (node.inputs.isEmpty()) return null
        val inputRef = node.inputs[0]
        // 找该 inputRef 的 producer
        val producer = graph.nodes.find { it.outputs.any { o -> o.valueId == inputRef.valueId } }
            ?: return inputRef  // 无 producer → graph input，直接返回
        if (producer !in nodesToRemove) return inputRef  // producer 幸存 → 用 inputRef
        // producer 也在被删集合：检查 producer 输入输出 shape 是否兼容。
        // 如果形状改变（如 RESIZE2D 把 (6,3) 变成 (10,3)），WIRE_AROUND 跳过它会改变下游 shape，
        // 导致 GATHER indices 越界等形状非法。此时停止递归，返回 producer 的输出值Id，
        // 后续 DEFAULT_VALUE 修复会创建常量替代（shape 保持），下游引用被重定向到该常量。
        val producerInputShape = producer.inputs.firstOrNull()?.type?.shape
        val producerOutputShape = producer.outputs.firstOrNull()?.type?.shape
        if (producerInputShape != null && producerOutputShape != null &&
            !shapesCompatible(producerInputShape, producerOutputShape)) {
            return inputRef  // 停止递归，返回 producer 输出，后续 DEFAULT_VALUE 接管
        }
        // producer 形状兼容（RELU 等 shape 保持算子）→ 递归向上
        return findSurvivingWireSource(producer, nodesToRemove)
    }

    /** 两个 shape 是否兼容：维度数相同且每维 CONSTANT 值相等 */
    private fun shapesCompatible(
        a: io.github.xyzboom.aiFuzzer.ir.types.UirShape,
        b: io.github.xyzboom.aiFuzzer.ir.types.UirShape,
    ): Boolean {
        if (a.dims.size != b.dims.size) return false
        for (i in a.dims.indices) {
            val da = a.dims[i]
            val db = b.dims[i]
            if (da.dimKind != UirDimKind.CONSTANT || db.dimKind != UirDimKind.CONSTANT) {
                // 任一方非 CONSTANT 时保守认为不兼容（避免运行时 shape 不一致）
                return false
            }
            if (da.value != db.value) return false
        }
        return true
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