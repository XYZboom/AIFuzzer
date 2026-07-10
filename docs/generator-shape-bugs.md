# Generator 形状适配 Bug 分析

> 2026-07-10 记录
> 修复详情见末尾「修复状态」章节

## 设计意图

```
① 选 op → ② 选输入值（插 wrapper 转换 shape）→ ③ 推导输出 shape → ④ 记入 valueShapes
```

`valueShapes` 是下游所有形状兼容性检查的锚点，必须准确。

## Bug 1（致命）：`inferOutputShapes` 对形状改变算子返回错误形状

**位置**：`LogicGenerator.kt:332`（原 `inferOutputShapes` 的 `else -> inputShapes`）

**影响**：`valueShapes` 记录错误形状，下游所有形状检查都基于错误数据。

| 算子 | 实际输出形状 | `valueShapes` 原记录为 |
|------|------------|------------------|
| REDUCE_SUM (keepdims=0) | ndim - 1 | ndim (不变) |
| REDUCE_MEAN (keepdims=0) | ndim - 1 | ndim (不变) |
| SQUEEZE | 去掉 size=1 维度 | 不变 |
| UNSQUEEZE | ndim + 1 | 不变 |
| CONCAT (axis=0) | axis 维度变化 | 不变 |
| TRANSPOSE | 维度重排 | 不变 |
| GATHER | 形状变化 | 不变 |
| EXPAND_DIMS | ndim + 1 | 不变 |

**影响链条示例**：
```
Step 1: REDUCE_SUM(v0) → v1（3D→2D）
         └─ valueShapes["v1"] = 错误地记录为 3D

Step 2: ADD(v1, v2)
         └─ areBroadcastable(valueShapes["v1"], valueShapes["v2"])
         └─ 读取到错误的 3D → 兼容性检查结果错误！
```

## Bug 2（致命）：只有二元运算的第二输入有 wrapper 插入

**位置**：`LogicGenerator.kt:202`（原 `selectInputValues` 的二元运算特殊分支）

`selectInputValues()` 中只有一个特殊分支处理二元运算的输入兼容性，其他算子的输入没有任何形状适配。

## Bug 3（中）：`insertConversionNode` 只能增维不能减维

**位置**：`LogicGenerator.kt:420`（原 `insertConversionNode`）

`expand_dims` 只能增加维度数，当上游维度多余预期时无法处理。

## Bug 4（中）：`generateAttributes` 和 `inferOutputShapes` 顺序颠倒

**位置**：`LogicGenerator.kt:154 和 198`

`inferOutputShapes` 在 `generateAttributes` 之前执行，导致形状推导无法读取 `axis`、`keepdims` 等属性。

## Bug 5（低）：重复代码

`LogicGenerator` 中有私有的 `areBroadcastable`、`broadcastShapes`、`valueOrNull`，与 `ShapeConstraints` 中的实现重复。

---

## 修复状态

| Bug | 修复内容 | 状态 |
|-----|---------|------|
| 1 | `inferOutputShapes` 委托给 `ShapeInferer.inferShape()` | ✅ 已完成 |
| 2 | 新增 `adaptInputShapes()` 为所有算子输入做 ndim 适配 | ✅ 已完成 |
| 3 | 新增 `insertReshapeForDimReduce()` 支持减维 | ✅ 已完成 |
| 4 | 调整 `generateAttributes()` 调用顺序到 `inferOutputShapes` 之前 | ✅ 已完成 |
| 5 | 删除私有 `areBroadcastable`/`broadcastShapes`/`valueOrNull` | ✅ 已完成 |

### 修复后关键流程

```
generateNode() {
    ① 选 op
    ② 选输入值（selectInputValues）
       └─ 二元运算：广播兼容性检查 + wrapper
       └─ 其他算子：adaptInputShapes() 确保 ndim 满足约束
    ③ 生成属性（generateAttributes）
    ④ 推导输出形状（ShapeInferer.inferShape）
    ⑤ 创建主节点
}
```

### 结果

- 49 个测试全部通过 ✅
- 所有测试通过验证