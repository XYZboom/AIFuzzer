package io.github.xyzboom.aiFuzzer.infer

import io.github.xyzboom.aiFuzzer.ir.UirDimKind
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDim
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildShape
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 测试 STRIDED_SLICE 形状推导。
 */
class StridedSliceShapeTest {
    
    /**
     * 测试默认切片参数：axes=[0], begin=[0], end=[-1]
     * 对于输入 [89, 37, 12, 44, 67]，输出应为 [88, 37, 12, 44, 67]
     */
    @Test
    fun testDefaultSlice() {
        // 构造输入形状 [89, 37, 12, 44, 67]
        val inputShape = buildShape {
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 89 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 37 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 12 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 44 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 67 })
        }
        
        // 调用形状推导（无属性，使用默认值）
        val outputShapes = ShapeInferer.inferShape(
            UirOpKind.STRIDED_SLICE,
            listOf(inputShape),
            emptyMap()
        )
        
        // 验证输出形状
        assertEquals(1, outputShapes.size)
        val outputShape = outputShapes[0]
        assertEquals(5, outputShape.dims.size)
        
        // 验证各维度值
        assertEquals(88, outputShape.dims[0].value)  // 89 - 1 = 88
        assertEquals(37, outputShape.dims[1].value)
        assertEquals(12, outputShape.dims[2].value)
        assertEquals(44, outputShape.dims[3].value)
        assertEquals(67, outputShape.dims[4].value)
        
        // 验证所有维度都是 CONSTANT
        assertTrue(outputShape.dims.all { it.dimKind == UirDimKind.CONSTANT })
    }
    
    /**
     * 测试 3D 输入的切片：[88, 37, 12] → [87, 37, 12]
     */
    @Test
    fun test3DInput() {
        val inputShape = buildShape {
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 88 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 37 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 12 })
        }
        
        val outputShapes = ShapeInferer.inferShape(
            UirOpKind.STRIDED_SLICE,
            listOf(inputShape),
            emptyMap()
        )
        
        assertEquals(1, outputShapes.size)
        val outputShape = outputShapes[0]
        assertEquals(3, outputShape.dims.size)
        
        assertEquals(87, outputShape.dims[0].value)  // 88 - 1 = 87
        assertEquals(37, outputShape.dims[1].value)
        assertEquals(12, outputShape.dims[2].value)
    }
    
    /**
     * 测试 1D 输入的切片：[106] → [105]
     */
    @Test
    fun test1DInput() {
        val inputShape = buildShape {
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 106 })
        }
        
        val outputShapes = ShapeInferer.inferShape(
            UirOpKind.STRIDED_SLICE,
            listOf(inputShape),
            emptyMap()
        )
        
        assertEquals(1, outputShapes.size)
        val outputShape = outputShapes[0]
        assertEquals(1, outputShape.dims.size)
        
        assertEquals(105, outputShape.dims[0].value)  // 106 - 1 = 105
    }
}