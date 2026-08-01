# 生成时 Pattern 去重（Dedup）

## 原理

### 核心思想

在**程序生成阶段**实时检测 IR 是否匹配已知 bug 的触发模式（pattern），若匹配则重新生成（重试 + 更换算子），从而在生成层面**直接避免**产出已知 bug 的程序。

### Pattern 定义

每个已知 bug 用一个 JSON pattern 描述，包含算子类型、属性、输入 shape 约束。例如：

```json
{
  "id": "tvm-20047",
  "compiler": "tvm",
  "target": "cuda",
  "description": "conv2d dlight gemv FloorDiv: kernel=[1,1,1,2], W∈{4,6}, H≥6",
  "severity": "crash",
  "nodes": [{
    "match": "node", "id": "n0", "op": "CONV2D",
    "inputs": ["v_input", "v_weight"],
    "outputs": ["v_out"],
    "attrs": {"stride": 1, "padding": 0, "dilation": 1, "groups": 1}
  }],
  "values": [
    {
      "match": "value", "id": "v_input",
      "ndim": 4,
      "shape": [{"$eq": 1}, {"$eq": 1}, {"$gte": 6}, {"$in": [4, 6]}],
      "dtype": "float32"
    },
    {
      "match": "value", "id": "v_weight",
      "ndim": 4,
      "shape": [{"$eq": 1}, {"$eq": 1}, {"$eq": 1}, {"$eq": 2}]
    }
  ]
}
```

Shape 约束支持的操作符（DimMatcher）：

| 操作符 | 含义 |
|--------|------|
| `$eq` | 等于 |
| `$ne` | 不等于 |
| `$gte` | 大于等于 |
| `$gt` | 大于 |
| `$lt` | 小于 |
| `$lte` | 小于等于 |
| `$in` | 枚举值列表 |
| `$mod` | 模数匹配（如 `{"$mod": 2}` 偶数，`{"$mod": {"d": 2, "r": 1}}` 奇数） |
| `$any` | 任意值 |

**复合约束**：可以用 `And` 组合多个操作符，如 `{"$gte": 5, "$ne": 6}` 表示 ≥5 且 ≠6。

### 生成时去重流程

```
UirGenerator.generate()
  └─ 每生成一个节点 →
       PatternMatcher.onNodeGenerated(node)
         ├─ 不匹配 → 接受，继续
         └─ 匹配某个 pattern →
              1. 记录匹配计数（matchCountByPattern）
              2. 将当前算子加入黑名单（dedupBlockedOps）
              3. 回退此节点，重试（更换算子/形状）
              4. 最多重试 maxRetries=10 次
              5. 耗尽后仍匹配 → 接受（兜底，不阻塞生成）
```

#### 关键设计

- **节点级黑名单**：当前节点重试结束后清空，不影响后续节点
- **跨目标差分模式**下 target 设为 `null`，不过滤 target
- 多线程环境每个 worker 有独立的 `UirGenerator` 实例

### 配置

```yaml
pipeline:
  dedup:
    enabled: true
    pattern_dir: "configs/patterns-tvm-cuda.json"  # pattern 数据库文件或目录
    compiler: "tvm"
    target: "cuda"
```

---

## 评估方法

### dedup-eval 子命令

```bash
aifuzzer dedup-eval --config configs/tvm-cuda-dedup-eval.yaml [-n 200] [--seed 1]
```

对**同一个种子**并行生成两个程序：

| 生成方式 | 说明 |
|---|---|
| **No-dedup 程序** | 不启用 pattern 去重，正常生成 |
| **Dedup 程序** | 启用 pattern 去重，匹配则重试 |

### 筛选执行

仅当 dedup 触发了重试（`dedupPreventedCount > 0`，即两个程序**不同**）时，才分别提交后端执行；否则跳过（skipped）。

### 结果四分类

| 分类 | no-dedup 程序 | dedup 程序 | 含义 |
|---|---|---|---|
| **Bug prevented** ✅ | 触发 bug | **不触发 bug** | Dedup 成功阻止了已知 bug |
| **Dedup-only fail** ⚠️ | 不触发 bug | **触发 bug** | 仅 dedup 程序触发了 bug，需进一步分析：<br>• 新 bug → dedup 附带发现了新 bug<br>• 已知变体 → pattern 需调整<br>• 生成不合理 → 真正 pattern 过宽 |
| **Both failed** | 触发 bug | 触发 bug | 两者均触发 bug（可能不同 bug） |
| **Both succeeded** | 不触发 bug | 不触发 bug | **pattern 过宽**。no-dedup 和 dedup 都不触发 bug，说明 pattern 匹配了不会触发的合法节点。需检查 no-dedup IR 找出匹配节点，收紧 pattern 约束。 |

### 核心指标

```
Trigger rate       = collected / total          (dedup 触发率)
Prevent rate       = bugPrevented / collected   (bug 规避率)
Dedup-only fail    = dedupOnlyFail / collected  (仅 dedup 失败率)
```

> **注意**：`dedupOnlyFail` 不能直接归为假阳性。dedup 程序走了不同的生成路径，可能触发了全新的 bug。必须先分析 dedup 程序的错误签名，区分是新 bug、已知变体、还是 pattern 过宽。

### 报告解读示例

```text
Total seeds:        2000
Collected:          18 (0.9%)    ← dedup触发了重试
Skipped:            1982 (99.1%) ← dedup未触发
Bug prevented:      5 (27.8%)    ← no-dedup触发bug，dedup没触发
Dedup-only fail:    0            ← 仅dedup触发bug
Both failed:        0            ← 两者都触发bug
Both succeeded:     13           ← 两者都不触发bug
```

### FP 分析

当分类为 **Both succeeded**（no-dedup 和 dedup 都不触发 bug，但 dedup 触发了重试导致程序不同）时，如果 no-dedup 程序的可打印摘要非空，自动保存其完整 IR 到 `reports/fp-analysis/fp_seed{seed}.jsonl`，用于离线分析 pattern 是否过宽。

> **注意**：Dedup-only fail（仅 dedup 程序触发 bug）不自动保存 IR，需通过分析 dedup 程序的错误签名来判断是新 bug 还是 pattern 问题。

### 验证流程

1. 跑 `dedup-eval`，得到 no-dedup vs dedup 的对比
2. 如果 Bug prevented > 0 → pattern 有效
3. 如果 Dedup-only fail > 0 → 检查 dedup 程序的错误签名，判断是新 bug 还是 pattern 问题
4. 如果 Both succeeded → 检查 saved no-dedup IR，确认是否真的匹配了 pattern，再收紧
5. 如果 Both failed → 正常现象（如 buffer_red 类 bug），需要更多 pattern 覆盖其他触发路径
6. 全量拦截效果用 `fuzz --seed-file` 评估，不是 `dedup-eval`