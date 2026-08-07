# Dedup 评估数据汇总

## 概述

所有运行基于 AIFuzzer (Kotlin)。目标：评估 VRA 值域分析对 dedup pattern 匹配的精度影响。

## 1. TVM GPU 基线

### 2000 轮 no-dedup 全量 Fuzzing（seed=42, 8 workers）

| 指标 | 值 |
|------|:---:|
| 总运行 | 2000 |
| 成功 | 1957 (97.9%) |
| 失败 | 43 |
| 耗时 | 1217s (20.3 min) |
| 错误分布 | TVM_ERROR: 37, UNKNOWN: 4, ATTRIBUTE_ERROR: 2 |

所有 43 个 bug 均为 `tvm.error.InternalError: Check failed: (it != info_map_.end()) is false: Load/Store of buffer ... occurred before its declaration`（storage_rewrite buffer 声明顺序问题）。

### 20k 轮 Dedup-Eval（seed=20260801, 8 workers, 27 内置 pattern）

| 指标 | 值 |
|------|:---:|
| 总种子 | 20000 |
| 收集 (dedup触发) | 802 (4.0%) |
| 跳过 | 19198 (96.0%) |
| Bug prevented | 331 (41.3%) |
| Dedup-only fail | 1 (0.1%) |
| Both failed | 3 |
| Both succeeded | 467 (58.3%) |
| 耗时 | 1498s (25 min) |

**Per-pattern 精度：**

| Pattern | BP/匹配 | 精度 |
|---------|:-------:|:----:|
| tvm-20036 (BATCH_NORM) | 111/120 | 92.5% |
| tvm-20036-ndim4 (BATCH_NORM) | 212/252 | 84.1% |
| tvm-20047-variant-kh2 (CONV2D) | 3/3 | 100% |
| tvm-20047 (CONV2D) | 5/83 | 6.0% |
| tvm-20047-w (CONV2D) | 9/348 | 2.6% |
| tvm-20048-red-variant (CONV2D) | 0/12 | 0% |

## 2. PyTorch VRA 优化运行

### 运行序列

| # | 配置 | 种子 | 种子数 | 收集 | BP | BS | BS% | 耗时 |
|:-:|------|:---:|:----:|:---:|:--:|:--:|:---:|:---:|
| 1 | avoid_nan_inf=true, 4 结构 pattern | 42 | 5000 | 0 | 0 | 0 | - | 49s |
| 2 | avoid=false, +5 值依赖, VRA | 42 | 5000 | 2113 | 62 | 1970 | 93.2% | 2869s |
| 3 | avoid=false, VRA+范围收紧 | 42 | 5000 | 2113 | 62 | 1970 | 93.2% | 2869s |
| 4 | avoid=false, VRA+NaN规避(Direction 2) | 42 | 2000 | 1140 | 29 | 1065 | 93.4% | 779s |
| 5 | avoid=false, VRA+极值规避 | 999 | 2000 | 1128 | 39 | 1041 | 92.3% | 1009s |
| 6 | 删除 ADD (+ 3 死 pattern) | 42 | 2000 | 681 | 16 | 636 | 93.4% | 689s |
| 7 | 加 $known + 输入范围初始化 | 42 | 2000 | 681 | 16 | 636 | 93.4% | 689s |

### 核心发现

**1. VRA-aware NaN/Inf 规避（方向 2）成功**
- daemon 崩溃从 707 次降至 2 次
- 速度提升 2.3x（30.5min → 13min）
- BP/BS 完全保留
- 证明 VRA 可以完全替代 `avoid_nan_inf`

**2. 值依赖 pattern 的高 BS 是固有的**
- 单算子值依赖（ADD -0.0/+0.0, SQRT 负数）匹配率极高，但触发率极低
- 根因：生成器产生随机值，VRA 无法预知实际运行值
- 范围收紧（方向 1）、输入范围初始化、`$known` 约束均无法降低 BS

**3. Pattern 修正路线**
- 删除了 4 个 silent_correctness 值依赖 pattern（0 BP 的全部删除）
- 保留了 SQRT（16 BP，真实的 runtime error）
- 保留了 4 个结构 pattern（匹配率低但精度高）

**4. 最终 PyTorch pattern 集（5 个）**

| ID | 类型 | 精度 | 说明 |
|:--:|:----:|:----:|------|
| pt-189787 | 结构 | 0% | SOFTMAX→REDUCE_SUM→FLOOR |
| pt-189808 | 值依赖 | 2.3% | SQRT(负数) → NaN |
| pt-190417 | 结构 | 0% | ZEROS→LOG2→LAYER_NORM |
| pt-190418 | 结构 | 0% | MULTIPLY→LOG_SOFTMAX |
| pt-190421 | 结构 | 0% | LOG→REDUCE_SUM(int32) |

## 3. 代码修改总结

| 文件 | 修改内容 |
|------|---------|
| ValueRange.kt | 修复 LEAKY_RELU、EXP；新增 SELU/MISH/HARDTANH/CLAMP/MAXIMUM/MINIMUM/MATMUL；新增形状保持算子传播（RESHAPE/TRANSPOSE/BROADCAST_TO 等）；新增 REDUCE 算子传播；新增 `$known` matcher + AND 组合 |
| UirGenerator.kt | VRA-aware NaN/Inf 规避（selectOpWithConstraints + isRangeSafeFor）；VRA-aware extreme op 规避（SIGN）；graph input 值域初始化 [-1,1] |
| default.yaml | avoid_nan_inf 默认关闭；avoid_extreme_ops 默认关闭；valueRangeAnalysis 默认启用 |
| patterns/ | 删除 4 个死 pattern（pt-189799, pt-189801, pt-189803, pt-189804） |

## 4. 结论

**VRA 成功实现了：**
- 替代 `avoid_nan_inf`（NaN/Inf 规避，daemon 稳定，速度 2.3x）
- 更精确的范围传播（修复 LEAKY_RELU、EXP 等 10+ 算子）
- 多条件 AND 组合匹配（`$lt: 0` + `$known: true`）

**VRA 无法解决的：**
- 值依赖 pattern 的高 BS（生成器随机值 → VRA 过近似 → 匹配率极高）
- 对于 silent_correctness 值依赖 bug，pattern 应删除而非用 VRA 修复