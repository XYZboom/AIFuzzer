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
    SIGMOID,
    TANH,
    GELU,
    SILU,
    SOFTMAX,

    // 一元数学
    NEG,
    ABS,
    EXP,
    LOG,
    SQRT,
    CEIL,
    FLOOR,

    // 形状变换
    RESHAPE,
    TRANSPOSE,
    SQUEEZE,
    UNSQUEEZE,

    // 拼接/分割
    CONCAT,
    SPLIT,

    // 归约
    REDUCE_SUM,
    REDUCE_MEAN,
    REDUCE_MAX,
    REDUCE_MIN,

    // 索引/切片
    GATHER,
    STRIDED_SLICE,

    // 广播/填充
    BROADCAST_TO,
    TILE,

    // 类型转换
    CAST,

    // 常数生成
    ARANGE,
    FULL,
    ONES,
    ZEROS,

    // 三角
    TRIL,
    TRIU,

    // 适配算子（由 ShapeAdapter 插入）
    EXPAND_DIMS,
    ;

    companion object {
        /** 单输入算子 */
        val singleInputOps = setOf(
            RELU, SIGMOID, TANH, GELU, SILU,
            ABS, EXP, LOG, SQRT, NEG,
            CEIL, FLOOR,
            SOFTMAX,
            RESHAPE, SQUEEZE, UNSQUEEZE,
            REDUCE_SUM, REDUCE_MEAN, REDUCE_MAX, REDUCE_MIN,
            CAST, TRIL, TRIU,
            TRANSPOSE, BROADCAST_TO,
            TILE, SPLIT,
            STRIDED_SLICE,
            GATHER,  // GATHER 当作单输入算子（假设 indices 是常量）
        )

        /** 双输入算子 */
        val binaryInputOps = setOf(
            ADD, SUBTRACT, MULTIPLY, DIVIDE,
            MAXIMUM, MINIMUM, POWER,
            CONCAT,
            MATMUL,
        )

        /** 常数生成算子（无输入） */
        val constantOps = setOf(ARANGE, FULL, ONES, ZEROS)

        /** 多输出算子 */
        val multiOutputOps = setOf(SPLIT)

        /** 需要 ndim >= 2 的算子 */
        val needNdimGe2 = setOf(
            TRANSPOSE, TRIL, TRIU, STRIDED_SLICE,
        )

        /** reduce 类算子 */
        val reducingOps = setOf(
            REDUCE_SUM, REDUCE_MEAN, REDUCE_MAX, REDUCE_MIN,
        )

        /** ndim 不变的算子 */
        val ndimStableOps = setOf(
            RELU, SIGMOID, TANH, GELU, SILU,
            NEG, ABS, EXP, LOG, SQRT, CEIL, FLOOR,
            SOFTMAX, CAST,
            SPLIT, CONCAT, TILE,
            TRANSPOSE,
        )

        /** 适配算子（由 ShapeAdapter 插入，不参与逻辑图生成） */
        val adapterOps = setOf(EXPAND_DIMS, SQUEEZE, RESHAPE)
    }
}