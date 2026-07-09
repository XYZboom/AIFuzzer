# UIR 形状语义规范

> 版本: 1.0
> 日期: 2026-07-09
> 状态: 实现指南

---

## 一、设计原则

### 1.1 核心原则

**禁止硬编码形状**：所有形状必须通过算子语义实际计算，而非预设固定值。

**单一源头原则**：形状推导逻辑只有一套实现（`ShapeInferer`），生成器、翻译器、验证器都使用同一套逻辑。

**语义驱动**：每个算子的形状推导规则必须符合其语义定义（参考 TVM Relax、ONNX、PyTorch 等规范）。

### 1.2 禁止事项

| 禁止行为 | 原因 |
|---------|------|
| 硬编码维度值（如 `dim = 16`） | 无法测试动态 shape、符号 shape |
| 硬编码 ndim（如 `outputNdim = 2`） | 限制算子的通用性 |
| 生成器与翻译器各算一遍 | 导致不一致，产生 0-D/axis 越界 bug |
| clamp 到固定范围（如 `[1, 4]`） | 限制测试覆盖率，应让编译器决定合法范围 |

### 1.3 允许的约束

以下约束来自文档规范或目标编译器的语义限制：

| 约束 | 来源 | 说明 |
|------|------|------|
| `MATMUL` 要求输入 ndim ≥ 2 | TVM Relax 语义 | 矩阵乘法至少需要 2-D |
| `CONV2D` 要求输入 ndim = 4 | TVM Relax 语义 | NCHW 或 NHWC 格式 |
| `TRIL`/`TRIU` 要求输入 ndim ≥ 2 | TVM Relax 语义 | 需要矩阵维度 |
| `REDUCE_*` 后 ndim 可能减少 | 归约语义 | `keepdims=False` 收缩维度 |
| `RESHAPE` 可以改变任意形状 | 形状变换语义 | 元素总数不变 |

---

## 二、形状表示

### 2.1 UirShape 结构

```kotlin
UirShape {
    dims: List<UirDim>
}

UirDim {
    dimKind: UirDimKind  // CONSTANT | SYMBOLIC | UNKNOWN
    value: Int?          // CONSTANT 时为具体值
    symbol: String?      // SYMBOLIC 时为符号名（需扩展 TreeBuilder）
}
```

### 2.2 维度语义

| DimKind | 语义 | 示例 | 用途 |
|---------|------|------|------|
| `CONSTANT` | 静态已知维度 | `dimKind=CONSTANT, value=128` | 固定 batch size、特征维度 |
| `SYMBOLIC` | 符号维度 | `dimKind=SYMBOLIC, symbol="N"` | 动态 batch size、序列长度（需扩展） |
| `UNKNOWN` | 完全未知 | `dimKind=UNKNOWN` | 运行时才能确定 |

### 2.3 形状构造辅助函数

```kotlin
// 创建常数维度
fun constantDim(value: Int): UirDim = buildDim {
    dimKind = UirDimKind.CONSTANT
    this.value = value
}

// 创建未知维度
fun unknownDim(): UirDim = buildDim {
    dimKind = UirDimKind.UNKNOWN
}

// 创建常数形状
fun constantShape(vararg values: Int): UirShape = buildShape {
    values.forEach { v -> dims.add(constantDim(v)) }
}
```

---

## 三、算子形状语义详解

### 3.1 形状推导函数签名

```kotlin
/**
 * 算子形状推导函数。
 *
 * @param op 算子类型
 * @param inputShapes 输入张量的形状列表
 * @param attributes 算子属性（如 axis、keepdims 等）
 * @return 输出张量的形状列表（可能多输出）
 * @throws ShapeInferenceError 形状不兼容时抛出
 */
fun inferShape(
    op: UirOpKind,
    inputShapes: List<UirShape>,
    attributes: Map<String, Attribute>
): List<UirShape>
```

### 3.2 算子分类与形状规则

#### 分类 A：形状不变（逐元素/激活）

**算子列表**：`RELU`, `SIGMOID`, `TANH`, `GELU`, `SILU`, `NEG`, `ABS`, `EXP`, `LOG`, `SQRT`, `CEIL`, `FLOOR`, `CAST`, `SOFTMAX`

**规则**：`output_shape = input_shape`

**约束**：输入 ndim ≥ 0（支持 0-D 标量）

#### 分类 B：广播二元运算

**算子列表**：`ADD`, `SUBTRACT`, `MULTIPLY`, `DIVIDE`, `MAXIMUM`, `MINIMUM`, `POWER`

**规则**：NumPy 广播，从右对齐

```
output_ndim = max(input1_ndim, input2_ndim)
output_shape[i] = max(input1_shape[i], input2_shape[i])  // 对齐后
```

**约束**：两个输入必须 broadcast-compatible

#### 分类 C：矩阵乘法（MATMUL）

**规则**：

```
// 2-D: (M, K) @ (K, N) -> (M, N)
// Batch: (B, M, K) @ (B, K, N) -> (B, M, N)

output_shape = batch_dims + [M, N]
```

**约束**：输入 ndim ≥ 2

#### 分类 D：归约运算

**算子列表**：`REDUCE_SUM`, `REDUCE_MEAN`, `REDUCE_MAX`, `REDUCE_MIN`

**规则**：

```
keepdims=True:  output_shape[i] = 1 if i in axes else input_shape[i]
keepdims=False: output_shape = input_shape without axes
```

**约束**：输入 ndim ≥ 1，axes 在合法范围

#### 分类 E：形状变换

**RESHAPE**：`output_shape = target_shape`（元素总数不变）

**TRANSPOSE**：`output_shape[i] = input_shape[perm[i]]`

**SQUEEZE**：去掉所有 size=1 的维度

**UNSQUEEZE**：在指定位置插入 size=1 维度

**CONCAT**：沿 axis 拼接，其余维度必须相等

**SPLIT**：沿 axis 分割，输出形状与输入相同（多输出）

#### 分类 F：索引/切片

**GATHER**：`output_ndim = input_ndim + indices_ndim - 1`

**STRIDED_SLICE**：取决于切片范围

#### 分类 G：三角矩阵

**算子列表**：`TRIL`, `TRIU`

**规则**：`output_shape = input_shape`

**约束**：输入 ndim ≥ 2（至少是矩阵）

#### 分类 H：常数生成

**算子列表**：`ARANGE`, `FULL`, `ONES`, `ZEROS`

**规则**：由参数决定输出形状

#### 分类 I：其他

**BROADCAST_TO**：`output_shape = target_shape`

**TILE**：`output_shape[i] = input_shape[i] * repeats[i]`

---

## 四、实现注意事项

### 4.1 广播算法

广播规则（NumPy 风格）：

1. 从右向左对齐维度
2. 对应维度要么相等，要么其中一个是 1
3. 缺失的维度视为 1

```kotlin
fun broadcastShapes(shape1: UirShape, shape2: UirShape): UirShape {
    val resultDims = mutableListOf<UirDim>()
    val dims1 = shape1.dims.reversed()
    val dims2 = shape2.dims.reversed()
    val maxLen = maxOf(dims1.size, dims2.size)
    
    for (i in 0 until maxLen) {
        val d1 = dims1.getOrElse(i) { constantDim(1) }
        val d2 = dims2.getOrElse(i) { constantDim(1) }
        resultDims.add(broadcastDim(d1, d2))
    }
    
    return buildShape { resultDims.reversed().forEach { dims.add(it) } }
}
```

### 4.2 维度比较与广播

```kotlin
fun broadcastDim(d1: UirDim, d2: UirDim): UirDim {
    // 未知维度传播
    if (d1.dimKind == UirDimKind.UNKNOWN || d2.dimKind == UirDimKind.UNKNOWN) {
        return unknownDim()
    }
    
    // 常数维度广播
    if (d1.dimKind == UirDimKind.CONSTANT && d2.dimKind == UirDimKind.CONSTANT) {
        return when {
            d1.value == d2.value -> d1
            d1.value == 1 -> d2
            d2.value == 1 -> d1
            else -> throw ShapeInferenceError("Cannot broadcast $d1 and $d2")
        }
    }
    
    // 其他情况返回未知
    return unknownDim()
}
```

### 4.3 形状兼容性检查

```kotlin
fun areShapesCompatible(shape1: UirShape, shape2: UirShape): Boolean {
    val dims1 = shape1.dims.reversed()
    val dims2 = shape2.dims.reversed()
    val maxLen = maxOf(dims1.size, dims2.size)
    
    for (i in 0 until maxLen) {
        val d1 = dims1.getOrElse(i) { constantDim(1) }
        val d2 = dims2.getOrElse(i) { constantDim(1) }
        
        if (!areDimsCompatible(d1, d2)) return false
    }
    
    return true
}

fun areDimsCompatible(d1: UirDim, d2: UirDim): Boolean {
    if (d1.dimKind == UirDimKind.UNKNOWN || d2.dimKind == UirDimKind.UNKNOWN) return true
    if (d1.dimKind == UirDimKind.CONSTANT && d2.dimKind == UirDimKind.CONSTANT) {
        return d1.value == d2.value || d1.value == 1 || d2.value == 1
    }
    return false  // 符号维度需特殊处理
}
```

---

## 五、ShapeInferer API

### 5.1 主要接口

```kotlin
object ShapeInferer {
    /**
     * 推导单个节点的输出形状。
     */
    fun inferNodeOutputShapes(
        node: UirNode,
        inputValueRefs: Map<String, UirValueRef>  // valueId -> valueRef（含 shape）
    ): List<UirShape>
    
    /**
     * 推导整个图的形状。
     * 
     * 拓扑序遍历，为每个节点的输出确定形状。
     * 如果形状不兼容，抛出异常。
     */
    fun inferGraphShapes(graph: UirGraph): Map<String, UirShape>
    
    /**
     * 单个算子的形状推导。
     */
    fun inferShape(
        op: UirOpKind,
        inputShapes: List<UirShape>,
        attributes: Map<String, Attribute>
    ): List<UirShape>
}
```

### 5.2 使用示例

```kotlin
// 生成器使用
val graph = logicGen.generate()  // 只有 op 和依赖关系
val shapeMap = ShapeInferer.inferGraphShapes(graph)

// 为每个 valueRef 设置形状
for (node in graph.nodes) {
    for (output in node.outputs) {
        output.type.shape = shapeMap[output.valueId] ?: error("No shape for ${output.valueId}")
    }
}

// 翻译器使用（直接读取，不再推导）
val outputShape = node.outputs[0].type.shape
val outputNdim = outputShape.dims.size
```

---

## 六、测试要点

### 6.1 单元测试

每个算子必须有形状推导测试：

```kotlin
@Test
fun testMatMulShape() {
    val s1 = constantShape(3, 4)  // (M, K)
    val s2 = constantShape(4, 5)  // (K, N)
    val result = ShapeInferer.inferShape(UirOpKind.MATMUL, listOf(s1, s2), emptyMap())
    assertEquals(1, result.size)
    assertEquals(2, result[0].dims.size)
    assertEquals(3, result[0].dims[0].value)  // M
    assertEquals(5, result[0].dims[1].value)  // N
}
```

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
            assertTrue(vr.type.shape.dims.isNotEmpty() || vr.type.shape.dims.all { it.dimKind != null })
        }
    }
}
```

---

## 七、注意事项（重要！）

### 7.1 禁止硬编码

**严格遵守以下规则**：

1. **禁止硬编码维度值**
   - ❌ 错误：`constantDim(16)` 在生成器中固定使用
   - ✅ 正确：使用随机值或语义推导
   - 示例：`val dim = rand.nextInt(1, 129)` 在 ShapeAdapter 中随机生成

2. **禁止硬编码 ndim**
   - ❌ 错误：`outputNdim = 2` 在 MATMUL 推导中
   - ✅ 正确：从输入形状实际计算 `shape1.dims.size - 2 + shape2.dims.size - 2 + 2`

3. **禁止生成器与翻译器各算一遍**
   - ❌ 错误：生成器有 `computeOutputNdim()`，翻译器有 `ndimMap`
   - ✅ 正确：统一使用 `ShapeInferer.inferShape()`

4. **禁止 clamp 到固定范围**
   - ❌ 错误：`ndim.coerceIn(1, 4)` 在翻译器中
   - ✅ 正确：让编译器决定合法范围，通过异常捕获非法情况

### 7.2 单一源头原则

**形状推导逻辑只在 `ShapeInferer` 中实现**：

```
LogicGenerator（逻辑图）
       ↓
ShapeAdapter（调用 ShapeInferer）
       ↓
UirProgram（含完整形状）
       ↓
Translator（直接读取形状，不再推导）
```

**检查清单**：
- [ ] 生成器中无 ndim 计算逻辑
- [ ] 翻译器中无 ndimMap 维护
- [ ] 所有形状推导调用 ShapeInferer.inferShape()
- [ ] 所有维度值来源于随机生成或语义推导

### 7.3 语义驱动

**每个算子的形状规则必须符合语义定义**：

1. **参考权威规范**
   - TVM Relax: `relax.op.*` 文档
   - ONNX: onnx.ai/operators
   - PyTorch: pytorch.org/docs

2. **记录推导规则**
   - 在代码注释中说明规则来源
   - 在 `docs/semantics.md` 中记录详细语义

3. **测试验证**
   - 每个算子必须有形状推导单元测试
   - 测试覆盖边界情况（0-D、高维、广播等）

### 7.4 允许的约束

**以下约束来自文档规范或编译器语义限制**（非硬编码）：

| 约束 | 来源 | 代码表示 |
|------|------|----------|
| MATMUL ndim ≥ 2 | TVM Relax 语义 | `if (shape.dims.size < 2) throw ...` |
| TRIL/TRIU ndim ≥ 2 | TVM Relax 语义 | `if (shape.dims.size < 2) throw ...` |
| REDUCE axis 范围 | 归约语义 | `if (axis < 0 || axis >= ndim) throw ...` |

**注意**：这些是语义约束，不是硬编码限制。

---

## 八、迁移路径

### 8.1 Phase 1：实现 ShapeInferer ✅

状态：已完成

文件：
- `src/main/kotlin/io/github/xyzboom/aiFuzzer/infer/ShapeInferer.kt`
- `src/test/kotlin/io/github/xyzboom/aiFuzzer/infer/ShapeInfererTest.kt`

### 8.2 Phase 2：生成器迁移 ✅

状态：已完成

文件：
- `src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/LogicGenerator.kt`
- `src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/ShapeAdapter.kt`
- `src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/UirGenerator.kt`

### 8.3 Phase 3：翻译器迁移（待做）

任务：
1. 删除翻译器的 ndimMap
2. 直接从 `node.outputs[i].type.shape` 读取形状
3. 无需 clamp axis（ShapeAdapter 已保证）

### 8.4 Phase 4：清理（待做）

任务：
1. 删除旧的 ndim 相关配置（`minInputNdim`、`maxInputNdim`）
2. 更新序列化器（使用完整 Shape 而非 ndim）
3. 更新文档

---

## 九、文件清单

**新增文件**：
- `src/main/kotlin/io/github/xyzboom/aiFuzzer/infer/ShapeInferer.kt` — 统一形状推导
- `src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/LogicGenerator.kt` — 逻辑图生成
- `src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/ShapeAdapter.kt` — 形状适配
- `src/test/kotlin/io/github/xyzboom/aiFuzzer/infer/ShapeInfererTest.kt` — 形状推导测试

**修改文件**：
- `src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/UirGenerator.kt` — 使用两阶段架构
- `docs/shape-semantics.md` — 形状语义规范
- `docs/tree-design-review.md` — 树设计审查报告