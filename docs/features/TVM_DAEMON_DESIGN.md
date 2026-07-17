# TVM Daemon 设计文档

> 版本: 0.1
> 日期: 2026-07-07
> 状态: 待实现

---

## 1. 问题

当前 `TvmBackend` 的执行模式是：

```
每轮测试：
  1. 生成 UIR
  2. 翻译器生成 Python 源码
  3. 写临时文件到磁盘
  4. 启动 python3 prog_xxx.py
  5. 等待进程退出
  6. 读 stdout/stderr
  7. 分析错误
```

这种"每轮 fork 一个 Python 进程"的模式有三个严重问题：

| 问题 | 影响 |
|------|------|
| **进程启动开销大** | `ProcessBuilder("python3", ...)` 每次约 50-150ms，100000 轮就是 5000-15000 秒 |
| **TVM 模块加载慢** | `import tvm` / `from tvm import relax` 每次约 0.5-1.5s，占测试时间的 80%+ |
| **无法积累训练/编译缓存** | JIT 编译结果每次丢弃，TVM 的缓存机制无法生效 |

在 100000 轮、16 workers 的典型配置下，实际 TVM 可并行的计算不足 5%，其余 95% 的时间浪费在进程 fork + 模块 import 上。

## 2. 方案：TVM Daemon

### 2.1 核心思路

**启动一个常驻 Python 进程**，该进程在 Fuzzer 生命周期内不退出。测试时通过 stdin/stdout 管道向该进程发送 UIR 程序块，进程在内存中编译执行并返回结果。

```
  AiFuzzer (JVM)
      │
      │  [stdin JSON] ──────► Python Daemon (单进程)
      │                         ├── import tvm, relax (一次)
      │                         ├── 接收 "RUN" 请求
      │                         ├── 编译 + 执行 UIR
      │                         └── 返回结果 (stdout JSON)
      │
      ▼
  FuzzingPipeline 继续下轮
```

### 2.2 通信协议

基于 JSON-over-stdin/stdout：

```
Request:
{
  "type": "run",
  "id": 42,                    // 请求 ID，用于对应响应
  "backend": "tvm",
  "source": "import tvm\n..."  // Python 源码（翻译器输出）
}

Response:
{
  "type": "result",
  "id": 42,
  "success": true,
  "exit_code": 0,
  "stdout": "...",
  "stderr": "",
  "elapsed_ms": 123
}
```

### 2.3 Python Daemon 伪代码

```python
# tvm_daemon.py
import sys, json, time, traceback
from io import StringIO

# 一次性导入 TVM（最耗时的部分）
import tvm
from tvm import relax
import tvm.relax.op as op

def run(source: str) -> dict:
    """执行单次测试，捕获 stdout/stderr"""
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    sys.stdout = StringIO()
    sys.stderr = StringIO()
    
    start = time.time()
    try:
        exec(source, {"tvm": tvm, "relax": relax, "op": op})
        success = True
        exit_code = 0
    except Exception as e:
        traceback.print_exc()
        success = False
        exit_code = 1
    
    elapsed = int((time.time() - start) * 1000)
    captured_stdout = sys.stdout.getvalue()
    captured_stderr = sys.stderr.getvalue()
    sys.stdout = old_stdout
    sys.stderr = old_stderr
    
    return {
        "success": success,
        "exit_code": exit_code,
        "stdout": captured_stdout,
        "stderr": captured_stderr,
        "elapsed_ms": elapsed,
    }

* main loop *
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    if line == "__SHUTDOWN__":
        break
    try:
        req = json.loads(line)
        req_id = req["id"]
        result = run(req["source"])
        result["id"] = req_id
        result["type"] = "result"
        print(json.dumps(result), flush=True)
    except Exception as e:
        print(json.dumps({
            "type": "result",
            "id": req.get("id", -1),
            "success": False,
            "exit_code": -1,
            "stdout": "",
            "stderr": str(e),
            "elapsed_ms": 0,
        }), flush=True)
```

### 2.4 关键设计决策

#### 决策 1: 单进程 vs 多进程

| 选项 | 优势 | 劣势 |
|------|------|------|
| **单进程** | 实现简单，IPC 开销小 | TVM 有全局状态，并发执行可能冲突 |
| **多进程 (pre-fork pool)** | 并发安全，每进程独立状态 | 复杂，每个进程都要 import tvm |
| **线程池** | 共享 TVM 模块 | TVM 部分操作非线程安全 |

**选择：单进程 + 同步串行执行**。理由是：

1. TVM 的 BlockBuilder 等 API 有全局状态，并发执行会导致不可预期的错误
2. 真正的瓶颈不在执行（~10ms 实际 TVM 执行），而在 import（~1s），单进程绕过 import 后已足够快
3. 并行由 JVM 侧的 `FuzzingPipeline` 协程池控制——多个 daemon 进程而非单进程内多线程

#### 决策 2: 通信协议

| 选项 | 优势 | 劣势 |
|------|------|------|
| **JSON-over-stdin/stdout** | 零依赖，容易 debug，跨平台 | 序列化开销，需处理换行符 |
| **Unix socket** | 高性能，支持并发连接 | 平台相关（Windows 不兼容），增加复杂度 |
| **ZeroMQ** | 真正并发 | 新依赖，部署复杂 |
| **gRPC** | 工业级 | 太重，Python 端需要 protobuf |

**选择：JSON-over-stdin/stdout**。最简单的可靠方案，Python 端无额外依赖，调试时可直接读管道内容。

#### 决策 3: exec 安全性

Python 的 `exec()` 存在安全隐患（恶意代码可能破坏 daemon 进程）。但考虑到：
- UIR 翻译器输出固定格式，没有用户输入
- 恶意代码破坏 daemon 最多导致当轮测试失败，**不会影响 JVM 主进程**
- TVM 错误由翻译器兜底（如 OpNotImplemented）

**选择：直接 exec。** 若未来需要隔离，可在 fork 子进程中 exec（类似 subprocess 但复用 TVM 模块）。

### 2.5 重试与错误恢复

Daemon 进程可能在 exec 时崩溃（少数 TVM 错误会导致 segfault）：

```
┌──────────────────────────────────────────┐
│  FuzzingPipeline                         │
│                                          │
│  1. 发送请求 → timeout (如 30s)          │
│  2. 没有收到回复 → 标记该请求失败        │
│  3. 检查 daemon 是否存活                 │
│     a. 存活 → 继续下一轮                 │
│     b. 死亡 → 重启 daemon，记录重启次数  │
│  4. 重启超过 N 次 → 停止测试            │
└──────────────────────────────────────────┘
```

心跳机制：每 N 轮发送 `{"type": "ping"}`，daemon 回复 `{"type": "pong"}` 确认存活。

### 2.6 与现有架构的集成

```kotlin
// 新的 daemon 后端
class TvmDaemonBackend(
    pythonPath: String = "/home/xyzboom/miniconda3/envs/aifuzzer/bin/python",
    daemonScriptPath: String = "./daemon/tvm_daemon.py",
) : Backend<TvmBackend.TvmResult> {
    
    private val daemon: TvmDaemonClient  // 管理 Python 子进程
    
    override fun execute(program: UirProgram): TvmResult {
        val source = translator.translate(program)
        val (success, stdout, stderr, elapsed) = daemon.sendAndWait(source)
        val errorInfo = ErrorAnalyzer.analyze(stderr, if (success) 0 else 1)
        return TvmResult(...)
    }
}
```

`NondaemonTvmBackend` 保留原有实现（用于调试或 daemon 崩溃时回退）。

## 3. 变更清单

### Kotlin 侧

| 文件 | 变更 |
|------|------|
| `src/main/kotlin/.../fuzzer/TvmDaemonBackend.kt` | **新增** — 基于 daemon 的后端 |
| `src/main/kotlin/.../fuzzer/TvmDaemonClient.kt` | **新增** — 管理 Python 子进程、通信、重启 |
| `src/main/kotlin/.../fuzzer/Backend.kt` | 新增 `close()` 接口（可选） |
| `src/main/kotlin/.../config/FuzzerConfig.kt` | `TvmConfig` 新增 `mode: "daemon" | "process"` 字段 |
| `src/main/kotlin/.../App.kt` | 根据配置选择 `TvmDaemonBackend` 或 `TvmBackend` |

### Python 侧

| 文件 | 变更 |
|------|------|
| `daemon/tvm_daemon.py` | **新增** — 常驻 Python 进程 |

### 配置

| 文件 | 变更 |
|------|------|
| `configs/default.yaml` | `backends.tvm` 增加 `mode: daemon`、`python` 路径 |
| `configs/debug.yaml` | 使用 `mode: daemon`、`python` 路径 |

## 4. 性能预期

| 指标 | 当前 (进程模式) | 预期 (Daemon) | 倍率 |
|------|----------------|---------------|------|
| 单轮耗时（含 import） | ~800-1200ms | ~50-200ms | 5-10x |
| 100000 轮 16 workers | ~2-3 小时 | ~10-30 分钟 | ~5x |
| TVM 进程数峰值 | 16 | 1-16（可配置 daemon 数） | — |
| Python 内存占用 | 每轮 ~100MB | 常驻 ~200MB | — |

## 5. 备用方案

如果 daemon 遇到难以解决的 Python exec 沙箱问题，备用方案是 **socket 模式 + fork 子进程**：

```
  AiFuzzer ──socket──► Daemon (统筹进程)
                           │
                    fork on each request ──► Worker (exec + 返回结果)
                           │
                     复用 import cache (若 TVM 支持)
```

但此方案复杂度高，需要 Python 端的 `multiprocessing` 或 `os.fork`。在单进程 exec 方案无法工作的情况下采用。

## 6. 边界情况处理

| 场景 | 处理方式 |
|------|---------|
| Python 进程启动失败 | 记录错误，回退到进程模式，或停止测试 |
| 执行中 segfault | 通过心跳超时检测，自动重启 daemon |
| source 包含特殊字符 | JSON 序列化天然转义 |
| stdout/stderr 过大 | 限制捕获长度（如 10KB），溢出时截断 |
| 连续失败 | 连续 3 次失败 → 停止 daemon → 重启 | 
| shutdown | FuzzingPipeline 结束后发送 `__SHUTDOWN__`，等待进程退出 |
| 多个 daemon 实例 | `FuzzingConfig.workers` 决定 daemon 实例数，每个独立进程 |