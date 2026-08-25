# Pattern 系统技术文档

**最后更新**：2026-08-25

---

## 概述

Pattern 系统是 aiFuzzer **生成时去重（generation-time deduplication）** 的核心。每个已知编译器 bug 用一个 pattern 描述其最小触发条件（算子结构 + shape/dtype 约束），在 IR 程序生成过程中逐节点匹配，命中即重试生成，从而在生成层面直接避免产出已知 bug 的程序。

**代码位置**：`tree/src/io/github/xyzboom/aiFuzzer/pattern/`

| 文件 | 职责 |
|:-----|:-----|
| `PatternDef.kt` | 数据结构定义（DimMatcher / AttrMatcher / DtypeMatcher / PatternNodeDef / PatternValueDef / PatternDef / PatternDatabase / ExpressionConstraint / FlowConstraint / GraphConstraints） |
| `PatternParser.kt` | JSON → 数据结构（kotlinx-serialization） |
| `PatternMatcher.kt` | 增量前缀树匹配器 |
| `ValueRange.kt` | 值域分析支持 |

**Pattern 数据位置**：`src/main/resources/patterns/*.json`（内置，21 个文件，共 41 个 pattern：TVM 34 / PyTorch 5 / ONNX 2）。

---

## 核心概念

一个 pattern 是对编译器 IR 中一个**特定子图**的规格描述，分两层：

- **节点层（nodes）**：匹配算子类型（op）与属性（attrs）。回答"哪些算子、按什么顺序、什么属性"。
- **值层（values）**：匹配算子的输入/输出张量。回答"这些张量的形状、维度数、dtype 是什么"。

两层都满足才算完整匹配。单节点 pattern 描述"一个特定算子 + 特定值的形状"；多节点 pattern 描述"一串相连的算子"。

**与正则表达式/完整程序的区别**：pattern 不是对整个程序的描述，而是对触发 bug 的**最小子图**的描述。生成器遇到匹配时只丢弃/重试当前的图，不影响全局。

---

## Pattern 格式（JSON DSL）

顶层结构：

```json
{
  "format_version": "1.0",
  "patterns": [ ... ]
}
```

### Pattern 字段

| 字段 | 类型 | 必需 | 说明 |
|:-----|:-----|:----:|:-----|
| `id` | string | ✅ | 唯一标识，如 `tvm-20015-variant-silu-3d` |
| `compiler` | string | ✅ | `tvm` / `pytorch` / `onnx` |
| `target` | string | ❌ | `cuda` / `llvm`；null = 匹配所有 target |
| `frontend` | string | ❌ | `relax` / `pytorch` / `onnx`；null = 匹配所有前端 |
| `description` | string | ❌ | 人类可读描述 |
| `severity` | string | ❌ | `crash` / `silent_correctness` / `error` |
| `nodes` | node[] | ✅ | 节点定义列表（≥1） |
| `values` | value[] | ❌ | 值定义列表（可空） |
| `graphConstraints` | object | ❌ | 图级约束 |
| `flowConstraints` | object[] | ❌ | 数据流约束 |

### 节点（nodes[]）

```json
{
  "id": "n0",
  "op": "CONV2D",
  "inputs": ["v_input", "v_weight"],
  "outputs": ["v_out"],
  "attrs": {"stride": 1, "padding": 0, "dilation": 1, "groups": 1}
}
```

| 字段 | 说明 |
|:-----|:-----|
| `id` | 节点在 pattern 内的标识（`n0`, `n1`, ...） |
| `op` | 精确匹配的算子类型名 |
| `inputs` / `outputs` | 引用值 ID 列表；**长度必须与实际节点一致**（只匹配数量，值连接见 flowConstraints） |
| `attrs` | 属性匹配器；只写需要约束的属性，未写的属性不检查 |

### 值（values[]）

```json
{
  "id": "v_input",
  "ndim": 4,
  "shape": [{"$eq": 1}, {"$in": [1, 4]}, {"$gte": 6, "$mod": {"d": 2, "r": 0}}, {"$eq": 3}],
  "dtype": "float32",
  "expressionConstraints": [...],
  "range": {...}
}
```

| 字段 | 说明 |
|:-----|:-----|
| `id` | 值在 pattern 内的标识（`v_input`, `v_out`, ...） |
| `ndim` | 维度数匹配器 |
| `shape` | 逐维 DimMatcher 列表（长度可以小于实际 ndim，未写的维不约束） |
| `dtype` | dtype 匹配器 |
| `expressionConstraints` | 跨维度表达式约束 |
| `range` | 值域匹配（需启用 valueRangeAnalysis） |

### 维度匹配器（DimMatcher）

| JSON 写法 | 含义 |
|-----------|------|
| `42` | 精确等于 42 |
| `{"$eq": N}` | 等于 N |
| `{"$ne": N}` | 不等于 N |
| `{"$gt": N}` | 大于 N |
| `{"$gte": N}` | 大于等于 N |
| `{"$lt": N}` | 小于 N |
| `{"$lte": N}` | 小于等于 N |
| `{"$in": [A, B]}` | 在列表中 |
| `{"$mod": D}` | 能被 D 整除（余数 0） |
| `{"$mod": {"d": D, "r": R}}` | 模 D 余 R |
| `{"$pow2": true}` | 是 2 的幂（false = 不是 2 的幂） |
| `{}` 或 `{"$any": true}` | 任意值（通配符） |

**复合条件（AND）**：同一对象中的多个匹配键自动组合，如 `{"$gte": 6, "$mod": {"d": 2, "r": 0}}` = ≥6 且偶数。

### 属性匹配器（AttrMatcher）

| JSON 写法 | 含义 |
|-----------|------|
| `2` | 精确整数 2 |
| `"cuda"` | 精确字符串 |
| `[2, 2]` | 精确整数列表 |
| `{"$eq": N}` | 等于 N（int/string/list） |
| `{"$in": [...]}` | 在字符串列表中 |
| `{"$ne": N}` | 不等于 N（int 或 int list） |

### 跨维度表达式约束（ExpressionConstraint）

对 shape 的多个维度做运算后检查结果，弥补单维 DimMatcher 无法表达跨维关系的缺陷：

```json
{
  "dimIndices": [1, 2],
  "op": "mul",
  "allowedValues": [10, 14],
  "divisors": [1, 1],
  "excludeWhen": [{"dimIndices": [0], "op": "mul", "allowedValues": [2]}]
}
```

| 字段 | 说明 |
|:-----|:-----|
| `dimIndices` | 参与运算的维度索引（0-based） |
| `op` | `mul`（乘积）/ `add`（求和）/ `sub`（差值）/ `mod`（取模） |
| `allowedValues` | 允许的运算结果集合 |
| `divisors` | 可选，每维先整除对应值再运算（与 dimIndices 一一对应） |
| `excludeWhen` | 可选，任一子约束命中则父约束不匹配（排除法） |

示例：`dimIndices=[1,2], op="mul", allowedValues=[10,14]` = `C×W ∈ {10,14}`。

### 图级约束（GraphConstraints）

```json
"graphConstraints": {
  "minNodes": 4,
  "maxNodes": 12,
  "requiredOps": ["CONV2D"]
}
```

| 字段 | 语义 |
|:-----|:-----|
| `minNodes` / `maxNodes` | 匹配时整个图至少/至多达到的节点数 |
| `requiredOps` | 图中必须出现**至少一个**列出的 op（任一满足即通过） |

### 数据流约束（FlowConstraint）

验证 pattern 中两个节点之间的值确实连通：

```json
"flowConstraints": [
  {"fromNode": "n0", "fromOutput": 0, "toNode": "n1", "toInput": 0}
]
```

语义：`n0` 的第 0 个输出值的 valueId 必须等于 `n1` 的第 0 个输入值的 valueId。

> ⚠️ 当前内置 pattern 均未使用 flowConstraints。多节点 pattern 的节点间连通性**目前不默认校验**，属已知缺口，见文末"已知问题"。

---

## 匹配算法（PatternMatcher）

### 总体策略：增量前缀树（Aho-Corasick 风格）

程序一边生成一边匹配——每生成一个节点调用一次 `onNodeGenerated()`，不依赖全图扫描。维护一个"活跃前缀"集合（已部分匹配的 pattern + 已匹配节点列表），新节点到来时：

1. **继续前缀**：对每个活跃 pattern，检查新节点是否匹配其下一个节点；若到达末节点则做完整校验（值约束 + 图级约束 + 数据流约束），全部通过才触发。
2. **启动新前缀**：对新节点匹配所有多节点 pattern 的首节点，启动新的前缀追踪。
3. **单节点 pattern**：独立路径，直接检查 `singleOpPatterns[node.op]`。

匹配节点（`matchNode`）规则：

- 只匹配 op 类型，不匹配名称；
- 属性按需匹配（未在 pattern 中列出的属性不检查）；
- input/output **数量**必须一致；
- 只有完整匹配（所有节点 + 所有值约束）才触发，前缀匹配只用于剪枝。

### 值匹配语义：按位置匹配

值约束**按位置**匹配，不按值 ID 匹配：

- pattern 中节点 `n0` 的第 \(i\) 个 input → 对应实际 `nodes[0]` 的第 \(i\) 个 input；
- pattern 中节点 `n0` 的第 \(j\) 个 output → 对应实际 `nodes[0]` 的第 \(j\) 个 output；
- `valueId` 只是 pattern 内部引用的键，不参与实际匹配；
- 查找 valueId 时先在 pattern 节点中定位其出现的位置，再从实际节点的对应槽位取值；找不到时回退到 `valueResolver(valueId)`。

这是因为生成时值 ID 是动态分配的，每次生成都不同，按位置匹配才能与生成器解耦。

### 增量维护的图级状态

匹配器内部用 O(1) 增量维护：当前图节点数（`currentNodeCount`）、已出现过的 op 集合（`seenOps`），用于 `graphConstraints` 的快速检查。

### 匹配触发后（去重流程）

命中 pattern 后由生成器处理（见 `docs/dedup.md`）：该算子加入临时黑名单 → 重试生成（最多 `maxRetries` 次）→ 仍匹配则接受并告警。

---

## Pattern 加载与配置

### pattern 来源与模式

启动时通过 `loadPatternDatabase()` 加载，三种模式（`pipeline.dedup.pattern_mode`）：

| 模式 | 含义 |
|:-----|:-----|
| `builtin`（默认） | 仅加载 `src/main/resources/patterns/` 内置资源 |
| `custom` | 仅加载 `pattern_dir` 指定的外部 pattern |
| `both` | 两者都加载 |

配置了 `pattern_dir` 但未指定模式时，自动切换到 `both`。

```yaml
pipeline:
  dedup:
    enabled: true
    mode: fuzz 或 dedup-eval
    pattern_mode: builtin      # builtin / custom / both
    pattern_dir: "configs/patterns-tvm-cuda.json"   # 外部文件或目录
    compiler: "tvm"
    target: "cuda"
    frontend: null              # null = any
```

### 筛选逻辑（PatternDatabase.filter）

按 `compiler` / `target` / `frontend` 筛选加载的 pattern：

- `compiler` 必须精确等于；
- `target`：pattern 未声明 target 或声明值等于当前 target 才保留（null = 匹配所有）；
- `frontend`：同上。

### 内置 pattern 统计（2026-08-25）

| compiler | 数量 | 典型用例 |
|:---------|:----:|:---------|
| tvm | 34 | conv2d/pool/zoo/silu dlight 崩溃、frontend min-dim 等 |
| pytorch | 5 | inductor 相关崩溃 |
| onnx | 2 | 8 节点多算子（norm 化简） |

---

## 开发指南

### 新增一个 pattern

1. **分析 bug 精确触发条件**：用缩减后的 minimal IR 确认触发节点的 op、属性、输入 shape 范围、dtype。
2. **编写 JSON**：新建文件放 `src/main/resources/patterns/`（内置）或 `configs/`（外部工作区，不提交回内置）。
3. **验证**：运行 `dedup-eval` 看能否阻止该 bug（BP 分类）；检查 BS 数量是否异常增多（pattern 过宽）。
4. **收紧**：BS 高说明 pattern 过宽，增加维度约束 / `$ne` 排除 / expressionConstraints。

### 调试开关

`-Dpattern.debug=true` 打开 pattern 解析与匹配的详细日志（表达式约束求值中间态等）。

---

## 已知问题与改进方向

详见 [`docs/issues/pattern-definition-clarity.md`](issues/pattern-definition-clarity.md)，要点：

1. **多节点 pattern 无数据流连通性默认校验**（最严重，疑似假阳性源）：nodes 间 valueId 连通性不检查，`flowConstraints` 存在但内置 pattern 无一个使用。
2. **`"match"` 字段是死代码**：JSON 中节点的 `"match": "node"` / `"match": "value"` 被解析器完全忽略。
3. **值匹配 hybrid 语义未文档化**：按位置为主、valueResolver 兜底。
4. **无正式版本演进策略**：`format_version` 硬编码默认 "1.0"。