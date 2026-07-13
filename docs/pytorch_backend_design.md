# PyTorch.compile Backend Design

> 为 aiFuzzer 添加 PyTorch 2.0 `torch.compile` 后端支持的设计文档
> 
> 作者：Claw (glm-5.2)  
> 日期：2026-07-11

## 目录

1. [概述](#概述)
2. [架构设计](#架构设计)
3. [翻译器设计](#翻译器设计)
4. [Daemon 运行器设计](#daemon-运行器设计)
5. [集成方案](#集成方案)
6. [实现计划](#实现计划)

---

## 概述

### 背景

PyTorch 2.0 引入了 `torch.compile`，基于 Dynamo（动态图捕获）和 Inductor（编译优化）的新一代编译技术。这为测试 AI 编译器提供了新目标。

### 目标

1. **翻译器**：将 UIR（统一中间表示）翻译为 PyTorch Python 代码
2. **Daemon 运行器**：常驻 Python 进程，避免重复加载 PyTorch，提高 fuzzing 效率

### 与 TVM 后端的对比

| 特性 | TVM Relax | PyTorch.compile |
|------|-----------|-----------------|
| IR 形式 | Relax IR (TVM 内部) | PyTorch 原生代码 |
| 编译方式 | BlockBuilder + Relax ops | `torch.compile` 装饰器 |
| 动态性 | 静态形状为主 | 支持动态形状 |
| 编译后端 | TVM 内部 | Inductor, AOTI, etc. |
| 错误类型 | TVM 编译/运行错误 | Dynamo 捕获错误, Inductor 编译错误 |

---

## 架构设计

### 组件关系图

```
┌─────────────────────────────────────────────────────────────┐
│                     FuzzingPipeline                         │
│  (生成 → 执行 → 收集 → 分析)                                 │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ├── TvmDaemonBackend
                       │
                       └── PytorchDaemonBackend ← 新增
                                   │
                                   ├─ PytorchTranslator ← 新增
                                   │
                                   └─ pytorch_daemon.py ← 新增
```

### 文件结构

```
src/main/kotlin/io/github/xyzboom/aiFuzzer/
├── translator/
│   ├── UirTranslator.kt              # 接口（已有）
│   ├── tvm/
│   │   └── TvmRelaxTranslator.kt     # TVM 翻译器（已有）
│   └── pytorch/                       # 新增目录
│       └── PytorchTranslator.kt       # PyTorch 翻译器
│
├── fuzzer/
│   ├── Backend.kt                    # 接口（已有）
│   ├── DaemonBackend.kt              # 基类（已有）
│   ├── TvmDaemonBackend.kt           # TVM 实现（已有）
│   └── PytorchDaemonBackend.kt       # 新增
│
├── config/
│   └── FuzzerConfig.kt               # 配置类（需添加 PytorchConfig）
│
pytorch_daemon.py                      # 新增 daemon 脚本（根目录）
```

---

## 翻译器设计

### PytorchTranslator

**核心职责**：将 UirProgram 翻译为可被 `torch.compile` 编译的 PyTorch Python 代码

**设计要点**：

1. **模块函数模式**
   ```python
   import torch
   
   class TestModule(torch.nn.Module):
       def forward(self, x, y):
           return torch.add(x, y)
   
   model = TestModule()
   compiled = torch.compile(model)
   output = compiled(input1, input2)
   ```

2. **输入张量生成**
   - 为每个 graph.inputs 生成随机输入张量
   - 形状从 UirShape 提取
   - dtype 从配置获取（默认 float32）

3. **算子映射**
   - UIR op → PyTorch function
   - 处理属性（axis, keepdims 等）

### 算子映射表

| UIR Op | PyTorch API | 备注 |
|--------|-------------|------|
| ADD | `torch.add` | |
| SUBTRACT | `torch.sub` | |
| MULTIPLY | `torch.mul` | |
| DIVIDE | `torch.div` | |
| MATMUL | `torch.matmul` | |
| RELU | `torch.nn.functional.relu` | |
| SIGMOID | `torch.sigmoid` | |
| TANH | `torch.tanh` | |
| GELU | `torch.nn.functional.gelu` | |
| SILU | `torch.nn.functional.silu` | |
| SOFTMAX | `torch.nn.functional.softmax` | 需要 axis |
| REDUCE_SUM | `torch.sum` | 需要 axis |
| REDUCE_MEAN | `torch.mean` | 需要 axis |
| RESHAPE | `torch.reshape` | |
| TRANSPOSE | `torch.transpose` | |
| SQUEEZE | `torch.squeeze` | |
| UNSQUEEZE | `torch.unsqueeze` | |
| CONCAT | `torch.cat` | 需要 axis |
| SPLIT | `torch.split` | 需要 axis |
| CAST | `torch.to(dtype)` | |
| ARANGE | `torch.arange` | |
| ONES | `torch.ones` | |
| ZEROS | `torch.zeros` | |
| TRIL | `torch.tril` | |
| TRIU | `torch.triu` | |

### 代码结构

```kotlin
class PytorchTranslator(
    private val dtype: String = "float32",
    private val seed: Long? = null,  // 随机种子（用于输入生成）
) : UirTranslator<UirProgram, String> {
    
    companion object {
        val defaultOpMapping: Map<UirOpKind, String> = mapOf(
            UirOpKind.ADD to "torch.add",
            UirOpKind.SUBTRACT to "torch.sub",
            // ... 完整映射
        )
    }
    
    override fun translate(element: UirProgram): String {
        // 1. 生成 imports
        // 2. 翻译每个 graph 为 torch.nn.Module
        // 3. 生成输入张量
        // 4. 调用 torch.compile 并执行
        // 5. 打印成功信息
    }
    
    private fun translateGraph(graph: UirGraph, graphIdx: Int): String {
        // 生成 Module 定义
    }
    
    private fun translateNode(node: UirNode, valueMap: MutableMap<String, String>): String {
        // 生成算子调用
    }
    
    private fun generateInputTensors(graph: UirGraph): String {
        // 生成 torch.randn 调用
    }
}
```

---

## Daemon 运行器设计

### pytorch_daemon.py

**核心职责**：常驻 Python HTTP 服务，接收源码并执行

**设计要点**：

1. **预加载 PyTorch**
   - 启动时 `import torch`，避免每次测试重复加载
   - 检测 CUDA 可用性

2. **HTTP API**
   ```
   POST /run {"source": "<python code>"}
   → {"success": true, "exit_code": 0, "stdout": "...", "stderr": "...", "elapsed_ms": 100}
   
   GET /health → {"status": "ok", "torch_available": true}
   
   POST /shutdown → 优雅关闭
   ```

3. **超时保护**
   - 使用 `signal.alarm` 或 `threading.Timer` 避免编译卡死
   - 默认超时 120 秒

4. **错误隔离**
   - 每次 exec 使用新的命名空间
   - 避免状态污染

### 代码结构

```python
"""
PyTorch.compile Daemon — 常驻 Python HTTP 服务

API:
  POST /run    {"source": "<python code>"}
  GET  /health → {"status": "ok", "torch_available": true}
  POST /shutdown → 优雅关闭
"""

import torch
import json
import signal
import sys
import time
import traceback
from http.server import HTTPServer, BaseHTTPRequestHandler
from io import StringIO

# 预加载 PyTorch
TORCH_AVAILABLE = True
CUDA_AVAILABLE = torch.cuda.is_available()

# 执行超时
EXEC_TIMEOUT_SECONDS = 120


def run_source(source: str, timeout: int = EXEC_TIMEOUT_SECONDS) -> dict:
    """执行 Python 源码，捕获 stdout/stderr"""
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    sys.stdout = StringIO()
    sys.stderr = StringIO()
    
    start = time.time()
    exit_code = 0
    success = True
    
    try:
        # 设置 alarm 超时
        if hasattr(signal, "SIGALRM"):
            signal.signal(signal.SIGALRM, lambda s, f: (_ for _ in ()).throw(TimeoutError(f"Timeout after {timeout}s")))
            signal.alarm(timeout)
        
        exec(source, {"torch": torch, "torch_nn": torch.nn, "torch_func": torch.nn.functional})
        
    except TimeoutError as e:
        success = False
        exit_code = -1
        print(f"TIMEOUT: {e}", file=sys.stderr)
    except Exception:
        traceback.print_exc()
        success = False
        exit_code = 1
    finally:
        if hasattr(signal, "SIGALRM"):
            signal.alarm(0)
    
    elapsed = int((time.time() - start) * 1000)
    stdout = sys.stdout.getvalue()
    stderr = sys.stderr.getvalue()
    sys.stdout = old_stdout
    sys.stderr = old_stderr
    
    return {
        "success": success,
        "exit_code": exit_code,
        "stdout": stdout,
        "stderr": stderr,
        "elapsed_ms": elapsed,
    }


class DaemonRequestHandler(BaseHTTPRequestHandler):
    """HTTP 请求处理器"""
    
    def do_POST(self):
        if self.path == "/run":
            self._handle_run()
        elif self.path == "/shutdown":
            self._handle_shutdown()
        else:
            self._json_response(404, {"error": f"unknown path: {self.path}"})
    
    def do_GET(self):
        if self.path == "/health":
            self._json_response(200, {
                "status": "ok",
                "torch_available": TORCH_AVAILABLE,
                "cuda_available": CUDA_AVAILABLE,
            })
        else:
            self._json_response(404, {"error": f"unknown path: {self.path}"})
    
    def _handle_run(self):
        """处理 /run 请求"""
        try:
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length)
            req = json.loads(body)
            source = req.get("source", "")
            
            result = run_source(source)
            self._json_response(200, result)
        except Exception as e:
            self._json_response(500, {
                "success": False,
                "exit_code": -1,
                "stdout": "",
                "stderr": f"Internal error: {e}\n{traceback.format_exc()}",
                "elapsed_ms": 0,
            })
    
    def _handle_shutdown(self):
        self._json_response(200, {"status": "shutting_down"})
        import threading
        threading.Thread(target=self.server.shutdown, daemon=True).start()
    
    def _json_response(self, status_code: int, data: dict):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    
    def log_message(self, format, *args):
        pass  # 抑制 HTTP 日志


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=34890)
    parser.add_argument("--test", action="store_true", help="Run self-test")
    args = parser.parse_args()
    
    if args.test:
        # 自测试
        source = """
import torch

class TestModule(torch.nn.Module):
    def forward(self, x):
        return torch.relu(x)

model = TestModule()
compiled = torch.compile(model)
x = torch.randn(16, device="cpu")
output = compiled(x)
print(f"Success: output shape = {output.shape}")
"""
        result = run_source(source)
        print(f"Success: {result['success']}")
        print(f"Stdout: {result['stdout']}")
        if not result['success']:
            print(f"Stderr: {result['stderr']}")
            sys.exit(1)
        return
    
    # 启动 daemon
    port = args.port
    server = HTTPServer(("127.0.0.1", port), DaemonRequestHandler)
    server.socket.settimeout(1.0)
    
    # 输出 ready 信息（JVM 读取）
    import os
    print(json.dumps({
        "type": "ready",
        "torch_available": TORCH_AVAILABLE,
        "cuda_available": CUDA_AVAILABLE,
        "port": port,
        "pid": os.getpid(),
    }), flush=True)
    
    # 注册信号处理
    def sig_handler(signum, frame):
        server.shutdown()
    
    signal.signal(signal.SIGTERM, sig_handler)
    signal.signal(signal.SIGINT, sig_handler)
    
    try:
        while True:
            try:
                server.handle_request()
            except TimeoutError:
                pass
            except OSError:
                break
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
```

---

### PytorchDaemonBackend

**核心职责**：Kotlin 后端实现，与 pytorch_daemon.py 通信

```kotlin
package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.config.PytorchConfig
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.translator.pytorch.PytorchTranslator
import java.io.File

/**
 * 基于常驻 daemon 的 PyTorch.compile 后端。
 */
class PytorchDaemonBackend(
    pythonPath: String = "python3",
    daemonScriptPath: String = "pytorch_daemon.py",
    private val dtype: String = "float32",
    workDir: File = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aiFuzzer_pytorch_daemon"),
) : DaemonBackend<PytorchDaemonBackend.PytorchResult>(
    pythonPath = pythonPath,
    daemonScriptPath = daemonScriptPath,
    workDir = workDir,
) {

    constructor(config: PytorchConfig) : this(
        pythonPath = config.python,
        daemonScriptPath = "pytorch_daemon.py",
        dtype = config.dtype,
        workDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aiFuzzer_pytorch_daemon"),
    )

    override val name = "PyTorch.compile (daemon)"
    
    override val translator: PytorchTranslator = PytorchTranslator(dtype = dtype)

    override fun createCopy(): Backend<PytorchResult> {
        val newWorkDir = File(
            workDir.parent,
            "${workDir.name}_thread_${Thread.currentThread().id}"
        )
        return PytorchDaemonBackend(
            pythonPath = pythonPath,
            daemonScriptPath = daemonScriptPath,
            dtype = dtype,
            workDir = newWorkDir,
        )
    }

    override fun execute(program: UirProgram): PytorchResult {
        val source = translator.translate(program)
        val daemonResult = daemon.sendAndWait(source)
        
        // 保存源码
        val sourceFile = File(workDir, "program.py")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(source)
        
        val errorInfo = ErrorAnalyzer.analyze(daemonResult.stderr, daemonResult.exitCode)
        
        return PytorchResult(
            success = daemonResult.success,
            exitCode = daemonResult.exitCode,
            stdout = daemonResult.stdout,
            stderr = daemonResult.stderr,
            elapsedMs = daemonResult.elapsedMs,
            errorCategory = errorInfo.category,
            errorSummary = errorInfo.summary,
            sourceFile = sourceFile.absolutePath,
        )
    }

    override fun toResult(program: UirProgram, daemonResult: DaemonResult): PytorchResult {
        val errorInfo = ErrorAnalyzer.analyze(daemonResult.stderr, daemonResult.exitCode)
        val source = translator.translate(program)
        val sourceFile = File(workDir, "program.py")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(source)
        
        return PytorchResult(
            success = daemonResult.success,
            exitCode = daemonResult.exitCode,
            stdout = daemonResult.stdout,
            stderr = daemonResult.stderr,
            elapsedMs = daemonResult.elapsedMs,
            errorCategory = errorInfo.category,
            errorSummary = errorInfo.summary,
            sourceFile = sourceFile.absolutePath,
        )
    }

    data class PytorchResult(
        override val success: Boolean,
        override val exitCode: Int,
        override val stdout: String,
        override val stderr: String,
        override val elapsedMs: Long,
        val errorCategory: ErrorCategory,
        val errorSummary: String,
        val sourceFile: String,
    ) : BackendResult(success, exitCode, stdout, stderr, elapsedMs)
}
```

---

## 集成方案

### 配置更新

在 `FuzzerConfig.kt` 中添加：

```kotlin
data class PytorchConfig(
    var python: String = "python3",
    var mode: String = "daemon",
    var timeoutSeconds: Int = 120,
    var keepArtifacts: Boolean = false,
    var workDir: String = System.getProperty("java.io.tmpdir", "/tmp") + "/aiFuzzer_pytorch",
    var dtype: String = "float32",
    var device: String = "cpu",  // cpu 或 cuda
)
```

在 `BackendsConfig` 中添加：

```kotlin
data class BackendsConfig(
    var enabled: List<String> = listOf("tvm"),
    var tvm: TvmConfig = TvmConfig(),
    var onnx: OnnxConfig = OnnxConfig(),
    var iree: IreeConfig = IreeConfig(),
    var pytorch: PytorchConfig = PytorchConfig(),  // 新增
)
```

### App.kt 更新

在 backend 创建逻辑中添加：

```kotlin
if ("pytorch" in config.backends.enabled) {
    val pytorchCfg = config.backends.pytorch
    echo("  PyTorch backend: daemon mode (python=${pytorchCfg.python}, device=${pytorchCfg.device})")
    backends.add(PytorchDaemonBackend(pytorchCfg))
}
```

### 错误分析器更新

在 `ErrorAnalyzer.kt` 中添加 PyTorch 特定错误模式：

```kotlin
enum class ErrorCategory {
    // ... 已有
    
    // Dynamo 捕获错误
    DYNAMO_ERROR,
    // Inductor 编译错误
    INDUCTOR_ERROR,
    // PyTorch 运行时错误
    PYTORCH_RUNTIME_ERROR,
}

fun analyze(stderr: String, exitCode: Int): ErrorInfo {
    // ... 已有逻辑
    
    return when {
        // ... 已有分支
        
        stderr.contains("torch._dynamo") || stderr.contains("TorchDynamo") -> {
            ErrorInfo(ErrorCategory.DYNAMO_ERROR, extractLine(stderr, "TorchDynamo"))
        }
        stderr.contains("inductor") || stderr.contains("torch._inductor") -> {
            ErrorInfo(ErrorCategory.INDUCTOR_ERROR, extractLine(stderr, "inductor"))
        }
        stderr.contains("RuntimeError") && stderr.contains("torch") -> {
            ErrorInfo(ErrorCategory.PYTORCH_RUNTIME_ERROR, extractLine(stderr, "RuntimeError"))
        }
        // ...
    }
}
```

---

## 实现计划

### Phase 1: 翻译器（1-2 天）

1. ✅ 创建 `translator/pytorch/PytorchTranslator.kt`
2. ✅ 实现算子映射表
3. ✅ 实现图翻译逻辑
4. ✅ 添加测试用例

### Phase 2: Daemon 脚本（1 天）

1. ✅ 创建 `pytorch_daemon.py`
2. ✅ 实现 HTTP API
3. ✅ 添加超时保护
4. ✅ 自测试模式

### Phase 3: Backend 集成（1 天）

1. ✅ 创建 `PytorchDaemonBackend.kt`
2. ✅ 添加配置类
3. ✅ 更新 `App.kt`
4. ✅ 更新 `ErrorAnalyzer.kt`

### Phase 4: 测试与优化（1-2 天）

1. ✅ 单元测试
2. ✅ 集成测试
3. ✅ 性能测试（daemon 启动时间、编译速度）
4. ✅ 文档更新

---

## 关键技术细节

### 1. torch.compile 模式选择

PyTorch 2.0 支持多种编译模式：

```python
# 默认模式（推荐）
compiled = torch.compile(model)

 reduce-overhead 模式（减少 CPU 开销）
compiled = torch.compile(model, mode="reduce-overhead")

# max-autotune 模式（最激进的优化）
compiled = torch.compile(model, mode="max-autotune")

# 自定义后端
compiled = torch.compile(model, backend="inductor")
compiled = torch.compile(model, backend="aot_eager")
```

**建议**：默认使用 `mode="default"`，可通过配置切换。

### 2. 动态形状处理

PyTorch.compile 支持动态形状，但需要特殊处理：

```python
# 使用 dynamic=True
compiled = torch.compile(model, dynamic=True)

# 或使用 symbolic tracing
from torch._dynamo import symbolic_trace
traced = symbolic_trace(model)
```

**建议**：UIR 中的符号维度（SYMBOLIC）映射为 PyTorch 的动态形状。

### 3. CUDA 支持

```python
# 检测 CUDA
if torch.cuda.is_available():
    device = "cuda"
    x = x.to(device)
else:
    device = "cpu"
```

**建议**：通过 `PytorchConfig.device` 配置，默认 "cpu"。

### 4. 错误隔离

daemon 每次执行使用独立的命名空间：

```python
exec(source, {
    "torch": torch,
    "torch_nn": torch.nn,
    "torch_func": torch.nn.functional,
    "__builtins__": __builtins__,
})
```

避免状态污染和意外访问。

---

## 示例：完整翻译流程

### 输入 UIR

```kotlin
// UirProgram:
// - 1 graph
// - 2 inputs: x[16], y[16]
// - 1 node: ADD(x, y) → z[16]
// - 1 output: z
```

### 输出 Python 代码

```python
# Auto-generated by aiFuzzer PyTorch translator

import torch
import torch.nn as nn
import torch.nn.functional as F

class TestModule_0(nn.Module):
    def forward(self, x, y):
        # Node: ADD
        z = torch.add(x, y)
        return z

# Create module and compile
model = TestModule_0()
compiled = torch.compile(model)

# Generate random inputs
x = torch.randn(16, dtype=torch.float32)
y = torch.randn(16, dtype=torch.float32)

# Execute
output = compiled(x, y)
print(f"Success: output shape = {output.shape}")
```

---

## 总结

本设计文档详细描述了为 aiFuzzer 添加 PyTorch.compile 后端的完整方案：

1. **翻译器**：将 UIR → PyTorch Module + torch.compile 调用
2. **Daemon**：常驻 Python 进程，避免重复加载 PyTorch
3. **集成**：复用现有 `DaemonBackend` 架构，最小化代码修改

该设计遵循了 aiFuzzer 的现有架构模式（翻译器 + daemon 后端），确保与 TVM 后端的一致性和可维护性。
