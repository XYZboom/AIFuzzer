**代码改动量**：~500 行

**预期效果**：成功率从 28% → 100%

### 4.2 阶段2：显式适配算子（可选）

**目标**：支持显式插入 broadcast_to/expand_dims

**适用场景**：
- 需要测试适配算子本身
- 输入形状已固定（如预训练权重）

**改动**：

1. 实现 `insertBroadcastAdapter`、`insertExpandDims` 等函数
2. 在 `ShapeAwareGenerator` 中添加适配逻辑

**代码改动量**：~200 行

---

## 5. 测试策略

### 5.1 单元测试

```kotlin
@Test
fun testBinaryOpShapeGeneration() {
    val rand = Random(42)
    val generator = ShapeAwareGenerator(ShapeGeneratorConfig(seed = 42))
    
    // 测试二元运算
    val shapeA = buildShape {
        dims.add(buildDim { value = 32 })
        dims.add(buildDim { value = 64 })
    }
    
    val shapeB = generator.generateBroadcastableShape(shapeA, rand)
    
    // 验证：shapeB 可广播到 shapeA
    assertTrue(areBroadcastable(shapeA, shapeB))
}

@Test
fun testMatmulShapeGeneration() {
    val rand = Random(42)
    val generator = ShapeAwareGenerator(ShapeGeneratorConfig(seed = 42))
    
    // 测试 Matmul
    val inputShape = buildShape {
        dims.add(buildDim { value = 32 })
        dims.add(buildDim { value = 64 })
    }
    
    val weightShape = generator.generateMatmulCompatibleShape(inputShape, rand)
    
    // 验证：K 维匹配
    assertEquals(inputShape.dims.last(), weightShape.dims[weightShape.dims.size - 2])
}

@Test
fun testConcatShapeGeneration() {
    val generator = ShapeAwareGenerator(ShapeGeneratorConfig(seed = 42))
    
    // 测试 Concat
    val graph = buildTestGraph()
    val node = graph.nodes.find { it.op == UirOpKind.CONCAT }!!
    
    generator.generateShapes(graph)
    
    // 验证：非拼接轴相同
    val axis = node.attributes["axis"]?.intValue ?: 0
    val nonConcatDims = node.inputs.map { it.type.shape.dims.filterIndexed { i, _ -> i != axis } }
    assertTrue(nonConcatDims.all { it == nonConcatDims[0] })
}
```

### 5.2 集成测试

```kotlin
@Test
fun testEndToEndGeneration() {
    val config = LogicGraphConfig(seed = 42, graphCount = 100)
    val logicGen = LogicGenerator(config)
    val shapeGen = ShapeAwareGenerator(ShapeGeneratorConfig(seed = 42))
    
    var successCount = 0
    val violations = mutableListOf<String>()
    
    repeat(100) { i ->
        try {
            val program = logicGen.generate()
            for (graph in program.graphs) {
                shapeGen.generateShapes(graph)
                
                // 验证形状一致性
                val result = ShapeValidator.validate(graph)
                if (result.isValid) {
                    successCount++
                } else {
                    violations.addAll(result.violations.map { it.message })
                }
            }
        } catch (e: Exception) {
            violations.add("Exception: ${e.message}")
        }
    }
    
    val successRate = successCount / 100.0
    println("Success rate: $successRate")
    println("Violations: ${violations.distinct()}")
    
    assertTrue(successRate >= 0.95, "Success rate $successRate < 0.95")
}
```

### 5.3 回归测试

```kotlin
@Test
fun testSuccessRateOverTime() {
    // 运行 30 轮测试（与 VIOLATIONS.md 对应）
    val numTrials = 30
    var successCount = 0
    
    repeat(numTrials) { seed ->
        val config = LogicGraphConfig(seed = seed.toLong())
        val program = LogicGenerator(config).generate()
        val graph = program.graphs.first()
        
        ShapeAwareGenerator(ShapeGeneratorConfig(seed = seed.toLong())).generateShapes(graph)
        
        val result = ShapeValidator.validate(graph)
        if (result.isValid) {
            successCount++
        }
    }
    
    val successRate = successCount.toDouble() / numTrials
    println("Success rate: ${successRate * 100}%")
    
    // 目标：100% 成功率
    assertEquals(1.0, successRate, 0.0, "Expected 100% success rate")
}
```

---

## 6. 与原方案对比

### 6.1 方案对比

| 维度 | 约束求解方案（原方案） | 显式生成方案（新方案） |
|------|---------------------|---------------------|
| **核心思想** | 收集约束 → 求解 | 生成时确保兼容 |
| **实现复杂度** | 高（符号系统、约束图） | 低（直接生成） |
| **代码量** | ~2000 行 | ~500 行 |
| **成功率** | 100% | 100% |
| **性能** | 较慢（约束求解） | 快（直接生成） |
| **可维护性** | 低（抽象层次高） | 高（直观易懂） |
| **生成代码** | 合法但可能复杂 | 合法且简单 |

### 6.2 为什么显式生成更好

**1. 简单直接**

```kotlin
// 约束求解方案
val constraints = collectConstraints(graph)
val solutions = solveConstraints(constraints)
val shapes = instantiateShapes(solutions)

// 显式生成方案
val shape = generateCompatibleShape(existingShape, rand)
```

**2. 不需要修改输入形状**

```python
# 约束求解：修改输入以满足约束
# 问题：可能破坏语义
input: [32, 64]
重新生成：[32, 128]  # 强制匹配权重

# 显式生成：生成兼容的权重
input: [32, 64]  # 保持不变
生成权重：[64, 128]  # 根据 input 生成
```

**3. 更贴近实际使用**

```python
# 实际模型代码
x = conv2d(input)  # 输入形状固定
weight = generateWeight(x.shape)  # 根据输入生成权重
y = matmul(x, weight)
```

**4. 可测试性强**

```kotlin
// 容易测试
val shapeA = [32, 64]
val shapeB = generateBroadcastableShape(shapeA, rand)

// 直接验证
assert(areBroadcastable(shapeA, shapeB))
```

---

## 7. 总结

### 7.1 核心认知

**错误认知**：
- 形状生成 = 约束求解问题
- 需要符号形状系统
- 需要复杂的约束求解器

**正确认知**：
- 形状生成 = 生成时确保兼容
- 不需要约束求解
- 简单直接的显式生成就够了

### 7.2 推荐方案

**主方案**：策略1（生成时强制兼容）

- 实现简单（~500 行）
- 成功率 100%
- 生成的代码清晰

**补充方案**：策略2（显式插入适配算子）

- 可选，用于特殊场景
- 增加代码多样性

**不推荐方案**：策略3（约束求解）

- 过度设计
- 不必要的复杂度

### 7.3 实施步骤

1. **实现 `ShapeAwareGenerator`**（1 天）
   - 核心函数：`generateBroadcastableShape`、`generateMatmulCompatibleShape` 等

2. **集成到现有流程**（半天）
   - 修改 `LogicGenerator`，调用 `ShapeAwareGenerator`

3. **测试验证**（半天）
   - 单元测试、集成测试、回归测试

4. **更新文档**（1 小时）
   - 删除错误的约束求解文档
   - 更新 VIOLATIONS.md

**总时间**：2-3 天

### 7.4 预期效果

- **成功率**：从 28% → 100%
- **代码质量**：生成的 TVM Relax 代码合法且简单
- **维护成本**：低（代码简洁、逻辑清晰）

---

## 8. 附录：关键代码片段

### 8.1 广播检查

```kotlin
/**
 * 检查两个形状是否可广播。
 */
fun areBroadcastable(s1: UirShape, s2: UirShape): Boolean {
    val dims1 = s1.dims.reversed()
    val dims2 = s2.dims.reversed()
    val maxLen = maxOf(dims1.size, dims2.size)
    
    for (i in 0 until maxLen) {
        val v1 = dims1.getOrNull(i)?.valueOrNull()
        val v2 = dims2.getOrNull(i)?.valueOrNull()
        
        if (v1 != null && v2 != null && v1 != v2 && v1 != 1 && v2 != 1) {
            return false
        }
    }
    
    return true
}
```

### 8.2 Matmul 输出形状推导

```kotlin
/**
 * 推导 Matmul 输出形状。
 * 
 * 输入：A[..., M, K], B[..., K, N]
 * 输出：C[..., M, N]
 */
fun inferMatmulOutputShape(shapeA: UirShape, shapeB: UirShape): UirShape {
    val M = shapeA.dims[shapeA.dims.size - 2]
    val N = shapeB.dims.last()
    
    // 批次维度：广播 A 和 B 的批次维度
    val batchA = shapeA.dims.dropLast(2)
    val batchB = shapeB.dims.dropLast(2)
    val batchShape = if (batchA.isEmpty() && batchB.isEmpty()) {
        emptyList()
    } else {
        broadcastShapes(
            buildShape { batchA.forEach { dims.add(it) } },
            buildShape { batchB.forEach { dims.add(it) } }
        ).dims
    }
    
    return buildShape {
        batchShape.forEach { dims.add(it) }
        dims.add(M)
        dims.add(N)
    }
}
```

### 8.3 Concat 输出形状推导

```kotlin
/**
 * 推导 Concat 输出形状。
 * 
 * 输入：多个形状（非拼接轴相同）
 * 输出：拼接轴维度求和
 */
fun inferConcatOutputShape(inputShapes: List<UirShape>, axis: Int): UirShape {
    val ndim = inputShapes[0].dims.size
    
    // 拼接轴维度求和
    val concatDimSum = inputShapes.sumOf { it.dims[axis].valueOrNull() ?: 0 }
    
    return buildShape {
        (0 until ndim).forEach { i ->
            if (i == axis) {
                dims.add(buildDim { value = concatDimSum })
            } else {
                dims.add(inputShapes[0].dims[i])
            }
        }
    }
}
```

---

## 9. 致谢

感谢用户的质疑和讨论，帮助我纠正了错误的认知：

- **原错误认知**：形状生成需要约束求解
- **用户正确认知**：形状可以通过显式操作改变，不需要复杂的约束系统

这次讨论让我意识到：**工程问题应该用工程方案解决，而不是过度抽象化**。

---

**文档版本**：v2.0（修正版）
**最后更新**：2026-07-10
**作者**：Claw（基于用户讨论修正）
