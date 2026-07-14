package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.UirDimKind
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.UirTypeKind
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.builder.buildNode
import io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef
import io.github.xyzboom.aiFuzzer.ir.types.UirDim
import io.github.xyzboom.aiFuzzer.ir.types.UirShape
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDataType
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDim
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildIntAttr
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildShape
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildTensorType

/**
 * 形状适配器：检查输入形状是否满足算子约束，必要时插入 wrapper 算子。
 *
 * 核心功能：
 * 1. 检查输入形状是否满足算子的形状约束
 * 2. 如果不满足，推导需要的目标形状
 * 3. 生成 wrapper 算子序列，将输入形状调整为目标形状
 *
 * Wrapper 算子类型：
 * - EXPAND_DIMS：增加维度（插入 size=1 的维度）
 * - RESHAPE：改变形状（包括增删维度）
 * - BROADCAST_TO：广播维度值
 *
 * 设计原则：
 * - 任意到任意：可以从任意形状 x, y, ..., z 调整到任意形状 m, n, o
 * - 最小化插入：优先选择最少的 wrapper 节点
 * - 保持语义：调整后的形状必须满足算子约束
 */
object ShapeAdapter {
    
    /**
     * 形状适配结果。
     *
     * @param adaptedRefs 适配后的输入值引用列表
     * @param wrapperNodes 插入的 wrapper 节点列表
     * @param adaptedShapes 适配后的形状列表
     */
    data class AdaptResult(
        val adaptedRefs: List<UirValueRef>,
        val wrapperNodes: List<UirNode>,
        val adaptedShapes: List<UirShape>
    )
    
    /**
     * 对算子的所有输入进行形状适配。
     *
     * 流程：
     * 1. 获取算子的形状约束
     * 2. 检查每个输入是否满足约束
     * 3. 对于二元运算，推导公共目标形状
     * 4. 对不满足的输入，生成 wrapper 序列
     * 5. 返回适配后的输入和插入的节点
     */
    fun adaptInputs(
        op: UirOpKind,
        inputValueRefs: List<UirValueRef>,
        valueShapes: MutableMap<String, UirShape>,
        valueCounter: Int,
        nodeCounter: Int
    ): AdaptResult {
        if (inputValueRefs.isEmpty()) {
            return AdaptResult(emptyList(), emptyList(), emptyList())
        }
        
        val constraint = ShapeConstraints.getConstraint(op)
        val inputShapes = inputValueRefs.map { ref -> valueShapes[ref.valueId]!! }
        
        // 检查是否已经满足约束
        if (ShapeConstraints.isApplicable(op, inputShapes)) {
            return AdaptResult(inputValueRefs, emptyList(), inputShapes)
        }
        
        // 特殊处理：需要精确 4D 的算子（conv2d, pool2d）必须优先于 binaryInputOps
        // 这些算子必须 4D (NCHW)，多于4D需要squeeze，少于4D需要expand
        if (op in setOf(UirOpKind.CONV2D, UirOpKind.MAX_POOL2D, UirOpKind.AVG_POOL2D)) {
            return adaptNchwConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, op)
        }
        
        // 特殊处理：INTERPOLATE 需要 3D-5D 输入 (PyTorch) 或 4D 输入 (TVM resize2d)
        // 为同时兼容两个后端，强制 4D 输入
        if (op == UirOpKind.INTERPOLATE) {
            return adaptNchwConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, op)
        }
        
        // 特殊处理：RESIZE2D 需要 4D 输入 (NCHW)
        if (op == UirOpKind.RESIZE2D) {
            return adaptNchwConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, op)
        }
        
        // 特殊处理：BATCH_NORM 需要 3D-4D 输入
        if (op == UirOpKind.BATCH_NORM) {
            return adaptBatchNormConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter)
        }
        
        // 特殊处理：MATMUL (must be before binaryInputOps check since MATMUL ∈ binaryInputOps)
        if (op == UirOpKind.MATMUL && inputShapes.size == 2) {
            return adaptMatmulInputs(
                inputValueRefs, inputShapes, valueShapes,
                valueCounter, nodeCounter
            )
        }
        
        // 特殊处理：CONCAT (must be before binaryInputOps check since CONCAT ∈ binaryInputOps)
        if (op == UirOpKind.CONCAT && inputShapes.size >= 2) {
            return adaptConcatInputs(
                inputValueRefs, inputShapes, valueShapes,
                valueCounter, nodeCounter
            )
        }
        
        // 特殊处理：CONV2D (must be before binaryInputOps check since CONV2D ∈ binaryInputOps)
        // Already handled above by NCHW constraint check
        
        // 特殊处理：二元运算需要推导公共目标形状
        if (op in UirOpKind.binaryInputOps && inputShapes.size == 2) {
            return adaptBinaryInputs(
                op, inputValueRefs, inputShapes, valueShapes, 
                valueCounter, nodeCounter, constraint
            )
        }
        
        // 特殊处理：需要至少 2D 的算子
        if (op in setOf(UirOpKind.TRANSPOSE, UirOpKind.TRIL, UirOpKind.TRIU, UirOpKind.STRIDED_SLICE)) {
            return adaptNdimConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, minNdim = 2)
        }
        
        // 特殊处理：GATHER
        if (op == UirOpKind.GATHER) {
            return adaptGatherInputs(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter)
        }
        
        // 特殊处理：STRIDED_SLICE
        if (op == UirOpKind.STRIDED_SLICE) {
            return adaptStridedSliceInputs(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter)
        }
        
        // 一般情况：检查最小维度要求
        if (constraint.minNdim > 0) {
            return adaptNdimConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, constraint.minNdim)
        }
        
        // 一般情况：对每个输入独立适配
        val wrapperNodes = mutableListOf<UirNode>()
        val adaptedRefs = mutableListOf<UirValueRef>()
        val adaptedShapes = mutableListOf<UirShape>()
        
        var localValueCounter = valueCounter
        var localNodeCounter = nodeCounter
        
        for ((ref, originalShape) in inputValueRefs.zip(inputShapes)) {
            // 推导该输入需要的目标形状
            val targetShape = deriveTargetShape(op, originalShape, inputShapes, constraint)
            
            // 生成 wrapper 序列
            val (adaptedRef, nodes) = generateWrapperSequence(
                ref, originalShape, targetShape,
                valueShapes, localValueCounter, localNodeCounter
            )
            
            wrapperNodes.addAll(nodes)
            adaptedRefs.add(adaptedRef)
            adaptedShapes.add(valueShapes[adaptedRef.valueId]!!)
            
            // 更新计数器
            localValueCounter += nodes.size
            localNodeCounter += nodes.size
        }
        
        return AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
    }
    
    /**
     * 适配二元运算的输入形状。
     * 
     * 策略：
     * 1. 检查两个输入是否可广播
     * 2. 如果可广播，推导公共目标形状，将两个输入都调整到该形状
     * 3. 如果不可广播，生成一个新的常量张量作为第二个输入
     */
    private fun adaptBinaryInputs(
        op: UirOpKind,
        inputValueRefs: List<UirValueRef>,
        inputShapes: List<UirShape>,
        valueShapes: MutableMap<String, UirShape>,
        valueCounter: Int,
        nodeCounter: Int,
        constraint: OpShapeConstraint
    ): AdaptResult {
        val shape1 = inputShapes[0]
        val shape2 = inputShapes[1]
        
        // 检查是否可广播
        val canBroadcast = canBroadcastTogether(shape1, shape2)
        
        if (canBroadcast) {
            // 可广播：推导公共目标形状，将两个输入都调整到该形状
            val commonTargetShape = deriveCommonBroadcastTarget(shape1, shape2)
            
            val wrapperNodes = mutableListOf<UirNode>()
            val adaptedRefs = mutableListOf<UirValueRef>()
            val adaptedShapes = mutableListOf<UirShape>()
            
            var localValueCounter = valueCounter
            var localNodeCounter = nodeCounter
            
            for ((ref, originalShape) in inputValueRefs.zip(inputShapes)) {
                val (adaptedRef, nodes) = generateWrapperSequence(
                    ref, originalShape, commonTargetShape,
                    valueShapes, localValueCounter, localNodeCounter
                )
                
                wrapperNodes.addAll(nodes)
                adaptedRefs.add(adaptedRef)
                adaptedShapes.add(valueShapes[adaptedRef.valueId]!!)
                
                localValueCounter += nodes.size
                localNodeCounter += nodes.size
            }
            
            return AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
        } else {
            // 不可广播：生成一个新的常量张量作为第二个输入
            // 使用第一个输入的原始形状作为目标形状（不做任何修改）
            
            val wrapperNodes = mutableListOf<UirNode>()
            val adaptedRefs = mutableListOf<UirValueRef>()
            val adaptedShapes = mutableListOf<UirShape>()
            
            var localValueCounter = valueCounter
            var localNodeCounter = nodeCounter
            
            // 第一个输入：保持原样，不修改
            adaptedRefs.add(inputValueRefs[0])
            adaptedShapes.add(shape1)
            
            // 第二个输入：生成常量张量（ZEROS），形状与第一个输入相同
            val (constRef, constNode) = generateConstantTensor(
                shape1, valueShapes, localValueCounter, localNodeCounter
            )
            wrapperNodes.add(constNode)
            adaptedRefs.add(constRef)
            adaptedShapes.add(valueShapes[constRef.valueId]!!)
            
            return AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
        }
    }
    
    /**
     * 适配维度数约束。
     */
    private fun adaptNdimConstraint(
        inputValueRefs: List<UirValueRef>,
        inputShapes: List<UirShape>,
        valueShapes: MutableMap<String, UirShape>,
        valueCounter: Int,
        nodeCounter: Int,
        minNdim: Int
    ): AdaptResult {
        val wrapperNodes = mutableListOf<UirNode>()
        val adaptedRefs = mutableListOf<UirValueRef>()
        val adaptedShapes = mutableListOf<UirShape>()
        
        var localValueCounter = valueCounter
        var localNodeCounter = nodeCounter
        
        for ((ref, shape) in inputValueRefs.zip(inputShapes)) {
            if (shape.dims.size < minNdim) {
                val (newRef, newNodes) = generateWrapperSequence(
                    ref, shape, expandToMinNdim(shape, minNdim),
                    valueShapes, localValueCounter, localNodeCounter
                )
                wrapperNodes.addAll(newNodes)
                adaptedRefs.add(newRef)
                adaptedShapes.add(valueShapes[newRef.valueId]!!)
                localValueCounter += newNodes.size
                localNodeCounter += newNodes.size
            } else {
                adaptedRefs.add(ref)
                adaptedShapes.add(shape)
            }
        }
        
        return AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
    }
    
    /**
     * 适配 GATHER 输入。
     */
    private fun adaptGatherInputs(
        inputValueRefs: List<UirValueRef>,
        inputShapes: List<UirShape>,
        valueShapes: MutableMap<String, UirShape>,
        valueCounter: Int,
        nodeCounter: Int
    ): AdaptResult {
        // GATHER 的输入约束较宽松，主要是确保输入有效
        // 如果输入维度不足，扩展到至少 1D
        return adaptNdimConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, minNdim = 1)
    }
    
    /**
     * 适配 STRIDED_SLICE 输入。
     */
    private fun adaptStridedSliceInputs(
        inputValueRefs: List<UirValueRef>,
        inputShapes: List<UirShape>,
        valueShapes: MutableMap<String, UirShape>,
        valueCounter: Int,
        nodeCounter: Int
    ): AdaptResult {
        // STRIDED_SLICE 通常需要至少 2D
        return adaptNdimConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, minNdim = 2)
    }
    
    /**
     * 适配 MATMUL 的输入形状。
     */
    private fun adaptMatmulInputs(
        inputValueRefs: List<UirValueRef>,
        inputShapes: List<UirShape>,
        valueShapes: MutableMap<String, UirShape>,
        valueCounter: Int,
        nodeCounter: Int
    ): AdaptResult {
        val shape1 = inputShapes[0]
        val shape2 = inputShapes[1]
        
        // MATMUL 要求：
        // - 每个输入至少 2D
        // - K 维匹配：shape1[-1] == shape2[-2]
        // - 批次维度可广播
        
        val wrapperNodes = mutableListOf<UirNode>()
        val adaptedRefs = mutableListOf<UirValueRef>()
        val adaptedShapes = mutableListOf<UirShape>()
        
        var localValueCounter = valueCounter
        var localNodeCounter = nodeCounter
        
        // 确保每个输入至少 2D
        var currentShape1 = shape1
        if (shape1.dims.size < 2) {
            val targetNdim = 2
            val (newRef, newNodes) = generateWrapperSequence(
                inputValueRefs[0], shape1, expandToMinNdim(shape1, targetNdim),
                valueShapes, localValueCounter, localNodeCounter
            )
            wrapperNodes.addAll(newNodes)
            adaptedRefs.add(newRef)
            currentShape1 = valueShapes[newRef.valueId]!!
            localValueCounter += newNodes.size
            localNodeCounter += newNodes.size
        } else {
            adaptedRefs.add(inputValueRefs[0])
        }
        adaptedShapes.add(currentShape1)
        
        var currentShape2 = shape2
        if (shape2.dims.size < 2) {
            val targetNdim = 2
            val (newRef, newNodes) = generateWrapperSequence(
                inputValueRefs[1], shape2, expandToMinNdim(shape2, targetNdim),
                valueShapes, localValueCounter, localNodeCounter
            )
            wrapperNodes.addAll(newNodes)
            adaptedRefs.add(newRef)
            currentShape2 = valueShapes[newRef.valueId]!!
            localValueCounter += newNodes.size
            localNodeCounter += newNodes.size
        } else {
            adaptedRefs.add(inputValueRefs[1])
        }
        adaptedShapes.add(currentShape2)
        
        // 检查 K 维是否匹配
        val k1 = currentShape1.dims.last().valueOrNull()
        val k2 = currentShape2.dims[currentShape2.dims.size - 2].valueOrNull()
        
        if (k1 != null && k2 != null && k1 != k2) {
            // K 维不匹配：生成常量张量替换第二个输入
            val (constRef, constNode) = generateConstantTensor(
                currentShape2, valueShapes, localValueCounter, localNodeCounter
            )
            wrapperNodes.add(constNode)
            adaptedRefs[1] = constRef
            adaptedShapes[1] = valueShapes[constRef.valueId]!!
        }
        
        return AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
    }
    
    /**
     * 适配 CONCAT 的输入形状。
     */
    private fun adaptConcatInputs(
        inputValueRefs: List<UirValueRef>,
        inputShapes: List<UirShape>,
        valueShapes: MutableMap<String, UirShape>,
        valueCounter: Int,
        nodeCounter: Int
    ): AdaptResult {
        // CONCAT 要求所有输入维度数相同
        val maxNdim = inputShapes.maxOfOrNull { it.dims.size } ?: 1
        
        val wrapperNodes = mutableListOf<UirNode>()
        val adaptedRefs = mutableListOf<UirValueRef>()
        val adaptedShapes = mutableListOf<UirShape>()
        
        var localValueCounter = valueCounter
        var localNodeCounter = nodeCounter
        
        for ((ref, shape) in inputValueRefs.zip(inputShapes)) {
            if (shape.dims.size < maxNdim) {
                // 扩展维度
                val (newRef, newNodes) = generateWrapperSequence(
                    ref, shape, expandToMinNdim(shape, maxNdim),
                    valueShapes, localValueCounter, localNodeCounter
                )
                wrapperNodes.addAll(newNodes)
                adaptedRefs.add(newRef)
                adaptedShapes.add(valueShapes[newRef.valueId]!!)
                localValueCounter += newNodes.size
                localNodeCounter += newNodes.size
            } else {
                adaptedRefs.add(ref)
                adaptedShapes.add(shape)
            }
        }
        
        return AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
    }
    
    /**
     * 检查两个形状是否可以广播。
     */
    private fun canBroadcastTogether(shape1: UirShape, shape2: UirShape): Boolean {
        val maxNdim = maxOf(shape1.dims.size, shape2.dims.size)
        val padded1 = expandToMinNdim(shape1, maxNdim)
        val padded2 = expandToMinNdim(shape2, maxNdim)
        
        // 检查每个维度是否可广播
        for (i in 0 until maxNdim) {
            val v1 = padded1.dims[i].valueOrNull()
            val v2 = padded2.dims[i].valueOrNull()
            
            // 如果两个维度都有值且都不为1且不相等，则不可广播
            if (v1 != null && v2 != null && v1 != v2 && v1 != 1 && v2 != 1) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * 生成常量张量（ZEROS）。
     */
    private fun generateConstantTensor(
        targetShape: UirShape,
        valueShapes: MutableMap<String, UirShape>,
        valueIdCounter: Int,
        nodeIdCounter: Int
    ): Pair<UirValueRef, UirNode> {
        val outputValueId = "v_${valueIdCounter}_${randomIdSuffix()}"
        valueShapes[outputValueId] = targetShape
        
        val outputRef = buildValueRef {
            valueId = outputValueId
            type = buildTensorType {
                typeKind = io.github.xyzboom.aiFuzzer.ir.UirTypeKind.TENSOR
                shape = targetShape
                dtype = buildDataType {
                    name = "float32"
                    bits = 32
                }
            }
        }
        
        val node = buildNode {
            name = "zeros_${nodeIdCounter}_${randomIdSuffix()}"
            op = UirOpKind.ZEROS
            inputs.clear()  // ZEROS 无输入
            outputs.add(outputRef)
        }
        
        return Pair(outputRef, node)
    }
    
    /**
     * 推导二元运算的公共广播目标形状。
     * 
     * 策略：
     * 1. 目标维度数 = max(两个输入的维度数)
     * 2. 目标形状的每个维度：
     *    - 如果两个维度相等，使用该值
     *    - 如果一个为1，使用另一个的值
     *    - 如果都不为1且不相等：使用第一个输入的值（第二个输入需要先调整为1）
     */
    private fun deriveCommonBroadcastTarget(shape1: UirShape, shape2: UirShape): UirShape {
        val targetNdim = maxOf(shape1.dims.size, shape2.dims.size)
        
        // 对齐维度数（在前面插入 1）
        val padded1 = expandToMinNdim(shape1, targetNdim)
        val padded2 = expandToMinNdim(shape2, targetNdim)
        
        // 计算公共目标形状：使用第一个输入的形状作为基础
        val targetDims = padded1.dims.zip(padded2.dims).map { (d1, d2) ->
            val v1 = d1.valueOrNull()
            val v2 = d2.valueOrNull()
            
            when {
                v1 == null || v2 == null -> buildDim {
                    dimKind = UirDimKind.UNKNOWN
                    value = null
                }
                v1 == v2 -> buildDim {  // 相等：直接使用
                    dimKind = UirDimKind.CONSTANT
                    value = v1
                }
                v1 == 1 -> buildDim {  // v1为1：广播到v2
                    dimKind = UirDimKind.CONSTANT
                    value = v2
                }
                v2 == 1 -> buildDim {  // v2为1：广播到v1
                    dimKind = UirDimKind.CONSTANT
                    value = v1
                }
                // 都不为 1 且不相等：使用较大值（允许通过 RESHAPE 或 BROADCAST_TO 调整）
                else -> buildDim {
                    dimKind = UirDimKind.CONSTANT
                    value = maxOf(v1, v2)  // 使用较大值
                }
            }
        }
        
        return buildShape { targetDims.forEach { dims.add(it) } }
    }
    
    /**
     * 推导输入需要的目标形状。
     *
     * 策略：
     * 1. 确保满足最小维度要求
     * 2. 确保满足特定算子的特殊约束
     * 3. 尽量保持原始形状的语义
     *
     * @param op 目标算子
     * @param currentShape 当前形状
     * @param allInputShapes 所有输入形状列表
     * @param constraint 算子约束
     * @return 目标形状
     */
    private fun deriveTargetShape(
        op: UirOpKind,
        currentShape: UirShape,
        allInputShapes: List<UirShape>,
        constraint: OpShapeConstraint
    ): UirShape {
        val currentNdim = currentShape.dims.size
        
        // 策略 1：满足最小维度要求
        if (currentNdim < constraint.minNdim) {
            return expandToMinNdim(currentShape, constraint.minNdim)
        }
        
        // 策略 2：满足特定算子的特殊约束
        when (op) {
            // MATMUL：需要至少 2D，且 K 维匹配
            UirOpKind.MATMUL -> {
                if (allInputShapes.size == 2) {
                    val currentIndex = allInputShapes.indexOf(currentShape)
                    val otherIndex = if (currentIndex == 0) 1 else 0
                    val otherShape = allInputShapes[otherIndex]
                    return deriveMatmulCompatibleShape(currentShape, otherShape)
                }
            }
            
            // 二元运算：需要可广播
            UirOpKind.ADD, UirOpKind.SUBTRACT, UirOpKind.MULTIPLY, 
            UirOpKind.DIVIDE, UirOpKind.MAXIMUM, UirOpKind.MINIMUM, 
            UirOpKind.POWER -> {
                if (allInputShapes.size == 2) {
                    val currentIndex = allInputShapes.indexOf(currentShape)
                    val otherIndex = if (currentIndex == 0) 1 else 0
                    val otherShape = allInputShapes[otherIndex]
                    return deriveBroadcastableShape(currentShape, otherShape)
                }
            }
            
            // TRANSPOSE/TRIL/TRIU/STRIDED_SLICE：需要至少 2D
            UirOpKind.TRANSPOSE, UirOpKind.TRIL, UirOpKind.TRIU, 
            UirOpKind.STRIDED_SLICE -> {
                if (currentNdim < 2) {
                    return expandToMinNdim(currentShape, 2)
                }
            }
            
            // CONCAT：需要所有输入 ndim 相同
            UirOpKind.CONCAT -> {
                val targetNdim = allInputShapes.maxOfOrNull { it.dims.size } ?: currentNdim
                if (currentNdim < targetNdim) {
                    return expandToMinNdim(currentShape, targetNdim)
                }
            }
            
            // 其他算子：默认保持原形状
            else -> {
                // 不需要特殊处理
            }
        }
        
        // 默认：保持原形状
        return currentShape
    }
    
    /**
     * 扩展形状以满足最小维度要求。
     *
     * 策略：在前面插入 size=1 的维度。
     * 例如：[3, 4] 扩展到 4D → [1, 1, 3, 4]
     */
    private fun expandToMinNdim(shape: UirShape, minNdim: Int): UirShape {
        val currentNdim = shape.dims.size
        if (currentNdim >= minNdim) return shape
        
        val extraDims = (1..(minNdim - currentNdim)).map { 
            buildDim {
                dimKind = UirDimKind.CONSTANT
                value = 1
            }
        }
        
        return buildShape {
            extraDims.forEach { dims.add(it) }
            shape.dims.forEach { dims.add(it) }
        }
    }
    
    /**
     * 推导与 MATMUL 兼容的形状。
     *
     * MATMUL 约束：
     * - 输入 A: [batch..., M, K]
     * - 输入 B: [batch..., K, N]
     * - 输出: [batch..., M, N]
     *
     * 策略：
     * - 如果当前形状 ndim < 2，扩展到 2D（在前面插入 1）
     * - 调整 K 维以匹配另一个输入
     */
    private fun deriveMatmulCompatibleShape(currentShape: UirShape, otherShape: UirShape): UirShape {
        // 确保至少 2D
        val paddedCurrent = if (currentShape.dims.size < 2) {
            expandToMinNdim(currentShape, 2)
        } else currentShape
        
        val paddedOther = if (otherShape.dims.size < 2) {
            expandToMinNdim(otherShape, 2)
        } else otherShape
        
        // 获取 K 维值
        val currentK = paddedCurrent.dims.last().valueOrNull()
        val otherK = paddedOther.dims[paddedOther.dims.size - 2].valueOrNull()
        
        // 如果两者都有 K 值且不匹配，调整当前的 K
        if (currentK != null && otherK != null && currentK != otherK) {
            val newDims = paddedCurrent.dims.toMutableList()
            newDims[newDims.size - 1] = buildDim {
                dimKind = UirDimKind.CONSTANT
                value = otherK  // 匹配另一个输入的 K
            }
            return buildShape { newDims.forEach { dims.add(it) } }
        }
        
        return paddedCurrent
    }
    
    /**
     * 推导可广播的形状。
     *
     * 广播规则（NumPy 风格）：
     * - 维度数可以不同（从右对齐）
     * - 每个维度要么相等，要么其中一个为 1
     *
     * 策略：
     * - 目标维度数 = max(两个输入的维度数)
     * - 将两个输入都扩展到目标维度数
     * - 对于不兼容的维度，将其调整为 1（使其可广播）
     */
    private fun deriveBroadcastableShape(currentShape: UirShape, otherShape: UirShape): UirShape {
        // 关键：目标维度数必须是 max，确保 broadcast_to 合法
        val targetNdim = maxOf(currentShape.dims.size, otherShape.dims.size)
        
        // 对齐维度数：在前面插入 size=1 的维度
        val paddedCurrent = expandToMinNdim(currentShape, targetNdim)
        val paddedOther = expandToMinNdim(otherShape, targetNdim)
        
        // 调整维度值以支持广播
        val newDims = paddedCurrent.dims.mapIndexed { i, dim ->
            val otherDim = paddedOther.dims[i]
            val currentVal = dim.valueOrNull()
            val otherVal = otherDim.valueOrNull()
            
            // 如果两者都有值且不兼容，将当前维度调整为 1（使其可广播）
            if (currentVal != null && otherVal != null && 
                currentVal != otherVal && currentVal != 1 && otherVal != 1) {
                buildDim {
                    dimKind = UirDimKind.CONSTANT
                    value = 1  // 调整为 1 以支持广播
                }
            } else {
                dim  // 保持原维度
            }
        }
        
        return buildShape { newDims.forEach { dims.add(it) } }
    }
    
    /**
     * 生成 wrapper 算子序列，将形状从 original 调整为 target。
     *
     * 策略（修正版）：
     * 1. 如果维度数不同：
     *    - originalNdim < targetNdim: 插入 EXPAND_DIMS 增维
     *    - originalNdim > targetNdim: 使用 RESHAPE（不应该发生！目标形状应该总是 >= 原始维度数）
     * 2. 如果维度数相同但维度值不同：插入 BROADCAST_TO 广播
     *
     * 重要约束：BROADCAST_TO 要求 targetNdim >= originalNdim
     */
    private fun generateWrapperSequence(
        inputRef: UirValueRef,
        originalShape: UirShape,
        targetShape: UirShape,
        valueShapes: MutableMap<String, UirShape>,
        valueCounter: Int,
        nodeCounter: Int
    ): Pair<UirValueRef, List<UirNode>> {
        val wrapperNodes = mutableListOf<UirNode>()
        var currentRef = inputRef
        var currentShape = originalShape
        var counter = valueCounter
        
        val originalNdim = currentShape.dims.size
        val targetNdim = targetShape.dims.size
        
        // 步骤 1：对齐维度数
        if (originalNdim < targetNdim) {
            // 增加维度：需要插入多个 EXPAND_DIMS 节点
            val numDimsToAdd = targetNdim - originalNdim
            // 为每个要插入的维度创建一个 EXPAND_DIMS 节点
            for (i in 0 until numDimsToAdd) {
                val (newRef, newNode) = insertSingleExpandDims(
                    currentRef, currentShape, axis = 0,
                    valueShapes, counter++, nodeCounter + wrapperNodes.size
                )
                wrapperNodes.add(newNode)
                currentRef = newRef
                currentShape = valueShapes[newRef.valueId]!!
            }
        } else if (originalNdim > targetNdim) {
            // 错误情况：目标维度数少于原始维度数
            // 这不应该发生！说明 deriveTargetShape 返回了错误的形状
            // 临时处理：使用 RESHAPE 展平
            val (newRef, newNode) = insertReshapeForDimReduce(
                currentRef, currentShape, targetNdim,
                valueShapes, counter++, nodeCounter + wrapperNodes.size
            )
            wrapperNodes.add(newNode)
            currentRef = newRef
            currentShape = valueShapes[newRef.valueId]!!
        }
        
        // 步骤 2：对齐维度值（广播）
        // 检查是否可以广播：每个维度要么相等，要么输入为1
        if (currentShape.dims.size == targetNdim && !shapesEqual(currentShape, targetShape)) {
            // 检查是否可以广播
            val canBroadcast = currentShape.dims.zip(targetShape.dims).all { (curDim, tgtDim) ->
                val curVal = curDim.valueOrNull()
                val tgtVal = tgtDim.valueOrNull()
                curVal == null || tgtVal == null || curVal == tgtVal || curVal == 1
            }
            
            if (canBroadcast) {
                // 可以直接广播
                val (newRef, newNode) = insertBroadcastTo(
                    currentRef, currentShape, targetShape,
                    valueShapes, counter++, nodeCounter + wrapperNodes.size
                )
                wrapperNodes.add(newNode)
                currentRef = newRef
                currentShape = valueShapes[newRef.valueId]!!
            } else {
                // 不能直接广播：需要先 RESHAPE 调整维度值
                // 策略：使用 RESHAPE 将形状调整为目标形状
                val (newRef, newNode) = insertReshape(
                    currentRef, currentShape, targetShape,
                    valueShapes, counter++, nodeCounter + wrapperNodes.size
                )
                wrapperNodes.add(newNode)
                currentRef = newRef
                currentShape = valueShapes[newRef.valueId]!!
            }
        }
        
        return Pair(currentRef, wrapperNodes)
    }
    
    /**
     * 插入单个 EXPAND_DIMS 节点，在指定位置插入一个 size=1 的维度。
     *
     * @param axis 插入维度的位置（0 表示在最前面）
     */
    private fun insertSingleExpandDims(
        inputRef: UirValueRef,
        inputShape: UirShape,
        axis: Int,
        valueShapes: MutableMap<String, UirShape>,
        valueIdCounter: Int,
        nodeIdCounter: Int
    ): Pair<UirValueRef, UirNode> {
        // 构造输出形状：在 axis 位置插入一个维度 1
        val normalizedAxis = axis.coerceIn(0, inputShape.dims.size)
        val outputShape = buildShape {
            // 在 normalizedAxis 位置插入 size=1 的维度
            for (i in 0 until normalizedAxis) {
                dims.add(inputShape.dims[i])
            }
            dims.add(buildDim {
                dimKind = UirDimKind.CONSTANT
                value = 1
            })
            for (i in normalizedAxis until inputShape.dims.size) {
                dims.add(inputShape.dims[i])
            }
        }
        
        val outputValueId = "v_${valueIdCounter}_${randomIdSuffix()}"
        valueShapes[outputValueId] = outputShape
        
        val outputRef = buildValueRef {
            valueId = outputValueId
            type = buildTensorType {
                typeKind = io.github.xyzboom.aiFuzzer.ir.UirTypeKind.TENSOR
                shape = outputShape
                dtype = inputRef.type.dtype
            }
        }
        
        val node = buildNode {
            name = "expand_dims_${nodeIdCounter}_${randomIdSuffix()}"
            op = UirOpKind.EXPAND_DIMS
            inputs.add(inputRef)
            outputs.add(outputRef)
            // 设置 axis 属性
            attributes["axis"] = buildIntAttr { value = normalizedAxis }
        }
        
        return Pair(outputRef, node)
    }
    
    /**
     * 插入 EXPAND_DIMS wrapper 节点（已废弃）。
     *
     * 在前面插入 numDims 个 size=1 的维度。
     * 注意：这个方法一次插入多个维度，但 TVM 的 expand_dims 只支持插入一个维度。
     * 请使用 insertSingleExpandDims 代替。
     */
    @Deprecated("Use insertSingleExpandDims instead")
    private fun insertExpandDims(
        inputRef: UirValueRef,
        inputShape: UirShape,
        numDims: Int,
        valueShapes: MutableMap<String, UirShape>,
        valueIdCounter: Int,
        nodeIdCounter: Int
    ): Pair<UirValueRef, UirNode> {
        // 构造输出形状：前面加 numDims 个 1
        val outputShape = buildShape {
            repeat(numDims) {
                dims.add(buildDim {
                    dimKind = UirDimKind.CONSTANT
                    value = 1
                })
            }
            inputShape.dims.forEach { dims.add(it) }
        }
        
        val outputValueId = "v_${valueIdCounter}_${randomIdSuffix()}"
        valueShapes[outputValueId] = outputShape
        
        val outputRef = buildValueRef {
            valueId = outputValueId
            type = buildTensorType {
                typeKind = io.github.xyzboom.aiFuzzer.ir.UirTypeKind.TENSOR
                shape = outputShape
                dtype = inputRef.type.dtype
            }
        }
        
        // 重要：虽然一次插入 numDims 个维度，但只创建一个节点
        // axis=0 表示在最前面插入维度
        val node = buildNode {
            name = "expand_dims_${nodeIdCounter}_${randomIdSuffix()}"
            op = UirOpKind.EXPAND_DIMS
            inputs.add(inputRef)
            outputs.add(outputRef)
            // 设置 axis 属性：在前面插入维度
            // 注意：TVM 的 expand_dims 一次只能插入一个维度
            // 如果需要插入多个维度，应该生成多个节点，或者使用其他方法
            // 这里暂时保持简单，axis=0 表示在最前面插入
            attributes["axis"] = buildIntAttr { value = 0 }
        }
        
        return Pair(outputRef, node)
    }
    
    /**
     * 插入 RESHAPE wrapper 节点，用于减少维度数。
     *
     * 策略：展平前面的维度，保持后面 targetNdim-1 个维度不变。
     * 例如：[4, 3, 5, 2] 降到 3D → 展平前 2 维为 [12, 5, 2]
     */
    private fun insertReshapeForDimReduce(
        inputRef: UirValueRef,
        inputShape: UirShape,
        targetNdim: Int,
        valueShapes: MutableMap<String, UirShape>,
        valueIdCounter: Int,
        nodeIdCounter: Int
    ): Pair<UirValueRef, UirNode> {
        val currentNdim = inputShape.dims.size
        val flattenCount = currentNdim - targetNdim + 1
        
        // 计算展平后的维度值
        val outputDims = mutableListOf<UirDim>()
        
        // 前面 flattenCount 维展平为一个维度
        var product = 1
        for (i in 0 until flattenCount) {
            val v = inputShape.dims[i].valueOrNull() ?: 16  // 未知维度用默认值
            product *= v
        }
        outputDims.add(buildDim {
            dimKind = UirDimKind.CONSTANT
            value = product
        })
        
        // 后面 targetNdim - 1 维保持不变
        for (i in flattenCount until currentNdim) {
            outputDims.add(inputShape.dims[i])
        }
        
        val outputShape = buildShape { outputDims.forEach { dims.add(it) } }
        
        val outputValueId = "v_${valueIdCounter}_${randomIdSuffix()}"
        valueShapes[outputValueId] = outputShape
        
        val outputRef = buildValueRef {
            valueId = outputValueId
            type = buildTensorType {
                typeKind = io.github.xyzboom.aiFuzzer.ir.UirTypeKind.TENSOR
                shape = outputShape
                dtype = inputRef.type.dtype
            }
        }
        
        val node = buildNode {
            name = "reshape_${nodeIdCounter}_${randomIdSuffix()}"
            op = UirOpKind.RESHAPE
            inputs.add(inputRef)
            outputs.add(outputRef)
        }
        
        return Pair(outputRef, node)
    }
    
    /**
     * 插入 RESHAPE wrapper 节点，将输入形状改变为目标形状。
     */
    private fun insertReshape(
        inputRef: UirValueRef,
        inputShape: UirShape,
        targetShape: UirShape,
        valueShapes: MutableMap<String, UirShape>,
        valueIdCounter: Int,
        nodeIdCounter: Int
    ): Pair<UirValueRef, UirNode> {
        val outputValueId = "v_${valueIdCounter}_${randomIdSuffix()}"
        valueShapes[outputValueId] = targetShape
        
        val outputRef = buildValueRef {
            valueId = outputValueId
            type = buildTensorType {
                typeKind = io.github.xyzboom.aiFuzzer.ir.UirTypeKind.TENSOR
                shape = targetShape
                dtype = inputRef.type.dtype
            }
        }
        
        val node = buildNode {
            name = "reshape_${nodeIdCounter}_${randomIdSuffix()}"
            op = UirOpKind.RESHAPE
            inputs.add(inputRef)
            outputs.add(outputRef)
        }
        
        return Pair(outputRef, node)
    }
    
    /**
     * 插入 BROADCAST_TO wrapper 节点。
     *
     * 将当前形状广播为目标形状。
     */
    private fun insertBroadcastTo(
        inputRef: UirValueRef,
        inputShape: UirShape,
        targetShape: UirShape,
        valueShapes: MutableMap<String, UirShape>,
        valueIdCounter: Int,
        nodeIdCounter: Int
    ): Pair<UirValueRef, UirNode> {
        val outputValueId = "v_${valueIdCounter}_${randomIdSuffix()}"
        valueShapes[outputValueId] = targetShape
        
        val outputRef = buildValueRef {
            valueId = outputValueId
            type = buildTensorType {
                typeKind = io.github.xyzboom.aiFuzzer.ir.UirTypeKind.TENSOR
                shape = targetShape
                dtype = inputRef.type.dtype
            }
        }
        
        val node = buildNode {
            name = "broadcast_to_${nodeIdCounter}_${randomIdSuffix()}"
            op = UirOpKind.BROADCAST_TO
            inputs.add(inputRef)
            outputs.add(outputRef)
        }
        
        return Pair(outputRef, node)
    }
    
    /**
     * 检查两个形状是否相等（维度数和每个维度值都相同）。
     */
    private fun shapesEqual(s1: UirShape, s2: UirShape): Boolean {
        if (s1.dims.size != s2.dims.size) return false
        return s1.dims.zip(s2.dims).all { (d1, d2) ->
            val v1 = d1.valueOrNull()
            val v2 = d2.valueOrNull()
            if (v1 == null || v2 == null) true  // 未知维度视为相等
            else v1 == v2
        }
    }
    
    /**
     * 生成随机 ID 后缀（用于节点命名）。
     */
    private fun randomIdSuffix(): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..8).map { chars.random() }.joinToString("")
        }
    
    /**
     * 适配 NCHW 格式的 4D 约束（conv2d, pool2d）。
     *
     * 这些算子需要精确的 4D 输入 [N, C, H, W]。
     * - <4D: 在前面插入 size=1 的维度（expand）
     * - >4D: 将多余的维度 squeeze 掉（通过 reshape 将前 extra 维合并到 batch dim）
     */
    private fun adaptNchwConstraint(
        inputValueRefs: List<UirValueRef>,
        inputShapes: List<UirShape>,
        valueShapes: MutableMap<String, UirShape>,
        valueCounter: Int,
        nodeCounter: Int,
        op: UirOpKind? = null
    ): AdaptResult {
        val wrapperNodes = mutableListOf<UirNode>()
        val adaptedRefs = mutableListOf<UirValueRef>()
        val adaptedShapes = mutableListOf<UirShape>()

        var localValueCounter = valueCounter
        var localNodeCounter = nodeCounter

        for ((ref, shape) in inputValueRefs.zip(inputShapes)) {
            val ndim = shape.dims.size
            val adapted: Pair<UirValueRef, List<UirNode>> = when {
                ndim < 4 -> {
                    // <4D: 在前面插入 size=1 的维度
                    val target = expandToMinNdim(shape, 4)
                    generateWrapperSequence(ref, shape, target, valueShapes, localValueCounter, localNodeCounter)
                }
                ndim > 4 -> {
                    // >4D: 将前 (ndim-4) 个维度 squeeze 到 batch 维度
                    // 策略：将前 (ndim-3) 个维度合并到 N 维，保留 C,H,W
                    val extra = ndim - 4
                    val mergedBatch = shape.dims.take(extra + 1)
                        .mapNotNull { it.valueOrNull() }
                        .filter { it > 0 }
                        .fold(1L) { acc, v -> acc * v }
                        .toInt()
                    
                    val targetShape = buildShape {
                        dims.add(buildDim {
                            dimKind = UirDimKind.CONSTANT
                            value = mergedBatch.coerceAtLeast(1)
                        })
                        for (i in (extra + 1) until ndim) {
                            dims.add(shape.dims[i])
                        }
                    }
                    generateWrapperSequence(ref, shape, targetShape, valueShapes, localValueCounter, localNodeCounter)
                }
                else -> Pair(ref, emptyList())
            }
            
            wrapperNodes.addAll(adapted.second)
            adaptedRefs.add(adapted.first)
            adaptedShapes.add(valueShapes[adapted.first.valueId]!!)
            
            localValueCounter += adapted.second.size
            localNodeCounter += adapted.second.size
        }

        // POOL2D: 确保空间维度 (H, W) 至少为 2，以避免 kernel_size=2 时输出为 0
        if ((op == UirOpKind.MAX_POOL2D || op == UirOpKind.AVG_POOL2D) && adaptedShapes.isNotEmpty()) {
            val shape = adaptedShapes[0]
            if (shape.dims.size == 4) {
                val h = shape.dims[2].valueOrNull() ?: 1
                val w = shape.dims[3].valueOrNull() ?: 1
                if (h < 2 || w < 2) {
                    // Reshape the input to have spatial dims at least 2
                    val targetShape = buildShape {
                        dims.add(shape.dims[0])
                        dims.add(shape.dims[1])
                        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = maxOf(h, 2) })
                        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = maxOf(w, 2) })
                    }
                    val (newRef, newNodes) = generateWrapperSequence(
                        adaptedRefs[0], shape, targetShape, valueShapes, localValueCounter, localNodeCounter
                    )
                    wrapperNodes.addAll(newNodes)
                    adaptedRefs[0] = newRef
                    adaptedShapes[0] = valueShapes[newRef.valueId]!!
                    localValueCounter += newNodes.size
                    localNodeCounter += newNodes.size
                }
            }
        }

        // CONV2D 特殊处理：确保权重的 C_in (dim[1]) 与输入的 C (dim[1]) 匹配
        // 同时确保 kH, kW 不超过输入的空间维度
        if (op == UirOpKind.CONV2D && adaptedShapes.size == 2) {
            val inputC = adaptedShapes[0].dims.getOrNull(1)?.valueOrNull()
            val weightCIn = adaptedShapes[1].dims.getOrNull(1)?.valueOrNull()
            val inputH = adaptedShapes[0].dims.getOrNull(2)?.valueOrNull() ?: 1
            val inputW = adaptedShapes[0].dims.getOrNull(3)?.valueOrNull() ?: 1
            
            val cInMismatch = inputC != null && weightCIn != null && inputC != weightCIn
            val origKH = adaptedShapes[1].dims.getOrNull(2)?.valueOrNull() ?: 3
            val origKW = adaptedShapes[1].dims.getOrNull(3)?.valueOrNull() ?: 3
            val kHTooBig = origKH > inputH
            val kWTooBig = origKW > inputW
            
            if (cInMismatch || kHTooBig || kWTooBig) {
                // 生成常量权重张量替换第二个输入
                // 权重形状: [C_out, C_in, kH, kW]
                val cOut = weightCIn ?: inputC ?: 1
                val cIn = inputC ?: weightCIn ?: 1
                val kH = minOf(origKH, inputH).coerceAtLeast(1)
                val kW = minOf(origKW, inputW).coerceAtLeast(1)
                
                val weightShape = buildShape {
                    dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = cOut })
                    dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = cIn })
                    dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = kH })
                    dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = kW })
                }
                
                val (constRef, constNode) = generateConstantTensor(
                    weightShape, valueShapes, localValueCounter, localNodeCounter
                )
                wrapperNodes.add(constNode)
                adaptedRefs[1] = constRef
                adaptedShapes[1] = valueShapes[constRef.valueId]!!
            }
        }

        return AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
    }

    /**
     * 适配 INTERPOLATE 的 3D-5D 约束。
     *
     * F.interpolate 只接受 3D、4D 或 5D 输入。
     * - <3D: 在前面插入 size=1 的维度（expand 到 3D）
     * - 3D-5D: 直接使用
     * - >5D: 将多余的维度 squeeze 掉（通过 reshape 将前 extra 维合并到 batch dim，降到 5D）
     */
    private fun adaptInterpolateConstraint(
        inputValueRefs: List<UirValueRef>,
        inputShapes: List<UirShape>,
        valueShapes: MutableMap<String, UirShape>,
        valueCounter: Int,
        nodeCounter: Int
    ): AdaptResult {
        val wrapperNodes = mutableListOf<UirNode>()
        val adaptedRefs = mutableListOf<UirValueRef>()
        val adaptedShapes = mutableListOf<UirShape>()

        var localValueCounter = valueCounter
        var localNodeCounter = nodeCounter

        for ((ref, shape) in inputValueRefs.zip(inputShapes)) {
            val ndim = shape.dims.size
            val adapted: Pair<UirValueRef, List<UirNode>> = when {
                ndim < 3 -> {
                    // <3D: 在前面插入 size=1 的维度到 3D
                    val target = expandToMinNdim(shape, 3)
                    generateWrapperSequence(ref, shape, target, valueShapes, localValueCounter, localNodeCounter)
                }
                ndim > 5 -> {
                    // >5D: 将前 (ndim-4) 个维度合并到 batch 维度，保留后 4 维
                    val extra = ndim - 5
                    val mergedBatch = shape.dims.take(extra + 1)
                        .mapNotNull { it.valueOrNull() }
                        .filter { it > 0 }
                        .fold(1L) { acc, v -> acc * v }
                        .toInt()
                    
                    val targetShape = buildShape {
                        dims.add(buildDim {
                            dimKind = UirDimKind.CONSTANT
                            value = mergedBatch.coerceAtLeast(1)
                        })
                        for (i in (extra + 1) until ndim) {
                            dims.add(shape.dims[i])
                        }
                    }
                    generateWrapperSequence(ref, shape, targetShape, valueShapes, localValueCounter, localNodeCounter)
                }
                else -> Pair(ref, emptyList())
            }
            
            wrapperNodes.addAll(adapted.second)
            adaptedRefs.add(adapted.first)
            adaptedShapes.add(valueShapes[adapted.first.valueId]!!)
            
            localValueCounter += adapted.second.size
            localNodeCounter += adapted.second.size
        }

        return AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
    }

    /**
     * 适配 BATCH_NORM 的 3D-4D 约束。
     *
     * F.batch_norm 只接受 3D 或 4D 输入，通道维度是 dims[1]。
     * - <3D: 在末尾插入 size=1 的维度（保持 dims[1] 作为通道维度）
     * - 3D-4D: 直接使用
     * - >4D: 将多余的维度 squeeze 掉（通过 reshape 将前 extra 维合并到 batch dim，降到 4D）
     */
    private fun adaptBatchNormConstraint(
        inputValueRefs: List<UirValueRef>,
        inputShapes: List<UirShape>,
        valueShapes: MutableMap<String, UirShape>,
        valueCounter: Int,
        nodeCounter: Int
    ): AdaptResult {
        val wrapperNodes = mutableListOf<UirNode>()
        val adaptedRefs = mutableListOf<UirValueRef>()
        val adaptedShapes = mutableListOf<UirShape>()

        var localValueCounter = valueCounter
        var localNodeCounter = nodeCounter

        for ((ref, shape) in inputValueRefs.zip(inputShapes)) {
            val ndim = shape.dims.size
            val adapted: Pair<UirValueRef, List<UirNode>> = when {
                ndim < 3 -> {
                    // <3D: 在末尾插入 size=1 的维度到 3D
                    // 这样 dims[1] 仍然是通道维度（C）
                    val target = buildShape {
                        for (dim in shape.dims) {
                            dims.add(dim)
                        }
                        // 补充到 3D
                        repeat(3 - ndim) {
                            dims.add(buildDim {
                                dimKind = UirDimKind.CONSTANT
                                value = 1
                            })
                        }
                    }
                    generateWrapperSequence(ref, shape, target, valueShapes, localValueCounter, localNodeCounter)
                }
                ndim > 4 -> {
                    // >4D: 将前 (ndim-3) 个维度合并到 batch 维度，保留后 3 维
                    val extra = ndim - 4
                    val mergedBatch = shape.dims.take(extra + 1)
                        .mapNotNull { it.valueOrNull() }
                        .filter { it > 0 }
                        .fold(1L) { acc, v -> acc * v }
                        .toInt()
                    
                    val targetShape = buildShape {
                        dims.add(buildDim {
                            dimKind = UirDimKind.CONSTANT
                            value = mergedBatch.coerceAtLeast(1)
                        })
                        for (i in (extra + 1) until ndim) {
                            dims.add(shape.dims[i])
                        }
                    }
                    generateWrapperSequence(ref, shape, targetShape, valueShapes, localValueCounter, localNodeCounter)
                }
                else -> Pair(ref, emptyList())
            }
            
            wrapperNodes.addAll(adapted.second)
            adaptedRefs.add(adapted.first)
            adaptedShapes.add(valueShapes[adapted.first.valueId]!!)
            
            localValueCounter += adapted.second.size
            localNodeCounter += adapted.second.size
        }

        return AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
    }
}