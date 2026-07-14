package io.github.xyzboom.aiFuzzer.ir

/**
 * UIR 中支持的算子类型枚举。
 *
 * 替代旧的 `op: String` 设计，提供类型安全和编译期检查。
 * 每个枚举值对应一个具体的算子操作。
 */
enum class UirOpKind {
    // 元素级二元
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    MAXIMUM,
    MINIMUM,
    POWER,

    // 矩阵乘法
    MATMUL,

    // 一元激活
    RELU,
    LEAKY_RELU,
    ELU,
    SELU,
    MISH,
    HARDTANH,
    SIGMOID,
    TANH,
    GELU,
    SILU,
    SOFTMAX,
    LOG_SOFTMAX,

    // 一元数学
    NEG,
    ABS,
    SIGN,
    EXP,
    LOG,
    LOG2,
    SQRT,
    RSQRT,
    RECIPROCAL,
    CEIL,
    FLOOR,
    ROUND,
    CLAMP,

    // 形状变换
    RESHAPE,
    TRANSPOSE,
    SQUEEZE,
    UNSQUEEZE,

    // 拼接/分割
    CONCAT,
    SPLIT,

    // 归约（支持显式 dtype）
    REDUCE_SUM,
    REDUCE_MEAN,
    REDUCE_MAX,
    REDUCE_MIN,
    CUMSUM,       // 累积和（Issue #189518: dtype bug）
    CUMPROD,      // 累积积
    ARGMAX,       // 最大值索引
    ARGMIN,       // 最小值索引

    // 归一化
    LAYER_NORM,
    BATCH_NORM,

    // 索引/切片
    GATHER,
    STRIDED_SLICE,

    // 广播/填充
    BROADCAST_TO,
    TILE,

    // 类型转换
    CAST,

    // 常数生成（支持显式 dtype）
    ARANGE,
    FULL,
    ONES,
    ZEROS,
    
    // 插值/Resize（Issue #19570: coordinate transformation bug）
    INTERPOLATE,  // nn.interpolate
    RESIZE2D,     // image.resize2d

    // 卷积
    CONV2D,
    MAX_POOL2D,
    AVG_POOL2D,

    // 三角
    TRIL,
    TRIU,

    // 适配算子（由 ShapeAdapter 插入）
    EXPAND_DIMS,
    ;

    companion object {
        /** 单输入算子 */
        val singleInputOps = setOf(
            RELU, LEAKY_RELU, ELU, SELU, MISH, HARDTANH, SIGMOID, TANH, GELU, SILU,
            ABS, SIGN, EXP, LOG, LOG2, SQRT, RSQRT, RECIPROCAL, NEG,
            CEIL, FLOOR, ROUND, CLAMP,
            SOFTMAX, LOG_SOFTMAX,
            RESHAPE, SQUEEZE, UNSQUEEZE,
            REDUCE_SUM, REDUCE_MEAN, REDUCE_MAX, REDUCE_MIN,
            CUMSUM, CUMPROD, ARGMAX, ARGMIN,
            CAST, TRIL, TRIU,
            TRANSPOSE, BROADCAST_TO,
            TILE, SPLIT,
            STRIDED_SLICE,
            GATHER,  // GATHER 当作单输入算子（假设 indices 是常量）
            MAX_POOL2D, AVG_POOL2D,
            LAYER_NORM, BATCH_NORM,
            INTERPOLATE, RESIZE2D,
        )

        /** 双输入算子 */
        val binaryInputOps = setOf(
            ADD, SUBTRACT, MULTIPLY, DIVIDE,
            MAXIMUM, MINIMUM, POWER,
            CONCAT,
            MATMUL,
            CONV2D,
        )

        /** 常数生成算子（无输入，支持显式 dtype） */
        val constantOps = setOf(ARANGE, FULL, ONES, ZEROS)

        /** 多输出算子 */
        val multiOutputOps = setOf(SPLIT)

        /** 需要 ndim >= 2 的算子 */
        val needNdimGe2 = setOf(
            TRANSPOSE, TRIL, TRIU, STRIDED_SLICE,
            CONV2D, MAX_POOL2D, AVG_POOL2D,
        )

        /** reduce 类算子（支持显式 dtype） */
        val reducingOps = setOf(
            REDUCE_SUM, REDUCE_MEAN, REDUCE_MAX, REDUCE_MIN,
            CUMSUM, CUMPROD, ARGMAX, ARGMIN,
        )

        /** ndim 不变的算子 */
        val ndimStableOps = setOf(
            RELU, LEAKY_RELU, ELU, SELU, MISH, HARDTANH, SIGMOID, TANH, GELU, SILU,
            NEG, ABS, SIGN, EXP, LOG, LOG2, SQRT, RSQRT, RECIPROCAL, CEIL, FLOOR, ROUND, CLAMP,
            SOFTMAX, LOG_SOFTMAX, CAST,
            SPLIT, CONCAT, TILE,
            TRANSPOSE,
            CONV2D, MAX_POOL2D, AVG_POOL2D,
            LAYER_NORM, BATCH_NORM,
        )

        /** 适配算子（由 ShapeAdapter 插入，不参与逻辑图生成） */
        val adapterOps = setOf(EXPAND_DIMS, SQUEEZE, RESHAPE, BROADCAST_TO, CONCAT, SPLIT, MATMUL)
    }
}
