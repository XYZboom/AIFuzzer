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
            UirOpKind.LEAKY_RELU,
            UirOpKind.ELU,
            UirOpKind.SELU,
            UirOpKind.MISH,
            UirOpKind.HARDTANH,
            UirOpKind.SIGMOID,
            UirOpKind.TANH,
            UirOpKind.GELU,
            UirOpKind.SILU,
            UirOpKind.NEG,
            UirOpKind.ABS,
            UirOpKind.SIGN,
            UirOpKind.EXP,
            UirOpKind.LOG,
            UirOpKind.LOG2,
            UirOpKind.SQRT,
            UirOpKind.RSQRT,
            UirOpKind.RECIPROCAL,
            UirOpKind.CEIL,
            UirOpKind.FLOOR,
            UirOpKind.ROUND,
            UirOpKind.CLAMP,
            UirOpKind.SOFTMAX,
            UirOpKind.LOG_SOFTMAX,
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

            // ===== 分类 C.2：卷积 =====
            UirOpKind.CONV2D -> {
                inferConv2dShape(inputShapes, attributes)
            }

            // ===== 分类 C.3：池化 =====
            UirOpKind.MAX_POOL2D,
            UirOpKind.AVG_POOL2D -> {
                inferPool2dShape(inputShapes, attributes)
            }
            
            // ===== 分类 D：归约运算 =====
            UirOpKind.REDUCE_SUM,
            UirOpKind.REDUCE_MEAN,
            UirOpKind.REDUCE_MAX,
            UirOpKind.REDUCE_MIN -> {
                inferReduceShape(inputShapes, attributes)
            }

            // ===== 分类 D.2：归一化运算 =====
            UirOpKind.LAYER_NORM,
            UirOpKind.BATCH_NORM -> {
                // 归一化算子形状不变
                requireSingleInput(op, inputShapes)
                listOf(inputShapes.first())
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
            
            // ===== 分类 G2：累积操作（P0 新增） =====
            UirOpKind.CUMSUM,
            UirOpKind.CUMPROD -> {
                // cumsum/cumprod 保持输入形状
                inputShapes
            }
            
            UirOpKind.ARGMAX,
            UirOpKind.ARGMIN -> {
                // argmax/argmin 在指定 axis 上降维
                inferArgmaxArgminShape(inputShapes, attributes)
            }
            
            // ===== 分类 G3：插值/Resize（P2 新增） =====
            UirOpKind.INTERPOLATE,
            UirOpKind.RESIZE2D -> {
                // F.interpolate with scale_factor=2.0 doubles spatial dimensions
                // Input: [N, C, ...spatial...] -> Output: [N, C, ...spatial*2...]
                val inputShape = inputShapes[0]
                val ndim = inputShape.dims.size
                listOf(buildShape {
                    for (i in 0 until ndim) {
                        val dimValue = inputShape.dims[i].value ?: 1
                        // First 2 dims (N, C) stay the same, spatial dims are doubled
                        val outValue = if (i < 2) dimValue else dimValue * 2
                        dims.add(buildDim {
                            dimKind = UirDimKind.CONSTANT
                            value = outValue
                        })
                    }
                })
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
     * CONV2D 形状推导。
     *
     * 假设 NCHW 格式：
     *   input:  [N, C_in, H, W]
     *   weight: [C_out, C_in/groups, kH, kW]
     *   output: [N, C_out, H_out, W_out]
     *
     * 属性：
     *   stride (int, 默认 1) - H 和 W 共用的步长
     *   padding (int, 默认 0) - H 和 W 共用的填充
     *   dilation (int, 默认 1) - H 和 W 共用的膨胀率
     *   groups (int, 默认 1) - 分组卷积的组数
     *
     * H_out = floor((H + 2*padding - dilation*(kH-1) - 1) / stride + 1)
     * W_out = floor((W + 2*padding - dilation*(kW-1) - 1) / stride + 1)
     */
    private fun inferConv2dShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireBinaryInput(UirOpKind.CONV2D, inputShapes)

        val inputShape = inputShapes[0]
        val weightShape = inputShapes[1]

        // 读取属性（默认值：stride=1, padding=0, dilation=1, groups=1）
        val stride = when (val attr = attributes["stride"]) {
            is UirIntAttr -> attr.value
            else -> 1
        }
        val padding = when (val attr = attributes["padding"]) {
            is UirIntAttr -> attr.value
            else -> 0
        }
        val dilation = when (val attr = attributes["dilation"]) {
            is UirIntAttr -> attr.value
            else -> 1
        }

        // 检查输入是否为 4D
        if (inputShape.dims.size < 4 || weightShape.dims.size < 4) {
            // 维度不足时，假设为 4D 并补齐
            val paddedInput = if (inputShape.dims.size < 4) {
                val missing = 4 - inputShape.dims.size
                val extra = (1..missing).map { constantDim(16) }
                shapeFromDims(extra + inputShape.dims)
            } else inputShape

            val paddedWeight = if (weightShape.dims.size < 4) {
                val missing = 4 - weightShape.dims.size
                val extra = (1..missing).map { constantDim(16) }
                shapeFromDims(extra + weightShape.dims)
            } else weightShape

            return inferConv2dShapeFrom4D(paddedInput, paddedWeight, stride, padding, dilation)
        }

        return inferConv2dShapeFrom4D(inputShape, weightShape, stride, padding, dilation)
    }

    /**
     * 从 4D 输入推导 CONV2D 输出形状。
     */
    private fun inferConv2dShapeFrom4D(
        inputShape: UirShape,
        weightShape: UirShape,
        stride: Int,
        padding: Int,
        dilation: Int
    ): List<UirShape> {
        // input: [N, C_in, H, W]
        val n = inputShape.dims[0]
        val cIn = inputShape.dims[1]
        val h = inputShape.dims[2]
        val w = inputShape.dims[3]

        // weight: [C_out, C_in/groups, kH, kW]
        val cOut = weightShape.dims[0]
        val kH = weightShape.dims[2]
        val kW = weightShape.dims[3]

        // 计算输出空间维度
        val hOut = computeConvOutputDim(h, kH, stride, padding, dilation)
        val wOut = computeConvOutputDim(w, kW, stride, padding, dilation)

        val outputDims = listOf(n, cOut, hOut, wOut)
        return listOf(shapeFromDims(outputDims))
    }

    /**
     * 计算卷积输出维度：
     * out = floor((in + 2*padding - dilation*(kernel-1) - 1) / stride + 1)
     */
    private fun computeConvOutputDim(
        inputDim: UirDim,
        kernelDim: UirDim,
        stride: Int,
        padding: Int,
        dilation: Int
    ): UirDim {
        val inVal = inputDim.value
        val kVal = kernelDim.value

        if (inVal == null || kVal == null) {
            // 任一维度未知，输出未知维度
            return unknownDim()
        }

        val outVal = (inVal + 2 * padding - dilation * (kVal - 1) - 1) / stride + 1
        return constantDim(outVal.coerceAtLeast(1))
    }

    /**
     * MAX_POOL2D / AVG_POOL2D 形状推导。
     *
     * 假设 NCHW 格式：
     *   input:  [N, C, H, W]
     *   output: [N, C, H_out, W_out]
     *
     * 属性：
     *   kernel_size (int, 默认 2) - 池化窗口大小
     *   stride (int, 默认等于 kernel_size) - 步长
     *   padding (int, 默认 0) - 填充
     *
     * H_out = floor((H + 2*padding - kernel_size) / stride + 1)
     * W_out = floor((W + 2*padding - kernel_size) / stride + 1)
     */
    private fun inferPool2dShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireSingleInput(UirOpKind.MAX_POOL2D, inputShapes)

        val inputShape = inputShapes[0]

        // 读取属性
        val kernelSize = when (val attr = attributes["kernel_size"]) {
            is UirIntAttr -> attr.value
            else -> 2
        }
        val stride = when (val attr = attributes["stride"]) {
            is UirIntAttr -> attr.value
            else -> kernelSize  // 默认 stride = kernel_size
        }
        val padding = when (val attr = attributes["padding"]) {
            is UirIntAttr -> attr.value
            else -> 0
        }

        // 确保 kernel_size 不超过输入的空间维度（与 PytorchTranslator 一致）
        // Translator 会裁剪 kernel_size 到 minSpatial，ShapeInferer 必须同步
        val adjustedKernelSize: Int
        val adjustedStride: Int
        if (inputShape.dims.size >= 4) {
            val h = inputShape.dims[2].value
            val w = inputShape.dims[3].value
            if (h != null && w != null) {
                val minSpatial = minOf(h, w)
                if (kernelSize > minSpatial) {
                    adjustedKernelSize = maxOf(1, minSpatial)
                    adjustedStride = minOf(stride, adjustedKernelSize)
                } else {
                    adjustedKernelSize = kernelSize
                    adjustedStride = stride
                }
            } else {
                adjustedKernelSize = kernelSize
                adjustedStride = stride
            }
        } else {
            adjustedKernelSize = kernelSize
            adjustedStride = stride
        }

        // 检查输入是否为 4D
        if (inputShape.dims.size < 4) {
            // 维度不足时，假设为 4D 并补齐
            val missing = 4 - inputShape.dims.size
            val extra = (1..missing).map { constantDim(16) }
            val paddedInput = shapeFromDims(extra + inputShape.dims)
            return inferPool2dShapeFrom4D(paddedInput, adjustedKernelSize, adjustedStride, padding)
        }

        return inferPool2dShapeFrom4D(inputShape, adjustedKernelSize, adjustedStride, padding)
    }

    /**
     * 从 4D 输入推导池化输出形状。
     */
    private fun inferPool2dShapeFrom4D(
        inputShape: UirShape,
        kernelSize: Int,
        stride: Int,
        padding: Int
    ): List<UirShape> {
        // input: [N, C, H, W]
        val n = inputShape.dims[0]
        val c = inputShape.dims[1]
        val h = inputShape.dims[2]
        val w = inputShape.dims[3]

        // 计算输出空间维度
        val hOut = computePoolOutputDim(h, kernelSize, stride, padding)
        val wOut = computePoolOutputDim(w, kernelSize, stride, padding)

        val outputDims = listOf(n, c, hOut, wOut)
        return listOf(shapeFromDims(outputDims))
    }

    /**
     * 计算池化输出维度：
     * out = floor((in + 2*padding - kernel) / stride + 1)
     */
    private fun computePoolOutputDim(
        inputDim: UirDim,
        kernelSize: Int,
        stride: Int,
        padding: Int
    ): UirDim {
        val inVal = inputDim.value

        if (inVal == null) {
            // 维度未知，输出未知维度
            return unknownDim()
        }

        // 确保输出至少为 1。如果输入空间维太小导致输出为 0 或负，
        // 将输出设为 1（PyTorch 不接受 0 维输出）
        val outVal = (inVal + 2 * padding - kernelSize) / stride + 1
        return constantDim(outVal.coerceAtLeast(1))
    }
    
    /**
     * 获取调整后的 pool kernel_size，确保在给定输入维度下输出至少为 1。
     */
    private fun adjustPoolKernelSize(inputDimVal: Int, kernelSize: Int, stride: Int, padding: Int): Int {
        var ks = kernelSize
        while (ks > 1) {
            val out = (inputDimVal + 2 * padding - ks) / stride + 1
            if (out >= 1) return ks
            ks--
        }
        return 1
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
            // 移除归约维度，可能产生 0-D tensor（标量）
            inputShape.dims.filterIndexed { i, _ -> i !in normalizedAxes }
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
        
        // 与所有 translator 一致：交换最后两个维度
        // PytorchTranslator: torch.transpose(x, ndim-2, ndim-1)
        // TvmRelaxTranslator: relax.op.permute_dims(x, [ndim-2, ndim-1])
        val outputDims = inputShape.dims.toMutableList()
        if (ndim >= 2) {
            val temp = outputDims[ndim - 2]
            outputDims[ndim - 2] = outputDims[ndim - 1]
            outputDims[ndim - 1] = temp
        }
        
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
     * 规则：单输入模式（indices 是标量常量）会移除 axis 维度
     * TVM 的 take(tensor, scalar_index, axis) 会减少一个维度
     */
    private fun inferGatherShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        if (inputShapes.size == 1) {
            val dataShape = inputShapes[0]
            // PyTorch translator generates torch.gather(x, axis, zeros([1]*x.ndim))
            // → output shape = index shape = [1]*ndim (same rank as input, all 1s)
            // This is the correct semantics for the fuzzer's generated code.
            val ndim = dataShape.dims.size
            val outputDims = (0 until ndim).map { constantDim(1) }
            return listOf(shapeFromDims(outputDims))
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
     * 规则：输出形状取决于切片范围
     * - 对于被切片的维度，输出维度值 = end - begin
     * - 对于未切片的维度，输出维度值 = 输入维度值
     * 
     * TVM 的 strided_slice 参数格式：
     * - axes: 被切片的轴列表
     * - begin: 各轴的起始索引
     * - end: 各轴的终止索引
     * 
     * 默认行为（无属性时）：
     * - axes=[0], begin=[0], end=[-1] → 在 axis=0 切掉最后一个元素
     */
    private fun inferStridedSliceShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireSingleInput(UirOpKind.STRIDED_SLICE, inputShapes)
        
        val inputShape = inputShapes[0]
        val ndim = inputShape.dims.size
        
        // 默认切片参数：axes=[0], begin=[0]
        // 取前半部分：[:shape[0]//2]，与 PyTorch 翻译器对齐
        val axes = listOf(0)  // 默认 axis=0
        val begins = listOf(0)  // 默认 begin=0
        
        // 计算输出形状
        val outputDims = inputShape.dims.toMutableList()
        
        for ((axisIdx, axis) in axes.withIndex()) {
            // 规范化 axis（处理负数）
            val normalizedAxis = if (axis < 0) axis + ndim else axis
            if (normalizedAxis < 0 || normalizedAxis >= ndim) continue
            
            val begin = begins.getOrElse(axisIdx) { 0 }
            
            val inputDim = inputShape.dims[normalizedAxis]
            val inputDimValue: Int? = inputDim.value
            
            // 计算切片后的维度值
            if (inputDimValue != null && inputDim.dimKind == UirDimKind.CONSTANT) {
                // 取前半部分：max(1, inputDimValue // 2)
                val sliceLength = if (inputDimValue == 0) {
                    0  // 空张量切片后仍为空张量
                } else {
                    maxOf(1, inputDimValue / 2)
                }
                outputDims[normalizedAxis] = constantDim(sliceLength)
            } else {
                // 输入维度未知，切片后也未知
                outputDims[normalizedAxis] = unknownDim()
            }
        }
        
        return listOf(shapeFromDims(outputDims))
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
    
    /**
     * ARGMAX/ARGMIN 形状推导（P0 新增）。
     * 
     * 规则：在 axis 维度上降维
     */
    private fun inferArgmaxArgminShape(
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape> {
        requireSingleInput(UirOpKind.ARGMAX, inputShapes)
        
        val inputShape = inputShapes[0]
        val axis = (attributes["axis"] as? UirIntAttr)?.value ?: -1
        val keepdims = (attributes["keepdims"] as? UirIntAttr)?.value ?: 0
        
        if (keepdims != 0) {
            // keepdims=true: 形状不变，axis 维度变为 1
            val outputDims = inputShape.dims.mapIndexed { i, dim ->
                if (i == axis || (axis < 0 && i == inputShape.dims.size + axis)) constantDim(1)
                else dim
            }
            return listOf(shapeFromDims(outputDims))
        } else {
            // keepdims=false: 移除 axis 维度
            val actualAxis = if (axis < 0) inputShape.dims.size + axis else axis
            val outputDims = inputShape.dims.filterIndexed { i, _ -> i != actualAxis }
            return listOf(shapeFromDims(outputDims))
        }
    }
}
