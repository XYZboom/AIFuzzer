# aiFuzzer 文档索引

## 有效文档

### 架构/设计

| 文档 | 说明 |
|------|------|
| [`pytorch_backend_design.md`](pytorch_backend_design.md) | PyTorch daemon 后端设计 |
| [`features/TVM_DAEMON_DESIGN.md`](features/TVM_DAEMON_DESIGN.md) | TVM daemon 设计 |
| [`features/EXTENSION_DESIGN.md`](features/EXTENSION_DESIGN.md) | 扩展设计 |
| [`features/TRANSLATOR_FUZZER_DESIGN.md`](features/TRANSLATOR_FUZZER_DESIGN.md) | 翻译器和 fuzzer 设计 |

### 模块说明

| 文档 | 说明 |
|------|------|
| [`reducer.md`](reducer.md) | 程序缩减模块 |
| [`LOGGING.md`](LOGGING.md) | 日志系统 |

### 归档文档（历史参考）

以下文档已移入 [`archive/`](archive/)，不再更新但保留历史参考价值：

- `bug_fixes_20260711.md` — 修复记录
- `eval.md` — 评估记录
- `failFast.md` — fail-fast 设计笔记
- `FINAL_SUMMARY.md` — 旧阶段总结
- `generator-shape-bugs.md` — 生成器形状 bug 分析
- `REDESIGN_PLAN.md` — 旧重构计划
- `semantic-testing-plan.md` — 语义测试方案
- `ShapeAdapter.md` — 历史形状适配方案
- `shape_assertion.md` — 形状断言笔记

归档目录还包含 `history/`（并发修复、生成器流程等）和 `old-architecture/`（旧版架构）、`shape-history/`（形状方案历史）。

## 代码文档

主入口 `App.kt` 有嵌入式 CLI 帮助。配置文件示例见 `configs/` 目录。
