# 生成器语义丰富性审查报告

> 基于代码审查日期：2026-08-03
> 审查范围：`UirGenerator`、`UirMutator`、`ShapeAdapter`、`ShapeConstraints`、`TensorValueGeneratorExt`
> 基准：`main` 分支最新代码

---

## 一、算子覆盖

**已覆盖 14 大类 ~60 个算子：**

| 类别 | 算子 | 状态 |
|------|------|------|
| 元素级二元 | ADD, SUBTRACT, MULTIPLY, DIVIDE, MAXIMUM, MINIMUM, POWER | ✅ |
| 矩阵乘法 | MATMUL | ✅ |
| 激活函数 | RELU, LEAKY_RELU, ELU, SELU, MISH, HARDTANH, SIGMOID, TANH, GELU, SILU, SOFTMAX, LOG_SOFTMAX | ✅ 丰富 |
| 一元数学 | NEG, ABS, SIGN, EXP, LOG, LOG2, SQRT, RSQRT, RECIPROCAL, CEIL, FLOOR, ROUND, CLAMP | ✅ 完整 |
| 形状变换 | RESHAPE, TRANSPOSE, SQUEEZE, UNSQUEEZE | ✅ 基本 |
| 归约 | REDUCE_SUM, REDUCE_MEAN, REDUCE_MAX, REDUCE_MIN, CUMSUM, CUMPROD, ARGMAX, ARGMIN | ✅ 丰富 |
| 拼接/分割 | CONCAT, SPLIT | ✅ |
| 归一化 | LAYER_NORM, BATCH_NORM | ✅ |
| 索引/切片 | GATHER, STRIDED_SLICE | ✅ 基本 |
| 卷积/池化 | CONV2D, MAX_POOL2D, AVG_POOL2D | ✅ 基本 |
| 插值 | INTERPOLATE, RESIZE2D | ✅ |
| 三角 | TRIL, TRIU | ✅ |
| 常数生成 | ARANGE, FULL, ONES, ZEROS | ✅ |
| 广播/填充 | BROADCAST_TO, TILE | ✅ |

**缺失但影响不大的算子：** PAD, REPEAT, ROLL, FLIP, TOPK, SORT, SCATTER_ADD, NONZERO, WHERE。作为编译器 fuzzer，当前覆盖度对于 TVM/PyTorch 后端测试已经足够，无需优先补充。

---

## 二、Dtype 多样性 — ⚠️ 已验证但已回退

**验证结果（2026-08-03）：** 曾尝试在生成器中添加 dtype 多样性（常量算子随机选 dtype + 非常量算子插入 CAST），经过 20 轮三后端的 fuzzing 测试后，**结论是该方向价值有限，已回退所有改动**。

**原因分析：**

| 观察 | 解释 |
|------|------|
| TVM 报 11 个 "requires float dtype" | ✅ **预期行为** — sigmoid/tanh/gelu/exp/leaky_relu 等数学算子本身就只定义在浮点数上，TVM 的类型检查是正确设计 |
| PyTorch 报 5 个 "not implemented" | ✅ **预期行为** — abs(Bool)、neg(Bool)、max_pool2d(Bool) 等在语义上没有意义 |
| ONNX Op Max/Min 形状报错 | ⚠️ **已有的形状推导问题**，与 dtype 无关 |
| 唯一跨的 bug：PyTorch Inductor `maximum(Bool)` → sympy 崩溃 | ❗️ 这是 Inductor 的 sympy 传播 bug，但与 dtype 变化关系不大 |

**核心结论：** AI 编译器的大多数算子对输入 dtype 有严格的语义约束（只有 float 类型才有激活函数、数学运算的意义）。向非 float 类型插入 CAST 不能有效暴露编译器的 dtype promotion bug，反而会产生大量无意义的假阳性报告。dtype promotion 的正确测试方向应该是**在同一个 compute graph 中使用不同的 float 精度（float32/float16）混合计算**，而不是向整数/bool 类型转换。

---

## 三、属性随机化 — ⚠️ 部分固定

**代码证据：** `UirGenerator.kt` 第 747-838 行 `generateAttributes()`。

### 3.1 已随机化的属性（好）

| 算子 | 属性 | 随机范围 |
|------|------|---------|
| LEAKY_RELU | negative_slope | 0.01-0.31 |
| ELU | alpha | 0.5-2.0 |
| HARDTANH | min_val, max_val | -2.5~-0.5, 0.5~2.5 |
| CLAMP | min, max | -2.0~0.0, 0.5~2.5 |
| CUMSUM/CUMPROD | dtype | 30% 概率添加 |
| REDUCE_* | dtype | 10% 概率添加 |
| POOL2D | kernel_size | 1 或 2（60% 概率 1） |
| POOL2D | stride | 1~kernel_size |

### 3.2 完全固定的属性（问题）

| 算子 | 固定值 | 缺少的变体 |
|------|--------|-----------|
| **CONV2D** | stride=1, padding=0, dilation=1, groups=1 | **✅ 已修复** — stride∈[1,H/W], padding∈[0,min(H,W)/2], 根据输入动态随机 |
| **CONCAT** | axis=0 | axis=1/-1 等 |
| **SOFTMAX / LOG_SOFTMAX** | axis=-1 | axis=0/1 等 |
| **SPLIT** | axis=0 | 其他轴 |
| **GATHER** | axis=0 | 其他轴 |
| **REDUCE_*** | axis=-1, keepdims=0 | keepdims=1 |
| **ARGMAX/ARGMIN** | axis=-1 | 其他轴 |
| **INTERPOLATE/RESIZE2D** | mode="nearest", coord_mode="half_pixel" | "linear"/"bilinear" 等 |

### 3.3 影响最大的固定属性（Conv2D stride/padding ✅ 已修复）

**修复时间：** 2026-08-03
**涉及文件：** `UirGenerator.kt`（`selectOpWithConstraints`、`generateNode`）
**验证：** 40 轮 × 3 后端（TVM CUDA / PyTorch CUDA / ONNX），119/120 成功

CONV2D 的 stride/padding 原本在 `generateAttributes()` 中固定为 1 和 0，从不测试下采样或填充路径。本次修复做了三件事：

**修复 1 — 属性随机化（step 5.5）：** 属性生成移至 CONV2D 特殊处理块中，ShapeAdapter 适配之后、ShapeInferer 推导之前，基于适配后的实际输入形状动态随机化：
- **stride** ∈ [1, min(H, W)]，覆盖下采样路径
- **padding** ∈ [0, min(H, W)/2]，软限制避免输出过大

合法性由 ShapeAdapter 保证（kH/kW ≤ H/W、C_in 匹配）、ShapeInferer 的 `computeConvOutputDim` 公式正确读取 stride/padding。

**修复 2 — 算子选择约束修复：** CONV2D 在 `UirOpKind` 中被归类为 `binaryInputOps`，但 `selectOpWithConstraints` 要求图中已有 2 个满足 4D+C_in 匹配的值，而 CONV2D 的权重在 `selectInputValues` 中自生成（只从图取 1 个输入）。导致 CONV2D 几乎永远选不到。修复为 CONV2D 特殊处理：只检查 1 个 4D 输入即可。

**修复 3 — numInputs 强制 2：** `generateNode` 中 `binaryInputOps` 的分支用 `minOf(2, availableValues.size)` 计算输入数，当 `availableValues.size == 1` 时 `numInputs = 1`，导致 `selectInputValues` 不进入权重生成分支。修复为 CONV2D 强制 `numInputs = 2`。

---

## 四、非连续张量布局 — ❌ 定义了但未使用

**代码证据：** `TensorValueGeneratorExt.kt` 第 27-95 行定义了 `generateWithLayout()`，支持 `TRANSPOSED` 和 `PERMUTED` 布局，但 `UirGenerator` 和 `UirMutator` 都**从未调用它**。

```kotlin
// TensorValueGeneratorExt 中完整实现
fun generateWithLayout(
    baseValue: UirValueRef,
    layout: UirTensorLayout,
    layoutConfig: TensorLayoutConfig? = null
): Pair<UirValueRef, List<UirNode>>
```

**影响：**
- 所有生成的值都是连续布局，无法测试非连续视图的 stride 计算
- 无法测试 `transpose` 后 shape 推导是否与后端一致
- 无法测试广播后的非连续内存布局

**修复建议：** 在 `generateNode()` 生成输出值后，以 5% 概率调用 `generateWithLayout()` 插入 TRANSPOSE 节点。这需要确保 ShapeInferer 能正确处理 TRANSPOSE 后的形状。

---

## 五、特殊值（NaN/Inf） — ❌ 定义了但未使用

**代码证据：** `TensorValueGeneratorExt.kt` 第 136-149 行定义了 `generateSpecialValue()`，支持 NaN、Inf、-Inf、-0.0，但**从未被调用**。

```kotlin
fun generateSpecialValue(prob: Double = 0.0, rand: Random = Random.Default): String? {
    if (rand.nextDouble() >= prob) return null
    return when (rand.nextInt(4)) {
        0 -> "float('nan')"
        1 -> "float('inf')"
        2 -> "float('-inf')"
        3 -> "-0.0"
        else -> null
    }
}
```

同时，`avoidNaNInf` 默认开启，排除了 LOG、SQRT、DIVIDE 等高风险算子——但这和生成 NaN/Inf 值是两个独立维度。即使排除这些算子，**在 FULL 节点中把 fill_value 设为 NaN/Inf** 仍然可以测试其他算子在异常输入下的行为。

**影响：**
- 无法测试激活函数在 NaN/Inf 输入下的行为
- 无法测试 conv2d 在 NaN 权重下的梯度传播
- 无法测试 -0.0 的符号位处理

**修复建议：** 在 FULL 节点的 `fill_value` 属性中，以 1-2% 概率使用 `generateSpecialValue()` 生成 NaN/Inf。同时可将 `avoidNaNInf` 默认值改为 `false`，让用户自行选择。

---

## 六、形状多样性 — ⚠️ 范围偏窄

**代码证据：** `UirGenerator.kt` 第 881-917 行 `generateRandomShape()` 和 `budgetAwareMaxDim()`。

### 6.1 维度数范围

`config.minNdim=2, maxNdim=4`（默认值），意味着：
- **从不生成 1D 张量**（尽管很多算子如 RELU/SIGMOID 支持 1D）
- **从不生成 5D+ 张量**（虽然 TVM 后端支持有限，但 PyTorch 测试可能涉及）
- 极端情况：`extreme` 档位支持 0-5D，但极少使用

### 6.2 维度值范围

`tiny` 档位每维 1-6，`small` 1-16，`medium` 1-32。意味着：
- 形状值普遍很小（< 32）
- 没有超长宽比形状（如 [1, 1, 1, 1000]）
- 几乎没有 0 维度形状（`TensorValueGeneratorExt.generateRandomShapeWithZeroDim()` 定义了但未使用）
- 没有形状值为 1 的退化为标量的情况

### 6.3 预算约束的影响

`tiny` 档位的 `maxTotalElements=8000`，对于 4D 张量，每维上限约 6-9。但预算**跨所有张量共享**，所以随着图变深，后续张量的形状会越来越小，趋向最小维度值 1。

**修复建议：**
1. 以 10% 概率生成 1D 或 5D 形状（需确认后端支持）
2. 在 `extreme` 档位中启用 `generateRandomShapeWithZeroDim` 测试 0 维度
3. 增加 `long-tail` 形状档位，专门生成极端长宽比

---

## 七、图结构多样性 — ⚠️ 偏简单

**代码证据：** `UirGenerator.kt` 第 197-397 行 `generate()` 和 `generateGraph()`。

### 7.1 当前结构

```
Graph_0 → Graph_1 → Graph_2 → ... → Graph_N
         ↑            ↑
    串联输入    串联输入（上一图所有输出）
```

- 多图串联（3-5 个图），链式连接
- 单层分支（`branchProbability=0.3`），只有 tip 和倒数第二个值的分支
- 每图 3-12 个节点
- 图输出选择：所有未被使用的值都作为输出

### 7.2 问题

1. **分支结构简单**：只有单层分支，没有嵌套分支/菱形汇聚/多级分支
2. **串联模式单调**：非首张图只生成 0-1 个额外输入，图间耦合度低
3. **节点数偏少**：maxNodesPerGraph=12，对于复杂图融合测试不够
4. **输出选择简单**：直接用所有未被使用的值作为输出，可能产生过多输出值

### 7.3 变异器补充

`UirMutator` 提供 INSERT/DELETE/OP/ATTRIBUTE 四种变异，从种子池中采样，每种变异操作质量中等。但仍有不足（见第八节）。

**修复建议：**
1. 增加 `maxNodesPerGraph` 上限到 20-30，用预算控制防止 OOM 而不是限制节点数
2. 在 `generateGraph()` 中支持菱形汇聚结构（branch+merge 两阶段）
3. 改进图输出选择：有意识选择关键路径上的值作为输出，而不是全部

---

## 八、变异器能力 — ⚠️ 中等

**代码证据：** `UirMutator.kt` 第 175-557 行。

### 8.1 已实现的变异类型

| 类型 | 操作 | 问题 |
|------|------|------|
| OP | 同族算子替换 | ✅ 不错，按算子族分组 |
| INSERT | 在已有节点后插入单输入算子 | ⚠️ 只插入单输入算子 |
| DELETE | 删除中间节点，下游输入重连 | ✅ 有形状修复 |
| ATTRIBUTE | 修改 axis/keepdims | ⚠️ 不改其他属性（如 stride） |

### 8.2 缺失的变异类型

1. **INSERT 二元算子**：变异器从不插入 ADD/MUL 等二元算子（需要两个输入），也从不插入 CONCAT 等多输入算子
2. **INSERT 常数生成算子**：从不插入 FULL/ONES/ZEROS 等无输入算子
3. **ATTRIBUTE 扩展**：不改 CONV2D 的 stride/padding/dilation，不改 POOL2D 的 padding
4. **图级变异**：没有跨图操作（如合并两个图、拆分图、重排图顺序）
5. **子图替换**：没有用随机生成的子图替换现有子图

### 8.3 算子族定义

`UirMutator.kt` 第 200-213 行定义了算子族，整体合理，但缺少：
- `RESHAPE/TRANSPOSE/SQUEEZE/UNSQUEEZE` 形状变换族
- `GATHER/STRIDED_SLICE` 索引族
- `CONCAT/SPLIT` 拼接族
- `INTERPOLATE/RESIZE2D` 插值族

**修复建议：**
1. INSERT 支持随机选择二元算子（复用 `selectInputValues` 的输入选择逻辑）
2. 增加 CONV2D 属性变异（stride/padding）
3. 增加算子族定义，补全形状变换/索引族

---

## 九、总结与优先级

| 优先级 | 维度 | 当前状态 | 影响范围 | 修复难度 | 预期收益 |
|--------|------|---------|---------|---------|---------|
| **P0** | Dtype 多样性 | ❌ 严重缺失 | 所有算子，所有后端 | 低（~10 行代码） | 高 — 暴露 dtype promotion bug |
| **P0** | Conv2D 属性随机化 | ⚠️ 部分固定 | conv2d 形状推导 | 低（~5 行代码） | 高 — 暴露 stride/padding 边界 bug |
| **P1** | 非连续张量布局 | ❌ 定义未用 | 所有算子的 stride 路径 | 中（调用已有方法） | 中 — 暴露非连续内存 bug |
| **P1** | 特殊值 | ❌ 定义未用 | 所有算子的异常输入处理 | 低（FULL 中调用） | 中 — 暴露 NaN/Inf 传播 bug |
| **P1** | 属性随机化扩展 | ⚠️ 部分固定 | SOFTMAX/CONCAT/SPLIT axis | 低 | 中 — 更多 axis 变体 |
| **P2** | 形状多样性 | ⚠️ 范围偏窄 | 所有算子 | 中 | 中 — 更多极端形状 |
| **P2** | 图结构多样性 | ⚠️ 偏简单 | 图融合优化路径 | 高 | 中 — 复杂图融合 bug |
| **P2** | 变异器能力 | ⚠️ 中等 | 变异种子质量 | 中 | 中 — 丰富变异类型 |

### 最优先修复的两项（Conv2D 随机化 ✅ 已修复）

1. **Dtype 多样性**：调用已有的 `randomDtype()` 让常量生成算子随机选择 dtype，或在生成节点时以 5-10% 概率插入 CAST 节点。改动量极小，收益极高，能立刻暴露 dtype promotion 类 bug。

2. **特殊值生成**：在 FULL 的 fill_value 中以 1-2% 概率插入 NaN/Inf。代码已有，只差调用。