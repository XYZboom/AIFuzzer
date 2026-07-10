# aiFuzzer 生成-翻译流程文档

> 本文档完整记录 UIR 生成器 → TVM Relax 翻译器的协作流程，
> 包括 ndim 追踪、算子约束、常见错误模式及修复策略。

---

## 1. 整体流程

```
Generator (UIR)  ── ndimMap ──►  IR JSONL  ──►  Translator (TVM Relax)
      │                                           │
      │  computeOutputNdim()                      │  inferInputType() + emitCall()
      │  isOpCompatibleWithNdims()                │  ndimMap 拓扑收敛
      │  selectCompatibleInputs()                 │  clamp axis
      ▼                                           ▼
  UirNode  ←─  ndim  ──►  visitValue         TVM Python source.py
```

**数据流**：
1. 生成器 `UirGenerator` 随机生成 UIR 子图（DAG），每个节点记录 op、inputs、outputs、attrs
2. 生成器内部维护 `ndimMap: Map<valueId, ndim>`，追踪每个 value 的 ndim
3. IR 序列化为 JSONL（包含 `visitNode` 和 `visitValue` 事件）
4. 翻译器 `TvmRelaxTranslator` 把 UIR 转为 TVM Relax Python 源码
5. 翻译器也维护自己的 `ndimMap`，但**根据拓扑排序第一遍收敛**后再 emit

---

## 2. 生成器（UirGenerator）逻辑

### 2.1 图生成流程

```
1. 生成 graph 输入（1-4 个，各带随机 ndim ∈ [1,4]）
2. 初始化 availableValues 和 ndimMap
3. 初始化 liveTips（分支拓扑追踪）
4. 循环生成 nodeCount（3-12）个节点：
   a. 枚举所有 availableNdims
   b. 用 isOpCompatibleWithNdims 筛选兼容算子
   c. 如果没选到，fallback 到 safeFallbackOps（一元激活/数学）
   d. 用 selectCompatibleInputs 选输入值
   e. 用 computeOutputNdim 算输出 ndim
   f. 用 generateAttributes 随机化参数
   g. 生成输出 valueId，更新 ndimMap
   h. 更新 liveTips（分支拓扑）
   i. 偶尔 fork 新分支
5. 选择 1-3 个输出
```

### 2.2 ndim 追踪（生成器侧）

**`computeOutputNdim(op, inputNdims)`** 的完整规则：

| 算子类别 | 算子 | 输出 ndim | 说明 |
|---------|------|-----------|------|
| 一元激活 | relu/sigmoid/tanh/gelu/silu | N | ndim 不变 |
| 一元数学 | neg/abs/exp/log/sqrt/ceil/floor | N | ndim 不变 |
| 元素级二元 | add/subtract/multiply/divide/maximum/minimum/power | **max(N1, N2)** | broadcasting |
| 矩阵乘法 | matmul | **2** | 固定（翻译器用 full 替换输入） |
| softmax | softmax | N | ndim 不变 |
| reduce 类 | reduce_sum/mean/max/min, max/min | **N - 1** | 单 axis，keepdims=False |
| reshape | reshape | 1 | 展平到 1-D |
| squeeze | squeeze | N - 1 | 去掉 1 维 |
| unsqueeze | unsqueeze | N + 1 | axis=0 扩维 |
| split | split | N | ndim 不变 |
| strided_slice | strided_slice | N - 1 | 沿 axis=0 固定降 1 |
| take | take | N - 1 | indices 为 0-D 标量 |
| tril/triu | tril/triu | **2** | 生成器强制输入 ndim=2 |
| broadcast_to | broadcast_to | **1** | 不建议生成 |
| tile/tile | tile | N | ndim 不变 |
| 常数生成 | arange/full/zeros/ones | 1 | 固定 1-D |
| 其他 | pad/cast 等 | N | ndim 不变 |

**`coerceAtLeast(1)` 保证**：所有算子输出 ndim ≥ 1，避免 0-D。

### 2.3 兼容性约束（isOpCompatibleWithNdims）

| 算子 | 约束条件 | 说明 |
|------|---------|------|
| tril, triu | **必须 ndim = 2** | 生成器精确要求 |
| transpose | ndim ≥ 2 | 需要至少 2-D 转置 |
| conv2d/pool2d | ndim ≥ 2 | 但实际期望 4-D |
| reduce 类 | ndim ≥ 2 | 保证 reduce 后 ndim ≥ 1 |
| softmax/reshape/squeeze/unsqueeze/gather | ndim ≥ 1 | 需要至少 1-D |
| matmul | **2 个 2-D 输入** | 与翻译器固定 2-D 一致 |
| 元素级二元 | **2 个同 ndim 输入** | 同 ndim 可 broadcast |
| concat | **2+ 个同 ndim 输入** | 所有输入 ndim 相等 |
| split | ndim ≥ 1 | 单个输入 |
| strided_slice | ndim ≥ 1 |  |
| broadcast_to | **永远 false** | 翻译器用 full 替代，不建议生成 |
| 常数生成 | 不依赖已有值 | 随时可用 |
| 一元（回退） | 任意 ndim | 兼容所有 ndim |

### 2.4 输入选择（selectCompatibleInputs）

- reduce 类：优先选 ndim ≥ 1 的值
- matmul：优先选 ndim ≥ 2 的值（至少 2 个）
- 元素级二元 / concat：按 ndim 分组，从同 ndim 组选
- tril/triu：精确选 ndim = 2
- 其他单输入：优先 ndim ≥ 1
- 常数生成：不依赖已有值（返回空列表）

### 2.5 分支拓扑

- 维护 `liveTips: Map<branchId, valueId>` 追踪每条活跃分支的末端
- 每次生成节点时，有一定概率（`branchProbability=0.3`）从不同分支取输入
- 分支合并（fork-join）：多分支输入合并到第一个分支
- 偶尔 fork 新分支（50% 的 branchProbability），共享输出值
- 目的是测试分支结构的合法性

### 2.6 参数随机化（generateAttributes）

| 算子 | 生成的参数 |
|------|-----------|
| softmax, split | axis: [0, effectiveNdim) |
| reduce_* | axis: [0, effectiveNdim), keepdims: 0 or 1 |
| reshape | shape: -1（固定） |
| conv2d | kernel_size: 2-5, strides: 1-3, padding: 0-2 |
| pool2d | pool_size: 2-4, strides: 1-3, padding: 0-1 |
| pad | pad_width: 1-2 |
| strided_slice | begin: 0-1, end: -1, strides: 1-2 |
| gather | axis: [0, inputNdim) |
| transpose | axes: -1（reverse） |
| squeeze | axis: [0, inputNdim) |
| unsqueeze | axis: [0, inputNdim+1] |
| tile | reps: 1-3 |
| cast | dtype: float32 |
| arange | start: 0-4, stop: start+5~20 |

---

## 3. 翻译器（TvmRelaxTranslator）逻辑

### 3.1 翻译流程

```
1. 生成 Python 模板（import + build_mod 函数）
2. visitGraph:
   a. 初始化 valueMap 和 ndimMap
   b. 生成 graph inputs 的 Relax.Var 声明（inferInputType）
   c. 拓扑排序第一遍：迭代收敛 ndimMap
   d. 第二遍：按节点顺序 visitNode 实际 emit
   e. 生成 bb.emit_func_output
3. visitNode:
   a. 用 opNameMapping 查 tvmOp
   b. 计算 inputNdims（从收敛后的 ndimMap 读取）
   c. 计算 outputNdim，更新 ndimMap（第二遍用）
   d. 调用 emitCall 生成 BBQ 调用
4. emitCall: 根据 tvmOp 生成对应的 relax.op.xxx 调用
```

### 3.2 inferInputType 与 ndim

- `inferInputType` 读取 UIR value 的 ndim，`coerceIn(1, 4)`，每个 dim 设为 16
  - 例如 ndim=3 → `ShapeExpr([16, 16, 16])`
- 翻译器 `ndimMap` 初始值为 `input.ndim`（UIR 值）
- 第一遍拓扑收敛时，`computeStaticOutputNdim` 按 keepdims/op 语义计算实际 ndim

### 3.3 emitCall 的关键语义

| tvmOp | 生成的 TVM 调用 | 特殊处理 | axis clamp |
|-------|---------------|---------|-----------|
| sum/mean/max/min | `relax.op.sum(..., axis=[$axis], keepdims=...)` | clamp axis | **rawAxis >= ndim → ndim-1** |
| nn.softmax | `relax.op.nn.softmax(..., axis=$axis)` | clamp axis | **rawAxis >= ndim → ndim-1** |
| split | `relax.op.split(..., axis=$axis)` | clamp axis | **rawAxis >= ndim → ndim-1** |
| matmul | 固定 2 个 16x16 full | 替换 input | 无 |
| broadcast_to | `relax.op.full(..., ShapeExpr([16]*ndim))` | 替换为 full | 无 |
| tril/triu | 先 expand_dims → 再三角 | 防御升维 | 无 |
| reshape | `relax.op.reshape(..., ShapeExpr([-1]))` | 展平 | 无 |
| squeeze | `relax.op.squeeze(...)` | 原生 squeeze | 无 |
| strided_slice | `relax.op.strided_slice(axes=[0], ...)` | 固定 axis=0 | 无 |
| take | `relax.op.take(..., zeros([1], int64), axis=0)` | indices 固定 0-D | 无 |
| arange | `relax.op.arange(0, 16)` | 固定 [0,16) | 无 |
| zeros/ones/full | `relax.op.xxx(ShapeExpr([16]*ndim), ...)` | 固定 shape | 无 |
| conv2d/pool2d | `relax.op.nn.xxx(...)` | 传递 attrs | 无 |

### 3.4 ndim 拓扑收敛

核心逻辑：在 `visitGraph` 中，先进行**第一遍收敛**，**不 emit**，只迭代更新 `ndimMap`。

```kotlin
while (changed && iterations < 50) {
    for (node in nodeOrder) {
        if (node.inputs.isEmpty()) {
            // 常数算子：直接设置 ndim
            setConstantOutputNdim(node, data)
        } else {
            // 读输入 ndim，如果还没准备好（null）则跳过
            if (inputNdims.any { it == null }) continue
            // 用 computeStaticOutputNdim 计算输出 ndim
            ndim = computeStaticOutputNdim(op, inputNdims, node)
            updateNdimMap(outputIds, ndim)
        }
    }
}
```

这样确保所有算子 emit 前 `ndimMap` 已收敛（包括 reduce keepdims 导致的 ndim 变化）。

---

## 4. 已知问题与故障模式（当前 still failing: ~51/2000）

### 4.1 axis 越界（~30/51）

**根因**：翻译器 `computeStaticOutputNdim` 会正确处理 reduce keepdims 的 ndim 变化，但**生成器也独立生成 axis**，两者可能不一致。

**详细链**：
1. 生成器 ndim=4，axis=3（合法）
2. 翻译器：输入 4-D，`reduce_mean(axis=3, keepdims=False)` → 输出 3-D
3. 翻译器：`ndimMap[output]=3` ✅（拓扑收敛正确）
4. 翻译器：`sum(output, axis=3)` → clamp: `3 >= 3 → axis=2` ✅
5. **但错误依然存在** → 说明有些算子的 clamp 没被调用或 `inputNdims` 值不对

**排查中的线索**：
- `nn.softmax` 和 `split` 也有 clamp（`rawAxis >= ndim → ndim - 1`）
- 但 `split` 的 axis 在生成器是 `rand.nextInt(effectiveNdim)`，如果 ndim=3 选到 3，`rawAxis=3 >= ndim=3` → `axis=2` ✅
- **可能的漏网之鱼**：`transpose`（permute_dims）没有 clamp。但 `transpose` 的 axis 是 idx range，不会越界？

**TODO**：对 `nn.softmax` 和 `split` 的 clamp 单独加调试日志。

### 4.2 0-D 输入（~20/51）

**根因**：`squeeze`、`take`、`strided_slice` 产生 0-D 输出后，被后续算子使用。

**具体场景**：
- `squeeze(4-D) → 3-D`（正常）
- 但多次 squeeze 或 `strided_slice` 固定降维可能导致 0-D
- 生成器 `computeOutputNdim` 有 `.coerceAtLeast(1)`，但翻译器**写死了 `squeeze` 的 ndim 为 `inputNdim`（不降维）**

**yak shaving 现场**：翻译器中 `squeeze` 的 `outputNdim` 追踪是 `(inputNdims.firstOrNull() ?: 2).coerceIn(1, 4)`，**没降维**。但生成器 `computeOutputNdim` 是 `N-1`。两者不一致。

而 `take` 的翻译器 outputNdim 是 `((inputNdims.firstOrNull() ?: 2) - 1).coerceIn(1, 4)`，**正确降维**。但生成器也在降维。两者一致。

**修复方向**：
- 统一生成器和翻译器的 ndim 追踪（已经做了部分）
- 对 `squeeze` 的翻译器 ndim 降维
- 生成器侧：避免对 0-D 输出的后续使用

### 4.3 翻译器替换算子导致的 ndim 错位

| 算子 | 生成器追踪 ndim | 翻译器实际输出 ndim | 问题 |
|------|---------------|--------------------|------|
| matmul | 输入 ndim 决定输出 | 固定 2-D | ✅ 已修复（生成器固定 ndim=2） |
| broadcast_to | 输入 ndim | 1-D（full 代替） | ✅ 已修复（标记为不建议生成） |
| tril/triu | 输入 ndim | 输入 ndim（先 expand_dims） | ⚠️ 生成器固定 ndim=2，翻译器 expand_dims 可能变成 N+1 |

---

## 5. 修复策略优先级

### First（高收益，低风险）
1. **让翻译器的 `squeeze` ndim 追踪降维**（与生成器一致）
2. **对 `nn.softmax` 和 `split` 的 clamp 加调试**（确认是否真的生效）

### Second
3. **0-D 传播的彻底阻断**：在 `selectCompatibleInputs` 中，对可能产生 0-D 的算子（squeeze/take/strided_slice）检查输出是否 0-D，若是则禁用后续使用
4. **拓扑收敛 debug log**：对比生成器和翻译器的 ndim 序列，确保完全一致

### Third
5. **`tril`/`triu` 的翻译器 expand_dims 问题**：如果生成器保证输入 ndim=2，不要 expand_dims 到 3-D