# IR 算子语义文档

> 本文档记录每个 IR 算子在 UIR → TVM Relax 翻译中的语义，
> 包括输入 ndim 约束、输出 ndim 变化、axis 语义、keepdims 行为等。
> 生成器应严格遵循此文档生成合法的 IR 子图。

## 约定

- **ndim**: tensor 的维度数。UIR 中 ndim 范围 1-10，翻译器 clamp 到 1-4。
- **axis**: 在 TVM 中 axis 范围 `[-ndim, ndim)`。
- **keepdims**: 具体见 4. 归约算子。`True` 时输出 ndim = 输入 ndim（该轴 size 变为 1）；`False` 时输出 ndim = 输入 ndim - len(axis)。
- 输入 ndim 用 `N` 表示，输出 ndim 用 `M` 表示。

---

## 1. 元素级一元算子

| IR 算子 | TVM 算子 | 输入 ndim | 输出 ndim | 输出可能 0-D | 说明 |
|---------|----------|-----------|-----------|-------------|------|
| `relu` | `nn.relu` | N ≥ 1 | M = N | 否 | 逐元素激活 |
| `sigmoid` | `sigmoid` | N ≥ 1 | M = N | 否 | 逐元素 |
| `tanh` | `tanh` | N ≥ 1 | M = N | 否 | 逐元素 |
| `gelu` | `nn.gelu` | N ≥ 1 | M = N | 否 | 逐元素 |
| `silu` | `nn.silu` | N ≥ 1 | M = N | 否 | 逐元素 |
| `negative` | `negative` | N ≥ 1 | M = N | 否 | 逐元素取负 |
| `abs` | `abs` | N ≥ 1 | M = N | 否 | 逐元素绝对值 |
| `exp` | `exp` | N ≥ 1 | M = N | 否 | 逐元素 |
| `log` | `log` | N ≥ 1 | M = N | 否 | 逐元素，输入必须 > 0 |
| `sqrt` | `sqrt` | N ≥ 1 | M = N | 否 | 逐元素，输入必须 ≥ 0 |
| `ceil` | `ceil` | N ≥ 1 | M = N | 否 | 逐元素 |
| `floor` | `floor` | N ≥ 1 | M = N | 否 | 逐元素 |
| `astype` | `astype` | N ≥ 0 | M = N | 是 | 类型转换，0-D 也可 |

**约束**: 所有一元激活/数学算子输入 ndim ≥ 1，输出 ndim = 输入 ndim。

---

## 2. 元素级二元算子

| IR 算子 | TVM 算子 | 输入 ndim | 输出 ndim | 输出可能 0-D | 说明 |
|---------|----------|-----------|-----------|-------------|------|
| `add` | `add` | A: N1≥1, B: N2≥1 | **M = max(N1, N2)** | 如果 N1=N2=1 且 broadcast 后为标量则可能 | broadcasting |
| `subtract` | `subtract` | 同上 | **M = max(N1, N2)** | 同上 | broadcasting |
| `multiply` | `multiply` | 同上 | **M = max(N1, N2)** | 同上 | broadcasting |
| `divide` | `divide` | 同上 | **M = max(N1, N2)** | 同上 | broadcasting |
| `maximum` | `maximum` | 同上 | **M = max(N1, N2)** | 同上 | 逐元素 max |
| `minimum` | `minimum` | 同上 | **M = max(N1, N2)** | 同上 | 逐元素 min |
| `power` | `power` | 同上 | **M = max(N1, N2)** | 同上 | broadcasting |

**约束**: 两个输入 ndim 均 ≥ 1，输出 ndim = max(N1, N2)。两个输入必须 compatible（可 broadcast）。

---

## 3. 矩阵乘法

| IR 算子 | TVM 算子 | 输入 ndim | 输出 ndim | 输出可能 0-D | 说明 |
|---------|----------|-----------|-----------|-------------|------|
| `matmul` | `matmul` | **A: 2-D, B: 2-D** | M = 2 | 否 | 翻译器固定用 2-D full 填空 |

**翻译器实现**: 翻译器生成 `full(ShapeExpr([16,16]), zeros([]))` 作为 matmul 的两个输入，输出恒为 2-D。

**生成器强制约束**:
- 生成器必须保证 matmul 的**两个输入 ndim 均为 2**（与翻译器实现一致）。
- 生成器 `computeOutputNdim` 中 matmul 输出恒为 2。
- 删除 1-D 和高维的 ndim 推导分支。

---

## 4. 归约算子 (reduce)

| IR 算子 | TVM 算子 | 输入 ndim | 输出 ndim | 输出可能 0-D | axis 约束 | 说明 |
|---------|----------|-----------|-----------|-------------|-----------|------|
| `sum` | `sum` | N ≥ 1 | 见下 | 否（生成器约束） | axis ∈ [-N, N) | 沿 axis 求和 |
| `mean` | `mean` | N ≥ 1 | 见下 | 否（生成器约束） | axis ∈ [-N, N) | 沿 axis 求平均 |
| `max` | `max` | N ≥ 1 | 见下 | 否（生成器约束） | axis ∈ [-N, N) | 沿 axis 求最大值 |
| `min` | `min` | N ≥ 1 | 见下 | 否（生成器约束） | axis ∈ [-N, N) | 沿 axis 求最小值 |
| `reduce_sum` | `sum` | 同上 | 同上 | 同上 | 同上 | 同上 |
| `reduce_mean` | `mean` | 同上 | 同上 | 同上 | 同上 | 同上 |
| `reduce_max` | `max` | 同上 | 同上 | 同上 | 同上 | 同上 |
| `reduce_min` | `min` | 同上 | 同上 | 同上 | 同上 | 同上 |

**输出 ndim 计算公式**:
- `keepdims=True`: M = N（该轴 size 变为 1）
- `keepdims=False`: M = N - len(axis)
  - 生成器固定只生成单个 axis（len(axis)=1），所以 M = N - 1

**关键约束**:
- **输入 ndim ≥ 1**（0-D tensor 不能 reduce）
- **axis 必须在 [-N, N) 范围内**，否则 TVM 报错
- **生成器约束**: 输出 ndim 始终 ≥ 1（`.coerceAtLeast(1)`），避免 0-D propagate

**生成器实现**: `axis = rand.nextInt(effectiveNdim)`，其中 `effectiveNdim = inputNdim.coerceIn(1, 4)`。axis 范围 [0, effectiveNdim)。仅在单个 axis 上 reduce。

---

## 5. Softmax

| IR 算子 | TVM 算子 | 输入 ndim | 输出 ndim | 输出可能 0-D | axis 约束 |
|---------|----------|-----------|-----------|-------------|-----------|
| `softmax` | `nn.softmax` | N ≥ 1 | M = N | 否 | axis ∈ [-N, N) |

**关键约束**: axis 必须在 [-N, N) 范围内。（TVM 0.25 实测允许 1-D 输入。）

---

## 6. 形状变换

| IR 算子 | TVM 算子 | 输入 ndim | 输出 ndim | 输出可能 0-D | 说明 |
|---------|----------|-----------|-----------|-------------|------|
| `reshape` | `reshape` | N ≥ 1 | M = 1 | 否 | 翻译器固定展平到 1-D |
| `permute_dims` | `permute_dims` | N ≥ 1 | M = N | 否 | 转置 |
| `squeeze` | `squeeze` | N ≥ 1 | M = N-1（可能） | **是** | 去掉所有 1 维 |
| `expand_dims` | `expand_dims` | N ≥ 0 | M = N+1 | 否 | 扩维，axis=0 |
| `broadcast_to` | **不建议生成** | — | — | — | 翻译器用 full 替代，与生成器追踪不一致 |

**约束**:
- `squeeze`: **可能导致 0-D**。生成器必须避免继续使用 0-D 结果（见常见错误模式 1）。
- `expand_dims`: 0-D 输入可用，输出 1-D。
- `broadcast_to`: 翻译器无视输入直接替换为 `full`，生成器不应生成此算子。

---

## 7. 拼接/分割

| IR 算子 | TVM 算子 | 输入 ndim | 输出 ndim | 输出可能 0-D | axis 约束 |
|---------|----------|-----------|-----------|-------------|-----------|
| `concat` | `concat` | **所有输入 ndim 相等** >= 1 | M = N | 否 | 沿 axis 拼接 |
| `split` | `split` | N ≥ 1 | M = N | 否 | axis ∈ [-N, N) |

**约束**:
- `concat`: **所有输入必须具有相同的 ndim**（轴上的 size 可以不同），需要至少 2 个输入。
- `split`: 翻译器固定 2 份，axis 从 UIR attr 读取。

---

## 8. 三角算子

| IR 算子 | TVM 算子 | 输入 ndim | 输出 ndim | 输出可能 0-D |
|---------|----------|-----------|-----------|-------------|
| `tril` | `tril` | **N = 2** | M = 2 | 否 |
| `triu` | `triu` | **N = 2** | M = 2 | 否 |

**关键约束**:
- **生成器必须保证输入 ndim = 2**（不依赖翻译器防御逻辑）。
- 翻译器防御：无条件 `expand_dims` 到 2-D（仅用于兜底，生成器不应依赖）。
- 输入 ndim ≥ 2 时 TVM 不会报错，但生成器约束为精确 2 以保持一致性。

---

## 9. 索引算子

| IR 算子 | TVM 算子 | 输入 ndim | 输出 ndim | 输出可能 0-D | 说明 |
|---------|----------|-----------|-----------|-------------|------|
| `take` | `take` | N ≥ 1 | M = N-1 | **是** | indices 为 0-D 标量，axis=0 |
| `gather` | `take` | N ≥ 1 | M = N-1 | **是** | 同 take |

**约束**: 输入 ndim ≥ 1，indices 固定为 0-D 标量（`zeros(ShapeExpr([1]), int64)`），axis=0。
输出 ndim = N-1，可能为 0-D。**生成器必须避免将 0-D 结果传给后续算子**。

---

## 10. 切片算子

| IR 算子 | TVM 算子 | 输入 ndim | 输出 ndim | 输出可能 0-D | 说明 |
|---------|----------|-----------|-----------|-------------|------|
| `strided_slice` | `strided_slice` | N ≥ 1 | M = N-1（固定 axis=0） | **是** | 沿 axis=0 |
| `pad` | `pad` | N ≥ 1 | M = N | 否 | 填充 |

**约束**: `strided_slice` 固定 axis=0，可能降维到 0-D。**生成器必须避免将 0-D 结果传给后续算子**。

---

## 11. 卷积/池化

| IR 算子 | TVM 算子 | 输入 ndim | 输出 ndim | 输出可能 0-D | 说明 |
|---------|----------|-----------|-----------|-------------|------|
| `conv2d` | `nn.conv2d` | N = 4 | M = 4 | 否 | 输入 [N, C, H, W] |
| `max_pool2d` | `nn.max_pool2d` | N = 4 | M = 4 | 否 | 输入 [N, C, H, W] |
| `avg_pool2d` | `nn.avg_pool2d` | N = 4 | M = 4 | 否 | 输入 [N, C, H, W] |

---

## 12. 常数生成算子

| IR 算子 | TVM 算子 | 输出 ndim | 输出可能 0-D | 说明 |
|---------|----------|-----------|-------------|------|
| `arange` | `arange` | M = 1 | 否 | 固定 [0, 16) |
| `zeros` | `zeros` | M = 1-4 | 否 | 根据 UIR ndim 生成 |
| `ones` | `ones` | M = 1-4 | 否 | 根据 UIR ndim 生成 |
| `full` | `full` | M = 1-4 | 否 | 填充 0 值 |

---

## 13. 其它

| IR 算子 | TVM 算子 | 输入 ndim | 输出 ndim | 输出可能 0-D | 说明 |
|---------|----------|-----------|-----------|-------------|------|
| `tile` | `tile` | N ≥ 1 | M = N | 否 | repeats=1 |
| `batch_norm` | `nn.batch_norm` | N ≥ 2 | M = N | 否 | 无实现 |
| `layer_norm` | `nn.layer_norm` | N ≥ 2 | M = N | 否 | 无实现 |

---

## 常见错误模式

### 1. 0-D Tensor 传给需要 ndim ≥ 1 的算子
**产生原因**: `squeeze`、`take`、`strided_slice` 可能产生 0-D 输出。
**影响算子**: 所有一元激活、reduce 类、`triu`/`tril`、`split`、`strided_slice`、`take`、`concat`、`matmul`、`tile` 等。
**修复**: 生成器确保所有值的 ndim ≥ 1（`computeOutputNdim` 末尾 `.coerceAtLeast(1)`）。

### 2. axis 超出 tensor ndim 范围
**产生原因**: UIR 中 ndim 可能超过 4（生成器轴范围基于 UIR ndim），但翻译器 clamp 到 1-4。
**影响算子**: `softmax`、`sum`/`mean`/`max`/`min`、`split`。
**修复**: 生成器生成 axis 时 clamp 输入 ndim 到 [1,4]。

### 3. 翻译器 ndim 追踪与运行时不一致
**产生原因**: 翻译器 `ndimMap` 初始值来自 UIR ndim（可能 >4），但 TVM 实际 ndim 受 `inferInputType` 的 `coerceIn(1,4)` 限制。
**影响**: 翻译器 clamp 逻辑基于错误的 ndim 值。
**修复**: 翻译器 `inputNdims` 和 `outputNdim` 都 clamp 到 [1,4]。

### 4. keepdims 影响 ndim 链
**产生原因**: `keepdims=False` 的 reduce 改变 ndim，但后续算子 axis 可能基于原 ndim 生成。
**影响**: reduce 链中的 ndim 塌缩。
**修复**: 翻译器读取 UIR attr 中的 `keepdims` 值。

### 5. 翻译器替换输入导致追踪错乱
**产生原因**: `matmul` 和 `broadcast_to` 翻译器无视输入直接替换为固定 full。
**影响**: 生成器以为输入 A 和 B 决定了输出 ndim，实际翻译器输出恒为 2-D。
**修复**: `matmul` 生成器强制输入 ndim=2，输出 ndim=2。`broadcast_to` 不建议生成。