# aiFuzzer 文档索引

**最后更新**：2026-07-10

---

## 📌 核心文档

### 架构设计
- [**architecture-v2.md**](core/architecture-v2.md) - 最新架构设计
- [**UIR_DESIGN.md**](core/UIR_DESIGN.md) - UIR 中间表示设计
- [**semantics.md**](core/semantics.md) - 语义定义

### 项目管理
- [**PROJECT_PLAN.md**](core/PROJECT_PLAN.md) - 项目计划
- [**PRINCIPLES.md**](core/PRINCIPLES.md) - 设计原则

---

## 🔧 形状适配

### 当前方案
- [**SHAPE_ADAPTER_IMPLEMENTATION_PLAN.md**](shape/SHAPE_ADAPTER_IMPLEMENTATION_PLAN.md) - 最终实施方案 ✅
- [**VIOLATIONS.md**](shape/VIOLATIONS.md) - 违规情况汇总（持续更新）

### 历史方案
已归档到 `archive/shape-history/`，包括：
- 约束求解方案（已废弃）
- 第一版设计（已废弃）
- 重新设计（已废弃）

---

## 🎯 功能设计

- [**TVM_DAEMON_DESIGN.md**](features/TVM_DAEMON_DESIGN.md) - TVM daemon 设计
- [**TRANSLATOR_FUZZER_DESIGN.md**](features/TRANSLATOR_FUZZER_DESIGN.md) - 翻译器和 fuzzer 设计
- [**EXTENSION_DESIGN.md**](features/EXTENSION_DESIGN.md) - 扩展设计
- [**tree-design-review.md**](features/tree-design-review.md) - tree 设计审查

---

## 📊 状态追踪

- [**IMPLEMENTATION_STATUS.md**](status/IMPLEMENTATION_STATUS.md) - 实施状态报告（最新）

---

## 📚 归档文档

### 形状方案历史
`archive/shape-history/` - 包含所有废弃的形状方案

### 旧架构
`archive/old-architecture/` - 旧版架构文档

### 历史记录
`archive/history/` - 历史问题和修复记录

### 提交审查
`commit-review/` - Git 提交审查记录

---

## 🔍 快速导航

### 我想了解...

- **项目架构** → [architecture-v2.md](core/architecture-v2.md)
- **UIR 设计** → [UIR_DESIGN.md](core/UIR_DESIGN.md)
- **形状问题现状** → [VIOLATIONS.md](shape/VIOLATIONS.md)
- **形状适配方案** → [SHAPE_ADAPTER_IMPLEMENTATION_PLAN.md](shape/SHAPE_ADAPTER_IMPLEMENTATION_PLAN.md)
- **实施进度** → [IMPLEMENTATION_STATUS.md](status/IMPLEMENTATION_STATUS.md)

---

## 📝 文档维护指南

### 添加新文档

1. **核心文档** → 放在 `core/`
2. **功能设计** → 放在 `features/`
3. **状态追踪** → 放在 `status/`
4. **历史方案** → 归档到 `archive/`

### 文档命名规范

- 使用大写加下划线：`FEATURE_NAME.md`
- 描述性名称：`IMPLEMENTATION_PLAN.md`
- 包含日期：`UPDATE_20260710.md`

### 归档规则

当文档过期时：
1. 移动到相应的 `archive/` 子目录
2. 在文档顶部添加"已归档"标记
3. 更新本索引

---

**维护者**：Claw
**创建日期**：2026-07-10