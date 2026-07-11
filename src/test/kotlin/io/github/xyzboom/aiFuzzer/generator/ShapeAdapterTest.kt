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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * ShapeAdapter 单元测试。
 *
 * 测试形状适配的核心功能：
 * - MATMUL K 维匹配
 * - 二元运算广播兼容
 * - 维度不足适配
 * - 无需适配的情况
 */
class ShapeAdapterTest {
    
    /**
     * 测试 MATMUL 形状适配：K 维匹配。
     * 
     * 注意：此测试验证维度不足的情况，而不是 K 维不匹配。
     * K 维匹配需要更复杂的双输入协调逻辑。
     */
    @Test
    fun testMatmulNdimAdaptation() {
        val valueShapes = mutableMapOf<String, UirShape>()
        
        // 输入 A: [3] (1D, 需要扩展到至少 2D)
        val shapeA = buildShape {
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
        }
        val refA = buildValueRef {
            valueId = "input_a"
            type = buildTensorType {
                typeKind = UirTypeKind.TENSOR
                shape = shapeA
                dtype = buildDataType { name = "float32"; bits = 32 }
            }
        }
        valueShapes["input_a"] = shapeA
        
        // 输入 B: [4, 5] (2D)
        val shapeB = buildShape {
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 4 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 5 })
        }
        val refB = buildValueRef {
            valueId = "input_b"
            type = buildTensorType {
                typeKind = UirTypeKind.TENSOR
                shape = shapeB
                dtype = buildDataType { name = "float32"; bits = 32 }
            }
        }
        valueShapes["input_b"] = shapeB
        
        // 适配
        val result = ShapeAdapter.adaptInputs(
            op = UirOpKind.MATMUL,
            inputValueRefs = listOf(refA, refB),
            valueShapes = valueShapes,
            valueCounter = 100,
            nodeCounter = 100
        )
        
        // 验证：应该插入 wrapper 节点（用于扩展输入 A 的维度）
        assertTrue(result.wrapperNodes.isNotEmpty(), "应该插入 wrapper 节点")
        
        // 验证：所有适配后的输入应该至少是 2D
        result.adaptedShapes.forEach { shape ->
            assertTrue(shape.dims.size >= 2, "所有输入应该至少是 2D")
        }
    }
    
    /**
     * 测试二元运算广播适配：维度值不兼容时自动调整。
     */
    @Test
    fun testBroadcastDimensionMismatch() {
        val valueShapes = mutableMapOf<String, UirShape>()
        
        // 输入 A: [4, 3, 5]
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
                dtype = buildDataType { name = "float32"; bits = 32 }
            }
        }
        valueShapes["input_a"] = shapeA
        
        // 输入 B: [4, 2, 5] - 第 1 维不兼容（3 vs 2）
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
                dtype = buildDataType { name = "float32"; bits = 32 }
            }
        }
        valueShapes["input_b"] = shapeB
        
        // 适配
        val result = ShapeAdapter.adaptInputs(
            op = UirOpKind.ADD,
            inputValueRefs = listOf(refA, refB),
            valueShapes = valueShapes,
            valueCounter = 200,
            nodeCounter = 200
        )
        
        // 验证：适配后的形状应该可广播
        assertTrue(
            ShapeConstraints.areBroadcastable(result.adaptedShapes[0], result.adaptedShapes[1]),
            "适配后的形状应该可广播"
        )
    }
    
    /**
     * 测试维度不足适配：自动插入 size=1 的维度。
     */
    @Test
    fun testNdimInsufficient() {
        val valueShapes = mutableMapOf<String, UirShape>()
        
        // 1D 输入: [3]
        val shape1D = buildShape {
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
        }
        val ref1D = buildValueRef {
            valueId = "input_1d"
            type = buildTensorType {
                typeKind = UirTypeKind.TENSOR
                shape = shape1D
                dtype = buildDataType { name = "float32"; bits = 32 }
            }
        }
        valueShapes["input_1d"] = shape1D
        
        // 适配（TRIL 要求 ndim >= 2）
        val result = ShapeAdapter.adaptInputs(
            op = UirOpKind.TRIL,
            inputValueRefs = listOf(ref1D),
            valueShapes = valueShapes,
            valueCounter = 300,
            nodeCounter = 300
        )
        
        // 验证：应该插入 EXPAND_DIMS wrapper
        assertTrue(result.wrapperNodes.isNotEmpty(), "应该插入 wrapper 节点")
        
        // 验证：适配后的 ndim >= 2
        val adaptedNdim = result.adaptedShapes[0].dims.size
        assertTrue(adaptedNdim >= 2, "适配后的 ndim 应该 >= 2")
        
        // 验证：应该满足 TRIL 约束
        assertTrue(
            ShapeConstraints.isApplicable(UirOpKind.TRIL, result.adaptedShapes),
            "适配后的形状应该满足 TRIL 约束"
        )
    }
    
    /**
     * 测试无需适配的情况：输入已经满足约束。
     */
    @Test
    fun testNoAdaptationNeeded() {
        val valueShapes = mutableMapOf<String, UirShape>()
        
        // 输入 A: [2, 3]（满足 MATMUL 的 ndim >= 2）
        val shapeA = buildShape {
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 2 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
        }
        val refA = buildValueRef {
            valueId = "input_a"
            type = buildTensorType {
                typeKind = UirTypeKind.TENSOR
                shape = shapeA
                dtype = buildDataType { name = "float32"; bits = 32 }
            }
        }
        valueShapes["input_a"] = shapeA
        
        // 输入 B: [3, 4]（K 维 = 3，与 A 的 K 维匹配）
        val shapeB = buildShape {
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 4 })
        }
        val refB = buildValueRef {
            valueId = "input_b"
            type = buildTensorType {
                typeKind = UirTypeKind.TENSOR
                shape = shapeB
                dtype = buildDataType { name = "float32"; bits = 32 }
            }
        }
        valueShapes["input_b"] = shapeB
        
        // 适配
        val result = ShapeAdapter.adaptInputs(
            op = UirOpKind.MATMUL,
            inputValueRefs = listOf(refA, refB),
            valueShapes = valueShapes,
            valueCounter = 400,
            nodeCounter = 400
        )
        
        // 验证：不应该插入 wrapper 节点
        assertTrue(result.wrapperNodes.isEmpty(), "不应该插入 wrapper 节点")
        
        // 验证：适配后的引用应该与原始引用相同
        assertEquals(
            result.adaptedRefs.map { it.valueId },
            listOf("input_a", "input_b"),
            "适配后的 valueId 应该与原始相同"
        )
    }
    
    /**
     * 测试广播兼容的情况：输入已经是可广播的。
     */
    @Test
    fun testAlreadyBroadcastable() {
        val valueShapes = mutableMapOf<String, UirShape>()
        
        // 输入 A: [4, 1, 5]
        val shapeA = buildShape {
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 4 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 1 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 5 })
        }
        val refA = buildValueRef {
            valueId = "input_a"
            type = buildTensorType {
                typeKind = UirTypeKind.TENSOR
                shape = shapeA
                dtype = buildDataType { name = "float32"; bits = 32 }
            }
        }
        valueShapes["input_a"] = shapeA
        
        // 输入 B: [4, 3, 5]（已经可广播）
        val shapeB = buildShape {
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 4 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 3 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 5 })
        }
        val refB = buildValueRef {
            valueId = "input_b"
            type = buildTensorType {
                typeKind = UirTypeKind.TENSOR
                shape = shapeB
                dtype = buildDataType { name = "float32"; bits = 32 }
            }
        }
        valueShapes["input_b"] = shapeB
        
        // 适配
        val result = ShapeAdapter.adaptInputs(
            op = UirOpKind.MULTIPLY,
            inputValueRefs = listOf(refA, refB),
            valueShapes = valueShapes,
            valueCounter = 500,
            nodeCounter = 500
        )
        
        // 验证：不应该插入 wrapper 节点
        assertTrue(result.wrapperNodes.isEmpty(), "不应该插入 wrapper 节点（已经可广播）")
    }
}