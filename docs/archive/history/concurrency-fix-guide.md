# aiFuzzer 并发问题修复指南

> 配合 `docs/concurrency-issues.md` 使用，提供具体的代码修复示例

---

## 修复优先级与步骤

### Phase 1：立即修复（P0 问题）

#### 修复 1：BugCollector.bugCounter 线程安全

**文件**：`src/main/kotlin/io/github/xyzboom/aiFuzzer/fuzzer/BugCollector.kt`

```kotlin
// 修改前
object BugCollector {
    private var bugCounter = 0
    
    fun collect(...) {
        if (!isWorthyBug(result)) return
        bugCounter++
        val bugDirName = "bug_%03d_...".format(bugCounter, ...)
        // ...
    }
}

// 修改后
object BugCollector {
    private val bugCounter = AtomicInteger(0)
    
    fun collect(...) {
        if (!isWorthyBug(result)) return
        val bugNum = bugCounter.incrementAndGet()
        val bugDirName = "bug_%03d_...".format(bugNum, ...)
        // ...
    }
    
    fun reset() {
        bugCounter.set(0)
    }
}
```

**验证测试**：
```kotlin
@Test
fun `bugCollector should be thread-safe`() {
    BugCollector.reset()
    val threads = (1..10).map {
        thread {
            repeat(100) {
                val result = mockk<BackendResult>(relaxed = true)
                every { result.success } returns false
                BugCollector.collect(result, it.toLong(), "test", null, "source")
            }
        }
    }
    threads.forEach { it.join() }
    
    val count = BugCollector.reportsDir.listFiles()
        ?.count { it.name.startsWith("bug_") } ?: 0
    assertEquals(1000, count)
}
```

---

#### 修复 2：UirGenerator Random 线程安全

**文件**：`src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/UirGenerator.kt`

```kotlin
// 修改前
class UirGenerator(private val config: GeneratorConfig) {
    private val rand = Random(config.seed)
    private val logicGen = LogicGenerator(...)
    private val shapeAdapter = ShapeAdapter()
    
    fun generate(): UirProgram {
        val program = logicGen.generate()
        for (graph in program.graphs) {
            shapeAdapter.adapt(graph, rand)
        }
        return program
    }
}

// 修改后（方案 A：ThreadLocal）
class UirGenerator(private val config: GeneratorConfig) {
    private val randLocal = ThreadLocal.withInitial { 
        Random(config.seed + Thread.currentThread().id)
    }
    private val logicGen = LogicGenerator(...)
    private val shapeAdapter = ShapeAdapter()
    
    fun generate(): UirProgram {
        val rand = randLocal.get()
        val program = logicGen.generate()
        for (graph in program.graphs) {
            shapeAdapter.adapt(graph, rand)
        }
        return program
    }
}
```

**注意**：`LogicGenerator` 也需要类似修改，见修复 6。

---

#### 修复 3：TvmDaemonBackend 文件名冲突

**文件**：`src/main/kotlin/io/github/xyzboom/aiFuzzer/fuzzer/TvmDaemonBackend.kt`

```kotlin
// 修改前
override fun execute(program: UirProgram): TvmBackend.TvmResult {
    val source = translator.translate(program)
    val daemonResult = daemon.sendAndWait(source)
    
    val sourceFile = File(workDir, "program.py")
    sourceFile.parentFile.mkdirs()
    sourceFile.writeText(source)
    // ...
}

// 修改后
override fun execute(program: UirProgram): TvmBackend.TvmResult {
    val source = translator.translate(program)
    val daemonResult = daemon.sendAndWait(source)
    
    // 使用 UUID 生成唯一文件名
    val sourceFile = File(workDir, "program_${UUID.randomUUID()}.py")
    sourceFile.parentFile.mkdirs()
    sourceFile.writeText(source)
    // ...
}

// 同样修改 toResult()
override fun toResult(program: UirProgram, daemonResult: DaemonResult): TvmBackend.TvmResult {
    // ...
    val source = translator.translate(program)
    val sourceFile = File(workDir, "program_${UUID.randomUUID()}.py")
    sourceFile.parentFile.mkdirs()
    sourceFile.writeText(source)
    // ...
}
```

**需要添加 import**：
```kotlin
import java.util.UUID
```

---

### Phase 2：短期修复（P1 问题）

#### 修复 4：DaemonClient.httpClient() 懒加载

**文件**：`src/main/kotlin/io/github/xyzboom/aiFuzzer/fuzzer/DaemonClient.kt`

```kotlin
// 修改前
@Volatile
private var _httpClient: HttpClient? = null

private fun httpClient(): HttpClient {
    val existing = _httpClient
    if (existing != null) return existing
    return createHttpClient()
}

@Synchronized
private fun createHttpClient(): HttpClient {
    _httpClient?.let { return it }
    return HttpClient(CIO) { ... }.also { _httpClient = it }
}

// 修改后（方案 A：直接构造）
private val httpClient: HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = requestTimeoutMs
        connectTimeoutMillis = 3_000
        socketTimeoutMillis = requestTimeoutMs
    }
}

// 或方案 B：简化懒加载
@Volatile
private var _httpClient: HttpClient? = null

private fun httpClient(): HttpClient {
    return _httpClient ?: synchronized(this) {
        _httpClient ?: HttpClient(CIO) { ... }.also { _httpClient = it }
    }
}
```

---

#### 修复 5：DaemonClient.destroy() 同步

**文件**：`src/main/kotlin/io/github/xyzboom/aiFuzzer/fuzzer/DaemonClient.kt`

```kotlin
// 添加锁
private val lock = Any()

// 修改 destroy()
private fun destroy() {
    synchronized(lock) {
        val p = process
        val oldPort = port
        process = null
        ready = false
        tvmAvailable = false
        port = 0
        
        if (oldPort > 0) {
            val baseUrl = "http://127.0.0.1:$oldPort"
            try {
                runBlocking {
                    _httpClient?.post("$baseUrl/shutdown") {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                }
            } catch (_: Exception) {}
        }
        
        try {
            p?.waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        p?.destroyForcibly()
        closeHttpClient()
    }
}

// 修改 ensureRunning()
private fun ensureRunning() {
    synchronized(lock) {
        if (!isAlive() || !ready) {
            if (retries >= maxRetries) {
                throw DaemonException("Daemon not running and max retries ($maxRetries) exceeded")
            }
            retries++
            System.err.println("[DaemonClient] Daemon not running, restarting (retry $retries/$maxRetries)...")
            val started = restart()
            if (!started) {
                throw DaemonException("Failed to restart daemon (retry $retries/$maxRetries)")
            }
        }
    }
}

// 修改 start()
fun start(): Boolean {
    synchronized(lock) {
        // ... 原有逻辑
    }
}

// 修改 isAlive()
fun isAlive(): Boolean {
    synchronized(lock) {
        if (process?.isAlive != true) return false
        return try {
            runBlocking {
                _httpClient?.get("http://127.0.0.1:$port/health")?.status == HttpStatusCode.OK
            }
        } catch (_: Exception) {
            false
        }
    }
}
```

---

### Phase 3：中期优化（P2-P3 问题）

#### 修复 6：LogicGenerator Random 线程安全

**文件**：`src/main/kotlin/io/github/xyzboom/aiFuzzer/generator/LogicGenerator.kt`

```kotlin
// 修改前
class LogicGenerator(private val config: LogicGraphConfig) {
    private val rand = Random(config.seed)
    // ...
}

// 修改后
class LogicGenerator(private val config: LogicGraphConfig) {
    private val randLocal = ThreadLocal.withInitial {
        Random(config.seed + Thread.currentThread().id)
    }
    
    fun generate(): UirProgram {
        val rand = randLocal.get()
        return buildProgram {
            for (i in 0 until config.graphCount) {
                graphs.add(generateGraph("graph_$i", rand))
            }
        }
    }
    
    private fun generateGraph(name: String, rand: Random): UirGraph {
        // 使用传入的 rand 参数
    }
}
```

---

#### 修复 7：统计一致性（可选）

**文件**：`src/main/kotlin/io/github/xyzboom/aiFuzzer/fuzzer/FuzzingPipeline.kt`

```kotlin
// 添加结果包装类
private data class WorkerResult(
    val seed: Long,
    val results: List<FuzzingResult>,
    val success: Boolean,
)

// 修改并行执行逻辑
val futures = (0 until count).map { i ->
    val seed = startSeed + i
    executor.submit<WorkerResult> {
        try {
            val results = runOnce(seed)
            WorkerResult(seed, results, true)
        } catch (e: Exception) {
            WorkerResult(seed, emptyList(), false)
        }
    }
}

futures.forEachIndexed { i, future ->
    val seed = startSeed + i
    try {
        val workerResult = future.get(...)
        if (workerResult.success) {
            allResults.addAll(workerResult.results)
            workerResult.results.forEach {
                if (it.backendResult.success) successCount.incrementAndGet()
                else failureCount.incrementAndGet()
            }
        } else {
            // 处理失败
        }
        completed.incrementAndGet()
    } catch (...) {
        // ...
    }
}
```

---

#### 修复 8：retries 线程安全

**文件**：`src/main/kotlin/io/github/xyzboom/aiFuzzer/fuzzer/DaemonClient.kt`

```kotlin
// 修改前
private var retries = 0

// 修改后
private val retries = AtomicInteger(0)

private fun ensureRunning() {
    synchronized(lock) {
        if (!isAlive() || !ready) {
            if (retries.get() >= maxRetries) {
                throw DaemonException("...")
            }
            retries.incrementAndGet()
            // ...
        }
    }
}

fun start(): Boolean {
    synchronized(lock) {
        // ...
        retries.set(0)
        return true
    }
}
```

---

### Phase 4：架构优化（P4 问题）

#### 修复 9：Daemon 池化

**文件**：`src/main/kotlin/io/github/xyzboom/aiFuzzer/fuzzer/DaemonBackend.kt`

```kotlin
abstract class DaemonBackend<T : BackendResult>(
    val pythonPath: String,
    val daemonScriptPath: String,
    val daemonCount: Int = 1,
    // ...
) : Backend<T> {
    
    // 修改前：单个 daemon
    protected val daemon: DaemonClient = DaemonClient(...)
    
    // 修改后：daemon 池
    protected val daemonPool: List<DaemonClient> = (0 until daemonCount).map {
        DaemonClient(
            pythonPath = pythonPath,
            daemonScriptPath = daemonScriptPath,
            maxRetries = 3,
            requestTimeoutMs = 120_000,
            envProvider = envProvider,
        )
    }
    
    private val daemonIndex = AtomicInteger(0)
    
    protected fun getNextDaemon(): DaemonClient {
        // 轮询选择 daemon
        val idx = daemonIndex.getAndIncrement() % daemonPool.size
        return daemonPool[idx]
    }
    
    override fun checkEnvironment(): Boolean {
        return daemonPool.all { daemon ->
            if (!daemon.isAlive()) daemon.start() else true
        } && daemonPool.any { it.tvmAvailable }
    }
    
    override fun close() {
        daemonPool.forEach { it.close() }
    }
}
```

**文件**：`src/main/kotlin/io/github/xyzboom/aiFuzzer/fuzzer/TvmDaemonBackend.kt`

```kotlin
override fun execute(program: UirProgram): TvmBackend.TvmResult {
    val source = translator.translate(program)
    val daemon = getNextDaemon()
    val daemonResult = daemon.sendAndWait(source)
    // ...
}
```

---

#### 修复 10：Python daemon 多线程安全

**文件**：`tvm_daemon.py`

```python
# 修改前
def run_source(source: str, timeout: int = EXEC_TIMEOUT_SECONDS) -> dict:
    global tvm, relax, op
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    sys.stdout = StringIO()
    sys.stderr = StringIO()
    # ...

# 修改后
from contextlib import redirect_stdout, redirect_stderr

def run_source(source: str, timeout: int = EXEC_TIMEOUT_SECONDS) -> dict:
    global tvm, relax, op
    
    stdout_buf = StringIO()
    stderr_buf = StringIO()
    start = time.time()
    exit_code = 0
    
    with redirect_stdout(stdout_buf), redirect_stderr(stderr_buf):
        try:
            exec(source, {"tvm": tvm, "relax": relax, "op": op})
            success = True
        except SystemExit as e:
            success = False
            exit_code = e.code if isinstance(e.code, int) else 1
            traceback.print_exc()
        except Exception:
            traceback.print_exc()
            success = False
            exit_code = 1
    
    elapsed = int((time.time() - start) * 1000)
    
    return {
        "success": success,
        "exit_code": exit_code,
        "stdout": stdout_buf.getvalue(),
        "stderr": stderr_buf.getvalue(),
        "elapsed_ms": elapsed,
    }
```

**启用多线程 HTTPServer**：
```python
# from http.server import HTTPServer
from http.server import ThreadingHTTPServer  # Python 3.7+

def main():
    # ...
    server = ThreadingHTTPServer(("127.0.0.1", port), DaemonRequestHandler)
    # ...
```

---

## 测试验证

创建并发测试文件：`src/test/kotlin/io/github/xyzboom/aiFuzzer/fuzzer/ConcurrencyTest.kt`

```kotlin
package io.github.xyzboom.aiFuzzer.fuzzer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ConcurrencyTest {
    
    @Test
    fun `BugCollector should handle concurrent access`() {
        BugCollector.reset()
        val threadCount = 10
        val opsPerThread = 100
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)
        
        repeat(threadCount) { threadId ->
            thread {
                try {
                    repeat(opsPerThread) { opId ->
                        val result = object : BackendResult(
                            success = false,
                            exitCode = 1,
                            stdout = "",
                            stderr = "test error",
                            elapsedMs = 0
                        ) {}
                        BugCollector.collect(
                            result = result,
                            seed = (threadId * 1000 + opId).toLong(),
                            backendName = "test",
                            program = null,
                            sourceCode = "test source"
                        )
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }
        
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        assertEquals(0, errors.get(), "No exceptions should occur during concurrent access")
        
        val bugCount = BugCollector.reportsDir.listFiles()
            ?.count { it.name.startsWith("bug_") } ?: 0
        assertEquals(threadCount * opsPerThread, bugCount, 
            "All bugs should be collected without duplicates")
    }
    
    @Test
    fun `UirGenerator should produce different results in parallel`() {
        val config = GeneratorConfig(seed = 42, minNodesPerGraph = 2, maxNodesPerGraph = 5)
        val generator = UirGenerator(config)
        val results = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(10)
        
        repeat(10) {
            thread {
                try {
                    val program = generator.generate()
                    results.add(program.toString())
                } finally {
                    latch.countDown()
                }
            }
        }
        
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        
        // 检查结果有足够的多样性（不完全相同）
        val uniqueResults = results.toSet()
        assertTrue(uniqueResults.size > 1, 
            "Parallel generation should produce variety, got ${uniqueResults.size} unique out of ${results.size}")
    }
    
    @Test
    fun `DaemonClient should handle concurrent requests safely`() {
        val client = DaemonClient(
            pythonPath = "python3",
            daemonScriptPath = "tvm_daemon.py",
            maxRetries = 3,
            requestTimeoutMs = 10_000
        )
        
        if (!client.start()) {
            println("Skipping test: daemon failed to start")
            return
        }
        
        try {
            val latch = CountDownLatch(5)
            val errors = AtomicInteger(0)
            
            repeat(5) {
                thread {
                    try {
                        val result = client.sendAndWait("print('test')")
                        assertNotNull(result)
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                        e.printStackTrace()
                    } finally {
                        latch.countDown()
                    }
                }
            }
            
            assertTrue(latch.await(30, TimeUnit.SECONDS))
            assertEquals(0, errors.get(), "Concurrent requests should not cause errors")
        } finally {
            client.close()
        }
    }
}
```

---

## 回归测试清单

修复后运行以下测试确保功能正常：

```bash
# 单元测试
./gradlew test

# 集成测试（小规模）
./gradlew run --args="--config configs/debug.yaml -n 10 -w 4"

# 并发压力测试
./gradlew run --args="--config configs/default.yaml -n 100 -w 8"
```

---

## 配置更新

修复后更新配置文件：`configs/default.yaml`

```yaml
pipeline:
  workers: 8  # 可安全使用多线程
  batch_size: 50000
  run_timeout_seconds: 60

backends:
  tvm:
    mode: "daemon"
    daemon_count: 8  # 与 workers 匹配
    # ...
```

---

## 参考

- Kotlin 并发最佳实践：https://kotlinlang.org/docs/coroutines-guide.html
- Java 并发编程：https://docs.oracle.com/javase/tutorial/essential/concurrency/
- Python threading：https://docs.python.org/3/library/threading.html
