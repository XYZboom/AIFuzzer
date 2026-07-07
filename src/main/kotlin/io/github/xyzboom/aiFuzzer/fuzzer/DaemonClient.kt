package io.github.xyzboom.aiFuzzer.fuzzer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后端 daemon 通信客户端。
 *
 * 管理一个常驻 Python 子进程，通过 stdin/stdout JSON 协议通信。
 * 支持掉线检测和自动重启。
 *
 * @param pythonPath Python 可执行文件路径
 * @param daemonScriptPath daemon Python 脚本路径
 * @param maxRetries 连续失败后重启次数上限
 * @param requestTimeoutMs 每个请求的超时时间（毫秒）
 * @param envProvider 环境变量提供者，用于设置子进程的环境变量
 */
class DaemonClient(
    val pythonPath: String,
    val daemonScriptPath: String,
    val maxRetries: Int = 3,
    val requestTimeoutMs: Long = 60_000,
    private val envProvider: DaemonEnvProvider = DefaultDaemonEnvProvider(pythonPath),
) : AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true }
    private val idCounter = AtomicInteger(0)
    private var process: Process? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var retries = 0

    /** 记录 daemon 是否已就绪（收到了 ready 消息） */
    @Volatile
    var ready: Boolean = false
        private set

    /** TVM 是否可用（daemon 启动时报告） */
    @Volatile
    var tvmAvailable: Boolean = false
        private set

    /**
     * 启动 daemon 进程并等待就绪。
     */
    fun start(): Boolean {
        synchronized(this) {
            if (process != null && process!!.isAlive) {
                return true
            }
            try {
                val pb = ProcessBuilder(pythonPath, daemonScriptPath)
                val env = pb.environment()
                env.clear()
                env.putAll(envProvider.getEnv())

                pb.redirectErrorStream(true)
                process = pb.start()
                writer = PrintWriter(OutputStreamWriter(process!!.outputStream, "UTF-8"), true)
                writer = PrintWriter(OutputStreamWriter(process!!.outputStream, "UTF-8"), true)
                reader = BufferedReader(InputStreamReader(process!!.inputStream, "UTF-8"))

                // 读取就绪消息（如果 readyLine 为 null，说明进程已退出）
                val readyLine = reader!!.readLine()
                if (readyLine != null) {
                    try {
                        val msg = json.decodeFromString<ReadyMessage>(readyLine)
                        ready = true
                        tvmAvailable = msg.tvmAvailable
                        if (!tvmAvailable) {
                            println("[DaemonClient] TVM import failed: ${msg.importError}")
                            println("[DaemonClient] Detail: ${msg.importDetail.take(500)}")
                        }
                    } catch (e: Exception) {
                        println("[DaemonClient] Failed to parse ready message: '$readyLine', error: ${e.message}")
                        ready = true  // 至少进程启动了
                    }
                } else {
                    // 进程可能在启动时就挂了，检查退出码
                    process?.waitFor(3, TimeUnit.SECONDS)
                    val exitCode = process?.exitValue()
                    println("[DaemonClient] Daemon process exited with code $exitCode on start")
                }
                retries = 0
                return ready
            } catch (e: Exception) {
                println("[DaemonClient] Failed to start daemon: ${e.message}")
                destroy()
                return false
            }
        }
    }

    /**
     * 发送 run 请求并等待结果。
     *
     * @param source Python 源码
     * @return 执行结果
     * @throws DaemonException 如果 daemon 崩溃或超时
     */
    fun sendAndWait(source: String): DaemonResult {
        val requestId = idCounter.incrementAndGet()
        val request = RunRequest(id = requestId, type = "run", source = source)
        val requestJson = json.encodeToString(request)

        synchronized(this) {
            ensureRunning()
            writer!!.println(requestJson)
            writer!!.flush()
        }

        // 等待响应（使用超时）
        val deadline = System.currentTimeMillis() + requestTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive()) {
                throw DaemonException("Daemon process died while waiting for response (request $requestId)")
            }
            try {
                val line = readLineWithTimeout(deadline - System.currentTimeMillis())
                if (line != null) {
                    val msg = json.decodeFromString<ResultMessage>(line)
                    if (msg.id == requestId) {
                        return DaemonResult(
                            success = msg.success,
                            exitCode = msg.exitCode,
                            stdout = msg.stdout,
                            stderr = msg.stderr,
                            elapsedMs = msg.elapsedMs,
                        )
                    }
                    // 收到的不是我们的请求的响应 —— 忽略（可能是之前超时的响应）
                }
            } catch (e: Exception) {
                throw DaemonException("Failed to read daemon response: ${e.message}", e)
            }
        }

        throw DaemonException("Request $requestId timed out after ${requestTimeoutMs}ms")
    }

    /**
     * 检查 daemon 是否存活。
     */
    fun isAlive(): Boolean {
        return process?.isAlive == true
    }

    /**
     * 重启 daemon。
     */
    fun restart(): Boolean {
        destroy()
        return start()
    }

    /**
     * 关闭 daemon 进程。
     */
    override fun close() {
        destroy()
    }

    private fun ensureRunning() {
        if (!isAlive() || !ready) {
            if (retries >= maxRetries) {
                throw DaemonException("Daemon not running and max retries ($maxRetries) exceeded")
            }
            retries++
            println("[DaemonClient] Daemon not running, restarting (retry $retries/$maxRetries)...")
            val started = restart()
            if (!started) {
                throw DaemonException("Failed to restart daemon (retry $retries/$maxRetries)")
            }
        }
    }

    private fun readLineWithTimeout(timeoutMs: Long): String? {
        if (timeoutMs <= 0) return null
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                if (reader!!.ready()) {
                    return reader!!.readLine()
                }
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                return null
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun destroy() {
        try {
            writer?.println("__SHUTDOWN__")
            writer?.flush()
        } catch (_: Exception) {}
        try {
            process?.waitFor(2, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        process?.destroyForcibly()
        writer?.close()
        reader?.close()
        process = null
        writer = null
        reader = null
        ready = false
        tvmAvailable = false
    }

    // --- 序列化消息类型 ---

    @Serializable
    data class ReadyMessage(
        val type: String = "ready",
        @kotlinx.serialization.SerialName("tvm_available")
        val tvmAvailable: Boolean = false,
        @kotlinx.serialization.SerialName("import_error")
        val importError: String = "",
        @kotlinx.serialization.SerialName("import_detail")
        val importDetail: String = "",
    )

    @Serializable
    data class RunRequest(
        val id: Int,
        val type: String = "run",
        val source: String,
    )

    @Serializable
    data class ResultMessage(
        val type: String = "result",
        val id: Int = -1,
        val success: Boolean = false,
        @kotlinx.serialization.SerialName("exit_code")
        val exitCode: Int = -1,
        val stdout: String = "",
        val stderr: String = "",
        @kotlinx.serialization.SerialName("elapsed_ms")
        val elapsedMs: Long = 0,
    )
}

/**
 * Daemon 进程环境变量提供者。
 * 自定义实现可自由添加/修改环境变量。
 */
interface DaemonEnvProvider {
    fun getEnv(): Map<String, String>
}

/**
 * 默认环境变量提供者。
 *
 * 从系统环境继承 PATH、HOME、USER、SHELL 等，
 * 并排除可能干扰子进程的环境变量。
 */
class DefaultDaemonEnvProvider(
    private val pythonPath: String,
) : DaemonEnvProvider {

    override fun getEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        val systemEnv = System.getenv()

        // 保留基础系统环境变量
        for (key in listOf("PATH", "HOME", "USER", "SHELL", "HOMEPATH", "USERNAME", "COMSPEC",
                           "TEMP", "TMP", "SYSTEMROOT", "WINDIR")) {
            systemEnv[key]?.let { env[key] = it }
        }

        // 如果 python 路径在 conda 环境中，自动设置 conda 相关变量
        val condaPrefix = pythonPath.substringBeforeLast("/bin/python")
        if (condaPrefix != pythonPath) {
            env["LD_LIBRARY_PATH"] = "$condaPrefix/lib"
            env["CONDA_PREFIX"] = condaPrefix
            env["CONDA_DEFAULT_ENV"] = condaPrefix.substringAfterLast("/")
        }

        // 排除可能干扰 Python 子进程的环境变量
        for (key in listOf("PYTHONHOME", "PYTHONPATH", "PYTHONSTARTUP",
                           "CLASSPATH", "JAVA_HOME", "GRADLE_OPTS", "JAVA_OPTS",
                           "JDK_HOME", "JRE_HOME")) {
            env.remove(key)
        }

        return env
    }
}

/**
 * Daemon 执行结果。
 */
data class DaemonResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val elapsedMs: Long,
)

/**
 * Daemon 通信异常。
 */
class DaemonException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)