# Feature: 添加避免 NaN/Inf 的生成开关

> 日期: 2026-07-19
> 类型: 功能增强
> 优先级: P1
> 状态: ✅ **已实现**（2026-07-19）

## 背景

当前 aiFuzzer 的生成器在生成计算图时，没有考虑算子执行结果是否会产生 NaN/Inf 等浮点异常值。这导致以下问题：

1. **差分测试假阴性**：当输出中包含 NaN/Inf 时，编译器间的比较结果不可靠（`allclose` 对 NaN 不通过，但这是浮点运算的固有行为，不是编译器 bug）
2. **Bug 被掩盖**：如 NNSmith 论文所述，某些 bug 只在数值有效时才能暴露（如 `Conv2d(Conv2d(...))` + `Pow(..., BIG_NUM)` 产生 Inf，掩盖了下游的语义 bug）
3. **VERIFY:FAIL 误报**：当前 `inf - inf = NaN` 模式已被识别为假阳性，但启用此开关后可以从源头减少这类误报

NNSmith 采用**梯度搜索**（gradient-guided search）来找到避免 NaN/Inf 的输入值，但这需要大量计算和可微的算子模型。本 issue 提出一个更轻量的替代方案。

## 方案：算子级回避策略

### 核心思路

在 `UirGenerator` 中添加一个开关 `avoidNaNInf`（默认 `true`），当开启时，生成器在算子选择阶段**排除**那些已知会导致 NaN/Inf 的算子。

### 算子分类

#### 高风险的"NaN/Inf 倾向算子"（`nanInfProneOps`）

| 算子 | 触发条件 | 产生值 | 备注 |
|------|---------|--------|------|
| `LOG` / `LOG2` | 输入 ≤ 0 | NaN | 对负数/零取对数 |
| `SQRT` / `RSQRT` | 输入 < 0 | NaN | 对负数开方；RSQRT 在输入=0 时产生 Inf |
| `RECIPROCAL` | 输入 = 0 | Inf | 1/0 |
| `DIVIDE` | 分母 = 0 | Inf/NaN | 零除 |
| `POWER` | 底数 ≤ 0 且指数为分数时；底数=0 且指数≤0 时；结果超出浮点范围时 | NaN/Inf | 多条件触发 |
| `EXP` | 输入值过大（如 > 89 对 float32） | Inf | 指数爆炸 |
| `CUMPROD` | 累积乘导致溢出 | Inf | 序列累积乘可能指数增长 |
| `REDUCE_MAX`/`REDUCE_MIN` | 空输入（0-D 张量） | 行为未定义 | 边界情况 |
| `ARGMAX`/`ARGMIN` | 空输入 | 行为未定义 | 边界情况 |

#### 条件安全的算子（`conditionallySafeOps`）

| 算子 | 安全条件 | 备注 |
|------|---------|------|
| `SIGMOID` | 输入不过大 | 输出范围为 (0, 1)，很安全 |
| `TANH` | 同上 | 输出范围为 (-1, 1) |
| `SOFTMAX` | 输入不过大 | 指数归一化，适中的输入安全 |
| `GELU` / `SILU` | 输入不过大 | 激活函数，通常安全 |
| `MATMUL` | 输入值不过大 | 矩阵乘法可能放大数值 |
| `CONV2D` | 权重/输入不过大 | 卷积可能放大数值 |
| `REDUCE_SUM` / `REDUCE_MEAN` | 输入不过大 | 求和可能溢出 |
| `BROADCAST_TO` / `TILE` | 语义安全 | 只是复制数据 |
| `ADD` / `SUBTRACT` / `MULTIPLY` | 输入不过大 | 二元运算可能放大数值 |

#### 完全安全的算子（`safeOps`）

| 算子 | 理由 |
|------|------|
| `RELU` / `LEAKY_RELU` / `ELU` / `SELU` / `MISH` / `HARDTANH` | 激活函数，输出有界或仅线性 |
| `ABS` / `NEG` / `SIGN` | 简单数学，不产生 NaN/Inf |
| `CEIL` / `FLOOR` / `ROUND` / `CLAMP` | 数值稳定 |
| `RESHAPE` / `TRANSPOSE` / `SQUEEZE` / `UNSQUEEZE` | 形状变换，不改变数值 |
| `CONCAT` / `SPLIT` | 数据搬运 |
| `TRIL` / `TRIU` | 掩码操作 |
| `ONES` / `ZEROS` / `FULL` / `ARANGE` | 常数生成，可控制值域 |
| `CAST` | 类型转换，不改变数值语义 |
| `GATHER` / `STRIDED_SLICE` / `TILE` | 索引/切片，不改变数值 |
| `MAX_POOL2D` / `AVG_POOL2D` | 池化，数值稳定 |
| `LAYER_NORM` / `BATCH_NORM` | 归一化，数值稳定 |
| `INTERPOLATE` / `RESIZE2D` | 插值，数值稳定 |
| `MAXIMUM` / `MINIMUM` | 逐元素极值，数值稳定 |
| `REDUCE_MAX` / `REDUCE_MIN` (非空输入时) | 取极值，不产生 NaN/Inf |

### 开关设计

```yaml
# generator 配置新增
generator:
  avoidNaNInf: true    # 默认开启（已实现）
  # 当开启时，以下算子被排除
  # - LOG, LOG2, SQRT, RSQRT, RECIPROCAL
  # - DIVIDE (仅在分母为 0 时危险，但生成时无法预知输入值，整体排除)
  # - POWER
  # - EXP
  # - CUMPROD
```

### 实现细节（已实现）

#### 1. 配置层

**`GeneratorConfig`** 新增字段（`src/main/kotlin/.../generator/UirGenerator.kt`）：

```kotlin
data class GeneratorConfig(
    // ... 现有字段
    val avoidNaNInf: Boolean = true,  // 默认开启
)
```

**`FuzzerGenConfig`** 新增字段，并在 `toGeneratorConfig()` 中传递（`src/main/kotlin/.../config/FuzzerConfig.kt`）：

```kotlin
data class FuzzerGenConfig(
    // ... 现有字段
    var avoidNaNInf: Boolean = true,
)
```

#### 2. 生成器层

**`UirGenerator`** 中 `opsEnum` 初始化时过滤 `nanInfProneOps`：

```kotlin
class UirGenerator(private val config: GeneratorConfig = GeneratorConfig()) {
    companion object {
        val nanInfProneOps = setOf(
            UirOpKind.LOG, UirOpKind.LOG2,
            UirOpKind.SQRT, UirOpKind.RSQRT,
            UirOpKind.RECIPROCAL,
            UirOpKind.DIVIDE,
            UirOpKind.POWER,
            UirOpKind.EXP,
            UirOpKind.CUMPROD,
        )
    }
    
    private val opsEnum: List<UirOpKind> = run {
        val baseOps = config.ops.mapNotNull {
            try { UirOpKind.valueOf(it) } catch (_: IllegalArgumentException) { null }
        }.ifEmpty { DefaultOps }
        if (config.avoidNaNInf) {
            baseOps.filter { it !in nanInfProneOps }
        } else {
            baseOps
        }
    }
}
```

#### 3. 翻译器层 — 随机输入改为安全的值域

所有三个翻译器的随机输入初始化从 `randn`（无界正态分布）改为 `uniform(0, 1)`（有界 [0,1) 均匀分布）：

- **PyTorch 后端**：`torch.randn(...)` → `torch.rand(...)`
- **TVM 后端**：`np.random.randn(...)` → `np.random.uniform(0.0, 1.0, size=(...))`
- **ONNX 后端**：同上

#### 4. ShapeAdapter 层

`ShapeAdapter` 生成的常量张量（`ZEROS` 节点、`FULL` 节点）本身不会产生 NaN/Inf。`FULL` 节点的 `fill_value=0.1` 已是安全正值。`DIVIDE` 已被排除，所以 `ZEROS` 作为分母的隐患也不存在。

### 配置 YAML 示例

```yaml
generator:
  avoidNaNInf: true
  # 其他配置保持默认
```

### 与现有架构的兼容性

- **默认开启** (`avoidNaNInf: true`)
- 当开启时，只影响 `opsEnum` 的初始化，不修改其他代码路径
- 配置可从 YAML 加载，也可通过 CLI 参数覆盖
- 可通过 `configs/` 下的 profile 文件按需关闭（如兼容性测试）

### 未来扩展

1. **更细粒度的控制**：允许用户指定要排除的算子列表，而不是硬编码的 `nanInfProneOps` 集合
2. **输入值优化**：借鉴 NNSmith 的梯度搜索思想，在不排除算子的前提下，通过优化输入值来避免 NaN/Inf
3. **运行时检测**：在 daemon 端检测执行结果是否包含 NaN/Inf，自动重新生成

## 参考

- NNSmith §3.3: "Improving Numeric Validity with Gradients" — 使用梯度搜索避免 NaN/Inf，覆盖率 98%
- NNSmith 论文 Table 1: 列出了易受攻击算子及其数值域约束
- aiFuzzer memory: `VERIFY:FAIL nan diff 分类` — 已识别 `inf - inf = NaN` 假阳性模式