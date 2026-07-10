# 形状适配器详细实施方案

## 1. 方案概述

### 1.1 核心策略

**在生成阶段，实时检查形状兼容性，不兼容就插入适配算子**

### 1.2 设计原则

1. **保持形状不变**：不重新生成形状，保持原有的随机形状
2. **显式插入适配**：使用 `expand_dims`、`broadcast_to` 等算子显式适配
3. **实时处理**：每个节点生成后立即检查和适配
4. **简单可靠**：逻辑简单，易于理解和维护

### 1.3 与之前方案对比

| 维度 | 之前方案 | 新方案 |
|------|---------|--------|
| **核心思路** | 重新生成兼容的形状 | 保持形状，插入适配算子 |
| **复杂度** | 高（形状生成 + 兼容保证） | 低（显式插入） |
| **可靠性** | 低（有 bug） | 高（逻辑简单） |
| **成功率** | 38%（不完整） | 预期 100% |
| **代码量** | ~500 行 | ~300 行 |
| **可维护性** | 低（复杂推导） | 高（直观易懂） |

---

## 2. 架构设计

### 2.1 三阶段流程

```
┌─────────────────┐
│ LogicGenerator  │ 阶段 1：生成 DAG 拓扑
│  (拓扑生成)     │ 输出：占位形状
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ ShapeGenerator  │ 阶段 2：生成具体形状
│  (形状生成)     │ 输出：随机形状（可能不兼容）
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  ShapeAdapter   │ 阶段 3：检查兼容性，插入适配
│  (形状适配)     │ 输出：兼容的图
└─────────────────┘
```

### 2.2 ShapeAdapter 核心逻辑

```kotlin
class ShapeAdapter {
    
    /**
     * 适配图的形状。
     * 
     * 流程：
     * 1. 为图输入生成随机形状
     * 2. 按拓扑序处理每个节点
     * 3. 使用 ShapeInferer 推导输出形状
     * 4. 检查输入形状兼容性，不兼容时插入适配算子
     */
    fun adapt(graph: UirGraph, rand: Random) {
        // 1. 为图输入生成随机形状
        for (input in graph.inputs) {
            input.type.shape = generateRandomShape(rand)
        }
        
        // 2. 按拓扑序处理每个节点
        for (node in graph.nodes) {
            // 3. 推导输出形状（使用 ShapeInferer）
            inferOutputShapes(node)
            
            // 4. 检查输入形状兼容性
            adaptNodeInputs(graph, node)
        }
    }
}
```

---

## 3. 详细实现

### 3.1 核心方法：adaptNodeInputs

**职责**：检查节点输入的形状兼容性，不兼容时插入适配算子

```kotlin
/**
 * 适配节点的输入形状。
 */
private fun adaptNodeInputs(graph: UirGraph, node: UirNode) {
    when (node.op) {
        // 二元运算：检查广播兼容性
        in binaryInputOps -> {
            if (node.inputs.size == 2) {
                adaptBinaryOpInputs(graph, node)
            }
        }
        
        // Matmul：检查 K 维匹配
        UirOpKind.MATMUL -> {
            adaptMatmulInputs(graph, node)
        }
        
        // Concat：检查非拼接轴一致性
        UirOpKind.CONCAT -> {
            adaptConcatInputs(graph, node)
        }
        
        // 其他算子：无需特殊处理
        else -> { }
    }
}
```

### 3.2 二元运算适配

**策略**：确保两个输入可广播

```kotlin
/**
 * 适配二元运算的输入形状。
 */
private fun adaptBinaryOpInputs(graph: UirGraph, node: UirNode) {
    val shapeA = node.inputs[0].type.shape
    val shapeB = node.inputs[1].type.shape
    
    // 检查是否可广播
    if (areBroadcastable(shapeA, shapeB)) {
        return  // 已兼容，无需适配
    }
    
    // 不兼容：适配输入 B 到 A
    val adaptedInput = adaptShapeToTarget(
        graph,
        node.inputs[1],
        shapeA
    )
    
    // 替换节点的输入
    node.inputs[1] = adaptedInput
}

/**
 * 适配输入形状到目标形状。
 * 
 * 返回适配后的 ValueRef。
 */
private fun adaptShapeToTarget(
    graph: UirGraph,
    input: UirValueRef,
    targetShape: UirShape
): UirValueRef {
    val currentShape = input.type.shape
    
    // 步骤 1：对齐维度数（expand_dims）
    var adaptedRef = input
    if (currentShape.dims.size < targetShape.dims.size) {
        adaptedRef = insertExpandDims(
            graph,
            adaptedRef,
            targetShape.dims.size - currentShape.dims.size
        )
    }
    
    // 步骤 2：对齐维度值（broadcast_to）
    val adaptedShape = adaptedRef.type.shape
    if (!areBroadcastable(adaptedShape, targetShape)) {
        adaptedRef = insertBroadcastTo(
            graph,
            adaptedRef,
            computeCompatibleTarget(adaptedShape, targetShape)
        )
    }
    
    return adaptedRef
}
```

### 3.3 创建适配算子节点

#### 3.3.1 insertExpandDims

**职责**：插入 expand_dims 节点，增加维度数

```kotlin
/**
 * 插入 expand_dims 节点。
 * 
 * @param graph 图
 * @param input 输入 ValueRef
 * @param numDims 要增加的维度数
 * @return 适配后的 ValueRef
 */
private fun insertExpandDims(
    graph: UirGraph,
    input: UirValueRef,
    numDims: Int
): UirValueRef {
    // 计算输出形状：在前面插入 numDims 个维度（值为 1）
    val outputShape = buildShape {
        // 插入 numDims 个维度
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
    
    // 创建 expand_dims 节点
    val expandNode = buildNode {
        name = "expand_dims_${nodeCounter++}"
        op = UirOpKind.EXPAND_DIMS
        inputs.add(input)
        outputs.add(buildValueRef {
            valueId = "v_${valueCounter++}"
            type = buildTensorType {
                shape = outputShape
                dtype = input.type.dtype
            }
        })
        attributes["num_dims"] = buildIntAttr { value = numDims }
    }
    
    // 插入节点到图中（在依赖它的节点之前）
    insertNodeBefore(graph, expandNode, findDependentNode(graph, input))
    
    return expandNode.outputs[0]
}
```

#### 3.3.2 insertBroadcastTo

**职责**：插入 broadcast_to 节点，对齐维度值

```kotlin
/**
 * 插入 broadcast_to 节点。
 * 
 * @param graph 图
 * @param input 输入 ValueRef
 * @param targetShape 目标形状
 * @return 适配后的 ValueRef
 */
private fun insertBroadcastTo(
    graph: UirGraph,
    input: UirValueRef,
    targetShape: UirShape
): UirValueRef {
    // 创建 broadcast_to 节点
    val broadcastNode = buildNode {
        name = "broadcast_to_${nodeCounter++}"
        op = UirOpKind.BROADCAST_TO
        inputs.add(input)
        outputs.add(buildValueRef {
            valueId = "v_${valueCounter++}"
            type = buildTensorType {
                shape = targetShape
                dtype = input.type.dtype
            }
        })
        attributes["shape"] = buildShapeAttr { value = targetShape }
    }
    
    // 插入节点到图中
    insertNodeBefore(graph, broadcastNode, findDependentNode(graph, input))
    
    return broadcastNode.outputs[0]
}
```

### 3.4 Matmul 适配

**策略**：强制 K 维匹配

```kotlin
/**
 * 适配 Matmul 的输入形状。
 */
private fun adaptMatmulInputs(graph: UirGraph, node: UirNode) {
    require(node.inputs.size == 2) { "Matmul requires 2 inputs" }
    
    val shapeA = node.inputs[0].type.shape
    val shapeB = node.inputs[1].type.shape
    
    // 检查维度数
    if (shapeA.dims.size < 2 || shapeB.dims.size < 2) {
        // 维度不足：需要特殊处理
        // 选项：跳过这个节点，或插入 reshape
        // 暂时跳过（可能需要重新设计）
        return
    }
    
    // 检查 K 维匹配
    val K_A = shapeA.dims.last().valueOrNull()
    val K_B = shapeB.dims[shapeB.dims.size - 2].valueOrNull()
    
    if (K_A != null && K_B != null && K_A != K_B) {
        // K 维不匹配
        // 策略1：调整输入 A 的最后一维（reshape）
        // 策略2：调整输入 B 的倒数第二维（reshape）
        // 
        // 注意：reshape 可能破坏语义
        // 这里选择调整 B（权重通常可以调整）
        
        val newShapeB = buildShape {
            // 批次维度
            shapeB.dims.dropLast(2).forEach { dims.add(it) }
            // K 维（改为匹配 A）
            dims.add(buildDim {
                dimKind = UirDimKind.CONSTANT
                value = K_A
            })
            // N 维
            dims.add(shapeB.dims.last())
        }
        
        val adaptedB = insertReshape(graph, node.inputs[1], newShapeB)
        node.inputs[1] = adaptedB
    }
}
```

### 3.5 Concat 适配

**策略**：确保非拼接轴维度相同

```kotlin
/**
 * 适配 Concat 的输入形状。
 */
private fun adaptConcatInputs(graph: UirGraph, node: UirNode) {
    val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: 0
    
    // 获取第一个输入的非拼接轴形状作为目标
    val targetNonConcatDims = node.inputs[0].type.shape.dims.filterIndexed { i, _ -> i != axis }
    
    // 检查所有输入的非拼接轴是否相同
    for (i in 1 until node.inputs.size) {
        val inputShape = node.inputs[i].type.shape
        val nonConcatDims = inputShape.dims.filterIndexed { j, _ -> j != axis }
        
        if (nonConcatDims != targetNonConcatDims) {
            // 不匹配：调整这个输入
            val newShape = buildShape {
                inputShape.dims.forEachIndexed { j, dim ->
                    if (j == axis) {
                        dims.add(dim)  // 拼接轴保持不变
                    } else {
                        dims.add(targetNonConcatDims[if (j < axis) j else j - 1])  // 非拼接轴改为目标
                    }
                }
            }
            
            val adaptedInput = insertReshape(graph, node.inputs[i], newShape)
            node.inputs[i] = adaptedInput
        }
    }
}
```

---

## 4. 辅助函数

### 4.1 insertNodeBefore

**职责**：在指定节点之前插入新节点

```kotlin
/**
 * 在指定节点之前插入新节点。
 */
private fun insertNodeBefore(graph: UirGraph, newNode: UirNode, targetNode: UirNode) {
    val index = graph.nodes.indexOf(targetNode)
    if (index >= 0) {
        graph.nodes.add(index, newNode)
    } else {
        graph.nodes.add(newNode)  // 如果找不到，添加到最后
    }
}
```

### 4.2 findDependentNode

**职责**：找到依赖某个 ValueRef 的节点

```kotlin
/**
 * 找到依赖某个 ValueRef 的节点。
 */
private fun findDependentNode(graph: UirGraph, valueRef: UirValueRef): UirNode {
    return graph.nodes.find { node ->
        node.inputs.any { it.valueId == valueRef.valueId }
    } ?: throw IllegalStateException("No dependent node found for ${valueRef.valueId}")
}
```

### 4.3 computeCompatibleTarget

**职责**：计算兼容的广播目标形状

```kotlin
/**
 * 计算兼容的广播目标形状。
 * 
 * 策略：保持 currentShape 可广播的最大形状
 */
private fun computeCompatibleTarget(currentShape: UirShape, targetShape: UirShape): UirShape {
    val currentDims = currentShape.dims.reversed()
    val targetDims = targetShape.dims.reversed()
    
    val resultDims = mutableListOf<UirDim>()
    
    for (i in 0 until maxOf(currentDims.size, targetDims.size)) {
        val c = currentDims.getOrNull(i)
        val t = targetDims.getOrNull(i)
        
        val cVal = c?.valueOrNull()
        val tVal = t?.valueOrNull()
        
        val resultVal = when {
            cVal == null && tVal == null -> 1
            cVal == null -> tVal!!
            tVal == null -> cVal
            cVal == 1 -> tVal  // current 可以广播
            tVal == 1 -> cVal  // target 可以广播
            cVal == tVal -> cVal
            else -> tVal  // 不兼容时，强制使用 target（可能导致错误）
        }
        
        resultDims.add(0, buildDim {
            dimKind = UirDimKind.CONSTANT
            value = resultVal
        })
    }
    
    return buildShape { resultDims.forEach { dims.add(it) } }
}
```

---

## 5. 完整代码实现

### 5.1 ShapeAdapter 完整代码

见文件：`src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/ShapeAdapter.kt`

（实现代码将在下一步编写）

---

## 6. 测试策略

### 6.1 单元测试

```kotlin
@Test
fun testBinaryOpAdaptation() {
    val graph = buildTestGraph {
        // v_0: [42, 57, 88]
        // v_3: [98, 122]
        // subtract(v_0, v_3)
    }
    
    ShapeAdapter().adapt(graph, Random(42))
    
    // 验证：应该插入了 expand_dims 和 broadcast_to 节点
    assertTrue(graph.nodes.any { it.op == UirOpKind.EXPAND_DIMS })
    assertTrue(graph.nodes.any { it.op == UirOpKind.BROADCAST_TO })
}
```

### 6.2 集成测试

```kotlin
@Test
fun testSuccessRate() {
    var successCount = 0
    
    repeat(100) { seed ->
        val config = GeneratorConfig(seed = seed.toLong())
        val program = UirGenerator(config).generate()
        
        val translator = TvmRelaxTranslator()
        try {
            val code = translator.translate(program)
            successCount++
        } catch (e: Exception) {
            // 失败
        }
    }
    
    val successRate = successCount / 100.0
    assertTrue(successRate >= 0.95, "Success rate $successRate < 0.95")
}
```

---

## 7. 示例场景

### 示例 1：二元运算广播适配

**输入图**：
```
v_0: [42, 57, 88]
v_3: [98, 122]
subtract(v_0, v_3)
```

**适配后图**：
```
v_0: [42, 57, 88]
v_3: [98, 122]
v_4 = expand_dims(v_3, num_dims=1)  → [1, 98, 122]
v_5 = broadcast_to(v_4, shape=[42, 98, 122])  → [42, 98, 122]
v_6 = subtract(v_0, v_5)  → [42, 57, 88] - [42, 98, 122] = [42, 57, 88] (广播)
```

**生成的 TVM Relax 代码**：
```python
v_0 = relax.Var("v_0", relax.TensorStructInfo([42, 57, 88], "float32"))
v_3 = relax.Var("v_3", relax.TensorStructInfo([98, 122], "float32"))

v_4 = relax.expand_dims(v_3, axis=0)  # shape: [1, 98, 122]
v_5 = relax.broadcast_to(v_4, shape=[42, 98, 122])
v_6 = relax.subtract(v_0, v_5)

return v_6
```

### 示例 2：Matmul K 维适配

**输入图**：
```
A: [32, 64]
B: [128, 10]
matmul(A, B)  # K 维不匹配：64 ≠ 128
```

**适配后图**：
```
A: [32, 64]
B: [128, 10]
B_adapted = reshape(B, shape=[64, 10])  # 强制 K=64
C = matmul(A, B_adapted)  # K 维匹配
```

**注意**：这种适配可能破坏语义（改变了权重的形状）

### 示例 3：Concat 非拼接轴适配

**输入图**：
```
input1: [10, 20, 30]
input2: [15, 25, 30]
concat([input1, input2], axis=0)  # 非拼接轴 dim 1 不匹配：20 ≠ 25
```

**适配后图**：
```
input1: [10, 20, 30]
input2: [15, 25, 30]
input2_adapted = reshape(input2, shape=[15, 20, 30])  # 强制 dim 1 = 20
result = concat([input1, input2_adapted], axis=0)
```

---

## 8. 风险与缓解

### 8.1 风险：适配算子破坏语义

**问题**：`reshape` 可能改变数据的语义

**缓解措施**：
1. **优先使用安全适配**：`expand_dims` 和 `broadcast_to` 是安全的
2. **谨慎使用 reshape**：仅在必要时使用，并添加警告
3. **TVM 编译器验证**：编译器会检查形状合法性
4. **文档说明**：在生成的代码中添加注释说明适配算子的用途

### 8.2 风险：性能开销

**问题**：插入大量适配算子可能影响性能

**缓解措施**：
1. **这是 fuzzing 工具**：性能不是首要目标，正确性更重要
2. **可选优化 pass**：后续可以添加优化 pass 去除冗余适配
3. **记录适配情况**：统计插入了多少适配算子

### 8.3 风险：适配策略不完整

**问题**：某些特殊算子可能需要特殊适配策略

**缓解措施**：
1. **逐步扩展**：先支持常见算子，后续扩展
2. **失败时记录**：记录无法适配的情况
3. **用户反馈**：根据实际测试结果改进策略

---

## 9. 实施计划

### 阶段 1：核心实现（2 小时）

**任务**：
1. 实现 `ShapeAdapter.adapt()` 主流程
2. 实现 `adaptBinaryOpInputs()` 二元运算适配
3. 实现 `insertExpandDims()` 和 `insertBroadcastTo()`
4. 使用 `ShapeInferer.inferGraphShapes()` 推导形状

**产出**：
- 核心适配逻辑
- 二元运算适配支持

### 阶段 2：扩展支持（1 小时）

**任务**：
1. 实现 `adaptMatmulInputs()` Matmul 适配
2. 实现 `adaptConcatInputs()` Concat 适配
3. 实现 `insertReshape()` reshape 节点创建

**产出**：
- Matmul、Concat 适配支持
- reshape 节点支持

### 阶段 3：测试验证（1 小时）

**任务**：
1. 运行 `./gradlew run` 测试成功率
2. 检查生成的图是否正确插入适配算子
3. 验证 TVM 编译通过
4. 更新文档

**产出**：
- 成功率达到预期（95%+）
- 测试报告
- 更新的文档

**总时间**：4 小时

---

## 10. 成功指标

### 10.1 功能指标

- [ ] 成功率从 38% 提升到 95%+
- [ ] 所有 "Shape not found" 错误消失
- [ ] 生成的 TVM Relax 代码可编译

### 10.2 代码指标

- [ ] 核心代码 ~300 行
- [ ] 测试覆盖率 ≥ 70%
- [ ] 代码可读性好，易于维护

### 10.3 文档指标

- [ ] 详细实施方案文档 ✅
- [ ] API 文档完整
- [ ] 示例代码清晰

---

## 11. 下一步行动

**立即开始实施**：

1. 删除旧的 `ShapeAwareGenerator`
2. 重写 `ShapeAdapter`（按本方案）
3. 测试验证

**预计完成时间**：今天下午（4 小时内）

---

**文档版本**：v1.0
**创建时间**：2026-07-10 12:36
**作者**：Claw
**状态**：已确认，开始实施