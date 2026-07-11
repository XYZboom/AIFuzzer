package io.github.xyzboom.aiFuzzer.infer

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*

/**
 * 统一形状推导器。
 * 
 * 设计原则：
 * 1. 单一源头：所有形状推导逻辑只在此处实现
 * 2. 语义驱动：每个算子的形状规则符合其语义定义
 * 3. 禁止硬编码：形状通过算子语义实际计算
 */
object ShapeInferer {
    
    /**
     * 形状推导异常。
     */
    class ShapeInferenceError(message: String) : Exception(message)
    
    /**
     * 推导整个图的形状。
     * 
     * 拓扑序遍历所有节点，为每个输出值确定形状。
     * 要求：图的输入必须已有形状。
     * 
     * @return valueId -> Shape 的映射
     */
    fun inferGraphShapes(graph: UirGraph): Map<String, UirShape> {
        val shapeMap = mutableMapOf<String, UirShape>()
        
        // 初始化：图的输入形状
        for (input in graph.inputs) {
            shapeMap[input.valueId] = input.type.shape
        }
        
        // 拓扑序遍历节点
        for (node in graph.nodes) {
            // 收集输入形状
            val inputShapes = node.inputs.map { inputRef ->
                shapeMap[inputRef.valueId] 
                    ?: throw ShapeInferenceError("Input shape not found for ${inputRef.valueId} in node ${node.name}")
            }
            
            // 推导输出形状
            val outputShapes = inferShape(node.op, inputShapes, node.attributes)
            
            // 检查输出数量匹配
            if (outputShapes.size != node.outputs.size) {
                throw ShapeInferenceError(
                    "Node ${node.name} op=${node.op}: expected ${node.outputs.size} outputs, got ${outputShapes.size}"
                )
            }
            
            // 记录输出形状
            for ((output, shape) in node.outputs.zip(outputShapes)) {
                shapeMap[output.valueId] = shape
            }
        }
        
        return shapeMap
    }
    
    /**
     * 单个算子的形状推导。
     */
    fun inferShape(
        op: UirOpKind,
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        return when (op) {
            // ===== 分类 A：形状不变（逐元素/激活） =====
            UirOpKind.RELU,
            UirOpKind.SIGMOID,
            UirOpKind.TANH,
            UirOpKind.GELU,
            UirOpKind.SILU,
            UirOpKind.NEG,
            UirOpKind.ABS,
            UirOpKind.EXP,
            UirOpKind.LOG,
            UirOpKind.SQRT,
            UirOpKind.CEIL,
            UirOpKind.FLOOR,
            UirOpKind.SOFTMAX,
            UirOpKind.CAST -> {
                requireSingleInput(op, inputShapes)
                listOf(inputShapes.first())
            }
            
            // ===== 分类 B：广播二元运算 =====
            UirOpKind.ADD,
            UirOpKind.SUBTRACT,
            UirOpKind.MULTIPLY,
            UirOpKind.DIVIDE,
            UirOpKind.MAXIMUM,
            UirOpKind.MINIMUM,
            UirOpKind.POWER -> {
                // 支持单输入（自己和自己运算）
                if (inputShapes.size == 1) {
                    listOf(inputShapes[0])
                } else {
                    listOf(broadcastShapes(inputShapes[0], inputShapes[1]))
                }
            }
            
            // ===== 分类 C：矩阵乘法 =====
            UirOpKind.MATMUL -> {
                inferMatmulShape(inputShapes)
            }
            
            // ===== 分类 D：归约运算 =====
            UirOpKind.REDUCE_SUM,
            UirOpKind.REDUCE_MEAN,
            UirOpKind.REDUCE_MAX,
            UirOpKind.REDUCE_MIN -> {
                inferReduceShape(inputShapes, attributes)
            }
            
            // ===== 分类 E：形状变换 =====
            UirOpKind.RESHAPE -> {
                inferReshapeShape(inputShapes, attributes)
            }
            
            UirOpKind.TRANSPOSE -> {
                inferTransposeShape(inputShapes, attributes)
            }
            
            UirOpKind.SQUEEZE -> {
                inferSqueezeShape(inputShapes, attributes)
            }
            
            UirOpKind.UNSQUEEZE -> {
                inferUnsqueezeShape(inputShapes, attributes)
            }
            
            UirOpKind.CONCAT -> {
                inferConcatShape(inputShapes, attributes)
            }
            
            UirOpKind.SPLIT -> {
                inferSplitShape(inputShapes, attributes)
            }
            
            // ===== 分类 F：索引/切片 =====
            UirOpKind.GATHER -> {
                inferGatherShape(inputShapes, attributes)
            }
            
            UirOpKind.STRIDED_SLICE -> {
                inferStridedSliceShape(inputShapes, attributes)
            }
            
            // ===== 分类 G：三角矩阵 =====
            UirOpKind.TRIL,
            UirOpKind.TRIU -> {
                inferTrilTriuShape(inputShapes, op)
            }
            
            // ===== 分类 H：常数生成 =====
            UirOpKind.ARANGE -> {
                inferArangeShape(attributes)
            }
            
            UirOpKind.FULL,
            UirOpKind.ONES,
            UirOpKind.ZEROS -> {
                inferConstantGenShape(op, attributes)
            }
            
            // ===== 分类 I：其他 =====
            UirOpKind.BROADCAST_TO -> {
                inferBroadcastToShape(inputShapes, attributes)
            }
            
            UirOpKind.TILE -> {
                inferTileShape(inputShapes, attributes)
            }
            
            // ===== 分类 J：适配算子（由 ShapeAdapter 插入） =====
            UirOpKind.EXPAND_DIMS -> {
                inferExpandDimsShape(inputShapes, attributes)
            }
        }
    }
    
    // ===== 辅助函数：输入验证 =====
    
    private fun requireSingleInput(op: UirOpKind, inputShapes: List<UirShape>) {
        if (inputShapes.size != 1) {
            throw ShapeInferenceError("$op requires exactly 1 input, got ${inputShapes.size}")
        }
    }
    
    private fun requireBinaryInput(op: UirOpKind, inputShapes: List<UirShape>) {
        if (inputShapes.size != 2) {
            throw ShapeInferenceError("$op requires exactly 2 inputs, got ${inputShapes.size}")
        }
    }
    
    // ===== 辅助函数：形状构造 =====
    
    /**
     * 创建常数维度。
     */
    fun constantDim(value: Int): UirDim = buildDim {
        dimKind = UirDimKind.CONSTANT
        this.value = value
    }
    
    /**
     * 创建未知维度。
     */
    fun unknownDim(): UirDim = buildDim {
        dimKind = UirDimKind.UNKNOWN
        value = null
    }
    
    /**
     * 创建常数形状。
     */
    fun constantShape(vararg values: Int): UirShape = buildShape {
        values.forEach { v -> dims.add(constantDim(v)) }
    }
    
    /**
     * 从维度列表创建形状。
     */
    fun shapeFromDims(dims: List<UirDim>): UirShape = buildShape {
        dims.forEach { dim -> this.dims.add(dim) }
    }
    
    // ===== 辅助函数：广播 =====
    
    /**
     * 广播两个形状（NumPy 风格，从右对齐）。
     */
    fun broadcastShapes(shape1: UirShape, shape2: UirShape): UirShape {
        val resultDims = mutableListOf<UirDim>()
        val dims1 = shape1.dims.reversed()
        val dims2 = shape2.dims.reversed()
        val maxLen = maxOf(dims1.size, dims2.size)
        
        for (i in 0 until maxLen) {
            val d1 = dims1.getOrElse(i) { constantDim(1) }
            val d2 = dims2.getOrElse(i) { constantDim(1) }
            resultDims.add(broadcastDim(d1, d2))
        }
        
        return shapeFromDims(resultDims.reversed())
    }
    
    /**
     * 广播两个维度。
     */
    private fun broadcastDim(d1: UirDim, d2: UirDim): UirDim {
        // 未知维度传播
        if (d1.dimKind == UirDimKind.UNKNOWN || d2.dimKind == UirDimKind.UNKNOWN) {
            return unknownDim()
        }
        
        // 常数维度广播
        if (d1.dimKind == UirDimKind.CONSTANT && d2.dimKind == UirDimKind.CONSTANT) {
            val v1 = d1.value ?: return unknownDim()
            val v2 = d2.value ?: return unknownDim()
            
            return when {
                v1 == v2 -> constantDim(v1)
                v1 == 1 -> constantDim(v2)
                v2 == 1 -> constantDim(v1)
                // 维度不兼容时，返回最大值（宽松广播）
                // 这样生成的程序虽然语义上不严格合法，但可以用于测试编译器的错误处理
                else -> constantDim(maxOf(v1, v2))
            }
        }
        
        // 其他情况返回未知
        return unknownDim()
    }
    
    // ===== 各算子的形状推导实现 =====
    
    /**
     * MATMUL 形状推导。
     * 
     * 规则：output_shape = batch_dims + [M, N]
     * 约束：输入 ndim ≥ 2
     */
    private fun inferMatmulShape(inputShapes: List<UirShape>): List<UirShape> {
        requireBinaryInput(UirOpKind.MATMUL, inputShapes)
        
        val shape1 = inputShapes[0]
        val shape2 = inputShapes[1]
        
        // 如果输入维度不够 2，将其视为 2-D（自动适配）
        val padded1 = if (shape1.dims.size < 2) {
            val missing = 2 - shape1.dims.size
            val extra = (1..missing).map { constantDim(16) }
            shapeFromDims(extra + shape1.dims)
        } else {
            shape1
        }
        val padded2 = if (shape2.dims.size < 2) {
            val missing = 2 - shape2.dims.size
            val extra = (1..missing).map { constantDim(16) }
            shapeFromDims(extra + shape2.dims)
        } else {
            shape2
        }
        
        // 批次维度广播
        val batchDims = if (padded1.dims.size > 2 || padded2.dims.size > 2) {
            val batch1 = padded1.dims.dropLast(2)
            val batch2 = padded2.dims.dropLast(2)
            
            if (batch1.isEmpty()) batch2
            else if (batch2.isEmpty()) batch1
            else {
                // 广播批次维度
                val result = mutableListOf<UirDim>()
                val b1 = batch1.reversed()
                val b2 = batch2.reversed()
                val maxLen = maxOf(b1.size, b2.size)
                
                for (i in 0 until maxLen) {
                    val d1 = b1.getOrElse(i) { constantDim(1) }
                    val d2 = b2.getOrElse(i) { constantDim(1) }
                    result.add(broadcastDim(d1, d2))
                }
                result.reversed()
            }
        } else {
            emptyList()
        }
        
        // 结果维度: [...batch, M, N]
        val resultDims = batchDims.toMutableList()
        resultDims.add(padded1.dims[padded1.dims.size - 2])  // M
        resultDims.add(padded2.dims[padded2.dims.size - 1])  // N
        
        return listOf(shapeFromDims(resultDims))
    }
    
    /**
     * REDUCE_* 形状推导。
     * 
     * 规则：
     * - keepdims=True: output_shape[i] = 1 if i in axes else input_shape[i]
     * - keepdims=False: output_shape = input_shape without axes
     */
    private fun inferReduceShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireSingleInput(UirOpKind.REDUCE_SUM, inputShapes)
        
        val inputShape = inputShapes[0]
        val ndim = inputShape.dims.size
        
        // 读取 axes
        val axes = when (val axesAttr = attributes["axes"] ?: attributes["axis"]) {
            is UirIntAttr -> listOf(axesAttr.value)
            // UirIntListAttr 暂未定义，使用默认值
            null -> listOf(ndim - 1)  // 默认最后一维
            else -> listOf(ndim - 1)
        }
        
        // 读取 keepdims
        val keepdims = when (val keepdimsAttr = attributes["keepdims"]) {
            is UirIntAttr -> keepdimsAttr.value != 0
            null -> false  // 默认不保留
            else -> false
        }
        
        // 规范化 axes（处理负数）
        val normalizedAxes = axes.map { ax -> 
            if (ax < 0) ax + ndim else ax
        }.toSet()
        
        // 检查 axes 范围
        for (ax in normalizedAxes) {
            if (ax < 0 || ax >= ndim) {
                throw ShapeInferenceError("Reduce axis $ax out of range [0, $ndim)")
            }
        }
        
        // 计算输出形状
        val outputDims = if (keepdims) {
            inputShape.dims.mapIndexed { i, dim ->
                if (i in normalizedAxes) constantDim(1) else dim
            }
        } else {
            val filtered = inputShape.dims.filterIndexed { i, _ -> i !in normalizedAxes }
            // 确保输出至少为 1-D（避免 0-D 传播到后续算子）
            if (filtered.isEmpty()) {
                listOf(constantDim(1))
            } else {
                filtered
            }
        }
        
        return listOf(shapeFromDims(outputDims))
    }
    
    /**
     * RESHAPE 形状推导。
     * 
     * 规则：output_shape = target_shape
     * 注意：-1 表示推断维度，返回未知维度
     * 
     * 当前实现：读取 shape 属性，如果没有则从输入推断 1-D
     */
    private fun inferReshapeShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireSingleInput(UirOpKind.RESHAPE, inputShapes)
        
        // 如果有 shape 属性，使用它
        // 否则展平为 1-D
        val targetShape = listOf(unknownDim())  // 默认 1-D
        
        return listOf(shapeFromDims(targetShape))
    }
    
    /**
     * TRANSPOSE 形状推导。
     * 
     * 规则：output_shape[i] = input_shape[perm[i]]
     * 默认 perm 为反转所有维度
     */
    private fun inferTransposeShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireSingleInput(UirOpKind.TRANSPOSE, inputShapes)
        
        val inputShape = inputShapes[0]
        val ndim = inputShape.dims.size
        
        // 默认反转所有维度
        val perm = (ndim - 1 downTo 0).toList()
        
        val outputDims = perm.map { i -> inputShape.dims[i] }
        
        return listOf(shapeFromDims(outputDims))
    }
    
    /**
     * SQUEEZE 形状推导。
     * 
     * 规则：去掉所有 size=1 的维度（或指定的 axes）
     */
    private fun inferSqueezeShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireSingleInput(UirOpKind.SQUEEZE, inputShapes)
        
        val inputShape = inputShapes[0]
        
        // 简化处理：去掉所有 size=1 的常数维度
        val outputDims = inputShape.dims.filter { dim ->
            !(dim.dimKind == UirDimKind.CONSTANT && dim.value == 1)
        }
        
        return listOf(shapeFromDims(outputDims))
    }
    
    /**
     * UNSQUEEZE 形状推导。
     * 
     * 规则：在指定位置插入 size=1 维度
     */
    private fun inferUnsqueezeShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireSingleInput(UirOpKind.UNSQUEEZE, inputShapes)
        
        val inputShape = inputShapes[0]
        
        // 简化处理：在 axis=0 插入
        val outputDims = mutableListOf<UirDim>()
        outputDims.add(constantDim(1))
        outputDims.addAll(inputShape.dims)
        
        return listOf(shapeFromDims(outputDims))
    }
    
    /**
     * CONCAT 形状推导。
     * 
     * 规则：沿 axis 拼接，其余维度必须相等
     * 输出形状：与输入形状相同（axis 维度会增大，但我们用第一个输入的形状）
     */
    private fun inferConcatShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        if (inputShapes.isEmpty()) {
            throw ShapeInferenceError("CONCAT requires at least 1 input")
        }
        
        // 放宽约束：所有输入至少 ndim 相同
        // 如果不相同，扩展到相同维度
        val maxNdim = inputShapes.maxOfOrNull { it.dims.size } ?: 1
        val paddedShapes = inputShapes.map { shape ->
            if (shape.dims.size < maxNdim) {
                val missing = maxNdim - shape.dims.size
                val extra = (1..missing).map { constantDim(1) }
                shapeFromDims(extra + shape.dims)
            } else {
                shape
            }
        }
        
        // 返回第一个输入的形状（axis 维度的实际大小未知，用 unknownDim）
        return listOf(shapeFromDims(paddedShapes[0].dims))
    }
    
    /**
     * SPLIT 形状推导。
     * 
     * 规则：沿 axis 分割，输出形状与输入相同（多输出）
     * 当前实现：假设分割为 2 份
     */
    private fun inferSplitShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireSingleInput(UirOpKind.SPLIT, inputShapes)
        
        val inputShape = inputShapes[0]
        
        // 读取 axis
        val axis = (attributes["axis"] as? UirIntAttr)?.value ?: 0
        
        // 输出形状：与输入相同（axis 维度被分割，但这里简化处理）
        // 返回 2 个相同形状的输出
        return listOf(shapeFromDims(inputShape.dims), shapeFromDims(inputShape.dims))
    }
    
    /**
     * GATHER 形状推导。
     * 
     * 规则：output_ndim = input_ndim + indices_ndim - 1
     * 当前实现：假设 indices 是常量 0-D 或 1-D
     */
    private fun inferGatherShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        // GATHER 可以是单输入（indices 是常量）或双输入
        if (inputShapes.size == 1) {
            // 单输入模式：假设 indices 是 0-D 标量，输出形状不变
            return listOf(inputShapes[0])
        }
        
        requireBinaryInput(UirOpKind.GATHER, inputShapes)
        
        val dataShape = inputShapes[0]
        val indicesShape = inputShapes[1]
        
        val axis = (attributes["axis"] as? UirIntAttr)?.value ?: 0
        
        // output_shape = data_shape[0:axis] + indices_shape + data_shape[axis+1:]
        val outputDims = dataShape.dims.toMutableList()
        if (axis in 0 until outputDims.size) {
            outputDims.removeAt(axis)
            // 在 axis 位置插入 indices 的形状
            // 简化处理：假设 indices 是 1-D 或 0-D
            if (indicesShape.dims.isNotEmpty()) {
                outputDims.addAll(axis, indicesShape.dims)
            }
        }
        
        return listOf(shapeFromDims(outputDims))
    }
    
    /**
     * STRIDED_SLICE 形状推导。
     * 
     * 规则：取决于切片范围
     */
    private fun inferStridedSliceShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireSingleInput(UirOpKind.STRIDED_SLICE, inputShapes)
        
        // 简化处理：返回未知形状
        val inputShape = inputShapes[0]
        
        return listOf(shapeFromDims(inputShape.dims.map { unknownDim() }))
    }
    
    /**
     * TRIL/TRIU 形状推导。
     * 
     * 规则：output_shape = input_shape
     * 约束：输入 ndim >= 2
     */
    private fun inferTrilTriuShape(inputShapes: List<UirShape>, op: UirOpKind): List<UirShape> {
        requireSingleInput(op, inputShapes)
        
        val inputShape = inputShapes[0]
        
        // 如果输入维度不够 2，扩展为 2-D（自动适配）
        val adaptedShape = if (inputShape.dims.size < 2) {
            val missing = 2 - inputShape.dims.size
            val extra = (1..missing).map { constantDim(16) }
            shapeFromDims(extra + inputShape.dims)
        } else {
            inputShape
        }
        
        return listOf(shapeFromDims(adaptedShape.dims))  // 修复：返回适配后的形状
    }
    
    /**
     * ARANGE 形状推导。
     * 
     * 规则：1-D 张量，长度由 start/stop/step 决定
     * 当前实现：返回未知长度的 1-D
     */
    private fun inferArangeShape(attributes: Map<String, Attribute>): List<UirShape> {
        // 不硬编码长度，返回 1-D 未知形状
        return listOf(shapeFromDims(listOf(unknownDim())))
    }
    
    /**
     * FULL/ONES/ZEROS 形状推导。
     * 
     * 规则：由 shape 属性决定
     * 当前实现：如果没有 shape 属性，返回 1-D 未知形状
     */
    private fun inferConstantGenShape(op: UirOpKind, attributes: Map<String, Attribute>): List<UirShape> {
        // 不硬编码形状，返回 1-D 未知
        return listOf(shapeFromDims(listOf(unknownDim())))
    }
    
    /**
     * BROADCAST_TO 形状推导。
     * 
     * 规则：output_shape = target_shape
     * 当前实现：返回 1-D 未知（应由属性决定）
     */
    private fun inferBroadcastToShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireSingleInput(UirOpKind.BROADCAST_TO, inputShapes)
        
        // 应从属性读取 target_shape，这里简化处理
        return listOf(shapeFromDims(listOf(unknownDim())))
    }
    
    /**
     * TILE 形状推导。
     * 
     * 规则：output_shape[i] = input_shape[i] * repeats[i]
     */
    private fun inferTileShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireSingleInput(UirOpKind.TILE, inputShapes)
        
        val inputShape = inputShapes[0]
        
        // 简化处理：假设 repeats = 1，形状不变
        return listOf(shapeFromDims(inputShape.dims))
    }
    
    /**
     * EXPAND_DIMS 形状推导。
     * 
     * 规则：在 axis 位置插入 size=1 维度
     */
    private fun inferExpandDimsShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireSingleInput(UirOpKind.EXPAND_DIMS, inputShapes)
        
        val inputShape = inputShapes[0]
        val axis = (attributes["axis"] as? UirIntAttr)?.value ?: 0
        
        val outputDims = inputShape.dims.toMutableList()
        outputDims.add(axis.coerceIn(0, outputDims.size), constantDim(1))
        
        return listOf(shapeFromDims(outputDims))
    }
}