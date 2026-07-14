# 程序缩减（Program Reduction）模块

基于 DRReduce 论文（arXiv:2605.19412）的依赖重建思想，在 UIR 层面自动缩减触发 bug 的程序。

**核心思路：** aiFuzzer 的 UIR 是计算图 IR，每个节点的 `inputs`/`outputs` 通过 `valueId` 天然编码了 def-use 依赖关系，相当于 DRReduce 论文所说的"零成本依赖图"。

## 模块结构

```
src/main/kotlin/io/github/xyzboom/aiFuzzer/reducer/
├── UirDependencyGraph.kt    # 依赖图：def-use 链构建 + 可达性分析
├── DDMin.kt                 # Delta-Debugging 最小化算法
├── DeadCodeEliminator.kt    # 反向可达性死代码消除
├── DependencyReconstructor.kt # 依赖重建：wire-around / 常量替换 / 级联删除
├── IrDdminReducer.kt        # 核心编排：DCE → Wire-around → DDMin → 验证
├── Reducer.kt               # AutoReducer 入口
└── ReductionResult.kt       # 结果数据结构
```

## 缩减策略

| 阶段 | 方法 | 说明 |
|:---|:---|:---|
| Stage 0 | 死代码消除 | 从 graph.outputs 反向可达，删除不可达节点，零风险 |
| Stage 1 | Wire-around 贪心 | 逐个移除 identity-like 算子（激活函数、形状变换等），直通替换 |
| Stage 2 | DDMin | 在节点列表上运行 Delta Debugging，删除无关算子 |
| Stage 3 | 最终验证 | 检查属性是否保持 |

## 使用方式

### 1. Fuzzing 中自动缩减（YAML 配置）

```yaml
pipeline:
  reducer:
    enabled: true   # 默认 false，不启用缩减
```

启用后，每个 bug 目录会额外保存：
- `minimal_ir.jsonl` — 缩减后的 IR
- `minimal_source.py` — 缩减后重新翻译的源码
- `reduction_summary.txt` — 缩减摘要

原始文件（`source.py`、`ir.jsonl`）始终保留，缩减失败不影响原始数据。

### 2. 从序列化 IR 缩减

```bash
# 缩减单个 IR 文件
./gradlew run --args="--reduce path/to/ir.jsonl"

# 缩减目录下所有 IR 文件
./gradlew run --args="--reduce path/to/bug_dir/"

# 指定输出目录
./gradlew run --args="--reduce path/to/ir.jsonl --reduce-output /tmp/out"
```

输出文件：
- `ir_minimal.jsonl` — 缩减后的 IR
- `ir_reduction_summary.txt` — 缩减摘要

### 3. 编程使用

```kotlin
val reducer = AutoReducer()
val jsonl = File("bug_001/ir.jsonl").readText()
val result = reducer.reduceFromJsonl(jsonl, myPropertyChecker)

if (result.propertyPreserved) {
    println("缩减率: ${"%.1f".format(result.reductionRatio * 100)}%")
}
```

## 兼容性说明

- `BugCollector.collect()` 签名不变，返回 `File`（bug 目录）
- `FuzzingConfig.reducerConfig` 默认 `null`，不启用缩减
- `PipelineConfig` 新增 `reducer.enabled` 字段，默认 `false`
- 所有现有 YAML 配置无需修改