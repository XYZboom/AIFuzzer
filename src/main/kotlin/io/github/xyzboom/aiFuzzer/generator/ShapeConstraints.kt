package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.types.UirDim
import io.github.xyzboom.aiFuzzer.ir.types.UirShape
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildShape

/**
 * 算子形状约束定义。
 *
 * 定义每个算子在形状层面的约束条件：
 * - 最小/最大维度数
 * - 输入数量范围
 * - 形状兼容性检查函数
 */
data class OpShapeConstraint(
    /** 最小维度数（输入至少需要的维度） */
    val minNdim: Int = 0,
    /** 最大维度数（可选，某些算子有上限） */
    val maxNdim: Int? = null,
    /** 输入数量范围 */
    val numInputs: IntRange = 1..1,
    /** 形状兼容性检查函数 */
    val isApplicable: (List<UirShape>) -> Boolean = { true },
    /** 描述信息（用于调试） */
    val description: String = ""
)

/**
 * 算子形状约束注册表。
 *
 * 提供每个算子的形状约束查询接口。
 */
object ShapeConstraints {
    
    /** 所有算子的约束定义 */
    private val constraints: Map<UirOpKind, OpShapeConstraint> = mapOf(
        // ===== 分类 A：形状不变（逐元素/激活） =====
        // 无维度要求，单输入
        UirOpKind.RELU to OpShapeConstraint(
            numInputs = 1..1,
            description = "逐元素激活，形状不变"
        ),
        UirOpKind.SIGMOID to OpShapeConstraint(
            numInputs = 1..1,
            description = "逐元素激活，形状不变"
        ),
        UirOpKind.TANH to OpShapeConstraint(
            numInputs = 1..1,
            description = "逐元素激活，形状不变"
        ),
        UirOpKind.GELU to OpShapeConstraint(
            numInputs = 1..1,
            description = "逐元素激活，形状不变"
        ),
        UirOpKind.SILU to OpShapeConstraint(
            numInputs = 1..1,
            description = "逐元素激活，形状不变"
        ),
        UirOpKind.SOFTMAX to OpShapeConstraint(
            numInputs = 1..1,
            minNdim = 1,
            description = "归一化，有 axis 参数"
        ),
        UirOpKind.NEG to OpShapeConstraint(
            numInputs = 1..1,
            description = "逐元素数学运算，形状不变"
        ),
        UirOpKind.ABS to OpShapeConstraint(
            numInputs = 1..1,
            description = "逐元素数学运算，形状不变"
        ),
        UirOpKind.EXP to OpShapeConstraint(
            numInputs = 1..1,
            description = "逐元素数学运算，形状不变"
        ),
        UirOpKind.LOG to OpShapeConstraint(
            numInputs = 1..1,
            description = "逐元素数学运算，形状不变"
        ),
        UirOpKind.SQRT to OpShapeConstraint(
            numInputs = 1..1,
            description = "逐元素数学运算，形状不变"
        ),
        UirOpKind.CEIL to OpShapeConstraint(
            numInputs = 1..1,
            description = "逐元素数学运算，形状不变"
        ),
        UirOpKind.FLOOR to OpShapeConstraint(
            numInputs = 1..1,
            description = "逐元素数学运算，形状不变"
        ),
        UirOpKind.CAST to OpShapeConstraint(
            numInputs = 1..1,
            description = "类型转换，形状不变"
        ),
        
        // ===== 分类 B：广播二元运算 =====
        // 支持单输入或双输入，广播后形状由两个输入决定
        UirOpKind.ADD to OpShapeConstraint(
            numInputs = 1..2,
            isApplicable = { shapes ->
                // 单输入总是合法（自己和自己运算）
                if (shapes.size == 1) true
                // 双输入：检查是否可广播
                else areBroadcastable(shapes[0], shapes[1])
            },
            description = "二元运算，支持广播"
        ),
        UirOpKind.SUBTRACT to OpShapeConstraint(
            numInputs = 1..2,
            isApplicable = { shapes ->
                if (shapes.size == 1) true
                else areBroadcastable(shapes[0], shapes[1])
            },
            description = "二元运算，支持广播"
        ),
        UirOpKind.MULTIPLY to OpShapeConstraint(
            numInputs = 1..2,
            isApplicable = { shapes ->
                if (shapes.size == 1) true
                else areBroadcastable(shapes[0], shapes[1])
            },
            description = "二元运算，支持广播"
        ),
        UirOpKind.DIVIDE to OpShapeConstraint(
            numInputs = 1..2,
            isApplicable = { shapes ->
                if (shapes.size == 1) true
                else areBroadcastable(shapes[0], shapes[1])
            },
            description = "二元运算，支持广播"
        ),
        UirOpKind.MAXIMUM to OpShapeConstraint(
            numInputs = 1..2,
            isApplicable = { shapes ->
                if (shapes.size == 1) true
                else areBroadcastable(shapes[0], shapes[1])
            },
            description = "二元运算，支持广播"
        ),
        UirOpKind.MINIMUM to OpShapeConstraint(
            numInputs = 1..2,
            isApplicable = { shapes ->
                if (shapes.size == 1) true
                else areBroadcastable(shapes[0], shapes[1])
            },
            description = "二元运算，支持广播"
        ),
        UirOpKind.POWER to OpShapeConstraint(
            numInputs = 1..2,
            isApplicable = { shapes ->
                if (shapes.size == 1) true
                else areBroadcastable(shapes[0], shapes[1])
            },
            description = "二元运算，支持广播"
        ),
        
        // ===== 分类 C：矩阵乘法 =====
        // 必须双输入，每个输入至少 2 维
        UirOpKind.MATMUL to OpShapeConstraint(
            minNdim = 2,
            numInputs = 2..2,
            isApplicable = { shapes ->
                if (shapes.size != 2) false
                else if (shapes[0].dims.size < 2 || shapes[1].dims.size < 2) false
                else {
                    // 检查 K 维匹配：shapes[0][-1] == shapes[1][-2]
                    val k1 = shapes[0].dims.last().valueOrNull()
                    val k2 = shapes[1].dims[shapes[1].dims.size - 2].valueOrNull()
                    val kMatch = (k1 == null || k2 == null) || (k1 == k2)
                    
                    // 检查批次维度可广播
                    val batch1 = shapes[0].dims.dropLast(2)
                    val batch2 = shapes[1].dims.dropLast(2)
                    val batchBroadcastable = areBroadcastable(
                        shapeFromDims(batch1),
                        shapeFromDims(batch2)
                    )
                    
                    kMatch && batchBroadcastable
                }
            },
            description = "矩阵乘法，需要 ndim≥2 且 K 维匹配且批次可广播"
        ),

        // ===== 分类 C.2：卷积运算 =====
        UirOpKind.CONV2D to OpShapeConstraint(
            minNdim = 4,  // 输入必须 4D (NCHW or NHWC)
            maxNdim = 4,  // 必须精确 4D，多于 4D 需要 squeeze
            numInputs = 2..2,  // 输入 + 权重
            isApplicable = { shapes ->
                // 简化约束：假设 NCHW 格式
                // shapes[0]: [N, C_in, H, W]
                // shapes[1]: [C_out, C_in/groups, kH, kW]
                if (shapes.size != 2) false
                else if (shapes[0].dims.size != 4 || shapes[1].dims.size != 4) false
                else {
                    // 检查 C_in 维度匹配（假设 groups=1）
                    val cInInput = shapes[0].dims[1].valueOrNull()  // C_in
                    val cInWeight = shapes[1].dims[1].valueOrNull()  // C_in/groups
                    val cMatch = (cInInput == null || cInWeight == null) || (cInInput == cInWeight)
                    cMatch
                }
            },
            description = "2D 卷积，需要 4D 输入（NCHW）和权重，C_in 维度匹配"
        ),

        // ===== 分类 C.3：池化运算 =====
        UirOpKind.MAX_POOL2D to OpShapeConstraint(
            minNdim = 4,  // 输入必须 4D (NCHW)
            maxNdim = 4,  // 必须精确 4D
            numInputs = 1..1,
            description = "2D 最大池化，需要 4D 输入（NCHW）"
        ),
        UirOpKind.AVG_POOL2D to OpShapeConstraint(
            minNdim = 4,  // 输入必须 4D (NCHW)
            maxNdim = 4,  // 必须精确 4D
            numInputs = 1..1,
            description = "2D 平均池化，需要 4D 输入（NCHW）"
        ),

        // ===== 分类 D.2：归一化运算 =====
        UirOpKind.LAYER_NORM to OpShapeConstraint(
            numInputs = 1..1,
            minNdim = 1,
            description = "层归一化，输出形状不变"
        ),
        UirOpKind.BATCH_NORM to OpShapeConstraint(
            minNdim = 3,  // F.batch_norm 需要 3D 或 4D 输入 [N, C, ...]
            maxNdim = 4,
            numInputs = 1..1,
            isApplicable = { shapes ->
                shapes.size == 1 && shapes[0].dims.size in 3..4
            },
            description = "批归一化，需要 3D 或 4D 输入（N,C 或 N,C,H,W）"
        ),
        // 单输入，无维度要求
        UirOpKind.REDUCE_SUM to OpShapeConstraint(
            numInputs = 1..1,
            minNdim = 1,
            description = "归约运算，输出维度减少"
        ),
        UirOpKind.REDUCE_MEAN to OpShapeConstraint(
            numInputs = 1..1,
            minNdim = 1,
            description = "归约运算，输出维度减少"
        ),
        UirOpKind.REDUCE_MAX to OpShapeConstraint(
            numInputs = 1..1,
            minNdim = 1,
            description = "归约运算，输出维度减少"
        ),
        UirOpKind.REDUCE_MIN to OpShapeConstraint(
            numInputs = 1..1,
            minNdim = 1,
            description = "归约运算，输出维度减少"
        ),
        
        // ===== 分类 E：形状变换 =====
        UirOpKind.RESHAPE to OpShapeConstraint(
            numInputs = 1..1,
            description = "形状变换，需要 shape 属性"
        ),
        UirOpKind.TRANSPOSE to OpShapeConstraint(
            minNdim = 2,
            numInputs = 1..1,
            isApplicable = { shapes ->
                shapes.size == 1 && shapes[0].dims.size >= 2
            },
            description = "维度转置，需要 ndim≥2"
        ),
        UirOpKind.SQUEEZE to OpShapeConstraint(
            numInputs = 1..1,
            description = "去掉 size=1 的维度"
        ),
        UirOpKind.UNSQUEEZE to OpShapeConstraint(
            numInputs = 1..1,
            description = "插入 size=1 的维度"
        ),
        
        // ===== 分类 F：拼接/分割 =====
        UirOpKind.CONCAT to OpShapeConstraint(
            numInputs = 2..Int.MAX_VALUE,  // 至少 2 个输入
            isApplicable = { shapes ->
                // 所有输入必须有相同的维度数
                if (shapes.isEmpty()) false
                else {
                    val ndim = shapes[0].dims.size
                    shapes.all { it.dims.size == ndim }
                }
            },
            description = "拼接，需要输入维度数相同"
        ),
        UirOpKind.SPLIT to OpShapeConstraint(
            numInputs = 1..1,
            description = "分割为多输出"
        ),
        
        // ===== 分类 G：索引/切片 =====
        UirOpKind.GATHER to OpShapeConstraint(
            numInputs = 1..1,  // 单输入模式（indices 是常量）
            minNdim = 1,
            description = "索引操作，需要至少 1D"
        ),
        UirOpKind.STRIDED_SLICE to OpShapeConstraint(
            minNdim = 2,
            numInputs = 1..1,
            isApplicable = { shapes ->
                shapes.size == 1 && shapes[0].dims.size >= 2
            },
            description = "切片操作，通常需要 ndim≥2"
        ),
        
        // ===== 分类 H：三角矩阵 =====
        UirOpKind.TRIL to OpShapeConstraint(
            minNdim = 2,
            numInputs = 1..1,
            isApplicable = { shapes ->
                shapes.size == 1 && shapes[0].dims.size >= 2
            },
            description = "下三角矩阵，需要 ndim≥2"
        ),
        UirOpKind.TRIU to OpShapeConstraint(
            minNdim = 2,
            numInputs = 1..1,
            isApplicable = { shapes ->
                shapes.size == 1 && shapes[0].dims.size >= 2
            },
            description = "上三角矩阵，需要 ndim≥2"
        ),
        
        // ===== 分类 I：常数生成 =====
        // 无输入
        UirOpKind.ARANGE to OpShapeConstraint(
            numInputs = 0..0,
            description = "生成序列张量"
        ),
        UirOpKind.FULL to OpShapeConstraint(
            numInputs = 0..0,
            description = "生成填充张量"
        ),
        UirOpKind.ONES to OpShapeConstraint(
            numInputs = 0..0,
            description = "生成全 1 张量"
        ),
        UirOpKind.ZEROS to OpShapeConstraint(
            numInputs = 0..0,
            description = "生成全 0 张量"
        ),
        
        // ===== 分类 J：广播/填充 =====
        UirOpKind.BROADCAST_TO to OpShapeConstraint(
            numInputs = 1..1,
            description = "广播到目标形状"
        ),
        UirOpKind.TILE to OpShapeConstraint(
            numInputs = 1..1,
            minNdim = 1,
            description = "复制填充，需要至少 1D"
        ),
        
        // ===== 分类 K：适配算子 =====
        UirOpKind.EXPAND_DIMS to OpShapeConstraint(
            numInputs = 1..1,
            description = "插入维度（适配算子）"
        ),

        // ===== 分类 L：插值/Resize =====
        UirOpKind.INTERPOLATE to OpShapeConstraint(
            minNdim = 4,  // Must be 4D to be compatible with both PyTorch and TVM (resize2d requires NCHW)
            maxNdim = 4,
            numInputs = 1..1,
            isApplicable = { shapes ->
                shapes.size == 1 && shapes[0].dims.size == 4
            },
            description = "插值运算，需要 4D 输入（NCHW，兼容 PyTorch F.interpolate 和 TVM resize2d）"
        ),
        UirOpKind.RESIZE2D to OpShapeConstraint(
            minNdim = 4,  // image.resize2d 需要 4D 输入 (NCHW)
            maxNdim = 4,
            numInputs = 1..1,
            isApplicable = { shapes ->
                shapes.size == 1 && shapes[0].dims.size == 4
            },
            description = "2D Resize，需要 4D 输入（NCHW）"
        ),
    )
    
    /**
     * 获取算子的形状约束。
     */
    fun getConstraint(op: UirOpKind): OpShapeConstraint {
        return constraints[op] ?: OpShapeConstraint(
            description = "默认约束：单输入，无维度要求"
        )
    }
    
    /**
     * 检查算子是否可用给定的输入形状。
     *
     * @param op 算子类型
     * @param inputShapes 输入形状列表
     * @return true 如果算子可以应用
     */
    fun isApplicable(op: UirOpKind, inputShapes: List<UirShape>): Boolean {
        val constraint = getConstraint(op)
        
        // 检查输入数量
        if (inputShapes.size !in constraint.numInputs) {
            return false
        }
        
        // 检查最小维度
        if (constraint.minNdim > 0) {
            for (shape in inputShapes) {
                if (shape.dims.size < constraint.minNdim) {
                    return false
                }
            }
        }
        
        // 检查最大维度
        if (constraint.maxNdim != null) {
            for (shape in inputShapes) {
                if (shape.dims.size > constraint.maxNdim) {
                    return false
                }
            }
        }
        
        // 检查自定义约束
        return constraint.isApplicable(inputShapes)
    }
    
    /**
     * 检查两个形状是否可广播（NumPy 风格）。
     */
    fun areBroadcastable(s1: UirShape, s2: UirShape): Boolean {
        // NumPy 广播规则：从右对齐，每个维度要么相等，要么其中一个为 1
        val dims1 = s1.dims.reversed()
        val dims2 = s2.dims.reversed()
        val maxLen = maxOf(dims1.size, dims2.size)
        
        for (i in 0 until maxLen) {
            val d1 = dims1.getOrElse(i) { null }
            val d2 = dims2.getOrElse(i) { null }
            
            val v1 = d1?.valueOrNull()
            val v2 = d2?.valueOrNull()
            
            // 如果任一维度未知，假设可广播
            if (v1 == null || v2 == null) continue
            
            // 广播规则：相等 或 一个为 1
            if (v1 != v2 && v1 != 1 && v2 != 1) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * 筛选出可用给定输入形状的算子列表。
     *
     * @param ops 可选的算子列表
     * @param inputShapes 输入形状列表
     * @return 可应用的算子列表
     */
    fun filterApplicable(ops: List<UirOpKind>, inputShapes: List<UirShape>): List<UirOpKind> {
        return ops.filter { op -> isApplicable(op, inputShapes) }
    }
    
    /**
     * 获取算子需要的最小维度数。
     */
    fun minNdimForOp(op: UirOpKind): Int {
        return getConstraint(op).minNdim
    }
    
    /**
     * 获取算子的输入数量范围。
     */
    fun inputCountRange(op: UirOpKind): IntRange {
        return getConstraint(op).numInputs
    }
    
    /**
     * 从维度列表创建形状。
     */
    private fun shapeFromDims(dims: List<UirDim>): UirShape {
        return buildShape {
            dims.forEach { dim ->
                this.dims.add(dim)
            }
        }
    }
    
    /**
     * 获取所有需要 ndim≥2 的算子。
     */
    fun opsNeedNdimGe2(): Set<UirOpKind> {
        return constraints.entries
            .filter { (_, c) -> c.minNdim >= 2 }
            .map { it.key }
            .toSet()
    }
    
    /**
     * 获取所有单输入算子。
     */
    fun singleInputOps(): Set<UirOpKind> {
        return constraints.entries
            .filter { (_, c) -> c.numInputs == 1..1 }
            .map { it.key }
            .toSet()
    }
    
    /**
     * 获取所有双输入算子。
     */
    fun binaryInputOps(): Set<UirOpKind> {
        return constraints.entries
            .filter { (_, c) -> c.numInputs == 2..2 }
            .map { it.key }
            .toSet()
    }
    
    /**
     * 获取所有无输入算子（常数生成）。
     */
    fun noInputOps(): Set<UirOpKind> {
        return constraints.entries
            .filter { (_, c) -> c.numInputs == 0..0 }
            .map { it.key }
            .toSet()
    }
}

/**
 * 辅助扩展：获取维度值的OrNull版本。
 */
fun UirDim.valueOrNull(): Int? {
    return if (dimKind == io.github.xyzboom.aiFuzzer.ir.UirDimKind.CONSTANT) value else null
}