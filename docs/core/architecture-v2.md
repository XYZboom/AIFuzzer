# aiFuzzer 架构设计 V2

> **已实现**（2026-07-10）：`UirGenerator` 已合并原 `LogicGenerator` 逻辑，成为单一生成器类。

## 核心问题

V1 架构（两阶段生成）的根本缺陷：
```
LogicGenerator（只管拓扑）→ ShapeAdapter（填充形状）
```

**问题**：LogicGenerator 不知道形状，生成的图在语义上不合法（如 `subtract([89], arange(3))` 形状不兼容）。

## V2 架构：单阶段生成 + 形状传播（已实现）

```
UirGenerator（拓扑 + 形状同步推导）
    ↓
生成语义合法的 UIR
    ↓
TvmRelaxTranslator（翻译）
    ↓
可执行的 TVM 代码
```

**关键设计**：
1. **生成器同时维护拓扑和形状**
2. **每个算子选择时检查形状兼容性**
3. **形状推导在生成时完成，不需要单独的 Adapter**

## 算子约束表

| 算子类别 | 约束 | 示例 |
|---------|------|------|
| 一元算子 | 输出形状 = 输入形状 | relu, sigmoid, tanh |
| 二元广播 | 形状必须可广播 | add([3,4], [4]) ✓, add([3], [4]) ✗ |
| 矩阵乘法 | ndim ≥ 2 | matmul([2,3], [3,4]) ✓ |
| 归约 | 输出至少 1-D | reduce_sum([2,3], axis=0) → [3] |
| arange | 固定 1-D，长度由参数决定 | arange(0, 10) → [10] |
| 常数生成 | 形状由参数决定 | full([3,4], 1.0) → [3,4] |

## 形状兼容性检查

### 广播规则（NumPy 风格）
```kotlin
fun canBroadcast(shape1: UirShape, shape2: UirShape): Boolean {
    // 从右对齐比较
    val dims1 = shape1.dims.reversed()
    val dims2 = shape2.dims.reversed()
    
    for (i in 0 until max(dims1.size, dims2.size)) {
        val d1 = dims1.getOrElse(i) { 1 }
        val d2 = dims2.getOrElse(i) { 1 }
        
        // 不兼容：两个维度都 > 1 且不相等
        if (d1 > 1 && d2 > 1 && d1 != d2) {
            return false
        }
    }
    return true
}
```

### arange 特殊处理
- `arange(0, N)` 生成形状 `[N]`
- 不能直接与任意形状的张量做二元运算
- **解决方案**：禁止 arange 连接到二元算子，或强制广播

## 生成策略

### 1. 输入形状初始化
```kotlin
fun generateInputShapes(numInputs: Int, rand: Random): List<UirShape> {
    // 所有输入使用相同的形状（确保兼容）
    val baseShape = generateRandomShape(rand)
    return List(numInputs) { baseShape }
}
```

### 2. 算子选择策略
```kotlin
fun selectOp(availableValues: List<ValueRef>, rand: Random): UirOpKind {
    // 1. 过滤不兼容的算子
    val compatibleOps = UirOpKind.values().filter { op ->
        isOpCompatible(op, availableValues)
    }
    
    // 2. 从兼容算子中随机选择
    return compatibleOps.random(rand)
}

fun isOpCompatible(op: UirOpKind, availableValues: List<ValueRef>): Boolean {
    return when (op) {
        in UirOpKind.binaryInputOps -> {
            availableValues.size >= 2 &&
            canBroadcast(availableValues[0].shape, availableValues[1].shape)
        }
        in UirOpKind.needNdimGe2 -> {
            availableValues.isNotEmpty() &&
            availableValues[0].shape.dims.size >= 2
        }
        else -> true
    }
}
```

### 3. arange 隔离
```kotlin
// arange 生成的值不参与二元运算
val binaryOpIncompatible = setOf(
    UirOpKind.ARANGE,  // 长度不可预测
    UirOpKind.FULL, ONES, ZEROS  // 形状可能不兼容
)
```

## 实现计划

1. **UirGenerator 重构**：✅ 已完成（2026-07-10）— 合并 LogicGenerator + ShapeAdapter
2. **形状传播**：✅ 已实现 — 生成节点时立即推导输出形状
3. **兼容性检查**：✅ 已实现 — 算子选择时验证形状约束
4. **测试**：✅ 确保 100% 可执行

## 预期效果

- ✅ 生成的 UIR 语义合法
- ✅ TVM 执行不会出现形状错误
- ✅ 无需后期修复