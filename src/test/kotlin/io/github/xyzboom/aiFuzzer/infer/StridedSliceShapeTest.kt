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
 * 当前实现：[:shape[0]//2]（取前半部分），与 PyTorch 翻译器对齐。
 */
class StridedSliceShapeTest {
    
    /**
     * 测试 5D 输入的切片：[89, 37, 12, 44, 67] → [44, 37, 12, 44, 67]
     * axis=0 的维度值变为 89//2 = 44
     */
    @Test
    fun testDefaultSlice() {
        val inputShape = buildShape {
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 89 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 37 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 12 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 44 })
            dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 67 })
        }
        
        val outputShapes = ShapeInferer.inferShape(
            UirOpKind.STRIDED_SLICE,
            listOf(inputShape),
            emptyMap()
        )
        
        assertEquals(1, outputShapes.size)
        val outputShape = outputShapes[0]
        assertEquals(5, outputShape.dims.size)
        
        assertEquals(44, outputShape.dims[0].value)  // max(1, 89//2) = 44
        assertEquals(37, outputShape.dims[1].value)
        assertEquals(12, outputShape.dims[2].value)
        assertEquals(44, outputShape.dims[3].value)
        assertEquals(67, outputShape.dims[4].value)
        
        assertTrue(outputShape.dims.all { it.dimKind == UirDimKind.CONSTANT })
    }
    
    /**
     * 测试 3D 输入的切片：[88, 37, 12] → [44, 37, 12]
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
        
        assertEquals(44, outputShape.dims[0].value)  // 88 // 2 = 44
        assertEquals(37, outputShape.dims[1].value)
        assertEquals(12, outputShape.dims[2].value)
    }
    
    /**
     * 测试 1D 输入的切片：[106] → [53]
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
        
        assertEquals(53, outputShape.dims[0].value)  // 106 // 2 = 53
    }
}
