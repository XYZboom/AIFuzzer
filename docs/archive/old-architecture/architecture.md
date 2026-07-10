# aiFuzzer 架构文档

## 概述

aiFuzzer 是一个面向 AI 编译器的模糊测试框架。核心思路是：
随机生成合法 IR 子图 → 翻译为目标编译器 IR → 执行验证。

**架构哲学**：
- 每张 IR 子图是**形状精确**的：每个节点的输入输出都带有明确的 shape 信息
- 生成器只关注**逻辑拓扑**：哪些算子连在一起，形状推导是独立的后处理步骤
- 形状不兼容时**隐式插入适配算子**（reshape/expand_dims/squeeze），而不是跳过

---

## 架构分层

```
┌─────────────────────────────────────────────────────────────┐
│                    UirProgram（顶层容器）                     │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  UirGraph（子图）                                     │  │
│  │   inputs: UirValueRef[]  ← 每个都带 TensorType       │  │
│  │   nodes: UirNode[]       ← 拓扑序（DAG）              │  │
│  │   outputs: UirValueRef[] ← 子图输出                  │  │
│  │  ┌────────────────────────────────────────────┐     │  │
│  │  │ UirNode（算子节点）                        │     │  │
│  │  │   op: String           ← 算子名            │     │  │
│  │  │   inputs: UirValueRef[] ← 引用输入值       │     │  │
│  │  │   outputs: UirValueRef[] ← 输出值          │     │  │
│  │  │   attributes: Map<>    ← 算子参数          │     │  │
│  │  └────────────────────────────────────────────┘     │  │
│  │  ┌────────────────────────────────────────────┐     │  │
│  │  │ UirValueRef（值的引用）                    │     │  │
│  │  │   valueId: String                          │     │  │
│  │  │   type: UirTensorType                      │     │  │
│  │  │     shape: UirShape = List<UirDim>         │     │  │
│  │  │     dtype: UirDataType                     │     │  │
│  │  └────────────────────────────────────────────┘     │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
         │
         ▼  serialize / deserialize
    ┌──────────┐
    │ JSONL    │  (中间表示，用于 debug 和传递)
    └──────────┘
         │
         ▼
    ┌──────────────────────────────┐
    │ Target Translator            │
    │ (TvmRelaxTranslator)         │
    └──────────────────────────────┘
```

---

## 核心数据结构

### UirValueRef

每个值的引用，携带完整的类型信息。

```kotlin
class UirValueRef {
    val valueId: String
    val type: UirTensorType   // 形状 + 类型
}
```

`type.shape` 是一个 `UirShape`，包含一个维度列表 `List<UirDim>`，每个 `UirDim` 有：
- `dimKind`: CONCRETE | SYMBOLIC
- `value`: 具体 size（CONCRETE 时）

### UirNode

```kotlin
class UirNode {
    val op: String
    val inputs: List<UirValueRef>
    val outputs: List<UirValueRef>
    val attributes: Map<String, Attribute>
}
```

**关键规则**：每个 `UirNode` 的所有输出值在**生成完成时已经确定形状**。
- `outputs[0].type.shape` 反应了算子语义下的实际输出形状
- 翻译器**直接使用**输出值的形状，不需要自己推断

---

## 生成阶段（Generator）

### 阶段 1：逻辑图生成

**目标**：生成一个 DAG，只确定算子类型和依赖关系，不负责形状。

```kotlin
// 伪代码
class LogicGraphGenerator {
    fun generate(): ProtoGraph {
        val graph = ProtoGraph()
        // 1. 生成输入
        // 2. 随机选取算子，建立连接
        // 3. 生成 DAG 拓扑
        // 4. 确定算子属性（axis, keepdims 等）
        return graph  // 每个节点有 op/attributes/依赖关系，但 shape 未知
    }
}
```

这个阶段不关心 ndim，不关心 broadcast 兼容性，不关心 axis 是否越界。**约束表达为"这个算子至少需要几个输入、单输入还是双输入"这么粗**。

### 阶段 2：形状推导

**目标**：为逻辑图中每个 value 确定精确 shape。

```kotlin
// 伪代码
class ShapeInferer {
    // 共享的 ndim/shape 推导逻辑
    fun infer(op: String, inputShapes: List<Shape>, attrs: ...): Shape

    fun process(proto: ProtoGraph): UirGraph {
        // 拓扑序推导
        // 形状不兼容时，插入适配算子
        // 返回形状精确的 UirGraph
    }
}
```

**关键设计**：形状推导逻辑只有**一套**。它同时服务于：
1. 生成器中的 ndim 追踪（形状推导第一步只需要 ndim）
2. 完整的形状确定（能算到具体的 dim size）
3. 翻译器不需要独立再算一遍 ndim

**形状不兼容时的隐式适配规则**：

| 不兼容类型 | 检测条件 | 插入的适配算子 |
|-----------|---------|---------------|
| ndim 不够 | 算子需要 N≥2，但输入只有 1-D | `expand_dims` 在 axis=0 扩维 |
| ndim 太多 | 算子需要 N=2，但输入 4-D | `squeeze` 仅当有多余 1-D；否则 `reshape` 到合法 shape |
| 广播不匹配 | 二元算子两个输入 shape 不 broadcastable | `broadcast_to`（或 reshape） |
| axis 越界 | axis >= ndim | clamp axis |

### 阶段 3：值选择

**目标**：确定每个 value 的具体数值（由 Johnny 的路径分析模块负责，当前方案：常数填空）。

---

## 翻译阶段（Translator）

翻译器**同时**：
1. 从 `UirNode` 读取算子类型、参数
2. 从 `UirValueRef.type` 读取形状（不是自己推断）
3. 生成对应的 TVM Relax 调用

**不再需要的事**：
- ❌ 翻译器自己维护 ndimMap
- ❌ 翻译器 clamp axis（生成器保证 axis 兼容）
- ❌ 翻译器拓扑收敛 ndim

**翻译器只需要知道**：
- 算子名 → TVM 算子名的映射
- 属性序列化格式
- 哪些算子有特殊翻译（如 matmul 替换为 full）

---

## 与前设计的对比

| 方面 | 旧设计 | 新设计 |
|------|-------|-------|
| ndim 追踪 | 生成器和翻译器各一套，独立维护 | 只有 ShapeInferer 一套 |
| 形状兼容性 | 生成器 isOpCompatibleWithNdims 硬编码 | 隐式适配算子解决 |
| axis 越界 | 翻译器 clamp | 生成器保证 axis < ndim |
| 算子选择 | 受 ndim 约束，可能选不到合适的 | 逻辑图阶段不分形状 |
| 翻译器 | 需拓扑收敛、clamp、自己的 ndim 逻辑 | 直接使用 UIR 形状信息 |
| 0-D 传播 | 生成器 coerceAtLeast(1) 处置 | ShapeInferer 保证不产生 0-D |
| 算子列表 | 硬编码 44 个，约束散落在多处 | 集中注册（op 注册表 + shape 推导函数） |

---

## 算子注册机制

每个算子只需注册：

```kotlin
OperatorRegistry.register("add") {
    numInputs = 2                       // 需要 2 个输入
    acceptsAny = true                   // 接受任意 ndim（broadcast 兜底）
    shapeFn = { inputs, attrs ->        // 形状推导函数
        val resultNdim = maxOf(inputs[0].ndim, inputs[1].ndim)
        Shape(resultNdim) { concrete(16) }
    }
}

OperatorRegistry.register("matmul") {
    numInputs = 2
    shapeFn = { inputs, attrs ->
        Shape(2) { concrete(16); concrete(16) }  // 固定 16x16
    }
}
```

这样：
- 加新算子只需要注册一个条目
- 形状推导逻辑在注册点集中维护
- 生成器不需要写 isOpCompatibleWithNdims 等硬编码

---

## 实现计划

### Phase 1：共享 ShapeInferer 逻辑
1. 把 `computeOutputNdim`（生成器）和 `computeStaticOutputNdim`（翻译器）统一为一个共享函数
2. 让翻译器直接读 UIR 的 shape 信息，停止自己的 ndimMap 追踪
3. 验证：翻译器不需要 clamp axis

### Phase 2：隐式适配算子
1. 生成流程改为两阶段（逻辑图 → 形状推导 + 适配）
2. ShapeInferer 中实现 expand_dims/squeeze/reshape 的自动插入
3. 删除 `isOpCompatibleWithNdims` 中复杂的 per-operator 约束
4. 当前生成的 51/2000 失败降到接近 0

### Phase 3：算子注册表
1. 设计 `OperatorRegistry` 接口
2. 从 `DefaultOps` + 散落约束 → 注册表
3. 翻译器的 `emitCall` 也从注册表读取，而不是硬编码 when 分支