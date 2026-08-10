# 前端/后端配置设计

## 问题

AI 编译器（TVM、PyTorch、ONNX Runtime）各自有**前端**（如何导入模型）和**后端**（如何编译优化）。我们的 fuzzing 需要同时指定这两者，例如：

- 通过 TVM 的 ONNX frontend 导入模型，用 CUDA 后端编译 → `frontend=onnx, backend=cuda`
- 通过 TVM 的 Relax frontend 直接导入 UIR，用 LLVM 后端编译 → `frontend=relax, backend=llvm`
- 通过 PyTorch 的 ONNX export 导出模型，在 ONNX Runtime 上运行 → `frontend=onnx, backend=cpu`

当前配置只指定了"后端"（target/device），没有指定"前端"（模型导入方式）。

## 设计

### 被测对象

**UIR 是我们的测试生成器，不是被测对象。** 被测对象是 AI 编译器（TVM、PyTorch、ONNX Runtime），每个编译器有自己的前端和后端。

### 核心改动

每个后端配置新增 `frontend` 字段，指定该编译器的模型导入方式。

```yaml
backends:
  enabled: ["tvm", "pytorch"]
  
  tvm:
    # 前端：模型如何导入 TVM
    #   "relax"（默认）— 直接翻译 UIR→Relax Python 脚本，传给 TVM daemon 编译运行
    #   "onnx"           — 翻译 UIR→ONNX 模型，通过 TVM 的 relax.frontend.onnx.from_onnx() 导入
    #   "pytorch"        — 翻译 UIR→PyTorch 模型，通过 TVM 的 relax.frontend.pytorch 导入
    frontend: "relax"
    # 后端：编译目标和执行设备
    target: "cuda"
    device: "cuda"
    
  pytorch:
    # 前端：模型如何导入 PyTorch
    #   "pytorch"（默认）— 直接翻译 UIR→PyTorch nn.Module 代码，torch.compile 编译运行
    #   "onnx"           — 翻译 UIR→ONNX 模型，通过 torch.onnx.export() 导出后在 ONNX Runtime 运行
    frontend: "pytorch"
    device: "cuda"
  
  onnx:
    # 前端：模型如何导入 ONNX Runtime
    #   "onnx"（默认）— 直接翻译 UIR→ONNX 模型，ONNX Runtime 直接运行
    #   "pytorch"      — 翻译 UIR→PyTorch，通过 torch.onnx.export() 导出后在 ONNX Runtime 运行
    frontend: "onnx"
```

### 数据结构

```kotlin
// BackendsConfig 不变，不新增字段
data class BackendsConfig(
    var enabled: List<String> = listOf("tvm"),
    var tvm: TvmConfig = TvmConfig(),
    var onnx: OnnxConfig = OnnxConfig(),
    var iree: IreeConfig = IreeConfig(),
    var pytorch: PytorchConfig = PytorchConfig(),
    var remote: RemoteSshConfig? = null,
)

// 每个后端配置新增 frontend 字段
data class TvmConfig(
    /** 前端：relax（默认，直接 UIR→Relax）/ onnx（通过 ONNX frontend 导入）/ pytorch */
    var frontend: String = "relax",
    // ... 现有字段不变
    var target: String = "llvm",
    var device: String = "cpu",
    var crossTargetDifferential: Boolean = false,
    var remote: RemoteSshConfig? = null,
)

data class PytorchConfig(
    /** 前端：pytorch（默认，直接 UIR→PyTorch）/ onnx（通过 ONNX export） */
    var frontend: String = "pytorch",
    var device: String = "cpu",
    var crossTargetDifferential: Boolean = false,
    var remote: RemoteSshConfig? = null,
)

data class OnnxConfig(
    /** 前端：onnx（默认，直接 UIR→ONNX）/ pytorch（通过 PyTorch→ONNX export） */
    var frontend: String = "onnx",
    var opsetVersion: Int = 21,
    var remote: RemoteSshConfig? = null,
)
```

### frontend 取值及对应行为

| 后端 | frontend 值 | 翻译器 | 导入方式 | 参考后端 |
|------|------------|--------|---------|---------|
| tvm | `"relax"`（默认） | TvmRelaxTranslator | 直接生成 Relax 脚本，TVM daemon 编译运行 | 无（现有行为） |
| tvm | `"onnx"` | OnnxTranslator | TVM `relax.frontend.onnx.from_onnx()` 导入 | ONNX Runtime |
| tvm | `"pytorch"` | PytorchTranslator | TVM `relax.frontend.pytorch.from_pytorch()` 导入 | torch.compile |
| pytorch | `"pytorch"`（默认） | PytorchTranslator | 直接生成 nn.Module + torch.compile | 无（现有行为） |
| pytorch | `"onnx"` | OnnxTranslator | `torch.onnx.export()` 导出后 ONNX Runtime 运行 | ONNX Runtime |
| onnx | `"onnx"`（默认） | OnnxTranslator | 直接生成 ONNX 模型，ONNX Runtime 运行 | 无（现有行为） |
| onnx | `"pytorch"` | PytorchTranslator | `torch.onnx.export()` 导出后 ONNX Runtime 运行 | torch.compile |

### 执行流程

#### `frontend="relax"`（默认，现有行为）

```
UIR 生成器
  → TvmRelaxTranslator → Relax 代码 → TVM daemon 编译+运行
  → 返回 success/fail
```

#### `frontend="onnx"`（转换测试）

```
UIR 生成器（强制单图）
  → OnnxTranslator → ONNX Python 代码
  → ONNX daemon 运行（参考输出 + 模型 bytes）
  → 目标后端 daemon（如 TVM from_onnx）导入并运行
  → 比较参考输出和目标输出（shape + dtype + 数值）
```

#### `frontend="pytorch"`（转换测试）

```
UIR 生成器（强制单图）
  → PytorchTranslator → PyTorch Python 代码
  → PyTorch daemon 运行（参考输出 + 模型）
  → 目标后端 daemon 导入并运行
  → 比较参考输出和目标输出
```

### 关键设计决策

1. **UIR 不是被测对象**：`frontend` 字段描述的是**被测 AI 编译器如何导入模型**，不是 UIR 的格式。UIR 始终是我们的测试生成器。

2. **`frontend` 决定翻译器**：每个后端根据 `frontend` 值选择不同的翻译器（OnnxTranslator / PytorchTranslator / TvmRelaxTranslator），而不是固定使用与后端名称对应的翻译器。

3. **参考后端自动确定**：当 `frontend != "relax"` 且 `frontend != "pytorch"` 时，需要参考后端来产生"正确"输出。参考后端由 `frontend` 值决定：
   - `frontend="onnx"` → 参考后端 = ONNX Runtime
   - `frontend="pytorch"` → 参考后端 = torch.compile

4. **`crossTargetDifferential` 独立于 frontend**：`crossTargetDifferential` 用于测试同一后端在不同设备上的输出一致性（如 CPU vs GPU），与 `frontend` 正交。两者可以组合使用。

5. **FuzzingPipeline 不修改**：现有 `FuzzingPipeline.runOnce()` 的逻辑不变。当 `frontend != "relax"` 时，`runOnce()` 走新的转换测试路径，但仍然返回 `FuzzingResult` 列表。

### 与现有 CLI 的关系

```bash
# 现有用法不变（frontend=relax 默认）
gradle :run --args="fuzz -c configs/tvm-cuda.yaml"

# 新用法：ONNX→TVM 转换测试
gradle :run --args="fuzz -c configs/onnx-tvm-convert.yaml"
# 配置示例：
# backends:
#   enabled: ["tvm"]
#   tvm:
#     frontend: "onnx"
#     target: "cuda"
#     device: "cuda"

# 命令行覆盖 frontend
gradle :run --args="fuzz -c configs/tvm-cuda.yaml --frontend=onnx"
```

### 实现优先级

| 优先级 | 前端 | 后端 | 状态 |
|--------|------|------|------|
| P0 | `"relax"`（默认） | tvm/pytorch/onnx | ✅ 已有，零改动 |
| P0 | `"onnx"` | tvm | ✅ 已实现（原 convert-test 命令） |
| P1 | `"pytorch"` | tvm | 待实现 |
| P1 | `"onnx"` | pytorch | 待实现 |
| P2 | `"pytorch"` | onnx | 待实现 |