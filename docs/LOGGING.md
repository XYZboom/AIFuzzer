# aiFuzzer 日志系统

## 概述

aiFuzzer 使用 **kotlin-logging** + **Logback** 作为日志框架，提供完整的可追踪日志。

## 依赖

```kotlin
// build.gradle.kts
implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
implementation("ch.qos.logback:logback-classic:1.5.18")
```

## 配置

日志配置文件位于 `src/main/resources/logback.xml`：

- **控制台输出**：INFO 级别，用于实时监控
- **文件输出**：DEBUG 级别，保存到 `logs/aifuzzer.log`
- **追踪日志**：TRACE 级别，保存到 `logs/aifuzzer-trace.log`

## 日志级别

| 级别 | 用途 |
|------|------|
| ERROR | 严重错误（daemon 启动失败、执行超时） |
| WARN | 警告（测试失败、非预期情况） |
| INFO | 重要事件（启动、完成、进度） |
| DEBUG | 调试信息（翻译完成、执行结果） |
| TRACE | 详细追踪（节点生成、形状推导） |

## 使用方法

### 1. 创建 Logger

```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}
```

### 2. 记录日志

```kotlin
log.info { "启动 fuzzer: seed=$seed" }
log.debug { "翻译完成，输出 ${builder.length} 字符" }
log.trace { "节点 $nodeIndex: 选择算子 $op" }
log.warn { "测试 seed=$seed 超时 (${timeout}s)" }
log.error(e) { "启动 daemon 失败" }
```

### 3. 追踪 ID

使用 `LogUtils` 可以设置追踪 ID，在分布式场景下追踪请求：

```kotlin
import io.github.xyzboom.aiFuzzer.LogUtils

LogUtils.withTrace {
    // 所有日志会自动带上 traceId
    log.info { "处理请求" }
}
```

## 日志文件位置

```
logs/
├── aifuzzer.log          # 主日志（滚动，保留 7 天）
├── aifuzzer-trace.log    # 详细追踪日志（滚动，保留 3 天）
└── aifuzzer.2026-07-10.log  # 历史日志
```

## 主要日志点

### 生成器 (UirGenerator)

- `INFO`: 开始/完成生成
- `DEBUG`: 创建节点
- `TRACE`: 节点选择、形状推导

### 翻译器 (TvmRelaxTranslator)

- `DEBUG`: 开始/完成翻译
- `TRACE`: 每个图的翻译

### Fuzzing (FuzzingPipeline)

- `INFO`: 进度报告（每 5 秒）
- `WARN`: 测试超时
- `ERROR`: 执行异常

### Daemon (DaemonClient)

- `INFO`: 启动/关闭 daemon
- `DEBUG`: 请求/响应
- `ERROR`: 启动失败、解析错误

## 调整日志级别

编辑 `logback.xml`：

```xml
<!-- 提高生成器日志级别 -->
<logger name="io.github.xyzboom.aiFuzzer.generator" level="DEBUG"/>

<!-- 降低整体日志级别 -->
<root level="DEBUG">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="FILE"/>
</root>
```

## 最佳实践

1. **使用合适的级别**：ERROR/WARN/INFO/DEBUG/TRACE 按严重程度使用
2. **结构化日志**：使用键值对格式，便于 grep 和分析
3. **避免敏感信息**：不要记录密码、密钥等
4. **异常日志**：使用 `log.error(e) { "message" }` 记录完整堆栈
