# aiFuzzer 文档结构

> **更新 2026-07-10**：`LogicGenerator` 已合并到 `UirGenerator`，相关架构文档已更新。

## 文档分类

### 📌 核心文档（保留）

**架构设计**：
- `architecture-v2.md` - 最新架构设计
- `UIR_DESIGN.md` - UIR 中间表示设计
- `semantics.md` - 语义定义

**项目计划**：
- `PROJECT_PLAN.md` - 项目计划
- `PRINCIPLES.md` - 设计原则

**状态追踪**：
- `VIOLATIONS.md` - 违规情况汇总（需要持续更新）
- `IMPLEMENTATION_STATUS.md` - 实施状态报告（最新）

### 📂 需要归档的文档

#### 形状相关（历史方案）

**最新方案**：
- `SHAPE_ADAPTER_IMPLEMENTATION_PLAN.md` - 最终实施方案 ✅

**历史方案**（已过期）：
- `SHAPE_ADAPTER_DESIGN.md` - 第一版设计（已废弃）
- `SHAPE_ADAPTER_REDESIGN.md` - 重新设计（已废弃）
- `SHAPE_AWARE_GENERATION.md` - 约束求解方案（已废弃）
- `WHY_CONSTRAINT_SOLVING.md` - 错误认知的文档（已废弃）
- `TECHNICAL_REFACTOR_PLAN.md` - 重构方案（已过期）
- `shape-consistency-fix.md` - 早期修复记录
- `shape-inference-impl.md` - 形状推导实现
- `shape-semantics.md` - 形状语义
- `unified-shape-inference-report.md` - 统一形状推导报告

#### 其他历史文档

- `architecture.md` - 旧版架构（已被 v2 替代）
- `generator-flow.md` - 生成器流程
- `generator_update_20260704.md` - 生成器更新记录
- `concurrency-fix-guide.md` - 并发修复指南
- `concurrency-issues.md` - 并发问题
- `code_status.md` - 代码状态

#### 提交审查记录

- `commit-review/` 目录下的所有文档

### 📋 功能设计文档（保留）

- `TVM_DAEMON_DESIGN.md` - TVM daemon 设计
- `TRANSLATOR_FUZZER_DESIGN.md` - 翻译器和 fuzzer 设计
- `EXTENSION_DESIGN.md` - 扩展设计
- `tree-design-review.md` - tree 设计审查

---

## 清理计划

### 删除目录

创建 `archive/` 目录，移动所有过期文档：

```
docs/
├── archive/                          # 归档目录
│   ├── shape-history/                # 形状方案历史
│   │   ├── SHAPE_ADAPTER_DESIGN.md
│   │   ├── SHAPE_ADAPTER_REDESIGN.md
│   │   ├── SHAPE_AWARE_GENERATION.md
│   │   ├── WHY_CONSTRAINT_SOLVING.md
│   │   ├── TECHNICAL_REFACTOR_PLAN.md
│   │   ├── shape-consistency-fix.md
│   │   ├── shape-inference-impl.md
│   │   ├── shape-semantics.md
│   │   └── unified-shape-inference-report.md
│   ├── old-architecture/             # 旧架构文档
│   │   └── architecture.md
│   ├── history/                      # 历史记录
│   │   ├── generator-flow.md
│   │   ├── generator_update_20260704.md
│   │   ├── concurrency-fix-guide.md
│   │   ├── concurrency-issues.md
│   │   └── code_status.md
│   └── commit-review/                # 提交审查（保持原位）
│       └── ...
├── core/                             # 核心文档
│   ├── architecture-v2.md
│   ├── UIR_DESIGN.md
│   ├── semantics.md
│   ├── PROJECT_PLAN.md
│   └── PRINCIPLES.md
├── shape/                            # 形状相关
│   ├── SHAPE_ADAPTER_IMPLEMENTATION_PLAN.md
│   └── VIOLATIONS.md
├── features/                         # 功能设计
│   ├── TVM_DAEMON_DESIGN.md
│   ├── TRANSLATOR_FUZZER_DESIGN.md
│   ├── EXTENSION_DESIGN.md
│   └── tree-design-review.md
└── status/                           # 状态追踪
    └── IMPLEMENTATION_STATUS.md
```

### 删除文档

以下文档完全删除（内容已过时或无用）：
- 无（所有文档都有历史价值，归档而非删除）

---

## 执行清理