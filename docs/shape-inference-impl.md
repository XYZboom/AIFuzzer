# 统一形状推导实现记录

> 日期: 2026-07-09
> 状态: 已完成核心实现

---

## 一、已完成的工作

### 1.1 新增文件

| 文件 | 功能 |
|------|------|
| `src/main/kotlin/io/github/xyzboom/aiFuzzer/infer/ShapeInferer.kt` | 统一形状推导器（所有算子的形状语义） |
| `src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/LogicGenerator.kt` | 逻辑图生成器（只负责 DAG 拓扑和算子依赖） |
| `src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/ShapeAdapter.kt` | 形状适配器（调用 ShapeInferer 填充形状） |
| `src/test/kotlin/io/github/xyzboom/aiFuzzer/infer/ShapeInfererTest.kt` | 形状推导单元测试 |
| `docs/shape-semantics.md` | 形状语义规范文档 |

### 1.2 修改的文件

| 文件 | 修改内容 |
|------|---------|
| `src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/UirGenerator.kt` | 使用两阶段架构（LogicGenerator + ShapeAdapter） |
| `tree/src/io/github/xyzboom/aiFuzzer/ir/serialize/UirSerializer.kt` | 修复 op 和 ndim 序列化（op: UirOpKind，ndim 从 shape 获取） |
| `docs/shape-semantics.md` | 添加注意事项、禁止事项、迁移路径 |

---

## 二、核心设计原则

### 2.1 单一源头原则

**所有形状推导逻辑只在 `ShapeInferer` 中实现**：

```
LogicGenerator（逻辑图）
       ↓
ShapeAdapter（调用 ShapeInferer）
       ↓
UirProgram（含完整形状）
       ↓
Translator（直接读取形状，不再推导）
```

### 2.2 禁止硬编码

**严格遵守**：

1. ❌ 禁止硬编码维度值（如 `dim = 16`）
   - ✅ 使用随机值：`rand.nextInt(1, 129)`
   
2. ❌ 禁止硬编码 ndim（如 `outputNdim = 2`）
   - ✅ 从输入形状计算：`shape1.dims.size - 2 + shape2.dims.size - 2 + 2`
   
3. ❌ 禁止生成器与翻译器各算一遍
   - ✅ 统一使用 `ShapeInferer.inferShape()`
   
4. ❌ 禁止 clamp 到固定范围（如 `ndim.coerceIn(1, 4)`）
   - ✅ 让编译器决定合法范围

### 2.3 语义驱动

每个算子的形状推导规则参考权威规范：
- TVM Relax: `relax.op.*` 文档
- ONNX: onnx.ai/operators
- PyTorch: pytorch.org/docs

---

## 三、架构变更

### 3.1 旧架构

```
UirGenerator
  ├── 直接生成带 ndim 的逻辑图
  ├── computeOutputNdim() — 生成器自己算形状
  └── isOpCompatibleWithNdims() — 生成器硬编码约束

Translator
  ├── ndimMap — 翻译器自己算形状
  ├── computeStaticOutputNdim() — 与生成器重复
  └── clamp axis — 补偿不一致

问题：
  - 两套形状逻辑不一致
  - 硬编码 ndim 约束
  - 51/2000 失败率
```

### 3.2 新架构

```
LogicGenerator
  └── 只生成 op、inputs、outputs（无形状）

ShapeAdapter
  ├── 为图输入分配随机形状
  ├── 调用 ShapeInferer.inferGraphShapes()
  └── 填充每个 ValueRef 的 shape

ShapeInferer
  └── 唯一的形状推导实现

Translator（待迁移）
  └── 直接读取 node.outputs[i].type.shape

优势：
  - 单一源头，无不一致
  - 无硬编码，由语义驱动
  - 翻译器无需 clamp
```

---

## 四、已实现的算子形状推导

| 分类 | 算子 | 形状规则 |
|------|------|---------|
| A. 形状不变 | RELU, SIGMOID, TANH, GELU, SILU, NEG, ABS, EXP, LOG, SQRT, CEIL, FLOOR, SOFTMAX, CAST | `output_shape = input_shape` |
| B. 广播二元 | ADD, SUBTRACT, MULTIPLY, DIVIDE, MAXIMUM, MINIMUM, POWER | NumPy 广播，从右对齐 |
| C. 矩阵乘法 | MATMUL | `output_shape = batch_dims + [M, N]`，约束 ndim ≥ 2 |
| D. 归约 | REDUCE_SUM, REDUCE_MEAN, REDUCE_MAX, REDUCE_MIN | `keepdims=True` 保持维度，`False` 收缩 |
| E. 形状变换 | RESHAPE, TRANSPOSE, SQUEEZE, UNSQUEEZE, CONCAT, SPLIT | 各自语义 |
| F. 索引 | GATHER, STRIDED_SLICE | 索引语义 |
| G. 三角矩阵 | TRIL, TRIU | `output_shape = input_shape`，约束 ndim ≥ 2 |
| H. 常数生成 | ARANGE, FULL, ONES, ZEROS | 由参数决定 |
| I. 其他 | BROADCAST_TO, TILE, EXPAND_DIMS | 各自语义 |

---

## 五、根据评审意见的修改记录

### 5.1 P0 问题修复

#### ShapeAdapter 修复
- 删除了未使用的 `checkNeedAdaptation` 和 `validateShapes` 方法
- 简化为直接调用 `ShapeInferer.inferGraphShapes()` 并填充形状
- 移除了推导失败时的静默处理，改为抛出异常

#### ShapeInferer 完善
- 移除了硬编码的 `constantShape(16)`，改为 `unknownDim()`
- 改进了 GATHER 的形状推导，正确处理 indices 维度
- 改进了 CONCAT 的形状推导，验证输入 ndim 一致性
- 改进了 SPLIT 的形状推导，正确读取 axis 属性
- 所有简化处理都添加了注释说明

### 5.2 P1 问题修复

#### TvmRelaxTranslator 恢复
- 恢复了 `opNameMapping` 和 `dtypeMapping` 可配置参数
- 添加了默认映射表 `defaultOpNameMapping`
- MATMUL 和 BROADCAST_TO 保持直接调用（不再硬编码 full 替换）
  - 原因：ShapeAdapter 现在正确填充形状，不再需要 full 替换

#### 代码清理
- 移除了 `AtomicLong` 未使用的 import（FuzzingPipeline.kt）

### 5.3 待完成事项

1. **序列化 round-trip 的形状丢失**：反序列化时需重建完整 shape 结构
2. **测试覆盖**：补充更多算子的形状推导测试
3. **文档同步**：确保文档与代码实现一致

---

## 六、测试验证

### 6.1 单元测试

`ShapeInfererTest.kt` 覆盖：
- 形状不变算子（RELU、SOFTMAX）
- 广播算子（ADD、MULTIPLY）
- 矩阵乘法（MATMUL 2-D 和约束检查）
- 归约算子（keepdims True/False）
- 三角矩阵（TRIL 及约束检查）
- 图级别推导

### 6.2 集成测试

验证生成器产出的每个 ValueRef 都有合法形状：

```kotlin
@Test
fun testAllValueRefsHaveShapes() {
    val gen = UirGenerator(GeneratorConfig(seed = 42))
    val program = gen.generate()
    
    for (graph in program.graphs) {
        for (vr in graph.inputs + graph.outputs + graph.nodes.flatMap { it.inputs + it.outputs }) {
            assertNotNull(vr.type.shape)
            // 所有维度都有 dimKind
            assertTrue(vr.type.shape.dims.all { it.dimKind != null })
        }
    }
}
```

---

## 七、文件清单

**新增**：
- `src/main/kotlin/io/github/xyzboom/aiFuzzer/infer/ShapeInferer.kt`
- `src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/LogicGenerator.kt`
- `src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/ShapeAdapter.kt`
- `src/test/kotlin/io/github/xyzboom/aiFuzzer/infer/ShapeInfererTest.kt`

**修改**：
- `src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/UirGenerator.kt`
- `tree/src/io/github/xyzboom/aiFuzzer/ir/serialize/UirSerializer.kt`
- `docs/shape-semantics.md`
- `docs/tree-design-review.md`

---

## 八、注意事项总结

1. **单一源头**：形状推导只在 `ShapeInferer` 中实现
2. **禁止硬编码**：维度值随机生成，ndim 从语义推导
3. **语义驱动**：每个算子的规则符合 TVM/ONNX 规范
4. **允许的约束**：只有来自语义的限制（如 MATMUL ndim ≥ 2）
5. **测试验证**：每个算子都有形状推导测试