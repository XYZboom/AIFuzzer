# 模型转换测试方案

## 背景

nnsmith 的能力之一是**模型转换测试**：生成一个 ONNX 模型，通过 TVM 的 `relax.frontend.onnx.from_onnx()` 导入 TVM，同时运行 ONNX Runtime 和 TVM 两个版本，比较输出以发现转换管道的 bug。

我们的 AIFuzzer 目前只测试**各个后端独立编译/运行是否成功**，没有测试模型转换路径。但底层翻译器已经能生成 ONNX 和 PyTorch 模型，具备加这一能力的条件。

## 目标

支持三种模型转换测试模式：

1. **ONNX → TVM 转换测试** — 对标 nnsmith，测试 TVM 的 ONNX frontend 是否正确
2. **PyTorch → ONNX export 测试** — 测试 PyTorch 的 `torch.onnx.export()` 是否正确
3. **跨后端差分测试** — 同一 UIR 翻译到三个后端，比较输出语义一致性（我们的独特优势）

## 架构变更

### 总览

当前 daemon 只返回 `success/fail`（二元），不返回数值输出。要支持差分测试，需要：

- daemon 层：运行后把模型输出 tensor 序列化返回
- Kotlin 层：新增 `BackendResult` 字段（输出数据），新增差分比较逻辑
- 新增 CLI 子命令 `convert-test` 或 `diff-test`

### 1. Daemon 改造：返回输出数据

#### TVM daemon (`daemon/tvm_daemon.py`)

新增 `/run_with_output` 端点，返回输出 tensor 的 numpy bytes（base64 编码）。

```python
# 在现有 run_source 基础上，增加输出捕获
# 执行后：从 globals() 中提取 output_tensor，序列化返回
{
    "success": true,
    "outputs": {
        "output_0": {
            "shape": [1, 3, 224, 224],
            "dtype": "float32",
            "data": "eJzr..."  # base64(numpy.tobytes())
        }
    },
    "stdout": "...",
    "stderr": "...",
    "elapsed_ms": 100
}
```

**关键设计**：不修改现有 `/run` 端点（现有 fuzzing 和 dedup-eval 继续用 success/fail）。新增 `/run_with_output` 端点，仅用于差分测试。

#### PyTorch daemon (`daemon/pytorch_daemon.py`)

同理，新增 `/run_with_output` 端点。

PyTorch translator 生成的代码需要在最后输出 `print("__OUTPUT__:" + numpy_output.tobytes().hex())` 或类似格式，daemon 解析 stdout 提取。

#### ONNX daemon (`daemon/onnx_daemon.py`)

同理，新增 `/run_with_output` 端点。

#### 新增：TVM ONNX frontend daemon (`daemon/tvm_onnx_frontend_daemon.py`)

**这是最核心的新增组件**。专门用于 ONNX→TVM 转换测试。

```python
"""
TVM ONNX Frontend Daemon — 测试 ONNX → TVM 转换路径

API:
  POST /run_onnx  {"onnx_bytes": "<base64_onnx_model>", "input_shapes": {"x": [1,3,224,224]}}
                   → {"success": true, "outputs": {...}, "elapsed_ms": 123}

流程:
  1. 解码 base64 ONNX 模型
  2. 用 tvm.relax.frontend.onnx.from_onnx(model, shape_dict) 导入 TVM
  3. 编译并运行
  4. 返回输出 tensor
"""
```

### 2. Kotlin 层：新增数据结构

#### `BackendResult` 扩展

```kotlin
data class BackendResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val elapsedMs: Long,
    // 新增：差分测试用
    val outputs: Map<String, TensorData>? = null,  // output_name → tensor data
)

data class TensorData(
    val shape: List<Int>,
    val dtype: String,
    val data: ByteArray,  // numpy bytes
)
```

#### 新增 `DifferentialConfig`

```kotlin
data class DifferentialConfig(
    val enabled: Boolean = false,
    val rtol: Float = 1e-3f,
    val atol: Float = 1e-5f,
    val mode: DifferentialMode = DifferentialMode.CROSS_BACKEND,
    // 仅 CONVERSION 模式需要
    val conversionSource: String = "onnx",  // onnx / pytorch
    val conversionTarget: String = "tvm",    // tvm / onnxruntime
)

enum class DifferentialMode {
    CROSS_BACKEND,   // 同一 UIR → 三个后端，比较输出
    CONVERSION,      // 生成 ONNX/PyTorch → 导入目标框架，比较输出
}
```

#### 新增 `ConversionTestPipeline`

```kotlin
/**
 * 模型转换测试流水线。
 *
 * 与 FuzzingPipeline 共用 UIR 生成器，但执行逻辑不同：
 * - 生成 UIR 程序
 * - 翻译为源格式（ONNX/PyTorch）
 * - 在源框架运行（参考输出）
 * - 通过转换路径导入目标框架运行
 * - 比较输出
 */
class ConversionTestPipeline(
    private val generatorConfig: GeneratorConfig,
    private val diffConfig: DifferentialConfig,
) {
    fun runConversionTest(seed: Long): ConversionTestResult {
        // 1. 生成 UIR
        val program = generator.generate()
        
        // 2. 翻译为源格式，运行参考版本
        // 3. 通过转换路径导入目标框架，运行
        // 4. 比较输出
        // 5. 返回结果（PASS / FAIL / ERROR）
    }
}
```

### 3. 三种模式的具体流程

#### 模式 A：ONNX → TVM 转换测试

```
UIR → OnnxTranslator → ONNX 模型
                        ├── ONNX Runtime 运行 → 参考输出（tensor A）
                        └── tvm_onnx_frontend_daemon（from_onnx 导入）
                             └── TVM 编译运行 → 转换后输出（tensor B）
                                 比较 A vs B（allclose）
```

#### 模式 B：PyTorch → ONNX export 测试

```
UIR → PytorchTranslator → PyTorch 模型
                           ├── torch.compile 运行 → 参考输出（tensor A）
                           └── torch.onnx.export() → ONNX 模型
                                └── ONNX Runtime 运行 → 导出后输出（tensor B）
                                    比较 A vs B（allclose）
```

#### 模式 C：跨后端差分测试

```
UIR → TvmRelaxTranslator → TVM 运行 → 输出 A
UIR → PytorchTranslator  → PyTorch 运行 → 输出 B
UIR → OnnxTranslator     → ONNX 运行 → 输出 C

两两比较：A vs B, B vs C, A vs C
```

### 4. CLI 子命令

```bash
# 模式 A：ONNX → TVM 转换测试
gradle :run --args="convert-test -c configs/onnx-tvm-conversion.yaml -n 1000"

# 模式 B：PyTorch → ONNX export 测试
gradle :run --args="convert-test -c configs/pytorch-onnx-export.yaml -n 1000"

# 模式 C：跨后端差分测试
gradle :run --args="diff-test -c configs/cross-backend-diff.yaml -n 1000"
```

### 5. 配置示例

```yaml
# configs/onnx-tvm-conversion.yaml
run:
  description: "ONNX → TVM 转换测试（1000 seeds）"
  output_dir: "./reports/onnx-tvm-conversion"

generator:
  min_nodes_per_graph: 3
  max_nodes_per_graph: 8
  mutation:
    enabled: false

differential:
  enabled: true
  mode: "conversion"  # conversion / cross_backend
  conversion_source: "onnx"
  conversion_target: "tvm"
  rtol: 1e-3
  atol: 1e-5

backends:
  enabled: ["onnx", "tvm"]
  tvm:
    python: "/root/miniconda3/envs/aifuzzer/bin/python3"
    target: "cuda"
    device: "cuda"
  onnx:
    python: "/root/miniconda3/envs/aifuzzer/bin/python3"
```

## 实现优先级

### P0（最小可行，对标 nnsmith）

**ONNX → TVM 转换测试**（模式 A）

- 改造 TVM ONNX frontend daemon（新增）
- 改造 ONNX daemon 返回输出（新增 `/run_with_output`）
- Kotlin 层：`ConversionTestPipeline` 核心逻辑
- CLI 子命令 `convert-test`

### P1（扩展）

**PyTorch → ONNX export 测试**（模式 B）

- 改造 PyTorch daemon 返回输出
- 改造 ONNX daemon 支持 PyTorch export 来的 ONNX 模型
- 扩展 `ConversionTestPipeline`

### P2（我们的独特优势）

**跨后端差分测试**（模式 C）

- 改造所有三个 daemon 返回输出
- 新增 `DifferentialTestPipeline` 或扩展 `FuzzingPipeline`
- CLI 子命令 `diff-test`

## 输出格式

每次转换测试报告：

```
=== Conversion Test Report ===
Mode: ONNX → TVM
Seeds: 1000
  PASS: 892  (输出一致)
  FAIL:  78  (输出不一致 → 转换 bug)
  ERROR: 30  (某一端崩溃，无法比较)

Failed seeds (output mismatch):
  seed 42:   ONNX output=[1.0, 2.0, 3.0], TVM output=[0.9, 2.1, 2.8]  max_diff=0.2
  seed 107:  ONNX shape=[1,3,4,4], TVM shape=[1,3,4,5]  shape mismatch
  ...

Conversion bugs found: 3
  Bug 1: seed 42, op=SOFTMAX, axis=1 → TVM from_onnx 输出 axis 偏移
  Bug 2: seed 107, op=RESHAPE, TVM from_onnx 动态 shape 推导错误
  Bug 3: seed 511, op=CONV2D, groups>1 → TVM from_onnx 权重顺序错误
```

## 风险与注意事项

### 1. 翻译器保真度先决条件

ONNX→TVM 转换测试的前提是：**我们的 ONNX 翻译器能生成正确的 ONNX 模型**。如果 UIR ONNX 翻译器本身有 bug，生成的 ONNX 模型就不合法，那 TVM frontend 导入失败不是 TVM 的 bug。

**对策**：先用 ONNX Runtime 验证 ONNX 翻译器输出（目前 fuzzing 已经在做这个——成功/失败判定），只有 ONNX Runtime 能跑通的 seed 才进入转换测试。

### 2. 输出对齐问题

不同框架对相同算子的输出可能因数值精度、默认值差异而有细微不同。allclose 的 rtol/atol 需要合理设置（默认 1e-3/1e-5），并可配置。

### 3. 多输出算子

某些算子有多个输出（如 `MAX` 同时返回 values 和 indices、`CONV2D_BACKWARD` 等）。需要确保所有输出都被比较。

### 4. 动态 shape

TVM 的 `from_onnx()` 需要 `shape_dict` 指定输入 shape。我们的 ONNX 模型是静态 shape 的（UIR 生成时就确定了），所以不成问题。

## 与现有架构的关系

```
FuzzingPipeline（现有）
├── runBatch()       → 只测成功/失败，不加输出比较
├── runDedupEval()   → 4 分类，不加输出比较
└── runOnce()        → 单次，不加输出比较

ConversionTestPipeline（新增）
├── runConversionTest() → ONNX→TVM / PyTorch→ONNX 转换测试
└── runDiffTest()       → 跨后端差分测试
```

**不修改现有 FuzzingPipeline**——保持现有 fuzzing 和 dedup-eval 逻辑不变。新增的 `ConversionTestPipeline` 独立于现有流水线，与 FuzzingPipeline 共享 UIR 生成器和翻译器。

## 实现状态（2026-08-08）

### 已实现：ONNX → TVM 转换测试（P0）

**CLI 命令**：
```bash
gradle :run --args="convert-test -c configs/onnx-tvm-convert-test.yaml -n 50 -s 1000"
```

**新增文件**：
| 文件 | 作用 |
|------|------|
| `daemon/tvm_onnx_frontend_daemon.py` | TVM ONNX frontend daemon：接收 ONNX 模型 bytes，用 `from_onnx()` 导入 TVM，编译运行，返回输出 tensor |
| `daemon/onnx_daemon.py`（修改） | 新增 `/run_with_output` 端点，返回输出 tensor + 输入 tensor + 序列化模型 bytes |
| `fuzzer/ConversionTest.kt` | 转换测试配置 + 结果 + 汇总 |
| `fuzzer/ConversionTestPipeline.kt` | 转换测试流水线（核心逻辑） |
| `fuzzer/DaemonClient.kt`（修改） | 新增 `sendAndWaitWithOutput()` + `OutputData` 序列化类型 |
| `cli/ConvertTestCommand.kt` | CLI 命令 |
| `configs/onnx-tvm-convert-test.yaml` | 测试配置 |

**架构流程**：
```
UIR 生成器（强制单图 graphCount=1..1）
    → OnnxTranslator 翻译为 ONNX Python 代码
    → ONNX daemon（/run_with_output）运行，得到：
        - ONNX Runtime 参考输出（output tensor）
        - 序列化 ONNX 模型 bytes
        - 输入 tensor
    → 发送模型 bytes + 输入 tensor 到 TVM frontend daemon
    → TVM（from_onnx 导入）编译运行，得到 TVM 输出
    → 比较两者的输出（shape + dtype + data_b64）
```

**关键设计决策**：
1. **多图合并**：转换测试强制 `graphCount=1..1`，让整个程序合并为单个 ONNX 模型，TVM frontend 测的是完整模型而非第一个子图。
2. **输入复用**：从 ONNX 捕获输入 tensor 传给 TVM，保证两者用相同输入运行，输出数值可比较。
3. **多输出处理**：TVM 前端多输出返回 `tvm_ffi.container.Array`（非 list/tuple），daemon 需识别可迭代容器，否则错误调用 `.numpy()` 产生 shape `[2]` 的假失败。
4. **数值比较**：`data_b64` 相同则数值完全一致；不同则视为不匹配（精度差异/转换 bug）。

**验证结果**（50 seeds，~16s）：
- PASS 29/50 (58%)：ONNX Runtime 与 TVM 输出一致
- FAIL 21/50：输出数值不一致（多为 ONNX/TVM 浮点精度差异）
- Target error 2/50：TVM frontend 无法处理（如 `Scan` op 不支持）

**发现的 TVM frontend 问题示例**：
- `Scan` op：TVM frontend 无法导入（target error）
- `Log` + `Div`：数值精度差异导致输出不一致

### 待实现（P1/P2）

- **PyTorch → ONNX export 测试**（P1）：`runPytorchToOnnx()` 已留空
- **跨后端差分测试**（P2）：比较 TVM/PyTorch/ONNX 三者输出
- **数值比较容差**：当前用 `data_b64` 精确比较，未来应支持 rtol/atol 下的 `allclose` 宽松比较，区分"精度差异"和"真实 bug"
- **多图程序转换**：当前强制单图，未来应支持多图合并为单 ONNX 模型