# 重新设计方案：生成时保证形状兼容

## 问题根源

当前架构：
```
LogicGenerator（生成拓扑，无形状）
    ↓
ShapeAdapter（填充形状，事后适配）
```

问题：**生成时不考虑形状，导致不兼容的组合**

## 新架构

```
LogicGenerator（生成拓扑 + 生成时保证形状兼容）
    ↓
输出：形状兼容的图
```

## 核心思路

在 `LogicGenerator.selectInputValues` 中：

1. **为每个 ValueRef 生成形状**（不是占位）
2. **选择输入时检查形状兼容性**
3. **如果不兼容，生成一个兼容的形状**

### 实现细节

#### 1. 为图输入生成形状

```kotlin
// 在 generateGraph 中：
graphInputs.forEach { input ->
    input.type.shape = generateRandomShape(rand)
    valueShapes[input.valueId] = input.type.shape
}
```

#### 2. 为节点输出生成形状

```kotlin
// 在 generateNode 中：
// 推导输出形状（基于输入形状）
val outputShape = inferOutputShape(op, inputShapes)
outputValueRefs.forEach { it.type.shape = outputShape }
```

#### 3. 选择兼容的输入

```kotlin
private fun selectInputValues(
    op: UirOpKind,
    numInputs: Int,
    availableValues: List<String>,
    valueShapes: Map<String, UirShape>
): List<String> {
    if (numInputs == 0) return emptyList()
    if (numInputs == 1) return listOf(availableValues.random(rand))
    
    // 对于二元运算：选择兼容的输入
    if (op in binaryOps && numInputs == 2) {
        val input1 = availableValues.random(rand)
        val shape1 = valueShapes[input1]!!
        
        // 生成一个兼容的形状
        val shape2 = generateBroadcastableShape(shape1, rand)
        
        // 选择或生成第二个输入
        val input2 = findOrCreateCompatibleInput(availableValues, shape2, rand)
        
        return listOf(input1, input2)
    }
    
    // 其他情况：随机选择
    return availableValues.shuffled(rand).take(numInputs)
}
```

---

## 实施计划

1. 修改 `LogicGenerator`：添加形状生成逻辑
2. 在 `selectInputValues` 中实现兼容性检查
3. 测试验证

预计时间：1 小时