# ShapeAdapter - 形状适配器

## 概述

`ShapeAdapter` 是 aiFuzzer 项目中的核心组件，负责在生成 UIR 程序时自动适配输入形状以满足算子约束。

## 功能

### 1. 形状约束检查

自动检查输入形状是否满足目标算子的约束条件：

- **最小/最大维度数**：如 MATMUL 要求 ndim ≥ 2
- **输入数量范围**：如 ADD 支持 1-2 个输入
- **形状兼容性**：如 MATMUL 的 K 维匹配，广播规则的维度匹配

### 2. 自动形状适配

当输入形状不满足约束时，自动插入 wrapper 算子进行调整：

- **EXPAND_DIMS**：增加维度（插入 size=1 的维度）
- **RESHAPE**：改变形状（包括增删维度）
- **BROADCAST_TO**：广播维度值

### 3. 任意到任意转换

支持从任意形状 `x, y, ..., z` 调整到任意形状 `m, n, o`，包括：

- 增加维度
- 删除维度
- 改变维度值

## 使用示例

### 基本用法

```kotlin
// 在 UirGenerator.kt 中
val adaptResult = ShapeAdapter.adaptInputs(
    op = op,                          // 目标算子
    inputValueRefs = inputValueRefs,  // 原始输入值引用
    valueShapes = valueShapes,        // 全局形状映射
    valueCounter = valueCounter,      // 值计数器
    nodeCounter = nodeCounter         // 节点计数器
)

// 使用适配后的输入
val adaptedInputRefs = adaptResult.adaptedRefs
val wrapperNodes = adaptResult.wrapperNodes
val adaptedShapes = adaptResult.adaptedShapes

// 将 wrapper 节点添加到转换节点列表
conversionNodes.addAll(wrapperNodes)

// 使用适配后的输入创建主节点
val mainNode = buildNode {
    // ...
    adaptedInputRefs.forEach { ref -> inputs.add(ref) }
    // ...
}
```

### MATMUL 形状适配

**场景**：输入 A 形状为 `[3, 4]`，输入 B 形状为 `[5, 2]`

**约束**：MATMUL 要求 K 维匹配（A[-1] == B[-2]），即 `4 == 5`

**结果**：
- 检测到 K 维不匹配
- 自动插入 RESHAPE 节点将输入 B 的 K 维调整为 4
- 输入 B 变为 `[4, 2]`

### 二元运算广播适配

**场景**：输入 A 形状为 `[4, 3, 5]`，输入 B 形状为 `[4, 2, 5]`

**约束**：广播要求维度要么相等，要么其中一个为 1

**结果**：
- 检测到第 1 维不匹配（`3 != 2`）
- 自动插入 BROADCAST_TO 节点将输入 B 的第 1 维调整为 1
- 输入 B 变为 `[4, 1, 5]`，可广播为 `[4, 3, 5]`

### 维度不足适配

**场景**：输入形状为 `[3]`（1D）

**约束**：TRIL 要求至少 2D

**结果**：
- 检测到 ndim < 2
- 自动插入 EXPAND_DIMS 节点在前面插入 size=1 的维度
- 输入变为 `[1, 3]`

## 设计原则

### 1. 最小化插入

优先选择最少的 wrapper 节点：

- 维度数不足 → 单个 EXPAND_DIMS
- 维度数过多 → 单个 RESHAPE（展平）
- 维度值不匹配 → 单个 BROADCAST_TO

### 2. 语义保持

调整后的形状必须满足算子的语义约束：

- MATMUL：K 维必须匹配
- 广播：必须符合 NumPy 广播规则
- 特殊算子：满足其特定的维度要求

### 3. 可追溯性

所有 wrapper 节点都有明确的命名：

```
expand_dims_{counter}_wrapper
reshape_{counter}_wrapper
broadcast_to_{counter}_wrapper
```

## API 文档

### `ShapeAdapter.adaptInputs()`

主入口函数，对算子的所有输入进行形状适配。

**参数**：
- `op: UirOpKind` - 目标算子
- `inputValueRefs: List<UirValueRef>` - 原始输入值引用列表
- `valueShapes: MutableMap<String, UirShape>` - 全局形状映射
- `valueCounter: Int` - 值计数器（用于生成新 valueId）
- `nodeCounter: Int` - 节点计数器（用于生成节点名）

**返回值**：`AdaptResult`
- `adaptedRefs: List<UirValueRef>` - 适配后的输入值引用列表
- `wrapperNodes: List<UirNode>` - 插入的 wrapper 节点列表
- `adaptedShapes: List<UirShape>` - 适配后的形状列表

### `ShapeAdapter.AdaptResult`

适配结果数据类。

```kotlin
data class AdaptResult(
    val adaptedRefs: List<UirValueRef>,
    val wrapperNodes: List<UirNode>,
    val adaptedShapes: List<UirShape>
)
```

## 与现有代码的集成

### UirGenerator.kt

在 `generateNode()` 函数中，ShapeAdapter 被集成到节点生成流程：

```kotlin
private fun generateNode(...): List<UirNode> {
    // ... 选择算子和输入 ...
    
    // 形状适配：检查输入形状是否满足算子约束，必要时插入 wrapper
    val adaptResult = ShapeAdapter.adaptInputs(
        op, inputValueRefs, valueShapes, valueCounter, nodeCounter
    )
    
    val adaptedInputRefs = adaptResult.adaptedRefs
    conversionNodes.addAll(adaptResult.wrapperNodes)
    
    // 推导输出形状（使用适配后的输入）
    val outputShapes = inferOutputShapes(op, adaptResult.adaptedShapes, attributes)
    
    // 创建主节点（使用适配后的输入）
    val mainNode = buildNode {
        // ...
        adaptedInputRefs.forEach { ref -> inputs.add(ref) }
        // ...
    }
    
    return conversionNodes + mainNode
}
```

### ShapeConstraints.kt

ShapeAdapter 依赖 `ShapeConstraints` 获取算子的形状约束：

```kotlin
val constraint = ShapeConstraints.getConstraint(op)
if (ShapeConstraints.isApplicable(op, inputShapes)) {
    // 已经满足约束，无需适配
}
```

## 扩展性

### 添加新的适配策略

在 `deriveTargetShape()` 函数中添加新的 `when` 分支：

```kotlin
when (op) {
    YOUR_NEW_OP -> {
        // 自定义适配逻辑
    }
    // ...
}
```

### 自定义 Wrapper 生成

扩展 `generateWrapperSequence()` 函数，添加新的 wrapper 类型：

```kotlin
// 步骤 N：自定义 wrapper
if (needCustomWrapper) {
    val (newRef, newNode) = insertCustomWrapper(...)
    wrapperNodes.add(newNode)
    currentRef = newRef
}
```

## 测试

运行示例代码：

```bash
./gradlew run --args="ShapeAdapterDemo"
```

或运行单元测试：

```bash
./gradlew test
```

## 未来改进

1. **智能策略选择**：根据上下文选择最优的适配策略
2. **性能优化**：缓存形状约束检查结果
3. **调试支持**：添加更详细的适配日志和可视化
4. **更多 wrapper 类型**：支持 TRANSPOSE、SQUEEZE 等

## 相关文件

- `ShapeAdapter.kt` - 主要实现
- `ShapeConstraints.kt` - 形状约束定义
- `ShapeInferer.kt` - 形状推导
- `UirGenerator.kt` - 集成点
- `ShapeAdapterDemo.kt` - 使用示例
