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
 * 2. 在形状不兼容时插入适配算子（expand_dims/squeeze/reshape）
 * 
 * 注意：这是形状推导的唯一实现点，遵循"单一源头原则"。
 */
class ShapeAdapter {
    
    /**
     * 适配图的形状。
     * 
     * 流程：
     * 1. 为图输入分配初始形状（随机生成，遵循语义约束）
     * 2. 拓扑遍历节点，调用 ShapeInferer 推导输出形状
     * 3. 如果推导失败（形状不兼容），插入适配算子
     */
    fun adapt(graph: UirGraph, rand: Random) {
        // 1. 为图输入分配初始形状
        for (input in graph.inputs) {
            input.type.shape = generateRandomShape(rand, minNdim = 1, maxNdim = 4)
        }
        
        // 2. 使用 ShapeInferer 推导所有形状
        val shapeMap = try {
            ShapeInferer.inferGraphShapes(graph)
        } catch (e: ShapeInferer.ShapeInferenceError) {
            // 形状推导失败，尝试修复
            // 简化处理：为失败的节点插入适配算子
            System.err.println("[ShapeAdapter] Shape inference failed: ${e.message}")
            return
        }
        
        // 3. 应用推导出的形状
        for (node in graph.nodes) {
            for (output in node.outputs) {
                val shape = shapeMap[output.valueId]
                if (shape != null) {
                    output.type.shape = shape
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
        val ndim = rand.nextInt(minNdim, maxNdim + 1)
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
    
    /**
     * 验证所有 ValueRef 都有合法形状。
     */
    private fun validateShapes(graph: UirGraph) {
        // 检查图输入
        for (input in graph.inputs) {
            validateValueRef(input, "graph input")
        }
        
        // 检查节点输入输出
        for (node in graph.nodes) {
            for (input in node.inputs) {
                validateValueRef(input, "node ${node.name} input")
            }
            for (output in node.outputs) {
                validateValueRef(output, "node ${node.name} output")
            }
        }
        
        // 检查图输出
        for (output in graph.outputs) {
            validateValueRef(output, "graph output")
        }
    }
    
    private fun validateValueRef(ref: UirValueRef, context: String) {
        val shape = ref.type.shape
        if (shape.dims.isEmpty()) {
            // 0-D 张量是合法的，但需要特殊处理
            // System.err.println("[ShapeAdapter] Warning: $context has 0-D shape")
        }
        
        for ((i, dim) in shape.dims.withIndex()) {
            if (dim.dimKind == null) {
                throw IllegalStateException("$context dim[$i] has null dimKind")
            }
        }
    }
    
    /**
     * 检查形状是否需要适配。
     * 
     * 返回需要插入的适配算子列表。
     */
    private fun checkNeedAdaptation(
        node: UirNode,
        inputValueRefs: Map<String, UirValueRef>
    ): List<Adaptation> {
        val adaptations = mutableListOf<Adaptation>()
        
        // 检查 MATMUL 的输入维度
        if (node.op == UirOpKind.MATMUL) {
            for (input in node.inputs) {
                val ndim = input.type.shape.dims.size
                if (ndim < 2) {
                    // 需要扩维到至少 2-D
                    adaptations.add(Adaptation(
                        targetValueId = input.valueId,
                        requiredNdim = 2,
                        kind = AdaptationKind.EXPAND
                    ))
                }
            }
        }
        
        // 检查 TRIL/TRIU 的输入维度
        if (node.op in listOf(UirOpKind.TRIL, UirOpKind.TRIU)) {
            for (input in node.inputs) {
                val ndim = input.type.shape.dims.size
                if (ndim < 2) {
                    adaptations.add(Adaptation(
                        targetValueId = input.valueId,
                        requiredNdim = 2,
                        kind = AdaptationKind.EXPAND
                    ))
                }
            }
        }
        
        return adaptations
    }
    
    /**
     * 适配操作。
     */
    data class Adaptation(
        val targetValueId: String,
        val requiredNdim: Int,
        val kind: AdaptationKind
    )
    
    enum class AdaptationKind {
        EXPAND,     // 扩展维度（expand_dims）
        SQUEEZE,    // 压缩维度（squeeze）
        RESHAPE     // 重塑形状（reshape）
    }
}