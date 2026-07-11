# 形状推导器Bug修复方案

## 发现的关键Bug

### Bug 1: TRIL/TRIU 形状推导错误（最严重）

**位置**: ShapeInferer.kt 第606-621行

**问题**: 计算了 `adaptedShape`（将1D扩展为2D），但返回的是原始的 `inputShape.dims`

**影响**: 导致 TRIU/TRIL 错误，约5-8%的测试失败

**修复**: 第620行，将 `inputShape.dims` 改为 `adaptedShape.dims`

### Bug 2: 常量生成算子返回未知形状

**位置**: 
- 第629-632行：inferArangeShape
- 第640-643行：inferConstantGenShape
- 第651-659行：inferBroadcastToShape

**问题**: 这些函数返回 `[unknown]` 而不是从节点输出读取实际形状

**影响**: 导致后续算子形状推导失败

## 修复代码

### 修复 Bug 1 (TRIL/TRIU)

```kotlin
// 第606-622行，inferTrilTriuShape 函数
private fun inferTrilTriuShape(inputShapes: List<UirShape>, op: UirOpKind): List<UirShape> {
    requireSingleInput(op, inputShapes)
    
    val inputShape = inputShapes[0]
    
    // 如果输入维度不够 2，扩展为 2-D（自动适配）
    val adaptedShape = if (inputShape.dims.size < 2) {
        val missing = 2 - inputShape.dims.size
        val extra = (1..missing).map { constantDim(16) }
        shapeFromDims(extra + inputShape.dims)
    } else {
        inputShape
    }
    
    // 修复：返回适配后的形状，而不是原始形状
    return listOf(shapeFromDims(adaptedShape.dims))
}
```

### 修复 Bug 2 (常量生成)

这些函数应该从节点输出中读取形状。但是，由于这些函数没有访问节点输出的权限，我们需要：
1. 在生成器中设置正确的形状
2. 或者从 attributes 中读取形状

当前生成器已经在正确的位置设置了形状，所以这些函数的问题是它们覆盖了生成器设置的形状。

**临时方案**: 让这些函数返回输入形状（如果有的话），而不是硬编码的未知形状。

### 对编译器和翻译器的影响

修复这些Bug后，需要确保：
1. 编译通过
2. 翻译器正确处理形状
3. 成功率提升

## 预期效果

修复后，预期成功率从 88.4% 提升到 **95%以上**