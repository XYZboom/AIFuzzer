# Project Principles & Bug Classification

> 核心共识（2026-07-04 确立）

## 源码 vs 测试代码区分准则

> ⚠️ **关键区分**（2026-07-04 记录）：本项目的代码有两类，切勿混淆。

### 项目源码（`src/main/kotlin/`）

**角色**：AI 编译器模糊测试框架本身。

这些代码是**产品**——生成 UIR、翻译为目标编译器代码、执行、收集 bug。
**bug 目标**是生成器和翻译器自身。当生成器产生非法 IR、或翻译器输出错误代码时，这是本项目需要修复的 bug。

### 测试代码（`src/test/kotlin/`）

**角色**：测试项目源码是否正常工作。

测试代码分为两类：
- **单元/集成测试**（如 `UirGeneratorTest`, `TvmFuzzerTest`）：验证项目源码功能正确
  - 测试生成器产出的 IR 结构是否合法
  - 测试翻译器输出的 Python 代码语法是否正确
  - 测试 BugCollector 的分类逻辑是否准确
  - **这些测试本身就是测试项目源码的**
- **Fuzzing 运行测试**（如 `LargeScaleFuzzingTest`）：通过项目源码驱动 AI 编译器，寻找 AI 编译器的 bug
  - 调用 `UirGenerator` → `TvmRelaxTranslator` → `TvmBackend` → `BugCollector`
  - 收集的 bug **属于被测试的 AI 编译器（TVM 等）**
  - 如果出现 SyntaxError/ImportError 等则说明项目源码有 bug，需要修复项目源码

### 报告输出（`reports/`）

`reports/` 目录中的 `.py` 文件是**疑似 TVM/目标编译器 bug** 的证据文件。
`reports/` 中的 `.txt` 报告是 fuzzing 运行的统计总结。

### 一句话总结

> 项目源码找的是**AI 编译器**的 bug；测试代码找的是**项目源码**的 bug。
> reports/ 目录保存的是**AI 编译器**的疑似 bug，不是项目源码的 bug。

---

## 信条

**生成器产生的 IR 必须是合法的。翻译器翻译后的代码必须是合法的。**

这意味着，如果 TVM 后端执行某个测试程序失败（报错、crash、异常结果），只有两种可能的原因：

1. **TVM 存在 bug**
2. **生成器或翻译器存在 bug**

不存在"合法生成的程序本身就有问题"这个选项。

## Bug 分类

### A 类：真实 TVM Bug（需要记录报告）

特征：
- IR 合法，翻译后的 Python/TVM 代码语法正确、语义合理
- TVM 报错信息与合法输入不符（如形状广播不兼容的误报）
- TVM 报错信息指向 TVM 内部错误（如类型推导失败、后端优化 crash）
- TVM 输出结果与预期不符（未报错但结果错误，需人工复核）

**处理**：保存到 `reports/` 目录，上报给 TVM 社区。

### B 类：生成器 / 翻译器 Bug（需要自行修复）

特征：
- IR 语义不合法（如类型不匹配、形状信息错误）
- 生成的 Python 代码语法错误（SyntaxError、IndentationError）
- 翻译后的代码运行时抛出 ImportError、AttributeError（说明翻译器映射不对）
- TVM 报错信息明显是因为 IR 或翻译器的逻辑错误导致的

**处理**：根据错误信息定位到生成器或翻译器对应逻辑，修复。
**误报过滤**：这些由 BugCollector 自动忽略（不会保存到 `reports/`）。

### C 类：已知限制/误报（不处理）

特征：
- 翻译器中明确标记为 OpNotImplemented 的算子
- 已知的 TVM 功能缺失（不支持的平台、不支持的算子组合）
- 因超时或 OOM 导致的失败

**处理**：跳过，不记录。

## BugCollector 过滤规则

BugCollector 仅在以下条件**全部满足**时保存测试文件到 `reports/`：
1. 执行失败（exitCode != 0）
2. 错误信息**不包含**已知误报关键词（SyntaxError, IndentationError, ImportError, ModuleNotFoundError, AttributeError, OpNotImplemented）

这意味着：
- 生成器/翻译器导致的语法类错误 → 自动过滤 → 开发者修复
- TVM 运行时错误 → 自动保存 → 人工研判是 TVM bug 还是逻辑错误

## 工作流

```
生成 IR → 翻译为 TVM Python → 执行
    ├── 成功 → 继续
    └── 失败
        ├── 已知误报 → 跳过
        ├── 生成器/翻译器 bug → 修复后重跑
        └── 疑似 TVM bug → 保存到 reports/
```

每次修复生成器或翻译器 bug 后，应重新跑 fuzzer 验证修复是否彻底。

## Shape Tracking（2026-07-04 实现）

**状态**：已完成 ✅。所有之前禁用的算子已重新启用。

### 实现方案

在生成器层（`UirGenerator`）维护 `ndimMap: Map<String, Int>`，追踪每个 value 的维度数：

1. **输入 ndim**：可配置 `minInputNdim` / `maxInputNdim`（默认 1-3），随机赋值
2. **算子兼容性**（`isOpCompatibleWithNdims`）：根据可用值的 ndim 集合过滤算子
   - `softmax`/`reshape`/reduce：需要 ndim ≥ 1
   - `transpose`：需要 ndim ≥ 2
   - `concat`：需要至少 2 个同 ndim 的值
   - `matmul`：需要至少 2 个 ndim ≥ 1 的值
   - 元素级二元：需要至少 2 个同 ndim 的值
3. **输入选择**（`selectCompatibleInputs`）：为每个算子选择 ndim 兼容的输入值（避免 0-D tensor 传入 softmax 等问题）
4. **输出 ndim**（`computeOutputNdim`）：算子级别的 ndim 变换规则
   - 元素级：ndim 不变
   - reduce：ndim - 1（保底 0）
   - reshape：始终输出 1-D（因为翻译器使用 `relax.ShapeExpr([-1])`）
   - matmul：1-D @ 1-D = 0-D，1-D @ 2-D = 1-D，2-D @ 2-D = 2-D
5. **翻译器 shape**：使用 `shape=(-1, -1, -1)` 支持最高 3-D 张量的类型标注

### 效果验证

- **200 轮 fuzzing，100% 成功率**（2026-07-04）
- 全部 18 个算子启用并成功通过 TVM 0.25 编译检查：
  `add`, `subtract`, `multiply`, `divide`, `matmul`, `relu`, `sigmoid`, `tanh`, `softmax`, `abs`, `exp`, `log`, `sqrt`, `reshape`, `transpose`, `concat`, `reduce_sum`, `reduce_mean`

---

## 今日修复总结（2026-07-04）

### Shape Tracking 实现
- **全部算子重新启用** — `matmul`, `concat`, `reduce_sum`, `reduce_mean` 不再被禁用
- **生成器 ndim 追踪** — `ndimMap` + `isOpCompatibleWithNdims` + `selectCompatibleInputs` + `computeOutputNdim`
- **生成器 axis 修复** — reduce 类算子使用 `axis=-1` 而非 `inputNdim - 1`，避免 axis 越界
- **翻译器 shape 修复** — `inferInputType` 使用 `shape=(-1, -1, -1)` 而非 `(-1,)`，支持多维度

### 翻译器修复（上午）
1. **`reshape` 双重传参** — 翻译器同时传了 `(-1,)` 和 `shape=-1`
   - 修复：改为 `relax.ShapeExpr([-1])`

### 生成器修复（下午 — ndim tracking 实现过程中发现）
1. **`selectCompatibleInputs` 默认分支不过滤 0-D 输入** — softmax/reshape 等算子会选到 0-D 值
   - 修复：为 `softmax`, `reshape`, `squeeze`, `unsqueeze` 添加 ndim ≥ 1 过滤
2. **`reshape` 输出 ndim 错误** — 生成器认为 reshape 保留 ndim，但翻译器产出 `ShapeExpr([-1])` 总是 1-D
   - 修复：`computeOutputNdim` 中 reshape 返回 1
3. **reduce axis 计算错误** — `axis = ndim - 1` 对 1-D 输入返回 0，但翻译器 shape 是 `(-1,)` (1-D)，axis=0 超范围
   - 修复：改为 `axis = -1`（TVM 会自动规范化）