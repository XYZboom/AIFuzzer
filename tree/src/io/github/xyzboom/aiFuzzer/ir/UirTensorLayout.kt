package io.github.xyzboom.aiFuzzer.ir

/**
 * 张量内存布局类型。
 * 
 * 用于生成非连续张量，测试编译器对 stride 的处理。
 * 
 * 参考：PyTorch Issue #189401 (transpose + reduction bug)
 */
enum class UirTensorLayout(
    val isContiguous: Boolean,
    val description: String
) {
    /**
     * 连续内存布局（默认）
     * stride = [d1*d2*...*dn, d2*d3*...*dn, ..., dn-1*dn, 1]
     */
    CONTIGUOUS(true, "Contiguous memory layout"),
    
    /**
     * 转置布局
     * 通过 transpose() 操作产生
     * 例如：shape=[M, N] -> stride=[1, M]
     */
    TRANSPOSED(false, "Transposed layout from transpose()"),
    
    /**
     * 置换布局
     * 通过 permute() 操作产生
     * 例如：shape=[M, N, K] permute(2, 0, 1) -> stride=[K, 1, M*K]
     */
    PERMUTED(false, "Permuted layout from permute()"),
    
    /**
     * 窄切片布局
     * 通过 narrow() 操作产生
     * 例如：narrow(dim=0, start=10, length=5) -> stride 相同但 offset 改变
     */
    NARROWED(false, "Narrowed layout from narrow()"),
    
    /**
     * 扩展布局
     * 通过 expand() 操作产生
     * 例如：shape=[M, 1] expand([M, N]) -> stride=[1, 0] (broadcast)
     */
    EXPANDED(false, "Expanded layout from expand()"),
    
    /**
     * 自定义 stride 布局
     * 任意非连续 stride
     */
    STRIDED(false, "Custom strided layout");
    
    companion object {
        /** 所有非连续布局 */
        val nonContiguousLayouts = entries.filter { !it.isContiguous }
        
        /** 所有布局（用于 fuzzing） */
        val allLayouts = entries.toList()
        
        /**
         * 随机选择一个布局（非连续概率可配置）
         * 
         * @param nonContiguousProb 非连续布局的概率 (0.0 - 1.0)
         */
        fun randomLayout(nonContiguousProb: Double = 0.3): UirTensorLayout {
            if (kotlin.random.Random.nextDouble() < nonContiguousProb) {
                return nonContiguousLayouts.random()
            }
            return CONTIGUOUS
        }
    }
}

/**
 * 张量布局配置
 * 
 * 描述如何从连续张量转换为特定布局
 */
data class TensorLayoutConfig(
    val layout: UirTensorLayout,
    val permuteAxes: List<Int>? = null,     // for PERMUTED
    val narrowDim: Int? = null,              // for NARROWED
    val narrowStart: Int? = null,
    val narrowLength: Int? = null,
    val expandShape: List<Int>? = null       // for EXPANDED
) {
    companion object {
        /**
         * 创建转置布局配置
         */
        fun transposed(): TensorLayoutConfig {
            return TensorLayoutConfig(UirTensorLayout.TRANSPOSED)
        }
        
        /**
         * 创建置换布局配置
         */
        fun permuted(axes: List<Int>): TensorLayoutConfig {
            return TensorLayoutConfig(
                layout = UirTensorLayout.PERMUTED,
                permuteAxes = axes
            )
        }
        
        /**
         * 创建窄切片布局配置
         */
        fun narrowed(dim: Int, start: Int, length: Int): TensorLayoutConfig {
            return TensorLayoutConfig(
                layout = UirTensorLayout.NARROWED,
                narrowDim = dim,
                narrowStart = start,
                narrowLength = length
            )
        }
        
        /**
         * 创建扩展布局配置
         */
        fun expanded(targetShape: List<Int>): TensorLayoutConfig {
            return TensorLayoutConfig(
                layout = UirTensorLayout.EXPANDED,
                expandShape = targetShape
            )
        }
    }
}
