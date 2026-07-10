# 形状适配器重新设计

## 1. 核心思路

**在生成阶段，实时检查形状兼容性，不兼容就插入适配算子**

### 关键认知

1. **不重新生成形状**：保持已生成的形状
2. **显式插入适配**：使用 `broadcast_to`、`expand_dims` 等算子
3. **实时处理**：每个节点生成后立即检查和适配

## 2. 架构设计

### 2.1 三阶段流程

```
阶段 1：LogicGenerator（拓扑生成）
  ↓ 输出：DAG 拓扑 + 占位形状

阶段 2：ShapeGenerator（形状生成）
  ↓ 输出：具体形状（可能不兼容）

阶段 3：ShapeAdapter（形状适配）
  ↓ 检查每个节点，不兼容时插入适配算子
  ↓ 输出：兼容的图
```

### 2.2 关键区别

| 之前的方案 | 新方案 |
|-----------|--------|
| 重新生成形状 | 保持形状不变 |
| 保证生成时兼容 | 生成后插入适配算子 |
| 复杂的形状推导 | 简单的显式适配 |

## 3. 实现方案

### 3.1 ShapeAdapter 核心逻辑

```kotlin
class ShapeAdapter {
    
    fun adapt(graph: UirGraph) {
        // 1. 为图输入生成随机形状
        assignRandomShapes(graph.inputs)
        
        // 2. 按拓扑序处理每个节点
        for (node in graph.nodes) {
            // 推导输出形状（使用 ShapeInferer）
            inferOutputShapes(node)
            
            // 检查输入形状是否兼容
            for ((input, expectedShape) in node.inputs.zip(getExpectedInputShapes(node))) {
                val actualShape = input.type.shape
                
                if (!isCompatible(actualShape, expectedShape)) {
                    // 不兼容：插入适配算子
                    insertAdapter(graph, node, input, expectedShape)
                }
            }
        }
    }
    
    /**
     * 插入适配算子。
     * 
     * 在 input 和 node 之间插入适配节点。
     */
    private fun insertAdapter(
        graph: UirGraph,
        node: UirNode,
        input: UirValueRef,
        targetShape: UirShape
    ) {
        val currentShape = input.type.shape
        
        // 策略：
        // 1. 如果维度数不同 → 插入 expand_dims
        // 2. 如果维度值不同 → 插入 broadcast_to
        
        val adapterNodes = mutableListOf<UirNode>()
        
        // 步骤 1：对齐维度数
        if (currentShape.dims.size < targetShape.dims.size) {
            val expandNode = createExpandDimsNode(
                input,
                targetShape.dims.size - currentShape.dims.size
            )
            adapterNodes.add(expandNode)
        }
        
        // 步骤 2：对齐维度值
        val expandedShape = adapterNodes.lastOrNull()?.outputs?.first()?.type?.shape ?: currentShape
        if (!areBroadcastable(expandedShape, targetShape)) {
            val broadcastNode = createBroadcastToNode(
                adapterNodes.lastOrNull()?.outputs?.first() ?: input,
                targetShape
            )
            adapterNodes.add(broadcastNode)
        }
        
        // 插入适配节点到图中
        adapterNodes.forEach { adapter ->
            graph.nodes.add(graph.nodes.indexOf(node), adapter)
        }
        
        // 更新 node 的输入
        if (adapterNodes.isNotEmpty()) {
            node.inputs.find { it.valueId == input.valueId }?.let {
                it.type.shape = adapterNodes.last().outputs.first().type.shape
            }
        }
    }
}
```

### 3.2 创建适配算子节点

```kotlin
/**
 * 创建 expand_dims 节点。
 */
private fun createExpandDimsNode(
    input: UirValueRef,
    numDims: Int
): UirNode {
    val outputShape = buildShape {
        // 在前面插入 numDims 个维度（值为 1）
        repeat(numDims) {
            dims.add(buildDim {
                dimKind = UirDimKind.CONSTANT
                value = 1
            })
        }
        // 添加原有维度
        input.type.shape.dims.forEach { dim ->
            dims.add(dim)
        }
    }
    
    return buildNode {
        name = "expand_dims_${input.valueId}"
        op = UirOpKind.EXPAND_DIMS
        inputs.add(input)
        outputs.add(buildValueRef {
            valueId = "${input.valueId}_expanded"
            type = buildTensorType {
                shape = outputShape
                dtype = input.type.dtype
            }
        })
        attributes["num_dims"] = buildIntAttr { value = numDims }
    }
}

/**
 * 创建 broadcast_to 节点。
 */
private fun createBroadcastToNode(
    input: UirValueRef,
    targetShape: UirShape
): UirNode {
    return buildNode {
        name = "broadcast_to_${input.valueId}"
        op = UirOpKind.BROADCAST_TO
        inputs.add(input)
        outputs.add(buildValueRef {
            valueId = "${input.valueId}_broadcasted"
            type = buildTensorType {
                shape = targetShape
                dtype = input.type.dtype
            }
        })
        attributes["shape"] = buildShapeAttr { value = targetShape }
    }
}
```

### 3.3 兼容性检查

```kotlin
/**
 * 检查形状是否兼容（可广播）。
 */
private fun isCompatible(actual: UirShape, expected: UirShape): Boolean {
    return areBroadcastable(actual, expected)
}

/**
 * 获取节点期望的输入形状（使用 ShapeInferer 推导）。
 */
private fun getExpectedInputShapes(node: UirNode): List<UirShape> {
    // 使用 ShapeInferer 推导节点需要的输入形状
    return ShapeInferer.inferExpectedInputShapes(node)
}
```

## 4. 优势

### 4.1 简单直接

- 不重新生成形状
- 不需要复杂的约束求解
- 显式插入算子，清晰可见

### 4.2 可测试性强

- 每个适配算子都是显式的
- 可以检查插入的节点
- TVM 编译器会验证正确性

### 4.3 符合直觉

- 形状不匹配 → 插入适配算子
- 就像写代码时显式调用 `broadcast_to`

## 5. 实施步骤

### 阶段 1：实现 ShapeAdapter（核心）

**时间**：2-3 小时

1. 实现 `insertAdapter` 逻辑
2. 实现 `createExpandDimsNode`、`createBroadcastToNode`
3. 使用 `ShapeInferer` 推导期望形状

### 阶段 2：测试验证

**时间**：1 小时

1. 运行 `./gradlew run` 测试成功率
2. 检查生成的图是否正确插入适配算子
3. 验证 TVM 编译通过

### 阶段 3：优化和扩展

**时间**：1 小时

1. 优化适配策略（选择最优适配方式）
2. 支持更多算子（Matmul K 维适配、Concat 轴适配）
3. 更新文档

## 6. 示例

### 示例 1：二元运算广播适配

**输入**：
```
v_0: [42, 57, 88]
v_3: [98, 122]
subtract(v_0, v_3)
```

**适配后**：
```
v_0: [42, 57, 88]
v_3: [98, 122]
v_3_expanded: [1, 98, 122]  ← expand_dims(v_3, 1)
v_3_broadcasted: [42, 98, 122]  ← broadcast_to(v_3_expanded, [42, 98, 122])
subtract(v_0, v_3_broadcasted)  ← 使用适配后的输入
```

**生成的 Python 代码**：
```python
v_0 = ...  # shape: [42, 57, 88]
v_3 = ...  # shape: [98, 122]
v_3_expanded = relax.expand_dims(v_3, axis=0)  # shape: [1, 98, 122]
v_3_broadcasted = relax.broadcast_to(v_3_expanded, shape=[42, 98, 122])
result = relax.subtract(v_0, v_3_broadcasted)
```

### 示例 2：Matmul K 维适配

**输入**：
```
A: [32, 64]
B: [128, 10]
matmul(A, B)  # K 维不匹配：64 ≠ 128
```

**适配后**：
```
A: [32, 64]
B: [128, 10]
A_reshaped: [32, 128]  ← reshape(A, [32, 128])
matmul(A_reshaped, B)
```

**注意**：Matmul 的 K 维适配可能破坏语义，需要谨慎处理。

## 7. 风险与缓解

### 7.1 风险：适配算子破坏语义

**问题**：`broadcast_to`、`reshape` 可能改变数据语义

**缓解**：
- 仅在必要时插入适配算子
- TVM 编译器会验证正确性
- 对于 Matmul 等关键算子，可能需要特殊处理

### 7.2 风险：性能开销

**问题**：插入大量适配算子可能影响性能

**缓解**：
- 这是 fuzzing 工具，性能不是首要目标
- 可以添加优化 pass 去除冗余适配

### 7.3 风险：ShapeInferer 推导错误

**问题**：如果 ShapeInferer 推导错误，会插入错误的适配

**缓解**：
- ShapeInferer 已经过测试，相对可靠
- 可以添加验证逻辑检查推导结果

## 8. 与之前方案对比

| 维度 | 之前方案 | 新方案 |
|------|---------|--------|
| **复杂度** | 高（形状生成 + 兼容保证） | 低（显式插入适配） |
| **可靠性** | 低（有 bug） | 高（逻辑简单） |
| **可维护性** | 低（复杂推导） | 高（直观易懂） |
| **成功率** | 38%（不完整） | 预期 100% |
| **代码量** | ~500 行 | ~300 行 |

## 9. 下一步行动

**我需要你确认这个方案，然后我会：**

1. 实现核心的 `ShapeAdapter.adapt()` 逻辑
2. 实现适配算子创建函数
3. 运行测试验证成功率

**预计时间**：3-4 小时

---

**文档版本**：v2.0（重新设计）
**创建时间**：2026-07-10 12:24
**作者**：Claw