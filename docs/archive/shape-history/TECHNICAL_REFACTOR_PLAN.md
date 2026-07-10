# 形状感知生成系统重构方案

## 1. 重构目标

### 1.1 当前问题

- **成功率低**：28%（30 轮测试）
- **失败原因**：100% 为二元运算形状不匹配
- **根本原因**：生成器不保证形状兼容性

### 1.2 目标

- **成功率**：100%
- **代码质量**：生成的 TVM Relax 代码合法且简单
- **可维护性**：代码简洁、逻辑清晰

### 1.3 核心策略

**生成时强制兼容**：在生成形状时，直接生成兼容的形状，避免不匹配

---

## 2. 架构设计

### 2.1 当前架构

```
┌─────────────────┐
│  UirGenerator   │ 统一入口
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ LogicGenerator  │ 生成 DAG 拓扑、算子类型
│  (拓扑生成)     │ 输出：占位形状（无实际值）
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  ShapeAdapter   │ 填充形状（随机生成）
│  (形状填充)     │ 使用 ShapeInferer 推导
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   输出 UIR      │ 形状可能不兼容
└─────────────────┘
```

**问题**：
- `ShapeAdapter` 随机生成形状，不保证兼容性
- `ShapeInferer` 只负责推导，不负责验证
- 无形状一致性检查

### 2.2 目标架构

```
┌─────────────────┐
│  UirGenerator   │ 统一入口（保持不变）
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ LogicGenerator  │ 生成 DAG 拓扑、算子类型（保持不变）
│  (拓扑生成)     │
└────────┬────────┘
         │
         ▼
┌─────────────────────────┐
│ ShapeAwareGenerator     │ 【新增】形状感知生成器
│  (形状生成 + 兼容性保证) │ 
│                         │
│ ├─ generateShapes()     │ 主入口
│ ├─ generateBinaryOpShapes()    │ 二元运算
│ ├─ generateMatmulShapes()      │ Matmul
│ ├─ generateConcatShapes()      │ Concat
│ └─ generateReductionShapes()   │ 归约
└────────┬────────────────┘
         │
         ▼
┌─────────────────┐
│   输出 UIR      │ 形状兼容、合法
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ TVM 编译器      │ 如果有问题会报错（无需我们验证）
└─────────────────┘
```

**改进**：
- **新增 `ShapeAwareGenerator`**：生成时保证兼容性
- **删除验证器**：生成时就保证兼容，TVM 编译器会报错
- **保留 `ShapeAdapter`**：向后兼容，内部调用 `ShapeAwareGenerator`

---

## 3. 文件改动清单

### 3.1 核心文件（必须修改）

#### ✅ 新增文件

| 文件路径 | 职责 | 代码量 |
|---------|------|--------|
| `src/main/kotlin/.../generator/ShapeAwareGenerator.kt` | 形状感知生成器（核心） | ~400 行 |
| `src/main/kotlin/.../generator/ShapeUtils.kt` | 形状工具函数 | ~150 行 |

**总计**：~550 行新增代码

#### 🔄 修改文件

| 文件路径 | 改动类型 | 改动量 |
|---------|---------|--------|
| `src/main/kotlin/.../generator/ShapeAdapter.kt` | 重构：内部调用 `ShapeAwareGenerator` | ~50 行 |
| `src/main/kotlin/.../generator/UirGenerator.kt` | 小改：更新注释 | ~5 行 |
| `src/main/kotlin/.../generator/LogicGenerator.kt` | 保持不变 | 0 行 |

#### 🧪 测试文件（必须新增）

| 文件路径 | 职责 | 代码量 |
|---------|------|--------|
| `src/test/kotlin/.../generator/ShapeAwareGeneratorTest.kt` | 单元测试 | ~200 行 |
| `src/test/kotlin/.../integration/ShapeConsistencyTest.kt` | 集成测试 | ~100 行 |

**总计**：~300 行测试代码

### 3.2 可选文件（后续优化）

| 文件路径 | 改动类型 | 说明 |
|---------|---------|------|
| `src/main/kotlin/.../generator/ShapeConstraints.kt` | 增强 | 添加更多算子约束 |
| `src/main/kotlin/.../infer/ShapeInferer.kt` | 优化 | 简化推导逻辑 |
| `configs/default.yaml` | 配置 | 添加形状生成参数 |

---

## 4. 详细改动说明

### 4.1 新增：ShapeAwareGenerator.kt

**职责**：形状感知生成，保证兼容性

**核心方法**：

```kotlin
class ShapeAwareGenerator(private val config: ShapeGeneratorConfig) {
    
    /**
     * 主入口：为图生成形状。
     */
    fun generateShapes(graph: UirGraph)
    
    /**
     * 为二元运算生成兼容形状。
     * 
     * 策略：生成可广播的形状 B
     */
    private fun generateBinaryOpShapes(
        node: UirNode,
        inputShapes: List<UirShape>,
        shapeMap: MutableMap<String, UirShape>
    ): List<UirShape>
    
    /**
     * 为 Matmul 生成兼容形状。
     * 
     * 策略：强制 K 维匹配
     */
    private fun generateMatmulShapes(
        node: UirNode,
        inputShapes: List<UirShape>,
        shapeMap: MutableMap<String, UirShape>
    ): List<UirShape>
    
    /**
     * 为 Concat 生成兼容形状。
     * 
     * 策略：非拼接轴维度相同
     */
    private fun generateConcatShapes(
        node: UirNode,
        inputShapes: List<UirShape>,
        shapeMap: MutableMap<String, UirShape>
    ): List<UirShape>
    
    /**
     * 生成可广播到 target 的形状。
     */
    fun generateBroadcastableShape(target: UirShape, rand: Random): UirShape
    
    /**
     * 生成 Matmul 兼容的权重形状。
     */
    fun generateMatmulCompatibleShape(inputShape: UirShape, rand: Random): UirShape
}
```

**实现要点**：

1. 按拓扑序遍历节点
2. 根据算子类型生成兼容形状
3. 不兼容时重新生成输入形状
4. 使用 ShapeInferer 推导输出形状

### 4.2 新增：ShapeValidator.kt

**职责**：验证形状一致性

**核心方法**：

```kotlin
object ShapeValidator {
    
    /**
     * 验证图的形状一致性。
     */
    fun validate(graph: UirGraph): ValidationResult
    
    /**
     * 检查二元运算广播兼容性。
     */
    fun checkBroadcastCompatibility(
        shapeA: UirShape,
        shapeB: UirShape
    ): Boolean
    
    /**
     * 检查 Matmul K 维匹配。
     */
    fun checkMatmulConstraint(
        shapeA: UirShape,
        shapeB: UirShape
    ): Boolean
    
    /**
     * 检查 Concat 非拼接轴一致性。
     */
    fun checkConcatConstraint(
        inputShapes: List<UirShape>,
        axis: Int
    ): Boolean
}

data class ValidationResult(
    val isValid: Boolean,
    val violations: List<ShapeViolation>
)

data class ShapeViolation(
    val nodeId: String,
    val op: UirOpKind,
    val violationType: ViolationType,
    val message: String,
    val inputShapes: List<UirShape>
)

enum class ViolationType {
    BROADCAST_MISMATCH,      // 广播不匹配
    MATMUL_K_MISMATCH,       // Matmul K 维不匹配
    CONCAT_AXIS_MISMATCH,    // Concat 非拼接轴不匹配
    DIMENSION_MISMATCH       // 维度数不匹配
}
```

### 4.3 新增：ShapeUtils.kt

**职责**：形状工具函数

**核心方法**：

```kotlin
/**
 * 检查两个形状是否可广播。
 */
fun areBroadcastable(s1: UirShape, s2: UirShape): Boolean

/**
 * 广播两个形状（返回广播后的形状）。
 */
fun broadcastShapes(s1: UirShape, s2: UirShape): UirShape

/**
 * 生成随机形状。
 */
fun generateRandomShape(rand: Random, minNdim: Int, maxNdim: Int): UirShape

/**
 * 推导 Matmul 输出形状。
 */
fun inferMatmulOutputShape(shapeA: UirShape, shapeB: UirShape): UirShape

/**
 * 推导 Concat 输出形状。
 */
fun inferConcatOutputShape(inputShapes: List<UirShape>, axis: Int): UirShape
```

### 4.4 修改：ShapeAdapter.kt

**改动类型**：重构，内部调用 `ShapeAwareGenerator`

**改动前**：

```kotlin
class ShapeAdapter {
    fun adapt(graph: UirGraph, rand: Random) {
        // 1. 为图输入分配初始形状
        for (input in graph.inputs) {
            input.type.shape = generateRandomShape(rand, ...)
        }
        
        // 2. 尝试形状推导
        val shapeMap = ShapeInferer.inferGraphShapes(graph)
        
        // 3. 应用形状
        ...
    }
}
```

**改动后**：

```kotlin
class ShapeAdapter {
    fun adapt(graph: UirGraph, rand: Random) {
        // 委托给 ShapeAwareGenerator
        val generator = ShapeAwareGenerator(
            ShapeGeneratorConfig(
                seed = rand.nextLong(),
                ...
            )
        )
        generator.generateShapes(graph)
        
        // 可选：验证形状一致性
        val result = ShapeValidator.validate(graph)
        if (!result.isValid) {
            throw IllegalStateException("Shape validation failed: ${result.violations}")
        }
    }
}
```

**改动量**：~50 行（简化原有逻辑）

### 4.5 修改：UirGenerator.kt

**改动类型**：更新注释，保持接口不变

**改动内容**：

```kotlin
/**
 * 合法的 UIR 程序生成器。
 * 
 * 三阶段生成：
 * 1. [LogicGenerator] 生成逻辑图（DAG 拓扑、算子类型）
 * 2. [ShapeAwareGenerator] 生成兼容形状（保证形状一致性）
 * 3. [ShapeValidator] 验证形状一致性（可选）
 * 
 * 形状生成单一源头：
 * - 形状生成逻辑只在 ShapeAwareGenerator 中实现
 * - 生成器保证形状兼容性
 * - ShapeAdapter 内部委托给 ShapeAwareGenerator
 */
class UirGenerator(private val config: GeneratorConfig = GeneratorConfig()) {
    // ... 保持不变
}
```

**改动量**：~5 行（仅注释）

### 4.6 保持不变：LogicGenerator.kt

**原因**：拓扑生成逻辑无需改动

**说明**：`LogicGenerator` 只负责生成 DAG 拓扑和算子类型，不涉及形状生成

---

## 5. 数据流变化

### 5.1 改动前

```kotlin
// 1. 生成拓扑
val graph = LogicGenerator().generate()

// 2. 随机填充形状
ShapeAdapter().adapt(graph, rand)

// 3. 形状推导（可能失败）
val shapeMap = ShapeInferer.inferGraphShapes(graph)

// 4. 翻译（可能因形状不匹配失败）
val code = TvmRelaxTranslator().translate(graph)
```

**问题**：步骤 2 和 3 之间无兼容性保证

### 5.2 改动后

```kotlin
// 1. 生成拓扑
val graph = LogicGenerator().generate()

// 2. 生成兼容形状
ShapeAwareGenerator().generateShapes(graph)

// 3. 验证形状一致性（可选）
val result = ShapeValidator.validate(graph)
check(result.isValid) { "Shape validation failed" }

// 4. 翻译（保证成功）
val code = TvmRelaxTranslator().translate(graph)
```

**改进**：步骤 2 保证兼容性，步骤 3 验证，步骤 4 必定成功

---

## 6. 测试策略

### 6.1 单元测试

**测试文件**：`ShapeAwareGeneratorTest.kt`

```kotlin
@Test
fun testBinaryOpShapeGeneration() {
    val generator = ShapeAwareGenerator(config)
    val shapeA = buildShape { dims.add(buildDim { value = 32 }) }
    
    val shapeB = generator.generateBroadcastableShape(shapeA, rand)
    
    assertTrue(areBroadcastable(shapeA, shapeB))
}

@Test
fun testMatmulShapeGeneration() {
    val inputShape = buildShape {
        dims.add(buildDim { value = 32 })
        dims.add(buildDim { value = 64 })
    }
    
    val weightShape = generator.generateMatmulCompatibleShape(inputShape, rand)
    
    assertEquals(inputShape.dims.last(), weightShape.dims[weightShape.dims.size - 2])
}
```

### 6.2 集成测试

**测试文件**：`ShapeConsistencyTest.kt`

```kotlin
@Test
fun testEndToEndSuccessRate() {
    var successCount = 0
    
    repeat(100) { seed ->
        val graph = LogicGenerator(config).generate()
        ShapeAwareGenerator(config).generateShapes(graph)
        
        val result = ShapeValidator.validate(graph)
        if (result.isValid) {
            successCount++
        }
    }
    
    val successRate = successCount / 100.0
    assertEquals(1.0, successRate, 0.0, "Expected 100% success rate")
}
```

### 6.3 回归测试

**测试文件**：`GenerationPipelineTest.kt`（已存在，需更新）

```kotlin
@Test
fun `test 100 graphs with shape validation`() {
    repeat(100) { seed ->
        val program = UirGenerator(GeneratorConfig(seed = seed.toLong())).generate()
        
        for (graph in program.graphs) {
            val result = ShapeValidator.validate(graph)
            assertTrue(result.isValid, "Graph $seed failed: ${result.violations}")
        }
    }
}
```

---

## 7. 实施步骤

### 7.1 阶段 1：核心实现（1 天）

**任务**：

1. 创建 `ShapeUtils.kt`（工具函数）
2. 创建 `ShapeAwareGenerator.kt`（核心生成器）
3. 创建 `ShapeValidator.kt`（验证器）
4. 单元测试

**产出**：
- ✅ 核心功能实现
- ✅ 单元测试通过

### 7.2 阶段 2：集成重构（半天）

**任务**：

1. 重构 `ShapeAdapter.kt`（内部委托）
2. 更新 `UirGenerator.kt`（注释）
3. 集成测试

**产出**：
- ✅ 集成到现有流程
- ✅ 集成测试通过

### 7.3 阶段 3：验证和文档（半天）

**任务**：

1. 回归测试（30 轮测试）
2. 更新 `VIOLATIONS.md`
3. 更新 `README.md`

**产出**：
- ✅ 成功率 100%
- ✅ 文档更新

**总时间**：2 天

---

## 8. 风险与缓解

### 8.1 风险列表

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 形状生成逻辑错误 | 中 | 高 | 充分单元测试、集成测试 |
| 性能下降 | 低 | 中 | 性能测试、优化 |
| 向后兼容性问题 | 低 | 高 | 保留 `ShapeAdapter` 接口 |
| 特殊算子未覆盖 | 中 | 中 | 逐步添加算子支持 |

### 8.2 回退计划

**如果重构失败**：

1. 回退代码到重构前版本
2. 使用原 `ShapeAdapter`（随机生成）
3. 接受较低成功率（28%）

---

## 9. 文件结构总览

### 9.1 新增文件

```
src/main/kotlin/io/github/xyzboom/aiFuzzer/
├── generator/
│   ├── ShapeAwareGenerator.kt     【新增】~400 行
│   ├── ShapeValidator.kt          【新增】~150 行
│   └── ShapeUtils.kt              【新增】~100 行

src/test/kotlin/io/github/xyzboom/aiFuzzer/
├── generator/
│   ├── ShapeAwareGeneratorTest.kt 【新增】~200 行
│   └── ShapeValidatorTest.kt      【新增】~100 行
└── integration/
    └── ShapeConsistencyTest.kt    【新增】~150 行
```

### 9.2 修改文件

```
src/main/kotlin/io/github/xyzboom/aiFuzzer/
├── generator/
│   ├── ShapeAdapter.kt            【修改】~50 行重构
│   ├── UirGenerator.kt            【修改】~5 行注释
│   ├── LogicGenerator.kt          【保持】不变
│   └── ShapeConstraints.kt        【可选】增强
```

### 9.3 文档更新

```
docs/
├── VIOLATIONS.md                  【更新】记录修复进展
├── README.md                      【更新】添加形状生成说明
└── TECHNICAL_REFACTOR_PLAN.md     【新增】本文档
```

---

## 10. 验收标准

### 10.1 功能验收

- [ ] `ShapeAwareGenerator` 实现完成
- [ ] `ShapeValidator` 实现完成
- [ ] `ShapeUtils` 工具函数实现完成
- [ ] `ShapeAdapter` 重构完成

### 10.2 测试验收

- [ ] 单元测试覆盖率 ≥ 80%
- [ ] 集成测试通过（100 个图）
- [ ] 回归测试通过（30 轮）
- [ ] 成功率 = 100%

### 10.3 性能验收

- [ ] 生成 100 个图时间 < 5 秒
- [ ] 形状验证时间 < 100 ms（单图）

### 10.4 文档验收

- [ ] `VIOLATIONS.md` 更新
- [ ] `README.md` 更新
- [ ] 代码注释完整

---

## 11. 后续优化（可选）

### 11.1 算子支持增强

**阶段 4**：支持更多算子的形状约束

**任务**：
- 添加 `Conv2d` 形状约束
- 添加 `Pool2d` 形状约束
- 添加 `Gather` 形状约束

**时间**：1 周

### 11.2 性能优化

**阶段 5**：优化形状生成性能

**任务**：
- 缓存常用形状
- 并行生成形状

**时间**：2 天

### 11.3 显式适配算子（可选）

**阶段 6**：支持显式插入 `broadcast_to`/`expand_dims`

**任务**：
- 实现 `ShapeAdapter.insertBroadcastAdapter()`
- 支持常量权重场景

**时间**：2 天

---

## 12. 总结

### 12.1 核心改动

- **新增 3 个文件**：`ShapeAwareGenerator`、`ShapeValidator`、`ShapeUtils`（~650 行）
- **修改 2 个文件**：`ShapeAdapter`、`UirGenerator`（~55 行）
- **新增 3 个测试文件**：~450 行

### 12.2 预期效果

- **成功率**：从 28% → 100%
- **代码质量**：清晰、可维护
- **测试覆盖**：单元 + 集成 + 回归

### 12.3 实施时间

- **阶段 1**：核心实现（1 天）
- **阶段 2**：集成重构（半天）
- **阶段 3**：验证文档（半天）

**总时间**：2 天

---

**文档版本**：v1.0
**创建时间**：2026-07-10
**作者**：Claw
