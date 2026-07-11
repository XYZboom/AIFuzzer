package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.UirDimKind
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.UirTypeKind
import io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef
import io.github.xyzboom.aiFuzzer.ir.types.UirShape
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDataType
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDim
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildShape
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildTensorType
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * ShapeAdapter 使用示例。
 *
 * 演示如何使用 ShapeAdapter 自动适配输入形状以满足算子约束。
 */
fun main() {
    // 示例 1：MATMUL 形状适配
    log.info { "=== 示例 1：MATMUL 形状适配 ===" }
    demonstrateMatmulAdaptation()
    
    // 示例 2：二元运算广播适配
    log.info { "=== 示例 2：二元运算广播适配 ===" }
    demonstrateBroadcastAdaptation()
    
    // 示例 3：维度不足适配
    log.info { "=== 示例 3：维度不足适配 ===" }
    demonstrateNdimAdaptation()
}

/**
 * 示例 1：MATMUL 形状适配
 * 
 * 场景：输入 A 形状为 [3, 4]，输入 B 形状为 [5, 2]
 * 约束：MATMUL 要求 K 维匹配（A[-1] == B[-2]），即 4 == 5
 * 结果：自动调整输入 B 的 K 维为 4
 */
fun demonstrateMatmulAdaptation() {
    val valueShapes = mutableMapOf<String, UirShape>()
    
    // 创建输入 A: [3, 4]
    val shapeA = buildShape {
        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 4 })
    }
    val refA = buildValueRef {
        valueId = "input_a"
        type = buildTensorType {
            typeKind = UirTypeKind.TENSOR
            shape = shapeA
            // dtype 省略（演示用）
        }
    }
    valueShapes["input_a"] = shapeA
    
    // 创建输入 B: [5, 2]
    val shapeB = buildShape {
        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 5 })
        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 2 })
    }
    val refB = buildValueRef {
        valueId = "input_b"
        type = buildTensorType {
            typeKind = UirTypeKind.TENSOR
            shape = shapeB
        }
    }
    valueShapes["input_b"] = shapeB
    
    log.info { "输入 A: ${shapeA.dims.map { it.value }}" }
    log.info { "输入 B: ${shapeB.dims.map { it.value }}" }
    
    // 适配形状
    val result = ShapeAdapter.adaptInputs(
        op = UirOpKind.MATMUL,
        inputValueRefs = listOf(refA, refB),
        valueShapes = valueShapes,
        valueCounter = 100,
        nodeCounter = 100
    )
    
    log.info { "适配后的输入数量: ${result.adaptedRefs.size}" }
    log.info { "插入的 wrapper 节点数量: ${result.wrapperNodes.size}" }
    
    result.adaptedRefs.forEachIndexed { i, ref ->
        val shape = valueShapes[ref.valueId]!!
        log.info { "  输入 $i: ${ref.valueId} -> ${shape.dims.map { it.value }}" }
    }
    
    if (result.wrapperNodes.isNotEmpty()) {
        log.info { "Wrapper 节点:" }
        result.wrapperNodes.forEach { node ->
            log.info { "  - ${node.name} (${node.op})" }
        }
    }
}

/**
 * 示例 2：二元运算广播适配
 * 
 * 场景：输入 A 形状为 [4, 3, 5]，输入 B 形状为 [4, 2, 5]
 * 约束：广播要求维度要么相等，要么其中一个为 1
 * 结果：将输入 B 的第 1 维调整为 1
 */
fun demonstrateBroadcastAdaptation() {
    val valueShapes = mutableMapOf<String, UirShape>()
    
    // 创建输入 A: [4, 3, 5]
    val shapeA = buildShape {
        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 4 })
        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 5 })
    }
    val refA = buildValueRef {
        valueId = "input_a"
        type = buildTensorType {
            typeKind = UirTypeKind.TENSOR
            shape = shapeA
        }
    }
    valueShapes["input_a"] = shapeA
    
    // 创建输入 B: [4, 2, 5]
    val shapeB = buildShape {
        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 4 })
        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 2 })
        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 5 })
    }
    val refB = buildValueRef {
        valueId = "input_b"
        type = buildTensorType {
            typeKind = UirTypeKind.TENSOR
            shape = shapeB
        }
    }
    valueShapes["input_b"] = shapeB
    
    log.info { "输入 A: ${shapeA.dims.map { it.value }}" }
    log.info { "输入 B: ${shapeB.dims.map { it.value }}" }
    
    // 适配形状
    val result = ShapeAdapter.adaptInputs(
        op = UirOpKind.ADD,
        inputValueRefs = listOf(refA, refB),
        valueShapes = valueShapes,
        valueCounter = 200,
        nodeCounter = 200
    )
    
    log.info { "适配后的输入数量: ${result.adaptedRefs.size}" }
    log.info { "插入的 wrapper 节点数量: ${result.wrapperNodes.size}" }
    
    result.adaptedRefs.forEachIndexed { i, ref ->
        val shape = valueShapes[ref.valueId]!!
        log.info { "  输入 $i: ${ref.valueId} -> ${shape.dims.map { it.value }}" }
    }
}

/**
 * 示例 3：维度不足适配
 * 
 * 场景：输入形状为 [3]（1D）
 * 约束：TRIL 要求至少 2D
 * 结果：在前面插入一个 size=1 的维度，变成 [1, 3]
 */
fun demonstrateNdimAdaptation() {
    val valueShapes = mutableMapOf<String, UirShape>()
    
    // 创建 1D 输入: [3]
    val shape1D = buildShape {
        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
    }
    val ref1D = buildValueRef {
        valueId = "input_1d"
        type = buildTensorType {
            typeKind = UirTypeKind.TENSOR
            shape = shape1D
        }
    }
    valueShapes["input_1d"] = shape1D
    
    log.info { "输入: ${shape1D.dims.map { it.value }} (ndim=${shape1D.dims.size})" }
    
    // 适配形状
    val result = ShapeAdapter.adaptInputs(
        op = UirOpKind.TRIL,
        inputValueRefs = listOf(ref1D),
        valueShapes = valueShapes,
        valueCounter = 300,
        nodeCounter = 300
    )
    
    log.info { "适配后的输入数量: ${result.adaptedRefs.size}" }
    log.info { "插入的 wrapper 节点数量: ${result.wrapperNodes.size}" }
    
    result.adaptedRefs.forEachIndexed { i, ref ->
        val shape = valueShapes[ref.valueId]!!
        log.info { "  输入 $i: ${ref.valueId} -> ${shape.dims.map { it.value }} (ndim=${shape.dims.size})" }
    }
    
    if (result.wrapperNodes.isNotEmpty()) {
        log.info { "Wrapper 节点:" }
        result.wrapperNodes.forEach { node ->
            log.info { "  - ${node.name} (${node.op})" }
        }
    }
}
