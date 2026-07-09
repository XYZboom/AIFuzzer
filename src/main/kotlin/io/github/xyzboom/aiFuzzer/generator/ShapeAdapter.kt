package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.infer.ShapeInferer
import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import kotlin.random.Random

/**
 * 形状适配器。
 * 
 * 职责：
 * 1. 使用 ShapeInferer 推导每个 ValueRef 的形状
 * 2. 在形状推导失败时，尝试自动适配（增加维度）
 * 
 * 注意：这是形状推导的唯一实现点，遵循"单一源头原则"。
 */
class ShapeAdapter {
    
    /**
     * 适配图的形状。
     * 
     * 流程：
     * 1. 为图输入分配初始形状（随机生成）
     * 2. 尝试使用 ShapeInferer 推导形状
     * 3. 如果失败，增加输入维度后重试
     * 4. 将推导结果填充到每个 ValueRef
     */
    fun adapt(graph: UirGraph, rand: Random) {
        // 检查图中是否有需要高维输入的算子
        val needHighDim = graph.nodes.any { it.op in UirOpKind.needNdimGe2 }
        val hasMatmul = graph.nodes.any { it.op == UirOpKind.MATMUL }
        
        // 确定最小 ndim
        val minNdim = when {
            hasMatmul -> 2
            needHighDim -> 2
            else -> 1
        }
        
        // 1. 为图输入分配初始形状
        // 如果有多个输入，确保它们在最后几维上兼容（便于广播）
        val targetNdim = if (graph.inputs.size > 1) rand.nextInt(2, 4) else rand.nextInt(minNdim, 4)
        
        for ((i, input) in graph.inputs.withIndex()) {
            input.type.shape = generateRandomShape(rand, minNdim = minNdim, maxNdim = targetNdim.coerceAtLeast(minNdim))
        }
        
        // 2. 尝试形状推导，如果失败则增加维度重试
        var attempt = 0
        val maxAttempts = 5
        
        while (attempt < maxAttempts) {
            try {
                val shapeMap = ShapeInferer.inferGraphShapes(graph)
                
                // 3. 应用推导出的形状到每个输出
                for (node in graph.nodes) {
                    for (output in node.outputs) {
                        val shape = shapeMap[output.valueId]
                            ?: throw IllegalStateException("Shape not found for output ${output.valueId} in node ${node.name}")
                        output.type.shape = shape
                    }
                }
                
                // 4. 更新图输出的形状
                for (output in graph.outputs) {
                    val shape = shapeMap[output.valueId]
                        ?: throw IllegalStateException("Shape not found for graph output ${output.valueId}")
                    output.type.shape = shape
                }
                
                // 成功，退出重试循环
                return
            } catch (e: ShapeInferer.ShapeInferenceError) {
                attempt++
                if (attempt >= maxAttempts) {
                    throw IllegalStateException("Shape inference failed after $maxAttempts attempts: ${e.message}", e)
                }
                
                // 增加输入维度后重试
                for (input in graph.inputs) {
                    val currentNdim = input.type.shape.dims.size
                    if (currentNdim < 4) {
                        input.type.shape = generateRandomShape(rand, minNdim = currentNdim + 1, maxNdim = 4)
                    }
                }
            }
        }
    }
    
    /**
     * 生成随机形状。
     * 
     * 注意：不硬编码维度值，使用随机值测试编译器的各种场景。
     */
    private fun generateRandomShape(rand: Random, minNdim: Int, maxNdim: Int): UirShape {
        val actualMinNdim = minNdim.coerceIn(1, maxNdim)
        val ndim = rand.nextInt(actualMinNdim, maxNdim + 1)
        val dims = (0 until ndim).map { i ->
            // 维度值随机，范围 [1, 128]
            // 注意：不使用 0（避免空张量），不硬编码特定值
            val dimValue = rand.nextInt(1, 129)
            buildDim {
                dimKind = UirDimKind.CONSTANT
                value = dimValue
            }
        }
        
        return buildShape { dims.forEach { dim -> this.dims.add(dim) } }
    }
}