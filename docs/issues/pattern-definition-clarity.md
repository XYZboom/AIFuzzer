---
title: "[spec] Pattern 定义需要明确：多节点数据流不校验、match 死字段、值匹配语义未文档化"
labels: []
assignees: []
---

## 背景

当前 pattern 系统（`tree/src/.../pattern/`，`format_version: "1.0"`）存在若干定义不明确的地方。其中多节点 pattern 缺少数据流连通性校验，可能产生假阳性匹配，建议优先处理。

## 问题清单

### 1. 多节点 pattern 无数据流连通性验证（最严重，疑似假阳性源）

现状：`nodes` 多节点 pattern 靠前缀树（Aho-Corasick 风格）按**生成顺序**匹配，每个节点的 value 约束按**位置**独立校验，但**从不检查 n0 的输出 valueId 是否等于 n1 的输入 valueId**。`flowConstraints` 字段已存在，但内置 27 个 pattern 中没有一个使用它（`onnx-8203`、`onnx-8204`、`pt-189787`、`tvm-20061` 均为多节点且 flowConstraints 为空）。

后果：多节点 pattern 可能匹配到"恰好按顺序生成但实际不连通"的节点序列 → 假阳性 → dedup 误触发（把不相关的程序当 bug 复现）。

建议：节点间 value 连通性（`pattern.nodes[i].outputs` 与 `pattern.nodes[j].inputs` 的 valueId 引用关系）应当**内置校验**，而不是依赖各 pattern 手动写 `flowConstraints`。参考 `PatternMatcher.checkFlowConstraints` 已有逻辑，可将其提升为默认行为。

### 2. `"match"` 字段是死代码

所有 JSON 的 node/value 都带 `"match": "node"` / `"match": "value"`，但 `PatternParser` 用 `ignoreUnknownKeys = true` 完全忽略，不读取、不校验。

建议：要么彻底删除该字段并清理所有 JSON，要么解析时校验其取值，二选一消除歧义。

### 3. 值匹配语义（按位置 vs 按 ID）未文档化

`PatternMatcher` 注释声明"按位置匹配"，但实际是 hybrid：先在 `pattern.nodes` 里定位 valueId 出现的位置（第 pNode 的第 i 个 input/output），对应到实际 `nodes[pNode]` 的对应槽位；找不到时 fallback 到 `valueResolver(valueId)`。

后果：新写 pattern 的人易误以为 valueId 是"按 ID 匹配"，写出实际不生效的约束。

建议：把该语义写成正式 spec（见问题 5），并统一注释与行为。

### 4. `target` / `frontend` 为 null 的语义未写明

`PatternDatabase.filter()`：`(p.target == null || p.target == effectiveTarget)` → pattern 未声明 target 时匹配所有 target。行为合理，但建议在 spec 中显式写明。

### 5. 缺少 pattern 格式正式 spec

无版本演进策略：`format_version` 硬编码默认 "1.0"，无迁移路径。若修复问题 1/2 改变格式语义，需要版本感知的解析。

建议：新增 `PATTERN_SPEC.md`，明确：
- 字段字典（含 `match` 字段的去留）
- 节点匹配语义（按位置、生成顺序、前缀剪枝）
- value 引用与数据流校验规则（问题 1 的默认行为）
- `target`/`frontend` null 语义
- DimMatcher / AttrMatcher / ExpressionConstraint 语法
- `format_version` 演进策略

## 附：现有能力盘点（供 spec 参考）

- `DimMatcher`：`$eq` `$ne` `$gt` `$gte` `$lt` `$lte` `$in` `$mod` `$any` `$pow2`，多键自动 AND
- `AttrMatcher`：`$eq`（int/string/int-list）`$in` `$ne`（int/int-list），裸值/裸数组
- `DtypeMatcher`：精确 / `$in`
- `ExpressionConstraint`：`dimIndices` + `op`(mul/add/sub/mod) + `allowedValues` + `divisors` + `excludeWhen`
- `GraphConstraints`：`minNodes` `maxNodes` `requiredOps`（存在任一即满足）
- `FlowConstraint`：`fromNode`/`fromOutput`/`toNode`/`toInput`

## 备注

仅调研与建议，未改任何代码。涉及源码改动（如默认数据流校验）需另行评估对现有 pattern 命中率的影响；`configs/` 外部 pattern 工作区不在此讨论范围。