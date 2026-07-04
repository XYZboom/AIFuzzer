# Project Principles & Bug Classification

> 核心共识（2026-07-04 确立）

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

## 已知限制 / TODO

以下算子/特性暂时禁用，需要 IR 层实现 **shape 追踪** 后才重新启用：

| 算子/特性 | 禁用原因 | 解锁条件 |
|-----------|----------|----------|
| `concat` | 无法保证所有输入 tensor 具有相同 ndim | IR 层记录每个 value 的 rank |
| `matmul` | 1-D @ 1-D 产生 0-D，下游算子（如 softmax）不接受 0-D 输入 | IR 层记录 shape 和 ndim 变化 |
| `reduce_sum` / `reduce_mean` | 降低维度后的输出 ndim 未知 | IR 层记录 reduce 后的 shape |
| `max` / `min` | 同 reduce 类问题 | |

Shape tracking 是优先度最高的子项目。只有 IR 层正确记录 ndim / shape 信息后，生成器才能避免产生 shape-incompatible 的组合。

## 今日修复总结（2026-07-04）

### 翻译器修复
1. **`reshape` 双重传参** — 翻译器同时传了 `(-1,)` 和 `shape=-1`（TypeError: multiple values for 'shape'）
   - 修复：改为 `relax.ShapeExpr([-1])`
2. **`concat` ndim 不一致** — 翻译器把 reduce 后的 0-D 和 1-D tensor 一起传给 concat
   - 修复：暂时禁用 concat（等 shape tracking）

### 生成器修复
1. **reduce 算子产生 0-D 输出** — `mean` / `sum` 等降低维度，IR 未记录
   - 修复：暂时禁用 `reduce_sum`、`reduce_mean`、`max`、`min`
2. **`matmul` 产生 0-D 输出导致 softmax 失败** — `matmul(1-D, 1-D) → 标量`，0-D 上 softmax axis=-1 越界
   - 修复：暂时禁用 `matmul`（等 shape tracking）