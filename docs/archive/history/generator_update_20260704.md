# AiFuzzer 项目更新记录 (2026-07-04)

## 生成器丰富度评估报告（已纳入 PROJECT_PLAN.md）

详见 `docs/PROJECT_PLAN.md` 中"生成器丰富度评估报告"章节。

## 本次改进内容

### 1. DefaultOps 补全
- DefaultOps 从 24 个算子扩展到 **38 个算子**
- 新增算子类别：
  - 索引/切片：`gather`, `strided_slice`
  - 广播：`broadcast_to`, `tile`
  - 类型转换：`cast`
  - 常数生成：`arange`, `full`, `ones`, `zeros`
  - 三角：`tril`, `triu`
- 注意：`conv2d`, `max_pool2d`, `avg_pool2d`, `batch_norm`, `layer_norm`, `pad` 保留在 translator 映射表中但未加入 DefaultOps（它们需要多输入或复杂类型约束，不适合当前 ndim-only 生成器）

### 2. 引入分支拓扑
- 添加 `branchProbability` 配置参数（默认 0.3）
- 引入 `liveTips` 活跃路径追踪机制
- 支持 fork-join：不同路径的 tip 可作为同一个算子的输入
- 支持偶尔创建新分支

### 3. 丰富 Attribute 生成
- 为 conv2d: kernel_size, strides, padding 随机化
- 为 max_pool2d/avg_pool2d: pool_size, strides, padding 随机化
- 为 pad: pad_width 随机化
- 为 gather: axis 随机化
- 为 squeeze/unsqueeze: axis 随机化
- 为 strided_slice: begin, end, strides 随机化
- 为 arange: start, stop 随机化

### 4. Translator 修复
- `TensorStructInfo(shape=...)` 改为 `TensorStructInfo(shape=relax.ShapeExpr([...]))` (TVM 0.25 API 变更)
- 为每个算子单独的 emit 分支（add/subtract/multiply/divide/tril/triu 等），避免通用 fallback
- `permute_dims` 不再传递 int 类型的 axes
- `expand_dims` 固定 axis=0 避免重复参数
- 修复常数生成算子（zeros/ones/full）的空输入处理

### 5. 测试结果对比

| 测试 | 成功率 | 说明 |
|------|--------|------|
| 改进前（20轮，所有算子） | 0% | 全部 TYPE_ERROR（TensorStructInfo API 不兼容） |
| 改进后（30轮，仅可靠算子） | 100% | relu, sigmoid, tanh, add, subtract, multiply |
| 改进后（30轮，全部 38 算子） | 43.3% | 仍有 17/30 失败 |

### 6. 剩余问题

- `reduce` 类算子 axis 有时超出范围：生成器 axis 生成需要更精确的 ndim 感知
- `arange`/`zeros`/`ones`/`full` 的 shape/dtype 需要更丰富的参数
- `topk` 多输出算子未接入 DefaultOps
- 分支拓扑尚未充分验证（branchProbability 在较小图结构中效果有限）