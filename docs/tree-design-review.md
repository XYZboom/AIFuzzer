# aiFuzzer 树设计审查报告

> 日期: 2026-07-09
> 范围: tree-generator 模块及 UIR 树结构设计
> 标准: 树设计能否有效帮助生成器和翻译器生成**语义合法**的 AI 编译器输入用例，能否有效覆盖 AI 编译器的某些语义

---

## 一、项目概述

**aiFuzzer** 是一个面向 AI 编译器（TVM、ONNX、IREE 等）的模糊测试框架，采用"统一中间表示（UIR）"策略：

```
生成器 → UIR → 翻译器 → 目标编译器输入（Python/Protobuf/MLIR）
```

核心设计借鉴了 Kotlin 编译器的 `generators/tree-generator-common` 框架，通过 `tree-generator` 模块自动生成 IR 树结构（接口、实现、Visitor、Builder），而非手写。

---

## 二、树结构设计

### 2.1 树生成器架构

`tree-generator` 位于 `tree/tree-generator/`，核心文件：

| 文件 | 角色 |
|------|------|
| `TreeBuilder.kt` | 元素配置器，定义所有 IR 元素及其字段 |
| `main.kt` | 生成入口，调用 kotlin 树生成器框架 |
| `model/Element.kt` | 元素模型（name、kind、fields） |
| `model/Field.kt` | 字段模型（SimpleField + ListField） |
| `model/Implementation.kt` | 实现类定义 |
| `printer/*.kt` | 各种打印器（接口/实现/Builder/Visitor） |
| `types.kt` | 类型引用定义（opKindType、typeKindType 等） |

### 2.2 生成的 IR 层级结构

```
UirElement（根接口）
│
├── UirProgram — 程序容器
│   ├── graphs: MutableList<UirGraph>
│   └── metadata: MutableMap<String, String>
│
├── UirGraph — 计算图（extends UirNamedElement）
│   ├── name: String
│   ├── inputs: MutableList<UirValueRef>
│   ├── outputs: MutableList<UirValueRef>
│   └── nodes: MutableList<UirNode>（拓扑有序）
│
├── UirNode — 算子节点（extends UirNamedElement）
│   ├── name: String
│   ├── op: UirOpKind（枚举，类型安全）
│   ├── inputs: MutableList<UirValueRef>
│   ├── outputs: MutableList<UirValueRef>
│   └── attributes: MutableMap<String, Attribute>
│
├── UirValueRef — 值引用（携带类型信息）
│   ├── valueId: String（唯一标识）
│   └── type: UirTensorType
│       ├── shape: UirShape → List<UirDim>
│       └── dtype: UirDataType
│
├── UirNamedElement — 命名元素接口
│   └── name: String
│
└── 类型系统元素
    ├── UirType — 类型基类
    │   └── typeKind: UirTypeKind
    ├── UirTensorType — 张量类型
    │   ├── shape: UirShape
    │   └── dtype: UirDataType
    ├── UirShape — 形状（List<UirDim>）
    ├── UirDim — 维度
    │   ├── dimKind: UirDimKind（CONSTANT | SYMBOLIC | UNKNOWN）
    │   └── value: Int?
    ├── UirDataType — 数据类型（name + bits）
    └── UirAttribute — 属性基类
        ├── UirIntAttr → value: Int
        └── UirStringAttr → value: String
```

### 2.3 TreeBuilder.kt 关键定义

```kotlin
val program: Element by element(Element.Kind.Other, "Program") {
    kind = ImplementationKind.Interface
    +listField("graphs", graph)
    +field("metadata", metadataMap, isChild = false)
}

val graph: Element by element(Element.Kind.Other, "Graph") {
    parent(namedElement)
    +listField("nodes", node)
    +listField("inputs", valueRef)
    +listField("outputs", valueRef)
}

val node: Element by element(Element.Kind.Other, "Node") {
    parent(namedElement)
    +field("op", opKindType, isChild = false)
    +listField("inputs", valueRef)
    +listField("outputs", valueRef)
    +field("attributes", attributeMap, isChild = false)
}

val valueRef: Element by element(Element.Kind.Other, "ValueRef") {
    +field("valueId", StandardTypes.string, isChild = false)
    +field("type", tensorType, isChild = false)
}
```

### 2.4 算子枚举设计（UirOpKind）

```kotlin
enum class UirOpKind {
    // 元素级二元
    ADD, SUBTRACT, MULTIPLY, DIVIDE, MAXIMUM, MINIMUM, POWER,
    // 矩阵乘法
    MATMUL,
    // 一元激活
    RELU, SIGMOID, TANH, GELU, SILU, SOFTMAX,
    // 一元数学
    NEG, ABS, EXP, LOG, SQRT, CEIL, FLOOR,
    // 形状变换
    RESHAPE, TRANSPOSE, SQUEEZE, UNSQUEEZE,
    // 拼接/分割
    CONCAT, SPLIT,
    // 归约
    REDUCE_SUM, REDUCE_MEAN, REDUCE_MAX, REDUCE_MIN,
    // 索引/切片
    GATHER, STRIDED_SLICE,
    // 广播/填充
    BROADCAST_TO, TILE,
    // 类型转换
    CAST,
    // 常数生成
    ARANGE, FULL, ONES, ZEROS,
    // 三角
    TRIL, TRIU,
    // 适配算子（由 ShapeAdapter 插入）
    EXPAND_DIMS,
}
```

分类集合（companion object）：

| 集合 | 语义 | 用途 |
|------|------|------|
| `singleInputOps` | 单输入算子 | 生成器选择输入时匹配 |
| `binaryInputOps` | 双输入算子 | 生成器选择输入时匹配 |
| `constantOps` | 常数生成算子 | 生成器始终可用 |
| `multiOutputOps` | 多输出算子 | 翻译器的特殊处理 |
| `needNdimGe2` | 需要 ndim≥2 | 生成器过滤输入 |
| `reducingOps` | 归约类算子 | 生成器/翻译器 ndim 推导 |
| `ndimStableOps` | ndim 不变 | 生成器/翻译器 ndim 推导 |
| `adapterOps` | 适配算子 | 生成器跳过（由 ShapeAdapter 插入） |

---

## 三、评价一：语义合法性支持度

### 3.1 ✅ 优点

#### 3.1.1 类型系统提供语义基础

**UirValueRef 携带完整类型信息**：每个值引用包含 `UirTensorType`（shape + dtype）。这使得：
- 生成器可以在生成时追踪 `ndimMap`，确保每个 valueRef 的维度数合法
- 翻译器可以直接从 UIR 读取形状，无需独立推导（当前实现存在分离问题，见 3.2）

**形状系统分层清晰**：

```
UirShape
  └── dims: List<UirDim>
        ├── CONSTANT + value → 静态 shape
        ├── SYMBOLIC + symbol → 符号 shape（TODO）
        └── UNKNOWN → 动态 shape（TODO）
```

**数据类型可扩展**：`UirDataType` 包含 name、bits、signed 等属性，覆盖 float32、int64 等主流类型。

#### 3.1.2 算子枚举提供类型安全

- 编译期检查，避免 `op: String` 的拼写错误
- 分类集合（`singleInputOps` 等）帮助生成器正确匹配输入数量
- 语义约束集中定义（`needNdimGe2`、`reducingOps`）

#### 3.1.3 DAG 结构保证拓扑正确性

- `UirGraph.nodes` 要求拓扑有序
- 生成器按拓扑序生成节点，翻译器按拓扑序遍历
- 避免循环依赖，翻译器可以直接 emit

#### 3.1.4 序列化支持

- `UirSerializer` 将 UIR 程序序列化为 JSONL 格式
- 支持反序列化重建，便于 debug 和传递
- 循环引用解除机制（`visitedIds` 集合）

#### 3.1.5 两阶段生成架构

```
Generator:
  1. LogicGenerator → 生成逻辑图（DAG 拓扑、算子类型、依赖关系）
  2. ShapeAdapter → 形状推导和适配（插入 expand_dims/squeeze）
```

两阶段架构将"拓扑生成"和"形状适配"解耦，是合理的设计。

### 3.2 ⚠️ 不足与风险

#### 3.2.1 形状信息与生成器追踪的分离（核心问题）

**现状**：生成器和翻译器各有一套 ndim 追踪逻辑。

| 角色 | 追踪方式 | 位置 |
|------|---------|------|
| 生成器 | `ndimMap: Map<String, Int>` | `UirGenerator` 内部 |
| 翻译器 | `ndimMap: Map<String, Int>` + 拓扑收敛 | `TvmRelaxTranslator` 内部 |

**产生的问题**（文档记录）：
- `squeeze` 在生成器追踪为 `ndim-1`，翻译器追踪为 `ndim`（未降维），导致 0-D 传播
- `matmul` 翻译器替换输入为固定 full，但生成器以为输入决定了输出 ndim
- `broadcast_to` 翻译器替换为 full，ndim 追踪完全偏离

**当前失败率**：约 51/2000（axis 越界 ~30/51，0-D 输入 ~20/51）

**根因**：UIR 树结构虽然有 `UirValueRef.type`，但生成器在生成阶段**并未填充完整的形状**，而是事后通过 ShapeAdapter 处理。翻译器无法直接信任 UIR 的形状信息。

**建议**：
1. **统一形状推导**：将 `computeOutputNdim`（生成器）和 `computeStaticOutputNdim`（翻译器）合并为共享函数
2. **翻译器直接读取 UIR shape**：删除翻译器自己的 ndimMap，从 `UirValueRef.type.shape` 获取信息
3. **ShapeInferer 确保所有 shape 在生成时已确定**

#### 3.2.2 UirTensorType 的 shape 字段标记为 isChild=false

```kotlin
val valueRef: Element by element(...) {
    +field("type", tensorType, isChild = false)
}
```

**问题**：Visitor 遍历 ValueRef 时不会自动进入 `type`，需要手动处理。如需遍历整个类型系统（类型重构、形状优化），需要额外逻辑。

**建议**：将 `type` 标记为 `isChild=true`，或在 Visitor 中提供专门的 `visitTensorType` 方法。

#### 3.2.3 常数张量表示不完整

- `UirAttribute` 定义了 `UirTensorAttr`（内嵌张量数据），但实现中未见使用
- 常数生成算子（ARANGE、FULL 等）的参数通过 `UirIntAttr` 传递
- 无法表达复杂的常数张量（如卷积权重初始化）

#### 3.2.4 生成器的输入选择仍有遗漏

根据文档，`selectCompatibleInputs` 中以下算子曾选到不兼容的输入：
- `softmax`、`reshape`、`squeeze`、`unsqueeze` — 选到 0-D 值
- 当前已修复（添加 ndim≥1 过滤），但缺乏完备性验证

---

## 四、评价二：语义覆盖度

### 4.1 ✅ 已覆盖的语义

#### 4.1.1 张量计算图核心语义

| AI 编译器核心语义 | UIR 表示 | 覆盖状态 |
|-----------------|---------|---------|
| DAG 计算图 | `UirGraph(nodes, inputs, outputs)` | ✅ 完整 |
| 算子节点 | `UirNode(op, inputs, outputs)` | ✅ 完整 |
| 值引用与数据流 | `UirValueRef(valueId, type)` | ✅ 完整 |
| 张量类型 | `UirTensorType(shape, dtype)` | ✅ 完整 |
| 形状系统 | `UirShape → UirDim → UirDimKind` | ✅ 完整 |
| 算子参数 | `attributes: Map<String, Attribute>` | ✅ 完整 |

#### 4.1.2 算子覆盖范围

| 算子类别 | UIR 算子数 | TVM Relax | ONNX | IREE |
|---------|-----------|-----------|------|------|
| 逐元素激活 | 6 | ✅ `nn.relu`、`sigmoid`、`tanh`、`nn.gelu`、`nn.silu`、`nn.softmax` | ✅ | ✅ |
| 逐元素数学 | 8 | ✅ `negative`、`abs`、`exp`、`log`、`sqrt`、`ceil`、`floor` | ✅ | ✅ |
| 二元运算 | 7 | ✅ `add`、`subtract`、`multiply`、`divide`、`maximum`、`minimum`、`power` | ✅ | ✅ |
| 矩阵乘法 | 1 | ✅ `matmul` | ✅ | ✅ |
| 归约 | 4 | ✅ `sum`、`mean`、`max`、`min` | ✅ | ✅ |
| 形状变换 | 4 | ✅ `reshape`、`permute_dims`、`squeeze`、`expand_dims` | ✅ | ✅ |
| 拼接/分割 | 2 | ✅ `concat`、`split` | ✅ | ✅ |
| 索引 | 2 | ✅ `take`、`strided_slice` | ✅ | ✅ |
| 三角矩阵 | 2 | ✅ `tril`、`triu` | ✅ | ✅ |
| 常数生成 | 4 | ✅ `arange`、`full`、`zeros`、`ones` | ✅ | ✅ |
| 广播/填充 | 2 | ✅ `broadcast_to`、`tile` | ✅ | ✅ |
| 类型转换 | 1 | ✅ `astype` | ✅ | ✅ |

**总覆盖率**：UIR 的 44 个算子覆盖了三个主流 AI 编译器的**核心算子交集**（约 70-80%）。

**算子映射举例**（UIR → TVM Relax）：

| UIR Op | TVM Relax 调用 |
|--------|---------------|
| `RELU` | `bb.emit(relax.op.nn.relu(x_var))` |
| `ADD` | `bb.emit(relax.op.add(a_var, b_var))` |
| `MATMUL` | 翻译器替换为固定 `full(ShapeExpr[16,16], 0) @ full(ShapeExpr[16,16], 0)` |
| `SOFTMAX` | `bb.emit(relax.op.nn.softmax(x_var, axis=-1))` |
| `RESHAPE` | `bb.emit(relax.op.reshape(x_var, ShapeExpr([-1])))` |
| `REDUCE_SUM` | `bb.emit(relax.op.sum(x_var, axis=[2], keepdims=False))` |
| `CONCAT` | `bb.emit(relax.op.concat([a_var, b_var], axis=0))` |

#### 4.1.3 广播语义覆盖

UIR 二元算子的 ndim 推导遵循 NumPy 广播规则：

```kotlin
// 生成器 computeOutputNdim
multiply → outputNdim = max(N1, N2)
```

翻译器直接使用 TVM Relax 的广播支持（`relax.op.add` 等自动广播）。

#### 4.1.4 归约语义覆盖

- 单 axis 归约：`REDUCE_SUM(axis=k)`
- keepdims 支持：`keepdims=True/False`
- 收缩维度：输出 ndim = 输入 ndim - 1（keepdims=False）

#### 4.1.5 形状变换语义覆盖

| 变换 | ndim 变化 | 说明 |
|------|----------|------|
| RESHAPE | N → 1 | 展平到 1-D（翻译器固定 `ShapeExpr([-1])`） |
| TRANSPOSE | N → N | ndim 不变，轴重排 |
| SQUEEZE | N → N-1 | 去掉 1-D 维度 |
| UNSQUEEZE | N → N+1 | 扩展维度 |
| EXPAND_DIMS | N → N+1 | ShapeAdapter 插入，axis=0 扩维 |

### 4.2 ⚠️ 未覆盖/不足的语义

#### 4.2.1 缺失的重要算子

| 算子类别 | 具体算子 | TVM 优先级 | 原因 |
|---------|---------|-----------|------|
| 卷积 | conv2d、conv1d、conv_transpose2d | 高 | CNN 核心算子，TVM 优化重点 |
| 池化 | max_pool2d、avg_pool2d | 高 | CNN 核心算子 |
| 归一化 | batch_norm、layer_norm | 高 | 主干网络必用 |
| 注意力 | attention | 中 | Transformer 核心 |
| Dropout | dropout | 中 | 训练模式算子 |
| Embedding | embedding | 中 | NLP 模型基础 |
| 额外归约 | reduce_prod、reduce_l2、argmax、argmin | 低 | 可选扩展 |
| 额外一元 | sin、cos、sign、isnan、round、rsqrt | 低 | 可选扩展 |
| 额外二元 | equal、greater、less、and、or、xor、mod | 低 | 可选扩展 |

**缺失后果**：无法测试 AI 编译器对 CNN、Transformer 等主流模型的编译优化能力。

#### 4.2.2 控制流未实现

| 控制流特性 | UIR 设计文档状态 | 代码实现 |
|-----------|----------------|---------|
| `UirBlock`（子图块） | 已定义 | ❌ 未实现 |
| `if` 条件分支 | 已定义 | ❌ 未实现 |
| `loop` 循环 | 已定义 | ❌ 未实现 |
| `call` 函数调用 | 已定义 | ❌ 未实现 |

**后果**：无法测试 AI 编译器处理动态模型的能力（如 RNN、Transformer 的动态序列长度）。

**建议**：按 UIR_DESIGN.md 第 3.8 节的设计，实现 `UirBlock` 和控制流算子。

#### 4.2.3 高维张量覆盖不足

| 维度范围 | 生成器限制 | 翻译器限制 | TVM Relax 实际支持 |
|---------|-----------|-----------|------------------|
| 0-D | ❌ coerceAtLeast(1) 禁用 | ❌ 不处理 | ✅ 支持标量 |
| 1-D | ✅ | ✅ | ✅ |
| 2-D | ✅ | ✅ | ✅ |
| 3-D | ✅ | ✅ | ✅ |
| 4-D | ✅ maxInputNdim=4 | ✅ clamp to 4 | ✅ |
| 5-D+ | ❌ | ❌ clamp to 4 | ✅ 支持任意 ndim |

**建议**：扩展 `maxInputNdim` 范围，测试 TVM 的高维支持。

#### 4.2.4 符号维度与动态 shape 未实现

- `UirDimKind.SYMBOLIC` 已定义但未使用
- `UirDimKind.UNKNOWN` 已定义但未使用

**后果**：无法测试 TVM Relax 的符号 shape 推导（如 `te.var("n")`）。

#### 4.2.5 稀疏张量与特殊编码未实现

- `UirTensorType` 有 `encoding: UirEncoding?` 定义，但未使用
- 无法测试 TVM 的 sparse tensor 支持

---

## 五、综合评价

### 5.1 整体评分

| 维度 | 评分 | 说明 |
|------|------|------|
| 树结构设计 | ⭐⭐⭐⭐☆ | 借鉴成熟框架，分层清晰，Visitor/Builder 自动生成 |
| 类型系统 | ⭐⭐⭐⭐☆ | 张量类型完整，符号维度未实现，常数张量表示不完整 |
| 算子覆盖 | ⭐⭐⭐☆☆ | 覆盖核心算子 70-80%，缺失 conv/pool/norm/attention |
| 语义合法性 | ⭐⭐⭐☆☆ | 类型安全、拓扑有序，ndim 追踪分离导致不一致 |
| 扩展性 | ⭐⭐⭐⭐☆ | 添加算子只需改 TreeBuilder.kt + UirOpKind |

**总体评分**：⭐⭐⭐⭐☆

### 5.2 关键优势总结

1. **自动生成的树结构**：借鉴 Kotlin 编译器框架，减少手写错误
2. **类型安全的算子枚举**：编译期检查，约束集中管理
3. **DAG 拓扑保证**：生成器和翻译器按一致拓扑序工作
4. **两阶段生成架构**：逻辑图 + 形状适配，职责分离
5. **类型安全的属性系统**：IntAttr/StringAttr 区分分明

### 5.3 关键不足总结

1. **形状追踪不一致**：生成器和翻译器各有一套 ndim 逻辑，是当前失败率的主要来源
2. **算子覆盖不完整**：缺失 conv/pool/norm/attention，无法测试 CNN 和 Transformer
3. **控制流缺失**：`UirBlock`/`if`/`loop` 设计已有但未实现
4. **符号维度未使用**：`SYMBOLIC` 和 `UNKNOWN` 定义但未实现
5. **常数张量表示不完整**：`UirTensorAttr` 定义但未使用

### 5.4 改进优先级

**P0（立即修复）**：

1. **统一形状推导逻辑**（对应 `architecture.md` Phase 1）
   - 将 `computeOutputNdim`（生成器）和 `computeStaticOutputNdim`（翻译器）合并
   - 翻译器直接读取 `UirValueRef.type.shape`，删除独立 ndimMap
   - 预期收益：消除所有 ndim 不一致 bug，翻译器无需 clamp axis

2. **验证测试**
   - 通过率从 1949/2000 提升到 2000/2000
   - 添加单元测试验证每个 valueRef 的 shape 在生成时已确定

**P1（短期改进）**：

3. **添加关键算子**
   - `conv2d`、`max_pool2d`（CNN 核心）
   - `batch_norm`、`layer_norm`（归一化）
   - `attention`（Transformer）

4. **实现控制流**
   - `UirBlock` 子图块
   - `if` 条件分支
   - `loop` 循环

**P2（中期改进）**：

5. **算子注册机制**
   ```kotlin
   OperatorRegistry.register("conv2d") {
       numInputs = 2
       shapeFn = { inputs, attrs -> Shape(4) { concrete(16) } }
   }
   ```

6. **符号维度支持**
   - 实现 `UirDimKind.SYMBOLIC`
   - 翻译器生成 `SymbolicVar` shape

7. **高维张量扩展**
   - 生成器 `maxInputNdim` 扩展至 6+ 
   - 翻译器移除 clamp

---

## 六、总结

**aiFuzzer 的树设计总体合理**，能够有效帮助生成器和翻译器生成语义合法的 AI 编译器输入用例。

✅ **核心优势**：
- 树结构借鉴成熟框架，Visitor/Builder 自动生成
- 类型系统完整，`UirValueRef` 携带精确 shape 信息（潜力）
- 算子枚举提供类型安全和语义约束
- 覆盖 TVM/ONNX/IREE 核心算子交集（70-80%）

⚠️ **核心问题**：
- **形状追踪不一致**是最紧迫的问题，修复后应能接近 2000/2000 通过率
- **算子覆盖不完整**限制了可测试的模型类型
- **控制流缺失**限制了动态模型测试
- 这些问题均有明确的设计文档或已规划解决方案

**实现改进路线**后，树设计将能够更有效、更全面地支撑 AI 编译器模糊测试的语义合法性验证和语义覆盖目标。