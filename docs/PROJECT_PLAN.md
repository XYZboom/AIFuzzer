# AiFuzzer — 项目执行计划

> 版本: 0.1
> 日期: 2026-07-03

---

## 项目概述

AiFuzzer 是一个 AI 编译器模糊测试框架。其核心思想是：

```
统一 IR (UIR) → Translator (TVM/ONNX/IREE) → 编译 → Oracle 验证 → 输出测试结果
```

设计参考：
- **CrossLangFuzzer**: 项目组织结构、IR 接口定义模式、Visitor/Transformer 模式、Printer 模式
- **Thalia (POPL 2024)**: API 枚举 + 类型居民查找 + 可组合的测试生成策略
- **Propilot (2026)**: 算子属性骨架 + 可执行 PBT 模板

---

## 项目组织结构 (与 CrossLangFuzzer 对齐，IR 使用 tree-generator 生成)

> **关键设计决策**: AiFuzzer 的 IR 树结构（接口、实现、Visitor、Builder）**不手写**，而是参考 CrossLangFuzzer，通过 `tree-generator`（Kotlin 编译器源码中的 `generators/tree-generator-common`）从 Kotlin 配置文件自动生成。
>
> 具体流程：
> 1. 在 `uir-builder/` 中编写 Kotlin 配置（类似 `TreeBuilder.kt`），定义 IR 元素、字段、父子关系
> 2. 运行 tree-generator 的主入口，自动生成 `uir/gen/` 下的所有接口、实现、Visitor、Builder
> 3. 生成的代码遵循 `DO NOT MODIFY IT MANUALLY` 原则，修改 IR 结构只需改配置后重新生成

```
aiFuzzer/
├── docs/
│   ├── UIR_DESIGN.md           # 统一 IR 设计文档
│   ├── PROJECT_PLAN.md         # 本文件
│   └── validation_report.md    # (Phase 4) 验证报告
├── libs/
│   └── tree-generator-common.jar  # Kotlin 编译器的 IR 树生成器 (64MB shadowJar)
├── uir/                        # 统一 IR 模块
│   ├── gen/                    # (自动生成) 由 tree-generator 产生的 IR 代码
│   │   ├── UirElement.kt
│   │   ├── UirProgram.kt
│   │   ├── UirGraph.kt
│   │   ├── UirNode.kt
│   │   ├── types/              # 类型系统
│   │   ├── ops/                # 算子枚举
│   │   ├── builder/            # Builder 模式构造器
│   │   ├── visitor/            # Visitor/Transformer
│   │   └── serialize/          # 序列化 (手写，非自动生成)
│   └── ...
├── uir-builder/                # (类似于 tree/tree-generator)
│   ├── build.gradle.kts        # 依赖 tree-generator-common.jar
│   └── src/main/kotlin/...
│       ├── TreeBuilder.kt      # IR 元素定义配置
│       ├── ImplConfigurator.kt # 实现配置
│       ├── BuilderConfigurator.kt # Builder 配置
│       └── main.kt             # 入口，启动生成
├── translator/                 # 编译器翻译器
│   ├── ...
├── generator/                  # IR 生成器
│   ├── ...
├── fuzzer/                     # 模糊测试引擎
│   ├── ...
├── cli/                        # 命令行入口
├── test/                       # 测试用例
│   └── ...
└── build.gradle.kts
```

---

## Phase 1: IR 框架简单实现

### 目标

实现 UIR 最小编码于集：能完整表达**一个到多个算子、有类型标注的 DAG 计算图**。

### 1.1 核心 IR 接口 (通过 tree-generator 自动生成)

**不手写 IR 接口/实现/Visitor/Builder**。参考 CrossLangFuzzer 的 `tree/tree-generator`，在 `uir-builder/` 模块中编写 Kotlin 配置，运行后自动生成到 `uir/gen/`。

需要编写的配置文件和自动生成的文件:

| 配置 (uir-builder/src) | 自动生成 (uir/gen) | 对应 CrossLangFuzzer 的 gen |
|------|------|------|
| `TreeBuilder.kt` — 定义 Element 树 | `UirElement.kt` | `IrElement.kt` |
| 同上 | `UirNode.kt` (新定义) | — |
| 同上 | `UirProgram.kt` | `IrProgram.kt` |
| 同上 | `UirGraph.kt` (新定义) | — |
| 同上 | `UirValue.kt` (新定义) | — |
| 同上 | `types/UirType.kt` | `IrType.kt` |
| 同上 | `types/UirTensorType.kt` | `IrClassifier` |
| 同上 | `types/UirDataType.kt` (新定义) | — |
| 同上 | `types/UirShape.kt` (新定义) | — |
| 同上 | `types/UirLayout.kt` (新定义) | — |
| 同上 | `ops/UirOp.kt` (新定义) | — |
| 同上 | `visitor/UirVisitor.kt` | `IrVisitor.kt` |
| 同上 | `builder/UirGraphBuilder.kt` | Builder 模块 |
| `ImplConfigurator.kt` | `impl/` 下的实现类 | 各 `*Impl.kt` 文件 |
| `BuilderConfigurator.kt` | `builder/` 下的 Builder | `builder/` 目录 |

### 1.2 Phase 1 算子子集

先实现**最核心的 15 个算子**，保证能覆盖：

| 算子类别 | 具体算子 | 验证点 |
|----------|----------|--------|
| 一元 | relu, sigmoid, tanh, abs, exp, cast | 张量形状不变 |
| 二元 | add, subtract, multiply, matmul | 广播 + 矩阵乘法 |
| 归约 | reduce_sum | 形状压缩 + keepdims |
| 形状 | reshape, transpose, concat | 形状变换 |
| NN | conv2d, max_pool2d | 复杂属性（stride/padding/dilation） |

### 1.3 输入/输出格式

- **UIR 序列化**: JSON 格式（参考 UIR_DESIGN.md 5.2）
- **从 JSON 反序列化**: 构建 UIR 对象树

### 1.4 Phase 1 完成标准

- [ ] 所有 14 个接口/类定义完成，编译成功
- [ ] Visitor/Builder 模式工作正常
- [ ] 可以手工构建一个最小 UIR 程序（relu + add）并 JSON 序列化
- [ ] 从 JSON 反序列化后可以正确重建 UIR 对象
- [ ] UirGraph 支持 DAG 的拓扑排序

---

## Phase 2: 打印器到被测 AI 编译器源码

### 目标

实现 `UIR → 目标编译器源码` 的翻译模块。参考 CrossLangFuzzer 的 `IrPrinter` 接口 + `IrProgramPrinter` 路由模式。

### 2.1 核心架构

每个编译器实现一个 `UirTranslator`，统一接口：

```kotlin
interface UirTranslator<out T> {
    fun translate(program: UirProgram, config: TranslatorConfig): T
}
```

### 2.2 具体任务

| 子任务 | 文件 | 说明 |
|--------|------|------|
| 2-1 | `translator/TranslatorConfig.kt` | 配置（输出目录、target 等） |
| 2-2 | `translator/UirTranslator.kt` | 接口定义 |
| 2-3 | `translator/tvm/TvmRelaxTranslator.kt` | TVM 翻译器：UIR → Python + Relax API |
| 2-4 | `translator/onnx/OnnxTranslator.kt` | ONNX 翻译器：UIR → ONNX ModelProto |
| 2-5 | `translator/iree/IreeStablehloTranslator.kt` | IREE 翻译器：UIR → StableHLO MLIR |

### 2.3 TVM 翻译器的实现细节

```
UirNode(conv2d, inputs=[x, w], attrs={strides:[1,1], padding:[0,0]})
    ↓ 翻译
bb.emit(relax.op.nn.conv2d(x_val, w_val, strides=(1,1), padding=(0,0)))
```

关键映射表（UIR 算子名 → TVM API 名）：

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

### 2.4 ONNX 翻译器的实现细节

```
UirNode(add, inputs=[a, b], outputs=[c])
    ↓ 翻译
onnx.helper.make_node("Add", inputs=["a", "b"], outputs=["c"])
```

| UIR | ONNX |
|-----|------|
| `transpose` | `Transpose` (属性: perm) |
| `gather` | `Gather` (属性: axis) |
| `conv2d` | `Conv` (属性: strides, pads, dilations, group) |
| `reshape` | `Reshape` (allowzero) |

### 2.5 设计模式 (继承 CrossLangFuzzer)

参考 `IrProgramPrinter.print(element: IrProgram): Map<String, String>`：

- 每个 Translator 输出**文件集**: `Map<String, String>`（文件名 → 内容）
- TVM: `{"main.py": "import tvm\nfrom tvm import relax\n..."}`
- ONNX: `{"model.onnx": <binary>}` 或 `{"model.pb": <binary>}`
- IREE: `{"main.mlir": "module { ... }"}`

### 2.6 Phase 2 完成标准

- [ ] UirTranslator 接口定义完成
- [ ] TVM 翻译器完成，UIR → 可执行的 TVM Python 脚本
- [ ] ONNX 翻译器完成，UIR → 有效的 .onnx 模型文件
- [ ] IREE 翻译器完成，UIR → 可编译的 StableHLO MLIR 文件
- [ ] 用 Phase 1 的 15 个算子手工构建 UIR 程序，通过三个阶段翻译器产生输出

---

## Phase 3: 自动化测试框架

### 目标

搭建完整的自动化测试闭环：`生成 UIR → 翻译 → 编译 → 执行 → Oracle 诊断 → 报告`。

### 3.1 模块实现

| 子任务 | 文件 | 说明 |
|--------|------|------|
| 3-1 | `fuzzer/AiFuzzer.kt` | 主循环控制器 |
| 3-2 | `generator/UirGenerator.kt` | 随机图生成器 |
| 3-3 | `generator/OpEnumerator.kt` | 算子枚举 + 类型约束解析 |
| 3-4 | `generator/ShapeCombinator.kt` | 形状组合 + 符号形状支持 |
| 3-5 | `fuzzer/oracle/CompileOracle.kt` | 检查编译成功/失败 |
| 3-6 | `fuzzer/oracle/ShapeOracle.kt` | 检查形状推断一致性 |
| 3-7 | `fuzzer/oracle/ValueOracle.kt` | 跨编译器数值对比 |
| 3-8 | `fuzzer/MutationEngine.kt` | 对已有图做变异 |
| 3-9 | `fuzzer/ShrinkEngine.kt` | DDMin 缩减 bug 用例 |
| 3-10 | `cli/CompilerDriver.kt` | 调用外部编译器执行 |

### 3.2 测试流程

```
1. UirGenerator 生成 UIR 程序
2. 通过各 Translator 翻译成具体编译器输入
3. CompilerDriver 调用编译器编译并执行
4. Oracle 对结果做诊断:
   a) CompileOracle: 编译是否成功？所有编译器应一致
   b) ShapeOracle: 形状推断是否一致？
   c) ValueOracle: 输出是否数值一致（跨编译器）？
5. 发现不一致 → ShrinkEngine 缩减 → 报告
```

### 3.3 Oracle 策略详解

```
三类 Oracle:

1. 编译 Oracle (CompileOracle)
   - 合法 UIR → 所有编译器应成功编译
   - 非法 UIR → 所有编译器应拒绝
   - 不一致 → 某个编译器有 bug

2. 形状 Oracle (ShapeOracle)  
   - 动态形状 UIR → 各编译器形状推断应一致
   - 不一致 → 推断 bug

3. 数值 Oracle (ValueOracle) — 最核心
   - 同一 UIR → 多编译器执行结果应数值接近
   - 使用相对误差: |A - B| / max(|A|, |B|) < ε (ε=1e-5)
   - 也支持 metamorphic: 原图 o 等价变换后的图 o'，输出应一致
```

### 3.4 Phase 3 完成标准

- [ ] 随机 UIR 生成器能产生合法计算图（15 个算子集）
- [ ] 自动化流程：生成 → 翻译 → 编译 → oracle → 报告，无需人工干预
- [ ] 能连续运行 100+ 个随机测试不崩溃
- [ ] 至少 3 种不同的图生成策略（随机、枚举、模板）
- [ ] 变异引擎实现（算子替换、插入、删除）
- [ ] 缩减引擎实现（DDMin 算法）
- [ ] 命令行入口可用

---

## Phase 4: 验证与全面自动化

### 目标

从手动验证过渡到全自动模糊测试流水线。

### 4.1 手动验证期 (4a → 4b → 4c)

| 步骤 | 内容 | 产出 |
|------|------|------|
| 4-a | 手写 20 个 UIR 测试用例，覆盖所有 15 个算子的各种形状/类型组合 | `test/ir/manual/` 下的 JSON 文件 |
| 4-b | 手动运行每个用例在三个编译器上，确认翻译 + 编译都正确 | 验证日志：`docs/validation_report.md` |
| 4-c | 修正发现的问题（翻译器错误/IR 设计缺陷） | 翻译器和 UIR 的修正补丁 |
| 4-d | 在修正后的基础上运行自动生成器 1000 轮 | 自动化测试日志 |

### 4.2 验证检查清单

对每个手工 UIR 用例，检查：

- [ ] UIR JSON 反序列化正常
- [ ] TVM 翻译器输出可被 TVM Python 执行
- [ ] ONNX 翻译器输出可通过 `onnx.checker.check_model()`
- [ ] IREE 翻译器输出可被 IREE 编译
- [ ] 三个编译器执行同一 UIR 的数值输出一致 (相对误差 < 1e-5)
- [ ] 形状推断结果一致
- [ ] 翻译生成的代码中算子名和属性正确映射

### 4.3 全自动化上线

- [ ] 所有手工 UIR 验证通过
- [ ] 生成器能生成 1000+ 种不同的合法 UIR
- [ ] 自动化流水线 7x24 运行
- [ ] Bug 报告自动生成（UIR + 编译器日志 + 差异分析）
- [ ] CI 集成（可选的 GitHub Actions）
- [ ] 持续测试至少 1 个 TVM 和 ONNX 的已知 bug 能被发现

---

## 时间线估算

| Phase | 预估时间 | 依赖 | 关键产出 |
|-------|----------|------|----------|
| Phase 1 | 核心 IR | Phase 1 完成 | IR 定义 + JSON 序列化 |
| Phase 2 | 翻译器 | Phase 1 | 三语言翻译器 |
| Phase 3 | 自动化框架 | Phase 1, 2 | 端到端流水线 |
| Phase 4 | 验证 + 全自动 | Phase 3 | 验证报告 + CI |

---

## 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| TVM 算子 API 变化 | 翻译器失效 | 跟随 TVM 版本更新映射表 |
| IREE 编译环境难配 | Phase 2 延迟 | Phase 2 先做 TVM+ONNX，IREE 后做 |
| 数值 Oracle 浮点不一致 | 假阳性 | 使用适中的 ε + 对数误差 + ULP 比较 |
| 生成器爆炸 | 超长 UIR | 限制节点数（≤ 50）、张量大小（≤ 4 维） |
| 编译器崩溃 | 测试中断 | 每个编译器独立进程，超时 kill |

---

## 参考项目组织对照 (CrossLangFuzzer → AiFuzzer)

| CrossLangFuzzer 组件 | AiFuzzer 对应 | 所在目录 |
|----------------------|---------------|----------|
| `tree/gen/ir/IrElement` | `UirElement` | `uir/core/` |
| `tree/gen/ir/IrProgram` | `UirProgram` | `uir/core/` |
| `tree/gen/ir/types/IrType` | `UirType` | `uir/types/` |
| `tree/gen/ir/types/IrClassifier` | `UirTensorType` | `uir/types/` |
| `tree/gen/ir/declarations/IrClassDeclaration` | `UirNode` (无类的概念) | `uir/core/` |
| `tree/gen/ir/expressions/IrExpression` | `UirOp` + 属性 | `uir/core/` + `uir/ops/` |
| `tree/gen/ir/visitors/IrVisitor` | `UirVisitor` | `uir/visitor/` |
| `tree/gen/builder/` | `uir/builder/` | `uir/builder/` |
| `src/printer/IrPrinter` | `UirTranslator` | `translator/` |
| `src/printer/IrProgramPrinter` | `translator/tvm/TvmRelaxTranslator` | `translator/tvm/` |
| `src/printer/clazz/JavaIrClassPrinter` | `translator/onnx/OnnxTranslator` | `translator/onnx/` |
| `src/algorithm/DDMin` | `ShrinkEngine` | `fuzzer/` |
| `src/algorithm/GeneticAlgorithm` | `MutationEngine` / `UirGenerator` | `fuzzer/` / `generator/` |
| `src/Main` | `cli/Main` | `cli/` |
