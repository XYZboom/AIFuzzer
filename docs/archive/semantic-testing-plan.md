# AiFuzzer 语义正确性验证规划

> 版本: v1.0  
> 日期: 2026-07-12  
> 背景: 基于 GitHub PyTorch (863 个静默错误 issue) + TVM (81 个开放 bug) 的 issue 分析

---

## 目录

1. [现状与问题](#1-现状与问题)
2. [验证架构总览](#2-验证架构总览)
3. [内存与形状约束](#3-内存与形状约束)
4. [差分测试（交叉验证）](#4-差分测试交叉验证)
5. [属性测试（不变性验证）](#5-属性测试不变性验证)
6. [算子级语义验证](#6-算子级语义验证)
7. [动态形状测试](#7-动态形状测试)
8. [边界条件与异常路径](#8-边界条件与异常路径)
9. [实现路线图](#9-实现路线图)
10. [附录: 关键参考 Issue](#10-附录-关键参考-issue)

---

## 1. 现状与问题

### 1.1 当前架构的局限性

```
生成 UIR 程序 → 翻译为 PyTorch/TVM 代码 → 编译（torch.compile）→ 检查是否崩溃
                                                                    ↑
                                                            只验证"不崩溃"
```

**核心缺陷**: 当前的验证只检查"编译器是否抛出异常"（crash/no-crash），完全不检查编译结果是否正确。这导致：

| 漏洞类型 | 能否检测 | 说明 |
|---------|---------|------|
| 编译期崩溃 (DYNAMO_ERROR) | ✅ | 当前 100% 检测到 |
| 运行时崩溃 (RuntimeError) | ❌ | 代码从不调用 forward()，运行时错误被隐藏 |
| 静默错误（结果错误） | ❌ | **最致命的漏洞，完全漏检** |
| 数值精度错误 | ❌ | 不比较输出，无法发现 |
| 形状推导错误 | ⚠️ 部分 | 仅通过 ShapeInferer 内部检查，不验证实际输出形状 |

### 1.2 来自 GitHub Issue 的统计

| 类别 | 数量 | 严重性 | 当前检测 |
|------|------|--------|---------|
| 静默正确性 (silent correctness) | 863 | 🔴 致命 | ❌ |
| 错误结果 (wrong result) | 272 | 🔴 致命 | ❌ |
| 错误形状 (wrong shape under compile) | 45 | 🟡 高 | ❌ |
| 动态形状错误 (dynamic shape) | 30+ | 🟡 高 | ❌ |
| 算子组合崩溃 (pattern crash) | 100+ | 🟠 中 | ⚠️ 部分 |
| TensorRT/TVM 前端导入错误 | 81 | 🟠 中 | ❌ |

### 1.3 为什么必须验证语义

在 AI 编译器中，一个**静默错误**的破坏力远大于一个崩溃：

```python
# 用户代码
model = MyModel()
compiled = torch.compile(model)

# 编译后输出错误结果，但不报错
output = compiled(x)  # ← 悄无声息地给出错误答案
# 用户继续训练，产生错误的梯度，浪费数小时 GPU 时间
```

这正是 GitHub 上 863 个 `module: correctness (silent)` 标签 issue 的背后原因。

---

## 2. 验证架构总览

### 2.1 三阶段验证管道

```
生成 UIR 程序
    │
    ▼
┌─────────────────────────────────────────────────────┐
│ 阶段一: 编译期验证 (当前已实现，100% DYNAMO_ERROR)   │
│   torcn.compile(model) → 捕获编译期异常               │
└─────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────┐
│ 阶段二: 执行期验证 (新增)                             │
│   ┌─ 内存约束检查: 确保可执行                         │
│   └─ 实际执行推理: forward(*inputs) → 捕获运行时异常   │
└─────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────┐
│ 阶段三: 语义正确性验证 (新增，核心)                    │
│   ├─ 差分测试: 跨后端/跨模式比较输出                   │
│   ├─ 属性测试: 数学性质不变性检查                      │
│   ├─ 形状验证: 实际输出形状 vs 预期形状                │
│   └─ 数值范围检查: NaN/Inf/溢出检测                   │
└─────────────────────────────────────────────────────┘
```

### 2.2 验证策略选择矩阵

| 测试类型 | 检测能力 | 开销 | 适用范围 |
|---------|---------|------|---------|
| 差分测试 (跨后端) | 数值错误、形状错误 | 高 | 所有算子 |
| 属性测试 (不变性) | 数学性质违反、数值错误 | 低 | 有数学性质的算子 |
| 形状验证 | 形状推导错误 | 低 | 所有算子 |
| 数值范围检查 | NaN/Inf/溢出 | 低 | 所有算子 |
| 交叉验证 (eager vs compile) | 编译引入的错误 | 中 | 所有算子 |
| 幂等性测试 | 状态错误 | 低 | 幂等算子 |

---

## 3. 内存与形状约束

### 3.1 问题: 当前形状生成导致内存溢出

当前代码 (`UirGenerator.kt` 第 417 行):

```kotlin
this.value = rand.nextInt(1, 129)  // 每个维度 1~128
```

对于一个 4D 张量，形状为 `[120, 114, 45, 15]`，内存占用：

```
120 × 114 × 45 × 15 × 4 bytes = 36,936,000 bytes ≈ 37 MB
```

对于有 9 个输入 + 30 个中间节点的图，总内存可达 **1 GB 以上**，在 CPU 上容易 OOM 或触发 swap。

### 3.2 改进方案: 分层形状生成

引入 **内存预算** 概念：

```kotlin
// 内存预算配置
data class MemoryBudget(
    val maxTotalElements: Long = 1_000_000,   // 每次测试的总元素数上限
    val maxTensorElements: Long = 256_000,    // 单个张量元素数上限
    val dtype: String = "float32",            // 默认 dtype
)
```

**形状生成策略**:

```
策略 1: 小形状 (default)
  每个维度 1~8，最多 4D
  单个张量: ≤ 8×8×8×8 = 4096 元素
  示例: [3, 5, 7, 2], [1, 4, 8]

策略 2: 中等形状
  每个维度 1~32，最多 4D
  单个张量: ≤ 32×32×32×32 = 1,048,576 元素

策略 3: 卷积友好形状
  形状遵循 NCHW 约定，生成合理的图像尺寸
  示例: [1, 3, 16, 16], [2, 64, 8, 8], [1, 128, 4, 4]

策略 4: 极端形状（边界测试）
  包含 1 的维度: [1, 1, 1, 1]
  包含 0 的维度: [0, 3, 5]（如果算子支持）
  极大维度: [1, 1, 65536]
```

### 3.3 实现: MemoryBudget 检查器

```kotlin
class MemoryBudgetChecker(private val budget: MemoryBudget) {

    fun checkProgram(program: UirProgram): Boolean {
        var totalElements = 0L
        for (graph in program.graphs) {
            for (node in graph.nodes) {
                for (output in node.outputs) {
                    val elements = output.type.shape.dims
                        .fold(1L) { acc, dim -> acc * (dim.value ?: 1) }
                    if (elements > budget.maxTensorElements) return false
                    totalElements += elements
                }
            }
        }
        return totalElements <= budget.maxTotalElements
    }

    /** 为测试输入生成合适的形状 */
    fun clampShape(shape: UirShape): UirShape {
        // 如果形状超出预算，按比例缩小
        val elements = shape.dims.fold(1L) { acc, dim -> acc * (dim.value ?: 1) }
        if (elements <= budget.maxTensorElements) return shape
        val scale = budget.maxTensorElements.toDouble() / elements
        // 等比例缩小每个维度
        return buildShape {
            shape.dims.forEach { dim ->
                val scaled = (dim.value?.toDouble()?.times(scale)?.pow(1.0 / shape.dims.size))?.toInt() ?: 1
                dims.add(buildDim {
                    dimKind = UirDimKind.CONSTANT
                    value = maxOf(1, scaled)
                })
            }
        }
    }
}
```

### 3.4 形状档位配置

```yaml
generator:
  shape_tier: "small"        # small | medium | conv | extreme
  memory_budget:
    max_total_elements: 100000
    max_tensor_elements: 16384
    strategy: "proportional"  # proportional | uniform | deterministic
```

---

## 4. 差分测试（交叉验证）

### 4.1 核心思想

同一程序在不同编译后端上执行，比较输出的一致性。如果两个后端输出不同，至少有一个是错误的。

```
┌──────────────────────────────────────────────────────┐
│                     UIR Program                       │
└──────────┬───────────────┬──────────────────┬────────┘
           ▼               ▼                  ▼
    ┌────────────┐  ┌────────────┐   ┌──────────────┐
    │ PyTorch    │  │ PyTorch    │   │ TVM Relax    │
    │ Eager      │  │ compile()  │   │ Build & Run  │
    └─────┬──────┘  └─────┬──────┘   └──────┬───────┘
          │               │                  │
          ▼               ▼                  ▼
    ┌────────────┐  ┌────────────┐   ┌──────────────┐
    │ ref_output │  │ cmp_output │   │ tvm_output   │
    └──────┬─────┘  └─────┬──────┘   └──────┬───────┘
           │              │                  │
           └──────────────┼──────────────────┘
                          ▼
              ┌───────────────────────┐
              │  allclose 比较        │
              │  ref vs cmp vs tvm    │
              │  → 不一致即 bug       │
              └───────────────────────┘
```

### 4.2 差分维度

#### 4.2.1 PyTorch 内部差分

| 差分对 | 检测目标 | 示例 |
|--------|---------|------|
| `eager` vs `compile(default)` | 编译引入的数值错误 | `#168126` bf16 数值发散 |
| `eager` vs `compile(reduce-overhead)` | 激进优化导致的错误 | 算子融合后精度损失 |
| `eager` vs `compile(max-autotune)` | Triton 自动调优错误 | `#164124` 用了错误的 kernel |
| CPU vs CUDA | 设备差异 | `#123177` 多线程上下文 |

#### 4.2.2 跨编译器差分

| 差分对 | 检测目标 |
|--------|---------|
| PyTorch Eager vs TVM Relax | 跨编译器语义一致性 |
| PyTorch Eager vs PyTorch TVM backend | 第三方后端正确性 |
| TVM LLVM vs CUDA | TVM 后端差异 |

#### 4.2.3 模式级差分

| 差分对 | 检测目标 |
|--------|---------|
| `static` vs `dynamic=True` | 动态形状 bug (`#178482`) |
| `float32` vs `float16` | 半精度数值稳定性 |
| 单精度 vs 双精度 | 浮点精度差异 |

### 4.3 差分测试实现框架

#### 4.3.1 统一执行接口

```kotlin
interface Executor {
    /** 编译并执行程序，返回输出张量（作为扁平数组） */
    fun execute(program: UirProgram, inputs: Map<String, Tensor>): ExecResult
}

data class ExecResult(
    val success: Boolean,
    val outputs: Map<String, Tensor>,  // valueId → Tensor
    val stdout: String,
    val stderr: String,
    val elapsedMs: Long,
)

data class Tensor(
    val shape: List<Int>,
    val dtype: String,
    val data: FloatArray,  // 扁平化的 float32 数据
)
```

#### 4.3.2 差分比较器

```kotlin
class DifferentialComparator(
    private val atol: Float = 1e-5f,
    private val rtol: Float = 1e-5f,
) {
    data class DiffResult(
        val consistent: Boolean,
        val maxAbsDiff: Float,
        val maxRelDiff: Float,
        val diffCount: Int,     // 差异元素数
        val totalElements: Int,
    )

    fun compare(
        label: String,        // 描述，如 "eager vs compile"
        ref: Map<String, Tensor>,
        target: Map<String, Tensor>,
    ): DiffReport {
        // 逐输出比较
        val results = ref.map { (key, refTensor) ->
            val targetTensor = target[key] ?: return@map key to null
            key to compareTensors(refTensor, targetTensor)
        }
        return DiffReport(label, results)
    }
}
```

#### 4.3.3 测试输入生成

要让程序真正执行，需要生成具体的测试输入数据：

```python
# 生成的 Python 代码
class TestModule_0(nn.Module):
    def forward(self, v_0, v_1, v_2):
        t0 = torch.abs(v_0)
        t1 = torch.exp(v_1)
        t2 = torch.add(t0, t1)
        return t2

# ====== 测试执行 ======
model = TestModule_0()

# 生成测试输入（形状受 MemoryBudget 约束）
v_0 = torch.randn(3, 5, 7, dtype=torch.float32)
v_1 = torch.randn(3, 5, 7, dtype=torch.float32)

# Eager 模式执行（ground truth）
with torch.no_grad():
    ref_output = model(v_0, v_1)

# torch.compile 执行
compiled = torch.compile(model, mode="default")
with torch.no_grad():
    cmp_output = compiled(v_0, v_1)

# 差分比较
if not torch.allclose(ref_output, cmp_output, atol=1e-5, rtol=1e-5):
    diff = (ref_output - cmp_output).abs().max().item()
    print(f"DIFF: max_diff={diff}")
    raise AssertionError(f"Semantic mismatch: eager vs compile, max_diff={diff}")
```

### 4.4 生成器输出映射

为每个图输出生成对应的 `torch.randn` 调用：

```python
# 当前: 只生成输入
v_0 = torch.randn(120, 114, 45, 15)

# 改进: 生成输入（受内存约束限制）
v_0 = torch.randn(3, 5, 7, 2)  # 小形状
v_1 = torch.randn(3, 5, 7, 2)
```

---

## 5. 属性测试（不变性验证）

### 5.1 核心思想

某些算子具有数学性质，对于任何输入都应该成立。如果编译后这些性质不再成立，说明编译器引入了错误。

### 5.2 算子属性分类

#### 5.2.1 交换律（Commutativity）

适用于: `ADD`, `MULTIPLY`, `MAXIMUM`, `MINIMUM`

```python
# 验证: f(a, b) == f(b, a) 对任何输入成立
def test_commutativity(model, a, b):
    out1 = model(a, b)
    out2 = model(b, a)
    assert torch.allclose(out1, out2, atol=1e-5)
```

#### 5.2.2 结合律（Associativity）

适用于: `ADD`, `MULTIPLY`

```python
# 验证: f(f(a, b), c) == f(a, f(b, c))
def test_associativity(model, a, b, c):
    out1 = model(model(a, b), c)
    out2 = model(a, model(b, c))
    assert torch.allclose(out1, out2, atol=1e-5)
```

#### 5.2.3 分配律（Distributivity）

适用于: `MULTIPLY` + `ADD`

```python
# 验证: a * (b + c) == a*b + a*c
def test_distributivity(mul_model, add_model, a, b, c):
    left = mul_model(a, add_model(b, c))
    right = add_model(mul_model(a, b), mul_model(a, c))
    assert torch.allclose(left, right, atol=1e-5)
```

#### 5.2.4 幂等性（Idempotence）

适用于: `RELU`, `CEIL`, `FLOOR`, `ABS`（对非负输入）

```python
# 验证: f(f(x)) == f(x)
def test_idempotence(model, x):
    out1 = model(x)
    out2 = model(out1)
    assert torch.allclose(out1, out2, atol=1e-5)
```

#### 5.2.5 对偶性（Duality）

适用于: `NEG`, `EXP` + `LOG`, `SQRT` + `POWER`

```python
# 验证: log(exp(x)) == x
def test_dual_inverse(forward_model, inverse_model, x):
    y = forward_model(x)
    z = inverse_model(y)
    assert torch.allclose(x, z, atol=1e-5)
```

#### 5.2.6 范围约束（Range Constraint）

适用于: `SIGMOID`, `TANH`, `RELU`, `SOFTMAX`

```python
# 验证: sigmoid(x) ∈ [0, 1] 对任何 x 成立
def test_range_constraint(model, x):
    out = model(x)
    assert (out >= 0).all() and (out <= 1).all()
```

#### 5.2.7 形状保持（Shape Preservation）

适用于: 所有逐元素算子（`RELU`, `ABS`, `NEG`, `EXP`, `ADD`, `MULTIPLY` 等）

```python
# 验证: f(x).shape == x.shape 对单输入算子成立
def test_shape_preservation(model, x):
    out = model(x)
    assert out.shape == x.shape
```

#### 5.2.8 符号不变性（Sign Preservation）

适用于: `RELU`, `ABS`, `SQUARE`, `EXP`

```python
# 验证: relu(x) >= 0
def test_sign_preservation(model, x):
    out = model(x)
    assert (out >= 0).all()
```

#### 5.2.9 保和性（Sum Preservation）

适用于: `SOFTMAX`, `LOG_SOFTMAX`

```python
# 验证: sum(softmax(x)) == 1（沿指定维度）
def test_sum_preservation(model, x, dim=-1):
    out = model(x)
    assert torch.allclose(out.sum(dim=dim), torch.tensor(1.0), atol=1e-5)
```

### 5.3 属性测试矩阵

| 算子 | 交换律 | 结合律 | 幂等性 | 范围约束 | 形状保持 | 对偶性 |
|------|--------|--------|--------|---------|---------|--------|
| ADD | ✅ | ✅ | ❌ | ❌ | ✅ | ❌ |
| MULTIPLY | ✅ | ✅ | ❌ | ❌ | ✅ | ❌ |
| RELU | ❌ | ❌ | ✅ | ✅[≥0] | ✅ | ❌ |
| SIGMOID | ❌ | ❌ | ❌ | ✅[0,1] | ✅ | ❌ |
| TANH | ❌ | ❌ | ❌ | ✅[-1,1] | ✅ | ❌ |
| EXP | ❌ | ❌ | ❌ | ✅[>0] | ✅ | Log |
| LOG | ❌ | ❌ | ❌ | ❌ | ✅ | Exp |
| SQRT | ❌ | ❌ | ❌ | ❌ | ✅ | Power |
| NEG | ❌ | ❌ | ❌ | ❌ | ✅ | NEG |
| ABS | ❌ | ❌ | ✅ | ✅[≥0] | ✅ | ❌ |
| CEIL | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ |
| FLOOR | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ |
| SOFTMAX | ❌ | ❌ | ❌ | ✅[0,1] | ❌ | ❌ |

### 5.4 代码生成示例

```python
# ====== 生成的属性测试代码 ======
import torch
import torch.nn as nn
import torch.nn.functional as F

# 生成测试输入
v_0 = torch.randn(3, 5, 7, dtype=torch.float32)
v_1 = torch.randn(3, 5, 7, dtype=torch.float32)

# 模型定义
class TestModule_0(nn.Module):
    def __init__(self):
        super().__init__()
    def forward(self, x):
        return torch.relu(x)

model = TestModule_0()

# ── 阶段 1: 编译 ──
compiled = torch.compile(model, mode="default")

# ── 阶段 2: 执行 ──
with torch.no_grad():
    output = compiled(v_0)
    print(f"output shape = {output.shape}")

# ── 阶段 3: 属性验证 ──
# 3a. 形状保持: f(x).shape == x.shape
assert output.shape == v_0.shape, f"Shape mismatch: {output.shape} vs {v_0.shape}"

# 3b. 范围约束: relu(x) >= 0
assert (output >= 0).all(), "RELU violated non-negativity"

# 3c. 幂等性: f(f(x)) == f(x)
with torch.no_grad():
    output2 = compiled(output)
assert torch.allclose(output, output2, atol=1e-5), "RELU violated idempotence"

print("All semantic checks passed")
```

---

## 6. 算子级语义验证

### 6.1 正交化测试（Orthogonal Test Suite）

为每个算子设计独立的测试案例，验证其在编译前后的语义一致性。

```python
# 每个算子独立的语义验证脚本
class OpSemanticTest:
    def test_add(self):
        a = torch.randn(3, 5)
        b = torch.randn(3, 5)
        eager = torch.add(a, b)
        compiled = torch.compile(lambda x, y: torch.add(x, y))(a, b)
        self.assert_allclose(eager, compiled)

    def test_conv2d(self):
        x = torch.randn(1, 3, 16, 16)
        w = torch.randn(4, 3, 3, 3)
        eager = F.conv2d(x, w)
        compiled = torch.compile(F.conv2d)(x, w)
        self.assert_allclose(eager, compiled)
```

### 6.2 算子组合测试（Pattern Test）

从 GitHub issue 中提取已知有问题的算子组合：

| 问题模式 | 来源 Issue | 测试用例 |
|---------|-----------|---------|
| `cat → slice → cat` | #100807 | 三节点链，检查输出形状 |
| `randperm + advanced indexing` | #158457 | 检查结果一致性 |
| `roll + fill_` | #117778 | 特定形状下的正确性 |
| `torch.empty(out=...)` + `dynamic=True` | #178482 | 检查输出形状 |
| `fill_` + `inplace` 操作 | 常见模式 | 就地操作后的正确性 |

### 6.3 数值稳定性验证

```python
# 验证: 不同数值范围下的稳定性
def test_numerical_stability():
    test_cases = [
        torch.randn(3, 5) * 1e-6,       # 极小值
        torch.randn(3, 5) * 1e6,         # 极大值
        torch.ones(3, 5) * 1e-10,        # 趋近零
        torch.ones(3, 5) * 1e10,         # 巨大正数
        -torch.ones(3, 5) * 1e10,        # 巨大负数
        torch.tensor([float('inf')] * 5), # 无穷大
        torch.tensor([float('nan')] * 5), # NaN
    ]
    for x in test_cases:
        eager = torch.sigmoid(x)
        compiled = torch.compile(torch.sigmoid)(x)
        # NaN 输入应该传播 NaN
        if x.isnan().any():
            assert compiled.isnan().any()
        else:
            assert torch.allclose(eager, compiled, equal_nan=True)
```

---

## 7. 动态形状测试

### 7.1 背景

GitHub 上 `torch.compile(dynamic=True)` 相关的 bug 持续增长（如 `#178482`、`#137217`、`#137275`）。动态形状测试需要生成带有 `dynamic=True` 的编译代码。

### 7.2 测试策略

```python
# 相同模型，编译时使用动态形状
model = torch.compile(TestModule(), dynamic=True, mode="default")

# 使用不同形状的输入调用
x1 = torch.randn(3, 5, 7)   # 形状 A
x2 = torch.randn(7, 5, 3)   # 形状 B

out1 = model(x1)
out2 = model(x2)  # 动态形状下应正常工作

# 与 eager 模式比较
eager_out1 = TestModule()(x1)
eager_out2 = TestModule()(x2)

assert torch.allclose(out1, eager_out1)
assert torch.allclose(out2, eager_out2)
```

### 7.3 符号形状测试

生成包含 `torch._check` 或 `symint` 的代码，测试 data-dependent 形状：

```python
def forward(self, x, mask):
    # mask 决定了输出形状（data-dependent）
    indices = torch.where(mask > 0)[0]
    return x[indices]  # 输出形状取决于 mask 内容
```

---

## 8. 边界条件与异常路径

### 8.1 边界形状

| 边界条件 | 测试用例 |
|---------|---------|
| 标量输入 | `torch.tensor(3.0)` |
| 零维张量 | `torch.randn(())` |
| 1D 张量 | `torch.randn(5)` |
| size=1 的维度 | `torch.randn(1, 3, 1, 5)` |
| 所有维度为 1 | `torch.randn(1, 1, 1)` |
| 极大维度 | `torch.randn(1, 1, 65536)` |

### 8.2 边界值

```python
# 生成的测试输入使用特殊值
test_inputs = {
    "zeros": torch.zeros(shape),
    "ones": torch.ones(shape),
    "uniform_0_1": torch.rand(shape),         # [0, 1)
    "uniform_neg1_1": torch.rand(shape) * 2 - 1,  # [-1, 1)
    "normal": torch.randn(shape),
    "inf": torch.full(shape, float('inf')),
    "neg_inf": torch.full(shape, float('-inf')),
    "nan": torch.full(shape, float('nan')),
    "mixed": torch.tensor([1.0, -1.0, 0.0, float('inf'), float('nan')]),
}
```

### 8.3 异常路径测试

不仅测试"正确路径"，还测试**应该抛出异常**的场景：

```python
# 测试: 形状不兼容应该报错，而不是静默输出错误结果
def test_shape_mismatch_should_error():
    a = torch.randn(3, 5)
    b = torch.randn(7, 9)
    try:
        output = torch.add(a, b)  # 不可广播，应该报错
        # 如果没报错，说明编译器没做形状检查
        print(f"BUG: Should have errored, but got {output.shape}")
    except RuntimeError:
        pass  # 正确的行为
```

---

## 9. 实现路线图

### Phase 1: 让程序真正可执行（2-3 周）

```
目标: 从"只编译不执行"到"编译并执行"

1. 添加 MemoryBudget 约束
   - 限制每个维度值在 1~8 范围内
   - 验证总元素数不超过预算
   └─ 修改文件: UirGenerator.kt, GeneratorConfig.kt

2. 修改 PytorchTranslator 生成执行代码
   - 添加 torch.randn 为每个输入生成测试数据
   - 添加 forward() 调用
   - 添加输出形状打印
   └─ 修改文件: PytorchTranslator.kt

3. 修改 TVM 执行路径
   - 生成测试输入数据
   - 编译后执行推理
   └─ 修改文件: TvmRelaxTranslator.kt
```

### Phase 2: 差分测试框架（3-4 周）

```
目标: 跨后端比较输出一致性

1. 实现统一 Executor 接口
   - PyTorchEagerExecutor
   - PyTorchCompileExecutor
   - TVMRelaxExecutor

2. 实现 DifferentialComparator
   - allclose 比较
   - 统计差异报告
   - 阈值配置

3. 集成到 FuzzingPipeline
   - 每个测试自动运行 2~3 个后端
   - 不一致即标记为 bug
```

### Phase 3: 属性测试引擎（2-3 周）

```
目标: 算子级别的数学性质验证

1. 实现属性检查器
   - 交换律检查器
   - 幂等性检查器
   - 范围约束检查器
   - 形状保持检查器

2. 算子属性注册表
   - 每个算子注册其可验证的属性
   - 生成器根据算子选择适用的属性测试

3. 集成到翻译阶段
   - 在生成的 Python 代码末尾追加属性验证代码
```

### Phase 4: 高级语义测试（4-6 周）

```
目标: 动态形状、边界条件、异常路径

1. 动态形状测试
   - dynamic=True 编译模式
   - 多形状输入序列
   - data-dependent 形状

2. 边界条件测试
   - 特殊值（NaN, Inf, 0, 极大/极小）
   - 边界形状（0D, 1D, size=1）

3. 异常路径测试
   - 形状不兼容的输入
   - 类型不匹配
   - 超出范围的参数
```

### 里程碑

| 里程碑 | 时间 | 可交付物 | 预期效果 |
|--------|------|---------|---------|
| M1: 可执行 | 第 3 周 | 程序可执行 + 内存约束 | 从 100% DYNAMO_ERROR 到发现运行时错误 |
| M2: 差分 | 第 7 周 | 跨后端差分框架 | 发现静默错误（对应 863 个 issue） |
| M3: 属性 | 第 10 周 | 属性测试引擎 | 发现数学性质违反（对应 272 个 issue） |
| M4: 高级 | 第 16 周 | 动态/边界/异常测试 | 全面覆盖编译器语义错误 |

---

## 10. 附录: 关键参考 Issue

### PyTorch 相关

| Issue | 标题 | 发现的 bug 类型 | 对应测试策略 |
|-------|------|----------------|-------------|
| #100807 | `torch.compile` returns output with WRONG SHAPE after `cat_slice_cat` | 形状错误 | 差分测试 + 形状验证 |
| #158457 | Inductor pattern matching failure with `randperm` + advanced indexing | 算子组合崩溃 | 正交化测试 |
| #117778 | `torch.roll` with `fill_` produces wrong outputs | 特定形状错误 | 边界形状测试 |
| #178482 | `torch.compile(dynamic=True)` ignores incompatible `out` tensor | 动态形状静默错误 | 动态形状测试 |
| #164124 | torch.compile uses wrong Triton autotune kernels | 自动调优错误 | 差分测试 |
| #168126 | Large numeric divergence for torch compile vs eager in bf16 | 数值精度 | 差分测试 |
| #169188 | `torch.compile(backend="tvm")` produces wrong result | 第三方后端错误 | 跨编译器差分 |
| #180642 | torch.compile broken with autograd.Function, incorrect outputs | 自动微分错误 | 属性测试 |
| #149768 | FSDP2 numeric bug for DTensor + python float | 静默数值错误 | 差分测试 |
| #144362 | Some operators miss dtype check when using `torch.compile` | 类型检查缺失 | 异常路径测试 |

### TVM 相关

| Issue | 标题 | 发现的 bug 类型 | 对应测试策略 |
|-------|------|----------------|-------------|
| #19965 | Relax ONNX import: Squeeze axes, shape Gather | 形状推导错误 | 形状验证 |
| #19971 | Relax ONNX shape graph Concat error | 前端转换错误 | 差分测试 |
| #19922 | Relax `reshape` yields 0-sized dimension, Divide by zero | 边界条件 | 边界形状测试 |
| #19694 | `conv_transpose` silently bails for unsupported dilation | 静默失败 | 异常路径测试 |
| #19531 | Relax ONNX Gather mishandles negative indices | 语义错误 | 差分测试 + 属性测试 |
| #19668 | ONNX Min and ArgMin mishandle NaN semantics | NaN 语义错误 | 数值范围检查 |
| #19974 | ONNX folds integer Div through floating-point Div | 精度损失 | 差分测试 |
| #19976 | MetaSchedule tune_relax aborts on Windows | 平台兼容性 | 边界条件 |

---

### 附录 B: 验证覆盖度矩阵

| 算子 | 形状保持 | 交换律 | 范围约束 | 幂等性 | 对偶性 | 差分测试 | 边界条件 |
|------|---------|-------|---------|-------|-------|---------|---------|
| ADD | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| SUBTRACT | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| MULTIPLY | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| DIVIDE | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| RELU | ✅ | ❌ | ✅[≥0] | ✅ | ❌ | ✅ | ✅ |
| SIGMOID | ✅ | ❌ | ✅[0,1] | ❌ | ❌ | ✅ | ✅ |
| TANH | ✅ | ❌ | ✅[-1,1] | ❌ | ❌ | ✅ | ✅ |
| EXP | ✅ | ❌ | ✅[>0] | ❌ | Log | ✅ | ✅ |
| LOG | ✅ | ❌ | ❌ | ❌ | Exp | ✅ | ✅ |
| CONV2D | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| MATMUL | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| SOFTMAX | ❌ | ❌ | ✅[0,1] | ❌ | ❌ | ✅ | ✅ |
| REDUCE_SUM | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| REDUCE_MAX | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| RESHAPE | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| TRANSPOSE | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| TRIL/TRIU | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| ARANGE | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |

---

### 附录 C: 关键设计决策

1. **差分测试优先于属性测试**: 差分测试检测面更广，前者覆盖所有算子，后者只覆盖有数学性质的算子。建议先实现差分测试，再补充属性测试。

2. **内存约束为刚性条件**: 所有测试输入必须满足 MemoryBudget 约束，否则跳过。宁可跳过测试，不可让测试进程 OOM。

3. **NaN/Inf 视为有效测试值**: 编译器应正确处理 NaN 和 Inf 传播（如 IEEE 754 标准），不应静默处理。

4. **阈值可配置**: 不同算子（如 bf16 vs float32）需要不同的 atol/rtol 阈值。阈值应记录在算子注册表中。

5. **异常路径是双向验证**: 检查"应该成功时是否成功"和"应该报错时是否报错"同样重要。编译器不应静默接受非法输入。