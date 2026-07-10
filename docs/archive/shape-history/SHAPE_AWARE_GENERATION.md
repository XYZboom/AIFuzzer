# 形状感知生成系统设计文档

## 1. 问题背景

### 1.1 当前问题

aiFuzzer 当前架构存在形状一致性问题：

```
LogicGenerator（生成拓扑） → ShapeAdapter（填充形状）
           ↓                          ↓
     无形状信息              形状推导可能失败
```

**失败模式**：
1. 二元运算形状不匹配（100% 失败原因）
2. `keepdims=False` 导致维度降低，后续算子输入不匹配
3. 常量算子（`ones`/`zeros`）生成固定形状，与现有输入不兼容

**根本原因**：
- 生成器缺少形状约束，允许不兼容的算子组合
- 形状推导是事后验证，而非生成时约束

### 1.2 目标

**短期目标**（Phase 1）：成功率从 28% 提升到 90%+
- 通过形状适配修复二元运算问题

**长期目标**（Phase 2）：100% 合法代码生成
- 引入符号形状系统
- 约束求解生成形状
- 支持复杂算子组合

---

## 2. 架构设计

### 2.1 三阶段生成流水线

```
┌─────────────────┐
│ LogicGenerator  │ 生成 DAG 拓扑、算子类型、依赖关系
│   (拓扑生成)     │ 输出：占位形状
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ ShapeGenerator  │ 为每个 ValueRef 生成兼容的形状
│   (形状生成)     │ 输出：符号形状或具体形状
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ ShapeValidator  │ 验证形状一致性
│   (形状验证)     │ 检查广播规则、维度约束
└─────────────────┘
```

### 2.2 核心组件

#### 2.2.1 符号形状系统

**目标**：支持形状的符号表示和约束求解

**设计**：

```kotlin
/**
 * 符号维度。
 * 
 * 三种形式：
 * 1. 常量维度：value = 42
 * 2. 符号维度：symbol = "N"，value = null
 * 3. 受限维度：symbol = "M", constraints = [M >= 1, M <= 128]
 */
sealed class SymbolicDim {
    abstract val symbol: String?
    abstract val value: Int?
    abstract val constraints: List<DimConstraint>
    
    data class Constant(
        override val value: Int
    ) : SymbolicDim() {
        override val symbol: String? = null
        override val constraints: List<DimConstraint> = emptyList()
    }
    
    data class Symbolic(
        override val symbol: String,
        override val constraints: List<DimConstraint> = emptyList()
    ) : SymbolicDim() {
        override val value: Int? = null
    }
}

/**
 * 维度约束。
 */
sealed class DimConstraint {
    data class GreaterThan(val value: Int) : DimConstraint()
    data class LessThan(val value: Int) : DimConstraint()
    data class Equals(val otherSymbol: String) : DimConstraint()
    data class DivisibleBy(val value: Int) : DimConstraint()
}

/**
 * 符号形状。
 */
data class SymbolicShape(
    val dims: List<SymbolicDim>
) {
    fun toUirShape(): UirShape {
        return buildShape {
            dims.forEach { dim ->
                when (dim) {
                    is SymbolicDim.Constant -> {
                        dims.add(buildDim {
                            dimKind = UirDimKind.CONSTANT
                            value = dim.value
                        })
                    }
                    is SymbolicDim.Symbolic -> {
                        dims.add(buildDim {
                            dimKind = UirDimKind.SYMBOLIC  // 新增类型
                            symbol = dim.symbol
                            // 如果有约束，求解后填充 value
                        })
                    }
                }
            }
        }
    }
}
```

#### 2.2.2 形状约束图

**目标**：跟踪形状之间的依赖关系

**设计**：

```kotlin
/**
 * 形状约束图。
 * 
 * 节点：ValueRef 的形状
 * 边：算子对形状的约束（广播、归约、reshape 等）
 */
class ShapeConstraintGraph {
    private val nodes = mutableMapOf<String, ShapeNode>()
    private val edges = mutableListOf<ShapeEdge>()
    
    /**
     * 添加形状节点。
     */
    fun addNode(valueId: String, shape: SymbolicShape) {
        nodes[valueId] = ShapeNode(valueId, shape)
    }
    
    /**
     * 添加形状约束边。
     */
    fun addEdge(
        fromValueId: String,
        toValueId: String,
        constraint: ShapeConstraint
    ) {
        edges.add(ShapeEdge(fromValueId, toValueId, constraint))
    }
    
    /**
     * 求解所有约束，返回具体形状。
     */
    fun solve(): Map<String, UirShape> {
        // 1. 收集所有约束
        val allConstraints = collectConstraints()
        
        // 2. 约束求解（简化版：贪婪实例化）
        val solutions = mutableMapOf<String, Int>()
        
        for (constraint in allConstraints) {
            when (constraint) {
                is DimConstraint.Equals -> {
                    // 如果一个符号已求解，传播到另一个
                    val v1 = solutions[constraint.symbol1]
                    val v2 = solutions[constraint.symbol2]
                    when {
                        v1 != null && v2 == null -> solutions[constraint.symbol2] = v1
                        v2 != null && v1 == null -> solutions[constraint.symbol1] = v2
                        v1 != null && v2 != null && v1 != v2 -> {
                            // 约束冲突，需要调整策略
                            // 例如：广播时选择较小值
                        }
                    }
                }
                is DimConstraint.GreaterThan -> {
                    // 设置下界
                    val current = solutions[constraint.symbol]
                    if (current == null || current < constraint.value) {
                        solutions[constraint.symbol] = constraint.value
                    }
                }
                is DimConstraint.LessThan -> {
                    // 设置上界
                    val current = solutions[constraint.symbol]
                    if (current == null || current > constraint.value) {
                        solutions[constraint.symbol] = constraint.value
                    }
                }
                is DimConstraint.DivisibleBy -> {
                    // 调整为可整除的值
                    val current = solutions[constraint.symbol] ?: 1
                    solutions[constraint.symbol] = ((current + constraint.value - 1) / constraint.value) * constraint.value
                }
            }
        }
        
        // 3. 实例化为具体形状
        return nodes.mapValues { (_, node) ->
            node.shape.instantiate(solutions)
        }
    }
}

/**
 * 形状节点。
 */
data class ShapeNode(
    val valueId: String,
    var shape: SymbolicShape
)

/**
 * 形状约束边。
 */
data class ShapeEdge(
    val fromValueId: String,
    val toValueId: String,
    val constraint: ShapeConstraint
)

/**
 * 形状约束类型。
 */
sealed class ShapeConstraint {
    /**
     * 广播约束。
     * 
     * from 和 to 的形状必须满足 NumPy 广播规则：
     * - 从右对齐
     * - 每个维度要么相等，要么其中一个为 1
     */
    data class Broadcast(
        val fromValueId: String,
        val toValueId: String
    ) : ShapeConstraint()
    
    /**
     * 归约约束。
     * 
     * 归约后，输出形状的某些维度变为 1（keepdims=true）
     * 或消失（keepdims=false）
     */
    data class Reduction(
        val inputValueId: String,
        val outputValueId: String,
        val axis: Int,
        val keepdims: Boolean
    ) : ShapeConstraint()
    
    /**
     * 矩阵乘法约束。
     * 
     * K 维必须匹配：A.shape[-1] == B.shape[-2]
     * 批次维度必须可广播
     */
    data class Matmul(
        val lhsValueId: String,
        val rhsValueId: String,
        val outputValueId: String
    ) : ShapeConstraint()
    
    /**
     * 拼接约束。
     * 
     * 所有输入在非拼接轴上维度必须相同
     */
    data class Concat(
        val inputValueIds: List<String>,
        val outputValueId: String,
        val axis: Int
    ) : ShapeConstraint()
}
```

#### 2.2.3 形状生成器

**目标**：为每个 ValueRef 生成形状，满足所有约束

**设计**：

```kotlin
/**
 * 形状生成器。
 * 
 * 职责：
 * 1. 分析图拓扑，识别算子对形状的约束
 * 2. 构建形状约束图
 * 3. 求解约束，生成具体形状
 */
class ShapeGenerator(
    private val config: ShapeGeneratorConfig
) {
    /**
     * 为图生成形状。
     */
    fun generate(graph: UirGraph): Map<String, UirShape> {
        // 1. 构建约束图
        val constraintGraph = buildConstraintGraph(graph)
        
        // 2. 求解约束
        return constraintGraph.solve()
    }
    
    /**
     * 构建形状约束图。
     */
    private fun buildConstraintGraph(graph: UirGraph): ShapeConstraintGraph {
        val constraintGraph = ShapeConstraintGraph()
        
        // 为图输入分配符号形状
        for (input in graph.inputs) {
            val symbolicShape = assignSymbolicShape(input, graph)
            constraintGraph.addNode(input.valueId, symbolicShape)
        }
        
        // 遍历节点，添加约束
        for (node in graph.nodes) {
            when (node.op) {
                // 二元运算：广播约束
                in UirOpKind.binaryInputOps -> {
                    if (node.inputs.size == 2) {
                        constraintGraph.addEdge(
                            node.inputs[0].valueId,
                            node.inputs[1].valueId,
                            ShapeConstraint.Broadcast(
                                node.inputs[0].valueId,
                                node.inputs[1].valueId
                            )
                        )
                    }
                }
                
                // 归约运算：降维约束
                in UirOpKind.reductionOps -> {
                    val axis = node.attributes["axis"]?.intValue ?: -1
                    val keepdims = (node.attributes["keepdims"]?.intValue ?: 0) == 1
                    
                    constraintGraph.addEdge(
                        node.inputs[0].valueId,
                        node.outputs[0].valueId,
                        ShapeConstraint.Reduction(
                            node.inputs[0].valueId,
                            node.outputs[0].valueId,
                            axis,
                            keepdims
                        )
                    )
                }
                
                // 矩阵乘法：K 维匹配 + 批次广播
                UirOpKind.MATMUL -> {
                    constraintGraph.addEdge(
                        node.inputs[0].valueId,
                        node.inputs[1].valueId,
                        ShapeConstraint.Matmul(
                            node.inputs[0].valueId,
                            node.inputs[1].valueId,
                            node.outputs[0].valueId
                        )
                    )
                }
                
                // 拼接：非拼接轴维度相同
                UirOpKind.CONCAT -> {
                    val axis = node.attributes["axis"]?.intValue ?: 0
                    constraintGraph.addEdge(
                        node.inputs[0].valueId,
                        node.outputs[0].valueId,
                        ShapeConstraint.Concat(
                            node.inputs.map { it.valueId },
                            node.outputs[0].valueId,
                            axis
                        )
                    )
                }
                
                // 其他算子：形状推导
                else -> {
                    // 使用 ShapeInferer 推导输出形状
                    // 并添加相应约束
                }
            }
        }
        
        return constraintGraph
    }
    
    /**
     * 为图输入分配符号形状。
     */
    private fun assignSymbolicShape(input: UirValueRef, graph: UirGraph): SymbolicShape {
        // 策略：分析图中有哪些算子，确定最小维度数
        val needNdim = analyzeMinNdim(graph)
        
        // 生成符号形状
        val dims = (0 until needNdim).map { i ->
            SymbolicDim.Symbolic(
                symbol = "N_${input.valueId}_$i",
                constraints = listOf(
                    DimConstraint.GreaterThan(1),
                    DimConstraint.LessThan(128)
                )
            )
        }
        
        return SymbolicShape(dims)
    }
    
    /**
     * 分析图所需的最小维度数。
     */
    private fun analyzeMinNdim(graph: UirGraph): Int {
        // 如果有 MATMUL，至少需要 2 维
        if (graph.nodes.any { it.op == UirOpKind.MATMUL }) {
            return 2
        }
        
        // 如果有 TRANSPOSE/TRIL/TRIU，至少需要 2 维
        if (graph.nodes.any { it.op in UirOpKind.needNdimGe2 }) {
            return 2
        }
        
        // 默认 1 维
        return 1
    }
}

data class ShapeGeneratorConfig(
    val maxNdim: Int = 4,
    val minDimValue: Int = 1,
    val maxDimValue: Int = 128,
    val useSymbolicShapes: Boolean = true
)
```

#### 2.2.4 形状验证器

**目标**：验证生成的形状满足所有约束

**设计**：

```kotlin
/**
 * 形状验证器。
 * 
 * 职责：验证形状一致性，报告违规
 */
object ShapeValidator {
    
    /**
     * 验证图的形状一致性。
     * 
     * @return 验证结果，包含所有违规
     */
    fun validate(graph: UirGraph): ValidationResult {
        val violations = mutableListOf<ShapeViolation>()
        
        for (node in graph.nodes) {
            val inputShapes = node.inputs.map { it.type.shape }
            val outputShapes = node.outputs.map { it.type.shape }
            
            // 检查算子形状约束
            if (!ShapeConstraints.isApplicable(node.op, inputShapes)) {
                violations.add(ShapeViolation(
                    nodeId = node.name,
                    op = node.op,
                    violationType = ViolationType.CONSTRAINT_VIOLATION,
                    message = "Operator ${node.op} not applicable for input shapes $inputShapes",
                    inputShapes = inputShapes,
                    outputShapes = outputShapes
                ))
            }
            
            // 检查广播规则（二元运算）
            if (node.op in UirOpKind.binaryInputOps && node.inputs.size == 2) {
                if (!areBroadcastable(inputShapes[0], inputShapes[1])) {
                    violations.add(ShapeViolation(
                        nodeId = node.name,
                        op = node.op,
                        violationType = ViolationType.BROADCAST_MISMATCH,
                        message = "Broadcast mismatch: ${inputShapes[0]} vs ${inputShapes[1]}",
                        inputShapes = inputShapes,
                        outputShapes = outputShapes
                    ))
                }
            }
            
            // 检查矩阵乘法约束
            if (node.op == UirOpKind.MATMUL) {
                val k1 = inputShapes[0].dims.last()
                val k2 = inputShapes[1].dims[inputShapes[1].dims.size - 2]
                
                if (k1 != k2) {
                    violations.add(ShapeViolation(
                        nodeId = node.name,
                        op = node.op,
                        violationType = ViolationType.MATMUL_K_MISMATCH,
                        message = "Matmul K-dimension mismatch: $k1 vs $k2",
                        inputShapes = inputShapes,
                        outputShapes = outputShapes
                    ))
                }
            }
        }
        
        return ValidationResult(
            isValid = violations.isEmpty(),
            violations = violations
        )
    }
    
    private fun areBroadcastable(s1: UirShape, s2: UirShape): Boolean {
        return ShapeConstraints.areBroadcastable(s1, s2)
    }
}

/**
 * 验证结果。
 */
data class ValidationResult(
    val isValid: Boolean,
    val violations: List<ShapeViolation>
)

/**
 * 形状违规。
 */
data class ShapeViolation(
    val nodeId: String,
    val op: UirOpKind,
    val violationType: ViolationType,
    val message: String,
    val inputShapes: List<UirShape>,
    val outputShapes: List<UirirShape>
)

enum class ViolationType {
    CONSTRAINT_VIOLATION,      // 算子约束违规
    BROADCAST_MISMATCH,        // 广播不匹配
    MATMUL_K_MISMATCH,         // 矩阵乘法 K 维不匹配
    DIMENSION_MISMATCH,        // 维度数不匹配
    AXIS_OUT_OF_RANGE          // 轴越界
}
```

---

## 3. 实现路径

### 3.1 Phase 1：快速修复（1-2 天）

**目标**：成功率从 28% → 90%+

**策略**：在 `ShapeAdapter` 中添加预处理逻辑

**实现步骤**：

1. 修改 `ShapeAdapter.adapt()`：
   ```kotlin
   fun adapt(graph: UirGraph, rand: Random) {
       // 1. 为图输入分配初始形状
       assignInitialShapes(graph, rand)
       
       // 2. 预处理二元算子输入（新增）
       preprocessBinaryOpInputs(graph, rand)
       
       // 3. 形状推导
       val shapeMap = ShapeInferer.inferGraphShapes(graph)
       
       // 4. 应用形状
       applyShapes(graph, shapeMap)
   }
   ```

2. 实现 `preprocessBinaryOpInputs()`：
   - 检查所有二元算子的输入形状
   - 如果不可广播，强制调整
   - 策略：将不兼容维度设为 1

3. 运行测试验证

**优点**：
- 快速见效
- 代码改动小
- 不影响现有架构

**缺点**：
- 治标不治本
- 可能生成不合理的形状（如大量 1）

### 3.2 Phase 2：符号形状系统（1 周）

**目标**：成功率 100%，支持复杂算子组合

**策略**：实现符号形状 + 约束求解

**实现步骤**：

1. 扩展 IR 类型系统：
   - 添加 `UirDimKind.SYMBOLIC`
   - 添加 `symbol` 字段

2. 实现符号形状类：
   - `SymbolicDim`
   - `SymbolicShape`

3. 实现形状约束图：
   - `ShapeConstraintGraph`
   - `ShapeConstraint` 及其子类

4. 实现 `ShapeGenerator`：
   - 分析图拓扑
   - 构建约束图
   - 求解约束

5. 重构生成流水线：
   ```kotlin
   // 旧流程
   LogicGenerator → ShapeAdapter → ShapeInferer
   
   // 新流程
   LogicGenerator → ShapeGenerator → ShapeValidator
   ```

**优点**：
- 彻底解决形状一致性问题
- 支持复杂约束（如矩阵乘法、拼接）
- 生成更合理的形状

**缺点**：
- 实现复杂度高
- 需要修改 IR 定义

### 3.3 Phase 3：增强约束求解（可选）

**目标**：支持更复杂的场景

**策略**：引入 SMT 求解器（如 Z3）

**实现步骤**：

1. 添加 Z3 依赖
2. 将形状约束转换为 Z3 表达式
3. 求解并提取模型
4. 实例化为具体形状

**优点**：
- 支持复杂约束（如 divisibility、范围约束）
- 保证找到解（如果存在）

**缺点**：
- 增加外部依赖
- 性能开销

---

## 4. 测试策略

### 4.1 单元测试

**形状约束测试**：
```kotlin
@Test
fun testBroadcastConstraint() {
    val s1 = buildShape { dims.add(buildDim { value = 3 }) }
    val s2 = buildShape { dims.add(buildDim { value = 5 }) }
    
    // 不可广播
    assertFalse(ShapeConstraints.areBroadcastable(s1, s2))
    
    // 调整后可广播
    val s2Adjusted = buildShape { dims.add(buildDim { value = 1 }) }
    assertTrue(ShapeConstraints.areBroadcastable(s1, s2Adjusted))
}

@Test
fun testMatmulConstraint() {
    val a = buildShape { 
        dims.add(buildDim { value = 2 })
        dims.add(buildDim { value = 3 })
    }
    val b = buildShape {
        dims.add(buildDim { value = 3 })
        dims.add(buildDim { value = 4 })
    }
    
    // K 维匹配
    assertTrue(ShapeConstraints.isApplicable(UirOpKind.MATMUL, listOf(a, b)))
}
```

### 4.2 集成测试

**端到端测试**：
```kotlin
@Test
fun testShapeAwareGeneration() {
    val config = LogicGraphConfig(seed = 42)
    val generator = LogicGenerator(config)
    val program = generator.generate()
    
    for (graph in program.graphs) {
        // 验证形状一致性
        val result = ShapeValidator.validate(graph)
        assertTrue(result.isValid, "Generated graph has shape violations: ${result.violations}")
        
        // 验证 TVM Relax 可编译
        val code = TvmRelaxTranslator().translate(graph)
        assertCompiles(code)
    }
}
```

### 4.3 回归测试

**持续监控成功率**：
```kotlin
@Test
fun testSuccessRate() {
    val numTrials = 100
    var successCount = 0
    
    repeat(numTrials) { seed ->
        val graph = LogicGenerator(LogicGraphConfig(seed = seed.toLong())).generate().graphs.first()
        val result = ShapeValidator.validate(graph)
        
        if (result.isValid) {
            successCount++
        }
    }
    
    val successRate = successCount.toDouble() / numTrials
    assertTrue(successRate >= 0.90, "Success rate $successRate < 0.90")
}
```

---

## 5. 性能考虑

### 5.1 约束求解复杂度

**问题**：约束图求解可能很慢（特别是图很大时）

**优化策略**：

1. **贪婪实例化**（Phase 2）：
   - 按拓扑序遍历节点
   - 每个约束立即求解
   - 时间复杂度：O(V + E)

2. **缓存求解结果**：
   - 相同约束模式缓存
   - 避免重复计算

3. **并行求解**：
   - 独立约束并行处理
   - 使用多线程

### 5.2 符号形状开销

**问题**：符号形状增加内存和计算开销

**优化策略**：

1. **惰性实例化**：
   - 符号形状只在需要时实例化
   - 避免中间步骤的开销

2. **形状池**：
   - 复用常见形状
   - 减少对象创建

---

## 6. 兼容性考虑

### 6.1 向后兼容

**问题**：现有代码依赖 `ShapeAdapter`

**策略**：

1. **保留 `ShapeAdapter`**：
   - Phase 1 修复基于现有代码
   - Phase 2 新增 `ShapeGenerator`，但 `ShapeAdapter` 保留为别名

2. **渐进迁移**：
   ```kotlin
   // Phase 1
   class ShapeAdapter {
       fun adapt(graph: UirGraph, rand: Random) { ... }
   }
   
   // Phase 2
   typealias ShapeAdapter = ShapeGenerator
   ```

### 6.2 翻译器适配

**问题**：翻译器需要处理符号形状

**策略**：

1. **翻译前实例化**：
   - 翻译器只处理具体形状
   - 在翻译前调用 `symbolicShape.instantiate()`

2. **增强 `TvmRelaxTranslator`**：
   - 支持 `UirDimKind.SYMBOLIC`
   - 生成符号 TVM 代码（如 `T.int64("N")`）

---

## 7. 风险与缓解

### 7.1 风险列表

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 约束求解太慢 | 中 | 高 | 贪婪算法、并行化、缓存 |
| 符号形状系统复杂 | 高 | 中 | 分阶段实现、充分测试 |
| IR 修改影响现有代码 | 中 | 中 | 向后兼容、渐进迁移 |
| TVM Relax 不支持符号形状 | 低 | 低 | 翻译前实例化 |

### 7.2 回退计划

**如果 Phase 2 失败**：

1. 回退到 Phase 1（`ShapeAdapter` 预处理）
2. 增加更多特殊规则（如禁用某些算子组合）
3. 接受较低成功率（90% 而非 100%）

---

## 8. 文档与培训

### 8.1 开发者文档

**需要更新**：
1. `README.md`：添加形状生成系统说明
2. `CONTRIBUTING.md`：形状约束开发指南
3. API 文档：`ShapeGenerator`、`ShapeValidator` 使用示例

### 8.2 示例代码

**形状感知生成示例**：
```kotlin
// 生成形状一致的图
val config = LogicGraphConfig(seed = 42)
val logicGen = LogicGenerator(config)
val program = logicGen.generate()

val shapeGen = ShapeGenerator(ShapeGeneratorConfig())
val shapes = shapeGen.generate(program.graphs.first())

// 验证
val result = ShapeValidator.validate(program.graphs.first())
if (!result.isValid) {
    println("Violations: ${result.violations}")
}
```

---

## 9. 时间线

### Phase 1（快速修复）
- Day 1：实现 `preprocessBinaryOpInputs`
- Day 2：测试、调试、更新文档

### Phase 2（符号形状系统）
- Day 1-2：扩展 IR 类型系统
- Day 3-4：实现符号形状类和约束图
- Day 5-6：实现 `ShapeGenerator` 和 `ShapeValidator`
- Day 7：集成测试、性能优化

### Phase 3（增强求解）
- 可选，根据 Phase 2 效果决定

---

## 10. 成功指标

### Phase 1
- [x] 成功率从 28% 提升到 90%+
- [x] 所有失败案例都是二元运算形状不匹配
- [x] 修复后成功率 ≥ 90%

### Phase 2
- [ ] 成功率达到 100%
- [ ] 支持 MATMUL、CONCAT 等复杂算子
- [ ] 性能：生成 100 个图 < 1 秒

### Phase 3（可选）
- [ ] 支持 divisibility、范围等复杂约束
- [ ] 性能：约束求解 < 100ms

---

## 11. 参考文献

###