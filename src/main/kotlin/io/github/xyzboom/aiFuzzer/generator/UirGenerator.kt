package io.github.xyzboom.aiFuzzer.generator

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.infer.ShapeInferer
import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import kotlin.random.Random

private val log = KotlinLogging.logger {}

/** 默认算子列表（所有已实现算子，除外适配算子） */
val DefaultOps: List<UirOpKind> = UirOpKind.entries.filter { it !in UirOpKind.adapterOps }

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
    val graphCount: Int = 1,
    val minNdim: Int = 2,  // 至少 2D
    val maxNdim: Int = 4,
    val dtype: String = "float32",
    val dtypeBits: Int = 32,
)

/**
 * UIR 程序生成器。
 *
 * 生成形状兼容的 DAG 图，直接输出可执行的 UIR 程序。
 */
class UirGenerator(private val config: GeneratorConfig = GeneratorConfig()) {

    private val rand = Random(config.seed)
    private val opsEnum: List<UirOpKind> = config.ops.mapNotNull {
        try { UirOpKind.valueOf(it) } catch (_: IllegalArgumentException) { null }
    }.ifEmpty { DefaultOps }

    private var valueCounter = 0
    private var nodeCounter = 0
    
    // 形状管理：valueId -> shape
    private val valueShapes = mutableMapOf<String, UirShape>()

    /** 生成随机 ID 后缀（用于追踪） */
    private fun randomIdSuffix(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { chars.random(rand) }.joinToString("")
    }

    /** 创建 dtype（从 config.dtype/config.dtypeBits） */
    private fun mkDataType(): UirDataType = buildDataType {
        this.name = config.dtype
        this.bits = config.dtypeBits
    }

    /**
     * 生成完整的 UIR 程序。
     */
    fun generate(): UirProgram {
        val program = buildProgram {
            for (i in 0 until config.graphCount) {
                log.debug { "生成图 $i/${config.graphCount}" }
                graphs.add(generateGraph("graph_$i"))
            }
        }
        
        return program
    }
    
    private fun generateGraph(name: String): UirGraph {
        log.debug { "生成图: $name" }
        valueCounter = 0
        
        // 1. 生成图输入
        val numInputs = rand.nextInt(config.minInputs, config.maxInputs + 1)
        log.trace { "图输入数量: $numInputs" }
        val availableValues = mutableListOf<String>()
        
        val graphInputs = (0 until numInputs).map {
            val valueId = newValueId()
            availableValues.add(valueId)
            
            // 为图输入生成形状
            val shape = generateRandomShape(config.minNdim, config.maxNdim)
            valueShapes[valueId] = shape
            log.trace { "输入值 $valueId: 形状=${shapeDims(shape)}" }
            
            buildValueRef {
                this.valueId = valueId
                this.type = buildTensorType {
                    this.typeKind = UirTypeKind.TENSOR
                    this.shape = shape
                    this.dtype = mkDataType()
                }
            }
        }
        
        // 2. 生成节点
        val numNodes = rand.nextInt(config.minNodesPerGraph, config.maxNodesPerGraph + 1)
        val nodeList = mutableListOf<UirNode>()
        val liveTips = mutableMapOf<Int, String>()  // branchId -> tip valueId
        var currentBranch = 0
        liveTips[currentBranch] = availableValues.last()
        
        repeat(numNodes) { nodeIndex ->
            val nodes = generateNode(nodeIndex, availableValues, liveTips, currentBranch)
            nodeList.addAll(nodes)
            
            // 更新可用值（只添加最后一个节点的输出）
            val lastNode = nodes.last()
            for (output in lastNode.outputs) {
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
                this.type = buildTensorType {
                    this.typeKind = UirTypeKind.TENSOR
                    this.shape = valueShapes[valueId] ?: buildShape { }
                    this.dtype = mkDataType()
                }
            }
        }
        
        return buildGraph {
            this.name = name
            graphInputs.forEach { inputs.add(it) }
            graphOutputs.forEach { outputs.add(it) }
            nodeList.forEach { nodes.add(it) }
        }
    }
    
    private fun generateNode(
        nodeIndex: Int,
        availableValues: MutableList<String>,
        liveTips: Map<Int, String>,
        currentBranch: Int
    ): List<UirNode> {
        // 1. 选择算子（随机）
        val op = when {
            availableValues.isEmpty() -> opsEnum.filter { it in UirOpKind.constantOps }.random(rand)
            availableValues.size == 1 -> opsEnum.filter { it in UirOpKind.constantOps || it in UirOpKind.singleInputOps }.random(rand)
            else -> opsEnum.random(rand)
        }
        log.trace { "节点 $nodeIndex: 选择算子 $op (可用值=${availableValues.size})" }
        
        // 2. 孜定输入数量
        val numInputs = when (op) {
            in UirOpKind.constantOps -> 0
            in UirOpKind.singleInputOps -> 1
            in UirOpKind.binaryInputOps -> minOf(2, availableValues.size)
            else -> 1
        }
        log.trace { "节点 $nodeIndex: 输入数量 $numInputs" }
        
        // 3. 选择输入值（可能插入转换节点）
        val conversionNodes = mutableListOf<UirNode>()
        val inputValueRefs = selectInputValues(op, numInputs, availableValues, liveTips, currentBranch, conversionNodes)
        
        // 记录输入详情
        if (inputValueRefs.isNotEmpty()) {
            log.trace { 
                "节点 $nodeIndex: 输入值 ${inputValueRefs.map { "${it.valueId}:${shapeDims(valueShapes[it.valueId]!!)}" }}" 
            }
        }
        
        // 4. 先生成属性（形状推导需要属性信息）
        val attributes = generateAttributes(op)
        if (attributes.isNotEmpty()) {
            log.trace { "节点 $nodeIndex: 属性 $attributes" }
        }
        
        // 5. 形状适配：检查输入形状是否满足算子约束，必要时插入 wrapper
        val adaptResult = ShapeAdapter.adaptInputs(
            op, inputValueRefs, valueShapes, valueCounter, nodeCounter
        )
        
        // 记录适配信息
        if (adaptResult.wrapperNodes.isNotEmpty()) {
            log.debug { "节点 $nodeIndex: 插入 ${adaptResult.wrapperNodes.size} 个 wrapper 节点" }
        }
        
        val adaptedInputRefs = adaptResult.adaptedRefs
        val adaptedInputShapes = adaptResult.adaptedShapes
        conversionNodes.addAll(adaptResult.wrapperNodes)
        
        // 6. 推导并生成输出值（委托给 ShapeInferer）
        val outputShapes = inferOutputShapes(op, adaptedInputShapes, attributes)
        
        log.trace { "节点 $nodeIndex: 输出形状 ${outputShapes.map(::shapeDims)}" }
        
        val outputValueRefs = outputShapes.map { shape ->
            val valueId = newValueId()
            valueShapes[valueId] = shape
            buildValueRef {
                this.valueId = valueId
                this.type = buildTensorType {
                    this.typeKind = UirTypeKind.TENSOR
                    this.shape = shape
                    this.dtype = mkDataType()
                }
            }
        }
        
        // 7. 创建主节点（使用适配后的输入）
        val mainNode = buildNode {
            name = "${op.name.lowercase()}_${nodeIndex}_${randomIdSuffix()}"
            this.op = op
            adaptedInputRefs.forEach { ref -> inputs.add(ref) }  // 使用适配后的输入
            outputValueRefs.forEach { ref -> outputs.add(ref) }
            this.attributes = attributes
        }
        
        log.debug { "创建节点: ${mainNode.name} (op=$op)" }
        log.debug { "  输入: ${adaptedInputRefs.map { "${it.valueId} ${shapeDims(valueShapes[it.valueId]!!)}" }}" }
        log.debug { "  输出: ${outputValueRefs.map { "${it.valueId} ${shapeDims(valueShapes[it.valueId]!!)}" }}" }
        
        // 8. 返回：转换节点 + wrapper节点 + 主节点
        return conversionNodes + mainNode
    }
    
    private fun selectInputValues(
        op: UirOpKind,
        numInputs: Int,
        availableValues: MutableList<String>,
        liveTips: Map<Int, String>,
        currentBranch: Int,
        nodeList: MutableList<UirNode>
    ): List<UirValueRef> {
        if (numInputs == 0) return emptyList()
        
        // 优先从当前分支的 tip 选择
        val tipValue = liveTips[currentBranch]
        log.trace { "选择输入: op=$op, numInputs=$numInputs, tip=$tipValue, 可用=${availableValues.take(5)}${if (availableValues.size > 5) "..." else ""}" }
        
        // 特殊处理：二元运算
        if (op in UirOpKind.binaryInputOps && numInputs == 2 && availableValues.size >= 2) {
            // 选择第一个输入
            val input1ValueId = if (tipValue != null && tipValue in availableValues) {
                log.trace { "二元运算: 使用 tip 作为第一个输入" }
                tipValue
            } else {
                availableValues.random(rand)
            }
            
            // 选择第二个输入（随机，不检查形状兼容性，由 ShapeAdapter 处理）
            val input2ValueId = availableValues.filter { it != input1ValueId }.random(rand)
            
            val input1Ref = buildValueRef {
                this.valueId = input1ValueId
                this.type = buildTensorType {
                    this.typeKind = UirTypeKind.TENSOR
                    this.shape = valueShapes[input1ValueId]!!
                    this.dtype = mkDataType()
                }
            }
            
            val input2Ref = buildValueRef {
                this.valueId = input2ValueId
                this.type = buildTensorType {
                    this.typeKind = UirTypeKind.TENSOR
                    this.shape = valueShapes[input2ValueId]!!
                    this.dtype = mkDataType()
                }
            }
            
            return listOf(input1Ref, input2Ref)
        }
        
        // 其他情况：随机选择
        val selectedIds = when {
            availableValues.isEmpty() -> emptyList()
            numInputs == 1 && tipValue != null && tipValue in availableValues -> listOf(tipValue)
            else -> availableValues.shuffled(rand).take(numInputs)
        }
        
        val inputRefs = selectedIds.map { valueId ->
            buildValueRef {
                this.valueId = valueId
                this.type = buildTensorType {
                    this.typeKind = UirTypeKind.TENSOR
                    this.shape = valueShapes[valueId] ?: buildShape { }
                    this.dtype = mkDataType()
                }
            }
        }
        
        // 形状适配由 ShapeAdapter 处理，这里直接返回
        return inputRefs
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
    
    /**
     * 推导输出形状（委托给 ShapeInferer）。
     */
    private fun inferOutputShapes(
        op: UirOpKind,
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        // 特殊处理：常数生成算子直接生成随机形状
        if (op in UirOpKind.constantOps) {
            return when (op) {
                UirOpKind.ARANGE -> {
                    // ARANGE 生成 1-D 张量，随机长度
                    val length = rand.nextInt(16, 257)  // 16-256
                    listOf(buildShape {
                        dims.add(buildDim {
                            dimKind = UirDimKind.CONSTANT
                            value = length
                        })
                    })
                }
                UirOpKind.FULL, UirOpKind.ONES, UirOpKind.ZEROS -> {
                    // 生成随机形状（至少 2D）
                    listOf(generateRandomShape(config.minNdim, config.maxNdim))
                }
                else -> listOf(generateRandomShape(1, 4))
            }
        }
        
        if (inputShapes.isEmpty()) {
            return listOf(generateRandomShape(1, 4))
        }
        return ShapeInferer.inferShape(op, inputShapes, attributes)
    }
    
    /**
     * 生成随机形状。
     */
    private fun generateRandomShape(minNdim: Int, maxNdim: Int): UirShape {
        // 至少 2D，避免很多算子不支持 1D
        val ndim = rand.nextInt(maxOf(2, minNdim), maxOf(2, maxNdim) + 1)
        return buildShape {
            repeat(ndim) {
                this.dims.add(buildDim {
                    this.dimKind = UirDimKind.CONSTANT
                    this.value = rand.nextInt(1, 129)
                })
            }
        }
    }
    
    /**
     * 生成可广播到 target 的形状。
     */
    private fun generateBroadcastableShape(target: UirShape): UirShape {
        return buildShape {
            target.dims.forEach { dim ->
                val targetValue = dim.valueOrNull() ?: rand.nextInt(1, 129)
                val value = if (rand.nextDouble() < 0.7) targetValue else 1
                
                this.dims.add(buildDim {
                    this.dimKind = UirDimKind.CONSTANT
                    this.value = value
                })
            }
        }
    }
    
    private fun newValueId(): String = "v_${valueCounter++}_${randomIdSuffix()}"
    
    /**
     * 对算子的所有输入进行形状适配，必要时插入 wrapper 节点。
     *
     * 策略：
     * - 如果输入维度不足，插入 EXPAND_DIMS 增维
     * - 如果输入维度多余，插入 RESHAPE 减维
     */
    /**
     * 格式化形状为易读字符串
     */
    private fun shapeDims(shape: UirShape): String {
        return shape.dims.map { it.valueOrNull() ?: "?" }.joinToString(", ", "[", "]")
    }
}