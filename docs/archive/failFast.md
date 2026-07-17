# FailFast 配置参数

## 功能说明

`failFast` 是一个配置参数，用于控制程序在遇到测试失败时的行为。

## 配置位置

在 `pipeline` 配置段中设置：

```yaml
pipeline:
  failFast: true  # 默认 false
```

## 行为说明

### failFast = false（默认）

- 继续执行所有测试
- 收集所有失败信息
- 适用于批量测试场景

### failFast = true

- 遇到第一个测试失败立即终止程序
- 适用于调试场景，快速定位问题
- 并行模式会立即关闭线程池

## 使用场景

### Debug 模式（推荐设置 failFast: true）

```yaml
# configs/debug.yaml
run:
  description: "Debug run - fail fast on any error"
  logLevel: debug

pipeline:
  workers: 1
  batchSize: 10
  failFast: true
```

运行：
```bash
./gradlew run --args="--config configs/debug.yaml"
```

### Production 模式（默认 failFast: false）

```yaml
# configs/production.yaml
run:
  description: "Production run - collect all failures"

pipeline:
  workers: 4
  batchSize: 1000
```

## 实现细节

### 串行模式

```kotlin
for (test in tests) {
    val result = runTest(test)
    if (!result.success && config.failFast) {
        log.error { "failFast=true: 检测到失败，终止测试" }
        break
    }
}
```

### 并行模式

```kotlin
val failFastTriggered = AtomicBoolean(false)

// 在 worker 线程中
if (!result.success && config.failFast) {
    if (failFastTriggered.compareAndSet(false, true)) {
        log.error { "failFast=true: 检测到失败，终止测试" }
    }
}

// 在主线程中
for (future in futures) {
    if (failFastTriggered.get()) {
        executor.shutdownNow()
        break
    }
    future.get()
}
```

## 日志输出

当 `failFast=true` 且检测到失败时，会输出：

```
ERROR: failFast=true: 检测到失败，终止测试
```

或（异常情况）：

```
ERROR: failFast=true: 检测到异常，终止测试
java.lang.Exception: ...
```

## 配置文件示例

参见 `configs/debug.yaml`：
```yaml
run:
  description: "Debug run - fail fast on any error"
  logLevel: debug

pipeline:
  workers: 1
  batchSize: 10
  failFast: true
```
