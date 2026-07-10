# aiFuzzer 并发问题报告

> 分析时间：2026-07-09  
> 分析范围：并行模式（`workers > 1`）下的数据竞争和线程安全问题

---

## 概述

aiFuzzer 项目在 `pipeline.workers > 1` 时会启用并行模式，使用 `ExecutorService.newFixedThreadPool(workers)` 创建多个 worker 线程并发执行 fuzzing 测试。经过代码审查，发现多个严重的并发问题，会导致数据竞争、结果丢失、不可复现等问题。

**结论**：当前并行模式存在至少 5 个严重数据竞争问题，在修复前建议将 `workers` 默认值保留为 `1`。

---

## 🔴 严重问题

### 1. BugCollector.bugCounter 数据竞争

**位置**：`BugCollector.kt:38`

```kotlin
private var bugCounter = 0

fun collect(...) {
    if (!isWorthyBug(result)) return
    bugCounter++  // ← 非原子操作：读-改-写
    val bugDirName = "bug_%03d_...".format(bugCounter, ...)
    // ...
}
```

**问题**：
- `bugCounter++` 不是原子操作，多线程同时调用会导致计数错误
- 可能产生重复的 bug 目录名，导致文件覆盖/数据丢失
- 报告中的 bug 编号与实际数量不符

**影响**：bug 报告文件覆盖、丢失、计数不准

**修复方案**：
```kotlin
private val bugCounter = AtomicInteger(0)

fun collect(...) {
    if (!isWorthyBug(result)) return
    val bugNum = bugCounter.incrementAndGet()
    val bugDirName = "bug_%03d_...".format(bugNum, ...)
    // ...
}
```

---

### 2. UirGenerator 共享 Random 实例

**位置**：`UirGenerator.kt:42`

```kotlin
class UirGenerator(private val config: GeneratorConfig) {
    private val rand = Random(config.seed)  // ← 共享实例，非线程安全
    private val logicGen = LogicGenerator(...)
    private val shapeAdapter = ShapeAdapter()
    
    fun generate(): UirProgram {
        val program = logicGen.generate()
        for (graph in program.graphs) {
            shapeAdapter.adapt(graph, rand)  // ← 多线程调用同一个 rand
        }
        return program
    }
}
```

**问题**：
- `kotlin.random.Random` 不是线程安全的
- 多线程共享调用会导致内部状态竞争
- 可能产生重复/非均匀的随机数序列
- 相同 seed 在并行模式下无法复现特定 bug

**影响**：
- fuzzing 覆盖率下降（重复生成相似结构）
- 不可复现（seed=42 的实际执行路径因竞态而异）

**修复方案**：

方案 A：每次调用创建独立 Random
```kotlin
fun generate(): UirProgram {
    val threadLocalRand = Random(config.seed + Thread.currentThread().id)
    val program = logicGen.generate()
    for (graph in program.graphs) {
        shapeAdapter.adapt(graph, threadLocalRand)
    }
    return program
}
```

方案 B：使用 ThreadLocal
```kotlin
private val randLocal = ThreadLocal.withInitial { 
    Random(config.seed + Thread.currentThread().id) 
}

fun generate(): UirProgram {
    val rand = randLocal.get()
    // ...
}
```

---

### 3. TvmDaemonBackend 写同一 sourceFile

**位置**：`TvmDaemonBackend.kt:65-68` 和 `TvmDaemonBackend.kt:78`

```kotlin
override fun execute(program: UirProgram): TvmBackend.TvmResult {
    val source = translator.translate(program)
    val daemonResult = daemon.sendAndWait(source)
    
    val sourceFile = File(workDir, "program.py")  // ← 固定文件名！
    sourceFile.parentFile.mkdirs()
    sourceFile.writeText(source)  // ← 所有线程写同一个文件
    
    // ...
}

override fun toResult(...): TvmBackend.TvmResult {
    // ...
    val sourceFile = File(workDir, "program.py")  // ← 同样写同一个文件
    sourceFile.writeText(source)
    // ...
}
```

**问题**：
- 所有 worker 线程写入同一个 `workDir/program.py`
- 并发写入导致文件内容混乱
- BugCollector 读到的 source.py 可能是另一线程的代码

**影响**：源码文件交叉污染，bug 报告中的源码与实际不匹配

**修复方案**：
```kotlin
override fun execute(program: UirProgram): TvmBackend.TvmResult {
    val source = translator.translate(program)
    val daemonResult = daemon.sendAndWait(source)
    
    // 使用 seed 或 UUID 生成唯一文件名
    val sourceFile = File(workDir, "program_${seed ?: UUID.randomUUID()}.py")
    sourceFile.parentFile.mkdirs()
    sourceFile.writeText(source)
    // ...
}
```

---

### 4. DaemonClient.httpClient() 懒加载竞态

**位置**：`DaemonClient.kt:46-55`

```kotlin
@Volatile
private var _httpClient: HttpClient? = null

private fun httpClient(): HttpClient {
    val existing = _httpClient
    if (existing != null) return existing  // ← 可能读到过期值
    return createHttpClient()
}

@Synchronized
private fun createHttpClient(): HttpClient {
    _httpClient?.let { return it }
    return HttpClient(CIO) { ... }.also { _httpClient = it }
}
```

**问题**：
- Double-checked locking 模式不完整（`existing` 局部变量绕过了 volatile）
- 可能创建多个 HttpClient 实例
- 与 `closeHttpClient()` 并发时可能导致悬挂引用

**影响**：资源泄漏，可能在极端情况下导致 HttpClient 使用已关闭的实例

**修复方案**：
```kotlin
private fun httpClient(): HttpClient {
    return _httpClient ?: createHttpClient()
}

@Synchronized
private fun createHttpClient(): HttpClient {
    return _httpClient ?: HttpClient(CIO) { ... }.also { _httpClient = it }
}
```

或直接在构造时创建：
```kotlin
private val httpClient: HttpClient = HttpClient(CIO) { ... }
```

---

### 5. DaemonClient.destroy() 与其他方法的并发竞态

**位置**：`DaemonClient.kt:195-218`

```kotlin
private fun destroy() {
    val p = process
    val oldPort = port
    process = null      // ← 非原子地重置多个字段
    ready = false
    tvmAvailable = false
    port = 0
    
    // 发送 HTTP shutdown
    if (oldPort > 0) {
        runBlocking { httpClient().post("$baseUrl/shutdown") }
    }
    
    p?.waitFor(3, TimeUnit.SECONDS)
    p?.destroyForcibly()
    closeHttpClient()
}
```

**问题**：
- `destroy()` 与 `ensureRunning()`、`sendAndWait()`、`isAlive()` 无统一同步
- 可能出现时序：线程 A 调 `isAlive()` → 返回 true → 线程 B 调 `destroy()` → 线程 A 调 `httpClient().post()` → 连接失败

**影响**：请求在 daemon 关闭过程中被丢弃，抛出 `ConnectException` 或 `ClosedClientException`

**修复方案**：
```kotlin
private val lock = ReentrantLock()

private fun destroy() {
    lock.lock()
    try {
        val p = process
        val oldPort = port
        process = null
        ready = false
        tvmAvailable = false
        port = 0
        // ... 其余逻辑
    } finally {
        lock.unlock()
    }
}

private fun ensureRunning() {
    lock.lock()
    try {
        // ... 原有逻辑
    } finally {
        lock.unlock()
    }
}
```

---

## 🟡 中等问题

### 6. LogicGenerator 共享 Random 实例

**位置**：`LogicGenerator.kt:36`

```kotlin
class LogicGenerator(private val config: LogicGraphConfig) {
    private val rand = Random(config.seed)  // ← 非线程安全
    
    fun generate(): UirProgram {
        // ... 多线程调用 generateGraph() → rand.nextInt()
    }
}
```

**问题**：与问题 #2 类似，`rand.nextInt()` 在多线程下状态竞争

**影响**：生成相同/相似的随机序列，降低 fuzzing 覆盖率

**修复方案**：同问题 #2

---

### 7. 统计计数与结果列表不一致

**位置**：`FuzzingPipeline.kt:143-147`

```kotlin
executor.submit<List<FuzzingResult>> {
    try {
        val results = runOnce(seed)
        results.forEach {
            if (it.backendResult.success) successCount.incrementAndGet()
            else failureCount.incrementAndGet()
        }
        completed.incrementAndGet()
        results  // ← 添加到 allResults 是在 future.get() 之后
    } catch (e: Exception) {
        // ...
    }
}

// 在主线程中
futures.forEachIndexed { i, future ->
    val results = future.get(...)
    allResults.addAll(results)  // ← 与计数更新不在同一原子操作中
}
```

**问题**：
- 计数器更新在 worker 线程中，结果添加在主线程中
- 极短窗口内统计结果可能与实际不符

**影响**：不致命，因为最终结果在 `executor.shutdownNow()` 后才被读取，统计已收敛

**修复方案**（可选）：将计数更新和结果添加放在同一个 synchronized 块中

---

### 8. DaemonClient.retries 字段线程不安全

**位置**：`DaemonClient.kt:91`

```kotlin
private var retries = 0

private fun ensureRunning() {
    if (!isAlive() || !ready) {
        if (retries >= maxRetries) {  // ← 读
            throw DaemonException(...)
        }
        retries++  // ← 写
        // ...
    }
}
```

**问题**：`retries` 非 `@Volatile`，且读写无同步，可能被线程本地缓存

**影响**：重试逻辑可能不准确，但在 retry 场景下影响有限

**修复方案**：
```kotlin
private val retries = AtomicInteger(0)

private fun ensureRunning() {
    if (!isAlive() || !ready) {
        if (retries.get() >= maxRetries) {
            throw DaemonException(...)
        }
        retries.incrementAndGet()
        // ...
    }
}
```

---

## 🔵 架构级建议

### 9. 单 Daemon 实例 + 多 Worker 的瓶颈

**位置**：`DaemonBackend.kt:31`，`configs/default.yaml:33`

**现状**：
- 配置 `workers: 16` 但 `daemonCount: 1`
- 所有 16 个 worker 的请求串行化到同一个 daemon 进程
- Python daemon 脚本使用 `HTTPServer`，单线程处理请求

**问题**：
- 并行退化为串行（瓶颈在 daemon 端）
- 多 worker 无法发挥真正并行优势

**修复方案**：

1. **Daemon 池化**：`DaemonBackend` 维护多个 daemon 实例
   ```kotlin
   private val daemonPool: List<DaemonClient> = (0 until daemonCount).map {
       DaemonClient(pythonPath, daemonScriptPath, ...)
   }
   
   override fun execute(program: UirProgram): TvmBackend.TvmResult {
       val daemon = daemonPool[Random.nextInt(daemonPool.size)]
       // ...
   }
   ```

2. **Python 端多线程**：使用 `ThreadingHTTPServer`
   ```python
   from http.server import ThreadingHTTPServer
   server = ThreadingHTTPServer(("127.0.0.1", port), DaemonRequestHandler)
   ```

---

### 10. Python daemon 的非线程安全

**位置**：`tvm_daemon.py:49-83`

```python
def run_source(source: str, timeout: int = EXEC_TIMEOUT_SECONDS) -> dict:
    global tvm, relax, op
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    sys.stdout = StringIO()  # ← 替换全局 stdout
    sys.stderr = StringIO()  # ← 替换全局 stderr
    # ...
```

**问题**：
- 替换全局 `sys.stdout`/`sys.stderr`，多线程请求会交叉污染
- `signal.alarm` 在 Python 多线程中不可靠（只有主线程接收信号）

**影响**：如果改为多线程 daemon（问题 #9 的方案 2），会出现问题

**修复方案**：
```python
from contextlib import redirect_stdout, redirect_stderr
import threading

_thread_local = threading.local()

def run_source(source: str, timeout: int = EXEC_TIMEOUT_SECONDS) -> dict:
    stdout_buf = StringIO()
    stderr_buf = StringIO()
    
    with redirect_stdout(stdout_buf), redirect_stderr(stderr_buf):
        try:
            exec(source, {"tvm": tvm, "relax": relax, "op": op})
            success = True
        except Exception:
            traceback.print_exc()
            success = False
    
    return {
        "success": success,
        "stdout": stdout_buf.getvalue(),
        "stderr": stderr_buf.getvalue(),
        # ...
    }
```

---

## 总结表

| # | 问题 | 严重度 | 影响 | 修复优先级 |
|---|------|--------|------|-----------|
| 1 | BugCollector.bugCounter 数据竞争 | 🔴 高 | Bug 报告覆盖/丢失 | P0 |
| 2 | UirGenerator 共享 Random | 🔴 高 | Fuzzing 质量/不可复现 | P0 |
| 3 | TvmDaemonBackend 写同一文件 | 🔴 高 | 源码文件交叉污染 | P0 |
| 4 | DaemonClient.httpClient() 竞态 | 🔴 高 | 资源泄漏/悬挂引用 | P1 |
| 5 | DaemonClient.destroy() 无同步 | 🔴 高 | 请求丢失/异常 | P1 |
| 6 | LogicGenerator 共享 Random | 🟡 中 | 随机序列问题 | P2 |
| 7 | 统计计数不一致 | 🟡 中 | 统计偏差（不致命） | P3 |
| 8 | retries 字段线程不安全 | 🟡 中 | 重试逻辑不准 | P3 |
| 9 | 单 Daemon 实例瓶颈 | 🔵 低 | 并行退化为串行 | P4 |
| 10 | Python daemon 非线程安全 | 🔵 低 | 多线程时会出问题 | P4 |

---

## 建议的修复顺序

1. **立即修复（P0）**：问题 #1、#2、#3 — 直接影响数据正确性
2. **短期修复（P1）**：问题 #4、#5 — 影响稳定性
3. **中期优化（P2-P3）**：问题 #6、#7、#8 — 提升质量
4. **长期架构（P4）**：问题 #9、#10 — 性能优化

---

## 临时缓解措施

在修复上述问题前，建议：

```yaml
# configs/default.yaml
pipeline:
  workers: 1  # 强制使用串行模式
  batch_size: 50000
  run_timeout_seconds: 60
```

或在代码中添加保护：
```kotlin
// FuzzingPipeline.kt
init {
    require(config.workers <= 1) {
        "Parallel mode is not safe yet. Set workers=1 until concurrency issues are fixed."
    }
}
```

---

## 附录：验证并发问题的测试方法

可以通过以下方式验证上述问题：

```kotlin
@Test
fun `bugCounter should be thread-safe`() {
    val threads = (1..10).map {
        thread {
            repeat(100) {
                BugCollector.collect(mockResult(), seed = it.toLong(), "test", null, null)
            }
        }
    }
    threads.forEach { it.join() }
    
    // 检查 bug 目录数量是否正确
    val bugDirs = File("reports").listFiles()?.filter { it.name.startsWith("bug_") }
    assertEquals(1000, bugDirs?.size, "Bug counter had race condition")
}
```

类似地，可以为其他问题编写多线程压力测试。
