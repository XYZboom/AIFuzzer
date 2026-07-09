package io.github.xyzboom.aiFuzzer.infer

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ShapeInfererTest {
    
    // ===== 辅助函数 =====
    
    private fun shapeOf(vararg dims: Int): UirShape = buildShape {
        dims.forEach { v ->
            this.dims.add(buildDim {
                dimKind = UirDimKind.CONSTANT
                value = v
            })
        }
    }
    
    private fun assertShapeEquals(expected: UirShape, actual: UirShape) {
        assertEquals(expected.dims.size, actual.dims.size, "ndim mismatch")
        expected.dims.zip(actual.dims).forEachIndexed { idx, (e, a) ->
            assertEquals(e.dimKind, a.dimKind, "dim[$idx] kind mismatch")
            if (e.dimKind == UirDimKind.CONSTANT) {
                assertEquals(e.value, a.value, "dim[$idx] value mismatch")
            }
        }
    }
    
    // ===== 分类 A：形状不变 =====
    
    @Test
    fun `RELU preserves shape`() {
        val inputShape = shapeOf(2, 3, 4)
        val outputShapes = ShapeInferer.inferShape(UirOpKind.RELU, listOf(inputShape), emptyMap())
        
        assertEquals(1, outputShapes.size)
        assertShapeEquals(inputShape, outputShapes[0])
    }
    
    @Test
    fun `SOFTMAX preserves shape`() {
        val inputShape = shapeOf(5, 10)
        val outputShapes = ShapeInferer.inferShape(UirOpKind.SOFTMAX, listOf(inputShape), emptyMap())
        
        assertEquals(1, outputShapes.size)
        assertShapeEquals(inputShape, outputShapes[0])
    }
    
    // ===== 分类 B：广播二元运算 =====
    
    @Test
    fun `ADD broadcasts shapes`() {
        val shape1 = shapeOf(3, 4)  // (3, 4)
        val shape2 = shapeOf(4)      // (4,)
        
        val outputShapes = ShapeInferer.inferShape(UirOpKind.ADD, listOf(shape1, shape2), emptyMap())
        
        assertEquals(1, outputShapes.size)
        assertShapeEquals(shapeOf(3, 4), outputShapes[0])
    }
    
    @Test
    fun `MULTIPLY broadcasts different ndim`() {
        val shape1 = shapeOf(2, 3, 4)  // (2, 3, 4)
        val shape2 = shapeOf(4)          // (4,)
        
        val outputShapes = ShapeInferer.inferShape(UirOpKind.MULTIPLY, listOf(shape1, shape2), emptyMap())
        
        assertEquals(1, outputShapes.size)
        assertShapeEquals(shapeOf(2, 3, 4), outputShapes[0])
    }
    
    // ===== 分类 C：矩阵乘法 =====
    
    @Test
    fun `MATMUL 2D x 2D`() {
        val shape1 = shapeOf(3, 4)  // (M, K)
        val shape2 = shapeOf(4, 5)  // (K, N)
        
        val outputShapes = ShapeInferer.inferShape(UirOpKind.MATMUL, listOf(shape1, shape2), emptyMap())
        
        assertEquals(1, outputShapes.size)
        assertShapeEquals(shapeOf(3, 5), outputShapes[0])  // (M, N)
    }
    
    @Test
    fun `MATMUL requires ndim at_least_2`() {
        val shape1 = shapeOf(4)  // 1-D，非法
        
        assertThrows(ShapeInferer.ShapeInferenceError::class.java) {
            ShapeInferer.inferShape(UirOpKind.MATMUL, listOf(shape1, shapeOf(4, 5)), emptyMap())
        }
    }
    
    // ===== 分类 D：归约运算 =====
    
    @Test
    fun `REDUCE_SUM with keepdims_false`() {
        val inputShape = shapeOf(2, 3, 4)
        val attrs = mapOf("axis" to buildIntAttr { value = 1 }, "keepdims" to buildIntAttr { value = 0 })
        
        val outputShapes = ShapeInferer.inferShape(UirOpKind.REDUCE_SUM, listOf(inputShape), attrs)
        
        assertEquals(1, outputShapes.size)
        assertShapeEquals(shapeOf(2, 4), outputShapes[0])  // 去掉 axis=1
    }
    
    @Test
    fun `REDUCE_MEAN with keepdims_true`() {
        val inputShape = shapeOf(2, 3, 4)
        val attrs = mapOf("axis" to buildIntAttr { value = 2 }, "keepdims" to buildIntAttr { value = 1 })
        
        val outputShapes = ShapeInferer.inferShape(UirOpKind.REDUCE_MEAN, listOf(inputShape), attrs)
        
        assertEquals(1, outputShapes.size)
        assertShapeEquals(shapeOf(2, 3, 1), outputShapes[0])  // axis=2 变为 1
    }
    
    // ===== 分类 G：三角矩阵 =====
    
    @Test
    fun `TRIL preserves shape`() {
        val inputShape = shapeOf(4, 5)
        val outputShapes = ShapeInferer.inferShape(UirOpKind.TRIL, listOf(inputShape), emptyMap())
        
        assertEquals(1, outputShapes.size)
        assertShapeEquals(inputShape, outputShapes[0])
    }
    
    @Test
    fun `TRIU requires ndim_at_least_2`() {
        val inputShape = shapeOf(5)  // 1-D，非法
        
        assertThrows(ShapeInferer.ShapeInferenceError::class.java) {
            ShapeInferer.inferShape(UirOpKind.TRIL, listOf(inputShape), emptyMap())
        }
    }
    
    // ===== 图级别推导 =====
    
    @Test
    fun `inferGraphShapes for simple DAG`() {
        val graph = buildGraph {
            name = "test"
            
            // 输入
            inputs.add(buildValueRef {
                valueId = "v0"
                type = buildTensorType {
                    typeKind = UirTypeKind.TENSOR
                    shape = shapeOf(2, 3)
                    dtype = buildDataType { name = "float32"; bits = 32 }
                }
            })
            
            // 节点：RELU
            nodes.add(buildNode {
                name = "relu_0"
                op = UirOpKind.RELU
                inputs.add(buildValueRef {
                    valueId = "v0"
                    type = buildTensorType {
                        typeKind = UirTypeKind.TENSOR
                        shape = shapeOf(2, 3)
                        dtype = buildDataType { name = "float32"; bits = 32 }
                    }
                })
                outputs.add(buildValueRef {
                    valueId = "v1"
                    type = buildTensorType {
                        typeKind = UirTypeKind.TENSOR
                        shape = buildShape { }  // 空，待推导
                        dtype = buildDataType { name = "float32"; bits = 32 }
                    }
                })
            })
            
            outputs.add(buildValueRef {
                valueId = "v1"
                type = buildTensorType {
                    typeKind = UirTypeKind.TENSOR
                    shape = buildShape { }
                    dtype = buildDataType { name = "float32"; bits = 32 }
                }
            })
        }
        
        val shapeMap = ShapeInferer.inferGraphShapes(graph)
        
        assertEquals(2, shapeMap.size)
        assertTrue(shapeMap.containsKey("v0"))
        assertTrue(shapeMap.containsKey("v1"))
        
        // v0 形状应为输入形状
        assertShapeEquals(shapeOf(2, 3), shapeMap["v0"]!!)
        
        // v1 形状应与 v0 相同（RELU 保持形状）
        assertShapeEquals(shapeOf(2, 3), shapeMap["v1"]!!)
    }
}