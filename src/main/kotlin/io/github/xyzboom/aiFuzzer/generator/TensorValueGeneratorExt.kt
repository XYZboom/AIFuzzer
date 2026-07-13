package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import kotlin.random.Random

/**
 * 张量值生成器扩展。
 * 
 * 支持：
 * - 非连续张量布局（transpose, permute, narrow, expand）
 * - 零维度张量
 * - 特殊值（NaN, Inf）
 */
object TensorValueGeneratorExt {
    
    /**
     * 生成带有特定布局的张量值引用。
     * 
     * @param baseValue 基础值引用（连续布局）
     * @param layout 目标布局
     * @param layoutConfig 布局配置（可选）
     * @return 带布局信息的值引用
     */
    fun generateWithLayout(
        baseValue: UirValueRef,
        layout: UirTensorLayout,
        layoutConfig: TensorLayoutConfig? = null
    ): Pair<UirValueRef, List<UirNode>> {
        if (layout == UirTensorLayout.CONTIGUOUS) {
            return Pair(baseValue, emptyList())
        }
        
        val wrapperNodes = mutableListOf<UirNode>()
        var currentValue = baseValue
        
        when (layout) {
            UirTensorLayout.TRANSPOSED -> {
                // 生成 transpose 节点
                val inputShape = baseValue.type.shape
                val ndim = inputShape.dims.size
                if (ndim >= 2) {
                    val transposedValue = buildValueRef {
                        valueId = "${baseValue.valueId}_transposed"
                        type = buildTensorType {
                            typeKind = UirTypeKind.TENSOR
                            // 形状由 ShapeInferer 推导
                            shape = baseValue.type.shape
                            dtype = baseValue.type.dtype
                        }
                    }
                    
                    val transposeNode = buildNode {
                        name = "transpose_${baseValue.valueId}"
                        op = UirOpKind.TRANSPOSE
                        inputs.add(baseValue)
                        outputs.add(transposedValue)
                        attributes["axes"] = buildStringAttr { value = "${ndim - 1},${ndim - 2}" }
                    }
                    
                    wrapperNodes.add(transposeNode)
                    currentValue = transposedValue
                }
            }
            
            UirTensorLayout.PERMUTED -> {
                val axes = layoutConfig?.permuteAxes
                if (axes != null) {
                    val permutedValue = buildValueRef {
                        valueId = "${baseValue.valueId}_permuted"
                        type = baseValue.type
                    }

                    val permuteNode = buildNode {
                        name = "permute_${baseValue.valueId}"
                        op = UirOpKind.TRANSPOSE
                        inputs.add(baseValue)
                        outputs.add(permutedValue)
                        attributes["axes"] = buildStringAttr { 
                            value = axes.joinToString(",") 
                        }
                    }
                    
                    wrapperNodes.add(permuteNode)
                    currentValue = permutedValue
                }
            }
            
            else -> { /* 其他布局类型待实现 */ }
        }
        
        return Pair(currentValue, wrapperNodes)
    }
    
    /**
     * 生成随机形状（支持零维度）。
     * 
     * @param minNdim 最小维度数
     * @param maxNdim 最大维度数
     * @param zeroDimProb 零维度概率 (0.0 - 1.0)
     * @return 生成的形状
     */
    fun generateRandomShapeWithZeroDim(
        minNdim: Int,
        maxNdim: Int,
        zeroDimProb: Double = 0.0,
        rand: Random = Random.Default
    ): UirShape {
        val ndim = rand.nextInt(maxOf(2, minNdim), maxOf(2, maxNdim) + 1)
        
        return buildShape {
            repeat(ndim) { dimIndex ->
                val dim = buildDim {
                    dimKind = UirDimKind.CONSTANT
                    
                    // 零维度检查
                    if (rand.nextDouble() < zeroDimProb) {
                        value = 0
                    } else {
                        value = rand.nextInt(1, 129)
                    }
                }
                dims.add(dim)
            }
        }
    }
    
    /**
     * 生成特殊浮点值（NaN, Inf）。
     * 
     * @param prob 特殊值概率
     * @return 生成的值字符串（用于常量）
     */
    fun generateSpecialValue(
        prob: Double = 0.0,
        rand: Random = Random.Default
    ): String? {
        if (rand.nextDouble() >= prob) return null
        
        return when (rand.nextInt(4)) {
            0 -> "float('nan')"
            1 -> "float('inf')"
            2 -> "float('-inf')"
            3 -> "-0.0"
            else -> null
        }
    }
}