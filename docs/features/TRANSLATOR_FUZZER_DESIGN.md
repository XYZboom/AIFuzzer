# AiFuzzer — 翻译器与模糊测试引擎设计文档

> 版本: 0.1 (Draft for Review)
> 日期: 2026-07-04
> 基于: CrossLangFuzzer 的 Printer / Generator / Runner 模式

---

## 目标

在已完成的 UIR 树结构（Phase 1）基础上，设计并实现以下三个模块：

1. **Translator（翻译器）** — 将 UIR 程序翻译为各 AI 编译器的输入（对应 CrossLangFuzzer 的 Printer）
2. **Generator（生成器）** — 保证生成合法 UIR 计算图
3. **Fuzzer（模糊测试引擎）** — 自动化测试流程

---

## 1. Translator 模块

### 1.1 核心接口

参考 CrossLangFuzzer 的 `IrPrinter`，设计 Translator 接口：

```kotlin
package io.github.xyzboom.aiFuzzer.translator

import io.github.xyzboom.aiFuzzer.ir.UirProgram

/**
 * 将 UIR 元素翻译为特定编译器的输入格式。
 *
 * @param IR 输入 IR 元素类型（目前只有 UirProgram）
 * @param R  输出类型（字符串、字节数组等）
 */
interface UirTranslator<in IR : UirElement, out R> {
    fun translate(element: IR): R
}
```

### 1.2 Program Translator

参考 CrossLangFuzzer 的 `IrProgramPrinter`，UirProgramTranslator 是入口点：

```kotlin
class UirProgramTranslator(
    private val translators: Map<String, UirTranslator<UirProgram, Map<String, String>>>
) : UirTranslator<UirProgram, Map<String, Map<String, String>>> {

    /**
     * @return Map<编译器名, Map<文件名, 文件内容>>
     */
    override fun translate(element: UirProgram): Map<String, Map<String, String>> {
        return translators.mapValues { (_, translator) ->
            translator.translate(element)
        }
    }

    fun saveTo(basePath: String, program: UirProgram) {
        val fileMap = translate(program)
        for ((compilerName, files) in fileMap) {
            val dir = File(basePath, compilerName)
            dir.mkdirs()
            for ((fileName, content) in files) {
                File(dir, fileName).writeText(content)
            }
        }
    }
}
```

### 1.3 各编译器翻译器

#### TVM Relax 翻译器

```kotlin
class TvmRelaxTranslator(
    private val dtypeMapping: Map<String, String> = defaultDtypeMapping,
    private val opNameMapping: Map<String, String> = defaultOpNameMapping,
) : UirTranslator<UirProgram, Map<String, String>> {

    override fun translate(program: UirProgram): Map<String, String> {
        return mapOf("main.py" to buildString {
            appendLine("import tvm")
            appendLine("from tvm import relax")
            appendLine("import tvm.relax.op as op")
            appendLine()
            appendLine("def main():")
            appendLine("    bb = relax.BlockBuilder()")
            appendLine()
            for (graph in program.graphs) {
                translateGraph(graph)
            }
            appendLine("    return bb.get()")
            appendLine()
            appendLine("if __name__ == '__main__':")
            appendLine("    mod = main()")
        })
    }

    private fun StringBuilder.translateGraph(graph: UirGraph) {
        appendLine("    with bb.function(\"${graph.name}\"):")
        // 声明输入参数
        for (input in graph.inputs) {
            // 根据 UirValue 类型推断 Relax 类型
            appendLine("        ${input.valueId} = relax.Var(\"${input.valueId}\", ...)")
        }
        // 翻译每个节点
        for (node in graph.nodes) {
            translateNode(node)
        }
        // 定义输出
        appendLine("        bb.emit_func_output([${graph.outputs.joinToString(", ") { it.valueId }}])")
    }

    private fun StringBuilder.translateNode(node: UirNode) {
        val tvmOp = opNameMapping[node.op] ?: node.op
        val inputs = node.inputs.joinToString(", ") { it.valueId }
        val outputs = node.outputs.joinToString(", ") { it.valueId }
        appendLine("        ${outputs} = bb.emit(relax.op.$tvmOp($inputs))")
        // 处理算子属性
        if (node.attributes.isNotEmpty()) {
            // 根据属性类型追加关键字参数
        }
    }

    companion object {
        val defaultDtypeMapping = mapOf(
            "float32" to "tvm.DataType(\"float32\")",
            "int64" to "tvm.DataType(\"int64\")",
        )
        val defaultOpNameMapping = mapOf(
            "add" to "add",
            "subtract" to "subtract",
            "multiply" to "multiply",
            "matmul" to "matmul",
            "relu" to "nn.relu",
            "sigmoid" to "sigmoid",
            "tanh" to "tanh",
            "conv2d" to "nn.conv2d",
            "max_pool2d" to "nn.max_pool2d",
            "reduce_sum" to "sum",
            "reshape" to "reshape",
            "transpose" to "permute_dims",
            "concat" to "concat",
            "abs" to "abs",
            "exp" to "exp",
            "neg" to "negative",
        )
    }
}
```

#### ONNX 翻译器

```kotlin
class OnnxTranslator(
    private val opNameMapping: Map<String, String> = defaultOpNameMapping,
) : UirTranslator<UirProgram, Map<String, String>> {

    override fun translate(program: UirProgram): Map<String, String> {
        // 使用 onnx.helper 构建 ModelProto，序列化为二进制
        // 输出 "model.onnx" 文件
        val model = makeModel(program)
        return mapOf("model.onnx" to model.SerializeToString().let { bytes ->
            // Base64 或直接二进制
            "TODO: serialization"
        })
    }
}
```

#### IREE / StableHLO 翻译器

```kotlin
class IreeStablehloTranslator : UirTranslator<UirProgram, Map<String, String>> {
    override fun translate(program: UirProgram): Map<String, String> {
        // 输出 StableHLO MLIR 内容
        return mapOf("main.mlir" to buildString {
            appendLine("module {")
            // 翻译每个图
            // ...
            appendLine("}")
        })
    }
}
```

### 1.4 TVM 翻译关键映射表

已由 PROJECT_PLAN.md 定义，按原样保留：

| UIR | TVM | 备注 |
|-----|-----|------|
| `neg` | `negative` | 命名不同 |
| `add` | `add` | 直接对应 |
| `conv2d` | `nn.conv2d` | 子模块路径 |
| `gather` | `take` | 命名不同 |
| `transpose` | `permute_dims` | 命名不同 |
| `pad` | `nn.pad` | 子模块路径 |
| `broadcast_to` | `broadcast_to` | 直接对应 |
| `scatter` | `scatter_elements` | 命名不同 |

---

## 2. Generator 模块

### 2.1 设计目标

保证生成的 UIR 程序是**合法的**。合法性定义：

1. **类型正确** — 算子的输入输出类型满足该算子的类型约束
2. **形状一致** — 算子间的形状传递关系正确（输出形状匹配后续算子的输入要求）
3. **命名唯一** — 所有 UirValue 的 id 在整个图内唯一
4. **DAG 结构** — 图中无环
5. **拓扑有序** — nodes 列表按拓扑顺序排列

### 2.2 核心架构

参考 CrossLangFuzzer 的 `IrDeclGenerator` + `GeneratorConfig` 模式：

```kotlin
package io.github.xyzboom.aiFuzzer.generator

class UirGenerator(
    private val config: GeneratorConfig = GeneratorConfig.default,
    private val random: Random = Random.Default,
    private val opRegistry: OpRegistry = OpRegistry.default,
) {
    /** 生成一个完整的 UIR 程序 */
    fun generate(): UirProgram {
        val program = buildUirProgram {
            name = "generated_${random.nextInt(100000)}"
            version = "0.1"
        }
        // 决定生成多少个图
        val graphCount = config.graphCountRange.random(random)
        repeat(graphCount) {
            generateGraph(program, it)
        }
        return program
    }

    private fun generateGraph(program: UirProgramBuilder, index: Int) {
        // 1. 选择随机节点数
        val nodeCount = config.nodeCountRange.random(random)
        // 2. 创建值池 → 逐步构建拓扑
        val valuePool = mutableListOf<UirValue>()
        val graphInputs = mutableListOf<UirValue>()
        val nodes = mutableListOf<UirNode>()

        // 3. 生成输入值
        val inputCount = config.inputCountRange.random(random)
        repeat(inputCount) {
            val value = createValue(valuePool, isInput = true)
            graphInputs.add(value)
        }

        // 4. 逐步生成节点，保证每个节点的输入来自值池
        repeat(nodeCount) {
            val node = createNode(valuePool)
            nodes.add(node)
        }

        // 5. 选择图输出
        val outputs = valuePool.shuffled(random).take(config.outputCountRange.random(random))

        // 6. 构建图
        buildGraph {
            name = "main_$index"
            inputs = graphInputs.map { uirValueRef(it.id) }.toMutableList()
            outputs = outputs.map { uirValueRef(it.id) }.toMutableList()
            nodes = nodes.toMutableList()
        }
    }

    /** 创建一个值 */
    private fun createValue(pool: MutableList<UirValue>, isInput: Boolean): UirValue {
        val type = randomType()
        return UirValue(
            id = nextValueId(),
            type = type,
            name = "v_${pool.size}"
        ).also { pool.add(it) }
    }

    /** 创建一个算子节点，从池中选择输入 */
    private fun createNode(pool: MutableList<UirValue>): UirNode {
        // 从 opRegistry 选择一个算子
        val opDef = opRegistry.choose(random)
        // 选择符合算子类型约束的输入
        val inputs = chooseInputs(pool, opDef.inputConstraints)
        // 创建输出值
        val outputs = opDef.outputShapes.map { shape ->
            createValue(pool, isInput = false)
        }
        // 生成属性
        val attributes = generateAttributes(opDef)
        return buildUirNode {
            name = "op_${nodeCounter++}"
            op = opDef.name
            this.inputs = inputs.toMutableList()
            this.outputs = outputs.toMutableList()
            this.attributes = attributes.toMutableMap()
        }
    }
}
```

### 2.3 算子注册表 (OpRegistry)

```kotlin
class OpRegistry {
    /** 算子名 → 算子定义 */
    private val ops: Map<String, OpDef> = linkedMapOf()

    /** 按类别分组 */
    private val byCategory: Map<OpCategory, List<OpDef>>

    fun choose(random: Random): OpDef {
        // 加权随机选择
        return ops.values.random(random)
    }

    companion object {
        val default: OpRegistry = OpRegistry().apply {
            // 注册核心算子
            register(unaryOp("relu", floatTypes))
            register(unaryOp("sigmoid", floatTypes))
            register(unaryOp("tanh", floatTypes))
            register(unaryOp("abs", numericTypes))
            register(unaryOp("exp", floatTypes))
            register(unaryOp("neg", numericTypes))
            register(binaryOp("add", numericTypes))
            register(binaryOp("subtract", numericTypes))
            register(binaryOp("multiply", numericTypes))
            register(reduceOp("reduce_sum", numericTypes))
            register(shapeOp("reshape"))
            register(shapeOp("transpose"))
            register(nnOp("conv2d"))
            register(nnOp("max_pool2d"))
        }
    }
}

/** 算子定义 */
data class OpDef(
    val name: String,
    val category: OpCategory,
    val supportedDtypes: List<UirDataType>,
    val inputConstraints: List<InputConstraint>,  // 每个输入的形状/类型约束
    val outputShapeRule: ShapeRule,                // 输出形状推导规则
    val attributes: List<AttrDef>,                 // 属性定义（名称 + 默认值 + 合法值范围）
)

/** 输入约束 */
data class InputConstraint(
    val count: IntRange,
    val typeFilter: (UirType) -> Boolean,
    val shapeFilter: (UirShape) -> Boolean,
)

/** 形状规则：从输入推导输出形状 */
sealed interface ShapeRule {
    /** 输出形状 = 输入形状（一元操作） */
    data object Identity : ShapeRule
    /** 广播规则（二元操作） */
    data object Broadcast : ShapeRule
    /** 自定义规则（如 conv2d） */
    data class Custom(val func: (List<UirShape>, Map<String, UirAttribute>) -> UirShape) : ShapeRule
}
```

### 2.4 生成策略

#### 2.4.1 拓扑感知生成

生成器维护一个**值池**（value pool），初始时填充图输入值。每次生成节点时，从值池中选择输入，新创建的输出值也加入值池。这保证了：

- DAG 结构（新节点只能引用已有值，不会产生环）
- 输出值可被后续节点使用（拓扑有序）
- 值池始终包含可供算子选择的兼容类型

#### 2.4.2 类型感知选择

`chooseInputs()` 方法根据算子定义的输入约束，从值池中筛选出类型/形状兼容的值：

```kotlin
private fun chooseInputs(
    pool: List<UirValue>,
    constraints: List<InputConstraint>,
): List<UirValueRef> {
    return constraints.map { constraint ->
        val candidates = pool.filter { constraint.typeFilter(it.type) }
        // 从 candidates 中选择，如果不够则填充新值
        if (candidates.isNotEmpty()) {
            candidates.random(random).let { uirValueRef(it.id) }
        } else {
            // 生成一个新的兼容值
            val newValue = createValue(pool, isInput = false)
            uirValueRef(newValue.id)
        }
    }
}
```

#### 2.4.3 形状推导

每个算子的 `outputShapeRule` 定义了如何从输入形状推导输出形状。生成器在创建节点后记录输出形状，确保后续节点看到正确的形状信息。

```kotlin
data class UirValueWithShape(
    val value: UirValue,
    val inferredShape: UirShape,  // 生成时追踪的形状
)
```

### 2.5 GeneratorConfig

```kotlin
data class GeneratorConfig(
    // 图参数
    val graphCountRange: IntRange = 1..2,
    val nodeCountRange: IntRange = 3..15,
    val inputCountRange: IntRange = 1..3,
    val outputCountRange: IntRange = 1..2,

    // 类型参数
    val floatTypesRatio: Float = 0.7f,
    val intTypesRatio: Float = 0.2f,
    val boolTypesRatio: Float = 0.1f,

    // 形状参数
    val minShapeDim: Int = 1,
    val maxShapeDim: Int = 4,
    val minDimSize: Int = 1,
    val maxDimSize: Int = 32,

    // 属性参数
    val generateAllAttributes: Boolean = false,  // true = 总是生成属性；false = 随机使用默认值
    val attributeRandomValueRatio: Float = 0.5f,
)
```

---

## 3. Fuzzer 模块

### 3.1 整体流程

```
Generator → Validator → Translator → CompilerDriver → Oracle → Reporter
    ↑                                                      |
    └─────────────────── 循环 ─────────────────────────────┘
```

### 3.2 主控制器

```kotlin
class AiFuzzer(
    private val config: FuzzerConfig = FuzzerConfig.default,
    private val generator: UirGenerator,
    private val programTranslator: UirProgramTranslator,
    private val compilerDriver: CompilerDriver,
    private val oracle: Oracle,
    private val reporter: Reporter,
) {
    /** 运行一轮测试 */
    fun runOne(): TestResult {
        // 1. 生成 UIR 程序
        val program = generator.generate()

        // 2. 验证程序合法性
        val validation = UirValidator.validate(program)
        if (validation.hasErrors()) {
            reporter.reportInvalid(program, validation)
            return TestResult(program, false, "invalid program")
        }

        // 3. 翻译到各编译器
        val compiled = mutableMapOf<String, CompileResult>()
        val outputs = mutableMapOf<String, RunResult>()

        for ((compilerName, _) in config.enabledCompilers) {
            // 4. 保存生成的文件
            val workDir = createWorkDir(program)
            programTranslator.saveTo(workDir.path, program)

            // 5. 调用编译器编译
            val compileResult = compilerDriver.compile(compilerName, workDir)
            compiled[compilerName] = compileResult

            if (compileResult.success) {
                // 6. 执行并获取结果
                outputs[compilerName] = compilerDriver.run(compilerName, workDir)
            }
        }

        // 7. Oracle 诊断
        val oracleResult = oracle.check(compiled, outputs)

        // 8. 报告
        reporter.report(program, compiled, outputs, oracleResult)

        return TestResult(program, oracleResult.isPass, oracleResult.message)
    }
}
```

### 3.3 Validator

对生成的 UIR 程序做合法性检查，确保：

```kotlin
object UirValidator {
    fun validate(program: UirProgram): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        // 1. 检查拓扑排序（nodes 列表无环）
        validateTopologicalOrder(program, errors)

        // 2. 检查值引用完整性（所有引用的 valueId 都存在）
        validateValueRefs(program, errors)

        // 3. 检查输入/输出有效性
        validateInputOutput(program, errors)

        // 4. 检查类型一致性（算子输出类型匹配定义）
        //   注意：这里只做结构性检查，不做数值级推理
        validateStructuralType(program, errors)

        return ValidationResult(errors)
    }
}
```

### 3.4 CompilerDriver

参考 CrossLangFuzzer 的 `CompilerRunner`，但需要适配 AI 编译器的调用方式：

```kotlin
class CompilerDriver(
    private val config: CompilerConfig,
) {
    fun compile(compilerName: String, workDir: Path): CompileResult {
        val command = when (compilerName) {
            "tvm" -> buildCommand("python3", listOf("main.py"), workDir)
            "onnx" -> buildCommand("python3", listOf("-m", "onnx.checker", "model.onnx"), workDir)
            "iree" -> buildCommand("iree-compile", listOf("main.mlir"), workDir)
            else -> throw IllegalArgumentException("Unknown compiler: $compilerName")
        }
        return runCommand(command)
    }

    fun run(compilerName: String, workDir: Path): RunResult {
        val command = when (compilerName) {
            "tvm" -> buildCommand("python3", listOf("run_tvm.py"), workDir)
            "onnx" -> buildCommand("python3", listOf("-m", "onnxruntime", "model.onnx"), workDir)
            "iree" -> buildCommand("iree-run-module", listOf("--module=module.vmfb"), workDir)
            else -> throw IllegalArgumentException("Unknown compiler: $compilerName")
        }
        return runCommand(command)
    }

    private fun runCommand(command: List<String>): ExecResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.reader().readText()
        val exitCode = process.waitFor()
        return ExecResult(exitCode == 0, output, exitCode)
    }
}
```

### 3.5 Oracle

本阶段**不使用差分测试**（跨编译器数值对比）。Oracle 策略：

```kotlin
class Oracle(
    private val config: OracleConfig,
) {
    fun check(
        compiled: Map<String, CompileResult>,
        outputs: Map<String, RunResult>,
    ): OracleResult {
        val findings = mutableListOf<Finding>()

        // Oracle 1: 编译检查
        // 合法 UIR → 应该成功编译
        for ((compiler, result) in compiled) {
            if (!result.success) {
                findings.add(Finding(
                    compiler = compiler,
                    type = FindingType.COMPILE_ERROR,
                    message = "Compilation failed: ${result.output}"
                ))
            }
        }

        // Oracle 2: 执行检查
        // 编译成功的应该能正确执行（无崩溃）
        for ((compiler, result) in outputs) {
            if (!result.success) {
                findings.add(Finding(
                    compiler = compiler,
                    type = FindingType.RUNTIME_ERROR,
                    message = "Runtime failed: ${result.output}"
                ))
            }
        }

        // Oracle 3: shape 检查（可选）
        // 检查形状推断是否一致（需要各编译器支持形状查询接口）
        if (config.checkShape) {
            checkShapeConsistency(compiled, outputs, findings)
        }

        return OracleResult(
            isPass = findings.isEmpty(),
            findings = findings,
        )
    }
}
```

### 3.6 Reporter

```kotlin
class Reporter(private val outputDir: Path) {
    fun report(
        program: UirProgram,
        compiled: Map<String, CompileResult>,
        outputs: Map<String, RunResult>,
        oracleResult: OracleResult,
    ) {
        val caseId = UUID.randomUUID().toString().take(8)
        val caseDir = outputDir.resolve("case_$caseId")
        caseDir.toFile().mkdirs()

        // 保存 UIR 程序
        saveUir(program, caseDir)

        // 保存各编译器的输入文件和输出日志
        for ((compiler, result) in compiled) {
            val compilerDir = caseDir.resolve(compiler)
            compilerDir.toFile().mkdirs()
            compilerDir.resolve("compile.log").toFile().writeText(result.output)
        }

        // 保存 Oracle 结果
        val summary = buildSummary(program, compiled, outputs, oracleResult)
        caseDir.resolve("summary.json").toFile().writeText(summary)
    }

    /** 打印统计信息到控制台 */
    fun printStats(stats: FuzzerStats) {
        println("=== AiFuzzer Run Stats ===")
        println("Total runs: ${stats.total}")
        println("Compile errors: ${stats.compileErrors}")
        println("Runtime errors: ${stats.runtimeErrors}")
        println("Success rate: ${stats.successRate()}")
    }
}
```

---

## 4. 目录结构

```
aiFuzzer/
├── tree/                        # (已有) IR 定义
├── src/
│   └── main/
│       └── kotlin/io/github/xyzboom/aiFuzzer/
│           ├── translator/      # 翻译器模块
│           │   ├── UirTranslator.kt           # 翻译器接口
│           │   ├── UirProgramTranslator.kt    # 程序级翻译器
│           │   ├── tvm/
│           │   │   └── TvmRelaxTranslator.kt  # TVM Relax 翻译器
│           │   ├── onnx/
│           │   │   └── OnnxTranslator.kt      # ONNX 翻译器
│           │   └── iree/
│           │       └── IreeStablehloTranslator.kt  # IREE 翻译器
│           ├── generator/       # IR 生成器模块
│           │   ├── GeneratorConfig.kt          # 生成器配置
│           │   ├── UirGenerator.kt             # 主生成器
│           │   ├── OpRegistry.kt               # 算子注册表 + OpDef
│           │   └── OpLibrary.kt                # 算子定义集合
│           ├── fuzzer/          # 模糊测试引擎模块
│           │   ├── AiFuzzer.kt                 # 主控制器
│           │   ├── CompilerDriver.kt           # 编译器驱动
│           │   ├── Oracle.kt                   # Oracle 诊断
│           │   ├── UirValidator.kt             # IR 合法性检查
│           │   └── Reporter.kt                 # 报告生成
│           └── cli/
│               └── Main.kt                     # 命令行入口
```

---

## 5. 工作流示例

### 5.1 生成 + 翻译 + 编译 + Oracle 完整流程

```kotlin
fun main() {
    val config = FuzzerConfig.default
    val generator = UirGenerator()
    val programTranslator = UirProgramTranslator(mapOf(
        "tvm" to TvmRelaxTranslator(),
        "onnx" to OnnxTranslator(),
    ))
    val compilerDriver = CompilerDriver(config.compiler)
    val oracle = Oracle()
    val reporter = Reporter(Paths.get("output"))

    val fuzzer = AiFuzzer(config, generator, programTranslator, compilerDriver, oracle, reporter)
    repeat(100) {
        fuzzer.runOne()
    }
}
```

### 5.2 仅翻译已有 UIR

```kotlin
fun translateProgram(program: UirProgram) {
    val translator = TvmRelaxTranslator()
    val files = translator.translate(program)
    for ((fileName, content) in files) {
        File(fileName).writeText(content)
    }
}
```

---

## 6. 后续扩展

- **变异模块** — 参考 CrossLangFuzzer 的 `IrMutator`，当前留作未来工作
- **差分测试** — 跨编译器数值对比，UIR_DESIGN.md 中有 ValueOracle 描述，当前阶段暂不实现
- **缩减引擎** — 参考 CrossLangFuzzer 的 `DDMin`，当前留作未来工作
- **更多编译器** — TensorRT、TensorFlow 等
- **图级别变异** — 节点插入/替换/删除

---

## 7. 参考：CrossLangFuzzer 模块对应表

| CrossLangFuzzer | AiFuzzer 对应 | 说明 |
|-----------------|---------------|------|
| `printer/IrPrinter.kt` | `translator/UirTranslator.kt` | 翻译接口 |
| `printer/IrProgramPrinter.kt` | `translator/UirProgramTranslator.kt` | 程序级翻译器 |
| `printer/clazz/JavaIrClassPrinter.kt` | `translator/tvm/TvmRelaxTranslator.kt` | 具体目标翻译器 |
| `printer/clazz/KtIrClassPrinter.kt` | `translator/tvm/TvmRelaxTranslator.kt` | 同上 |
| `printer/TypeContext.kt` | — | 不需要（无类型上下文） |
| `generator/IrDeclGenerator.kt` | `generator/UirGenerator.kt` | 程序生成器 |
| `generator/GeneratorConfig.kt` | `generator/GeneratorConfig.kt` | 生成器配置 |
| `generator/Generators.kt` | — | 类型别名（不需要） |
| `runner/CompilerRunner.kt` | `fuzzer/CompilerDriver.kt` | 编译器调用 |
| `runner/CoverageRunner.kt` | — | 暂不需要 |
| `validator/IrValidator.kt` | `fuzzer/UirValidator.kt` | 合法性检查 |
| `config/RunConfig.kt` | `fuzzer/FuzzerConfig.kt` | 运行时配置 |
| `mutator/IrMutator.kt` | — | 留作未来工作 |
| `algorithm/DDMin.kt` | — | 留作未来工作 |
| `Main.kt` | `cli/Main.kt` | 入口 |

---

> **Review 要点**:
> 1. Translator 接口是否需要支持更细粒度的元素翻译（单个 UirNode → 代码片段）？
> 2. Generator 中形状推导是否需要独立的 ShapeInferenceEngine？
> 3. Oracle 当前只做编译检查，是否需要在 Phase 3 就加入更完整的诊断？
> 4. 目录结构是否需要调整？