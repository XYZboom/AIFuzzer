package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import kotlin.random.Random

/**
 * 逻辑图配置。
 */
data class LogicGraphConfig(
    val seed: Long,
    val minNodesPerGraph: Int = 3,
    val maxNodesPerGraph: Int = 12,
    val minInputs: Int = 1,
    val maxInputs: Int = 4,
    val branchProbability: Double = 0.3,
    val ops: List<UirOpKind> = UirOpKind.entries.filter { it !in UirOpKind.adapterOps },
    val graphCount: Int = 1,
)

/**
 * 逻辑图生成器。
 * 
 * 职责：生成 DAG 拓扑、算子类型、依赖关系（不负责形状）。
 * 输出：每个节点的 inputs/outputs 都是 ValueRef，但 type.shape 为空或占位。
 */
class LogicGenerator(private val config: LogicGraphConfig) {
    
    private val rand = Random(config.seed)
    private var valueCounter = 0
    
    /**
     * 生成完整的 UIR 程序（逻辑图，无形状）。
     */
    fun generate(): UirProgram {
        return buildProgram {
            for (i in 0 until config.graphCount) {
                graphs.add(generateGraph("graph_$i"))
            }
        }
    }
    
    private fun generateGraph(name: String): UirGraph {
        valueCounter = 0
        
        // 1. 生成图输入（无形状）
        val numInputs = rand.nextInt(config.minInputs, config.maxInputs + 1)
        val availableValues = mutableListOf<String>()
        
        val graphInputs = (0 until numInputs).map {
            val valueId = newValueId()
            availableValues.add(valueId)
            buildValueRef {
                this.valueId = valueId
                // type 由 ShapeAdapter 填充
                this.type = placeholderTensorType()
            }
        }
        
        // 2. 生成节点
        val numNodes = rand.nextInt(config.minNodesPerGraph, config.maxNodesPerGraph + 1)
        val nodes = mutableListOf<UirNode>()
        val liveTips = mutableMapOf<Int, String>()  // branchId -> tip valueId
        var currentBranch = 0
        liveTips[currentBranch] = availableValues.last()
        
        repeat(numNodes) { nodeIndex ->
            val node = generateNode(nodeIndex, availableValues, liveTips, currentBranch)
            nodes.add(node)
            
            // 更新可用值
            for (output in node.outputs) {
                availableValues.add(output.valueId)
            }
            
            // 随机创建新分支
            if (rand.nextDouble() < config.branchProbability && availableValues.size >= 2) {
                currentBranch++
                liveTips[currentBranch] = availableValues[availableValues.size - 2]
            }
        }
        
        // 3. 选择图输出
        val numOutputs = rand.nextInt(1, minOf(3, availableValues.size) + 1)
        val graphOutputs = availableValues.takeLast(numOutputs).map { valueId ->
            buildValueRef {
                this.valueId = valueId
                this.type = placeholderTensorType()
            }
        }
        
        return buildGraph {
            this.name = name
            graphInputs.forEach { inputs.add(it) }
            graphOutputs.forEach { outputs.add(it) }
            nodes.forEach { nodes.add(it) }
        }
    }
    
    private fun generateNode(
        nodeIndex: Int,
        availableValues: List<String>,
        liveTips: Map<Int, String>,
        currentBranch: Int
    ): UirNode {
        // 1. 选择算子（随机）
        val op = config.ops.random(rand)
        
        // 2. 确定输入数量
        val numInputs = when (op) {
            in UirOpKind.constantOps -> 0
            in UirOpKind.singleInputOps -> 1
            in UirOpKind.binaryInputOps -> 2
            else -> 1
        }
        
        // 3. 选择输入值
        val inputValueIds = selectInputValues(op, numInputs, availableValues, liveTips, currentBranch)
        
        // 4. 生成输出值（无形状）
        val numOutputs = if (op in UirOpKind.multiOutputOps) 2 else 1
        val outputValueRefs = (0 until numOutputs).map {
            buildValueRef {
                valueId = newValueId()
                type = placeholderTensorType()
            }
        }
        
        // 5. 生成属性
        val attributes = generateAttributes(op)
        
        return buildNode {
            name = "${op.name.lowercase()}_$nodeIndex"
            this.op = op
            inputValueIds.forEach { id ->
                inputs.add(buildValueRef {
                    valueId = id
                    type = placeholderTensorType()
                })
            }
            outputValueRefs.forEach { ref -> outputs.add(ref) }
            this.attributes = attributes
        }
    }
    
    private fun selectInputValues(
        op: UirOpKind,
        numInputs: Int,
        availableValues: List<String>,
        liveTips: Map<Int, String>,
        currentBranch: Int
    ): List<String> {
        if (numInputs == 0) return emptyList()
        
        // 优先从当前分支的 tip 选择
        val tipValue = liveTips[currentBranch]
        
        return when {
            availableValues.isEmpty() -> emptyList()
            numInputs == 1 && tipValue != null && tipValue in availableValues -> listOf(tipValue)
            else -> availableValues.shuffled(rand).take(numInputs)
        }
    }
    
    private fun generateAttributes(op: UirOpKind): MutableMap<String, Attribute> {
        val attrs = mutableMapOf<String, Attribute>()
        
        when (op) {
            UirOpKind.SOFTMAX -> {
                attrs["axis"] = buildIntAttr { value = -1 }
            }
            UirOpKind.REDUCE_SUM, UirOpKind.REDUCE_MEAN, UirOpKind.REDUCE_MAX, UirOpKind.REDUCE_MIN -> {
                attrs["axis"] = buildIntAttr { value = -1 }
                attrs["keepdims"] = buildIntAttr { value = 0 }
            }
            UirOpKind.SPLIT -> {
                attrs["axis"] = buildIntAttr { value = 0 }
            }
            UirOpKind.CONCAT -> {
                attrs["axis"] = buildIntAttr { value = 0 }
            }
            UirOpKind.GATHER -> {
                attrs["axis"] = buildIntAttr { value = 0 }
            }
            else -> { /* 无特殊属性 */ }
        }
        
        return attrs
    }
    
    private fun newValueId(): String = "v_${valueCounter++}"
    
    private fun placeholderTensorType(): UirTensorType = buildTensorType {
        typeKind = UirTypeKind.TENSOR
        shape = buildShape { }  // 空形状，由 ShapeAdapter 填充
        dtype = buildDataType {
            name = "float32"
            bits = 32
        }
    }
}