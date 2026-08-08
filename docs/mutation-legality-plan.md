# 变异器合法性保证方案

## 问题

当前变异器（DELETE/INSERT/ATTRIBUTE）改变形状后，依赖 `fixGraphConsistency` 事后修补非法程序（RESHAPE 元素数不匹配、STRIDED_SLICE 越界等）。这违反了"变异时就要要求合法"的原则。

## 核心原则

**合法程序 = 每个节点满足其算子约束。非法程序 = 存在节点不满足约束。**

变异操作改变形状后，如果所有下游节点仍满足约束 → 合法。如果有下游节点不满足约束 → 非法。

## 各算子的合法性约束

| 算子 | 约束 | 能否通过更新属性修复？ |
|------|------|:----:|
| RESHAPE | 输出元素数 = 输入元素数 | ✅ 更新 shape 属性 |
| STRIDED_SLICE | begin/end ≤ 轴长度，axes ≤ ndim | ✅ 更新 begin/end/axes |
| TILE | 输出 ndim = 输入 ndim | ✅ 插入 RESHAPE 展平 (当前做法) |
| BROADCAST_TO | 目标形状与输入广播兼容 | ✅ 重置为恒等广播 |
| CONV2D | 输入通道数 = weight 通道数 | ❌ 无法通过属性更新修复 |
| BATCH_NORM | ndim ∈ {3,4}，特定 shape | ❌ 无法通过属性更新修复 |
| POOL | kernel_size 与 padding 关系 | ✅ 更新 padding |
| REDUCE_* | axis ≤ ndim | ✅ 更新 axis (已有 sanitizeAxisAttrs) |
| SOFTMAX | axis ≤ ndim | ✅ 同上 |
| CONCAT | 所有输入 ndim 相同，除 concat 轴外维度相同 | ❌ |
| MATMUL | 内维度匹配 | ❌ |
| 形状保持算子 (RELU/SILU/TANH/等) | 无形状约束 | ✅ 任何形状都合法 |

## 变异操作设计

### 1. DELETE 变异

**删除节点后，其消费者的输入变为被删节点的第一个输入。如果被删节点改变了形状，消费者的输入形状就变了。**

例：`A → REDUCE(shape改变) → CONV2D` 删除 REDUCE 后 → `A → CONV2D`，CONV2D 的输入形状变了，可能不满足约束。

**DELETE 的合法性判定**：删除节点后，所有受影响的下游节点必须满足约束。如果某个下游节点不满足约束且无法通过属性更新修复 → 该 DELETE 无效。

**增加复杂程度**：DELETE 本身减少节点数，但通过清理冗余节点使图结构更清晰。结合 INSERT 可产生更复杂的图（删除后被 INSERT 补上不同的节点）。

### 2. INSERT 变异

**在节点后插入一个新节点，插入节点的输出替换消费者的输入。**

插入的算子分为两类：
- **形状保持算子**（RELU/SILU/TANH/等）：输出形状 = 输入形状 → 不影响下游，**始终合法**
- **形状改变算子**（REDUCE/ARGMAX/ARGMIN）：输出形状 ≠ 输入形状 → 下游可能不满足约束

**INSERT 的合法性判定**：
- 形状保持算子 → 始终合法 ✅
- 形状改变算子 → 下游所有节点必须满足约束。如果不满足且无法通过属性更新修复 → 该 INSERT 无效

**增加复杂程度**：INSERT 直接增加节点数，是增加复杂度的主要手段。

### 增加复杂程度的关键策略：配对插入

仅靠形状保持算子（RELU/SILU/TANH 等）插入虽然总是合法，但只加 1 个节点，且不改变形状，程序多样性有限。

**策略：插入一对节点——形状改变算子 + RESHAPE 恢复形状**

例：`A → CONV2D` 变成 `A → REDUCE_MEAN(axis=1) → RESHAPE(恢复原形状) → CONV2D`

因为 RESHAPE 恢复了原形状，下游 CONV2D 看到的输入形状不变，**始终合法**。
同时增加了 2 个节点和形状变化，显著增加程序复杂程度。

具体实现：
1. 在 INSERT 中，新增一个模式：以 50% 概率插入**单节点**（形状保持算子），以 50% 概率插入**一对节点**（形状改变算子 + RESHAPE）
2. 插入一对节点时：
   a. 插入形状改变算子（如 REDUCE_MEAN），其输出形状改变
   b. 在它后面插入 RESHAPE，目标形状设为原形状（即 REDUCE 前的形状）
   c. 消费者的输入指向 RESHAPE 的输出，消费者看到的是原形状
3. 这样插入的节点数翻倍（2 个 vs 1 个），且形状变化不影响下游

同理，DELETE 删除一对节点（形状改变算子 + RESHAPE）也始终合法——因为删除后下游看到的形状不变。

### 3. ATTRIBUTE 变异

**修改 axis/keepdims 属性，改变节点的输出形状。下游节点可能因此不满足约束。**

**ATTRIBUTE 的合法性判定**：修改属性后，所有受影响的下游节点必须满足约束。如果不满足且无法通过属性更新修复 → 该 ATTRIBUTE 无效。

**增加复杂程度**：ATTRIBUTE 不增加节点数，但改变算子行为，增加程序多样性。

## 传播算法 `propagateShapeChange`

每个变异操作调用此函数，验证并保证下游合法：

```
propagateShapeChange(graph, changedValueIds):
  1. 从 changedValueIds 开始，拓扑遍历下游节点
  2. 对每个节点，检查输入形状是否满足约束
  3. 如果满足 → 继续推导输出形状，更新 valueShapes，传播到下游
  4. 如果不满足：
     a. 如果是 RESHAPE → 更新 shape 属性 → 重新推导输出形状 → 继续传播
     b. 如果是 STRIDED_SLICE → 更新 begin/end/axes → 重新推导 → 传播
     c. 如果是 TILE → 插入 RESHAPE 展平（当前行为） → 传播
     d. 如果是 BROADCAST_TO → 重置为恒等广播 → 传播
     e. 如果是 POOL → 更新 padding → 传播
     f. 如果是 REDUCE_*/SOFTMAX → 更新 axis → 传播
     g. 如果是 CONV2D/BATCH_NORM/CONCAT/MATMUL 等 → 无法修复 → 返回 false
  5. 所有节点都合法 → 返回 true
```

**关键区别**：这不是事后修复，而是变异操作的**合法性验证**。如果 `propagateShapeChange` 返回 false，该变异操作不执行（跳过）。

## 实现计划

1. 新增 `propagateShapeChange(graph, changedValueIds): Boolean`
2. 从 DELETE/INSERT/ATTRIBUTE 中移除 `fixGraphConsistency` 调用
3. 在每个变异操作中调用 `propagateShapeChange`
4. 如果返回 false，变异操作不执行（return）
5. 保留 `fixGraphConsistency` 但不再被变异操作调用（仅用于生成器？实际可移除）
6. 保留 `sanitizeReshapeShapeAttr`、`sanitizeStridedSliceAttrs` 等辅助函数，供 `propagateShapeChange` 使用