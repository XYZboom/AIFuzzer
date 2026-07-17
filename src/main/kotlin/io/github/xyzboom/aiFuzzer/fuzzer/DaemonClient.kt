package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * TVM Daemon HTTP 客户端。
 *
 * 管理一个常驻 Python HTTP 服务进程，通过 Ktor HTTP 客户端通信。
 * 不再依赖 stdin/stdout 管道（避免管道 buffer/block 问题）。
 *
 * @param pythonPath Python 可执行文件路径
 * @param daemonScriptPath daemon Python 脚本路径
 * @param maxRetries 连续失败后重启次数上限
 * @param requestTimeoutMs 每个 HTTP 请求的超时时间（毫秒）
 * @param envProvider 环境变量提供者，用于设置子进程的环境变量
 */
class DaemonClient(
    val pythonPath: String,
    val daemonScriptPath: String,
    val maxRetries: Int = 3,
    val requestTimeoutMs: Long = 120_000,
    private val envProvider: DaemonEnvProvider = DefaultDaemonEnvProvider(pythonPath),
) : AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true }

    /** Ktor HttpClient，懒加载以支持 close() 后重新创建 */
    @Volatile
    private var _httpClient: HttpClient? = null

    @Synchronized
    private fun httpClient(): HttpClient {
        _httpClient?.let { return it }
        return createHttpClient()
    }

    @Synchronized
    private fun createHttpClient(): HttpClient {
        _httpClient?.let { return it }
        return HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = requestTimeoutMs
                connectTimeoutMillis = 3_000
                socketTimeoutMillis = requestTimeoutMs
            }
        }.also { _httpClient = it }
    }

    private fun closeHttpClient() {
        val client = _httpClient
        _httpClient = null
        try {
            runBlocking { client?.close() }
        } catch (_: Exception) {}
    }

    private var process: Process? = null
    private var retries = 0

    /** daemon HTTP 服务端口 */
    @Volatile
    var port: Int = 0
        private set

    /** 记录 daemon 是否已就绪 */
    @Volatile
    var ready: Boolean = false
        private set

    /** 后端是否可用（daemon 启动时由 ready 消息报告） */
    @Volatile
    var backendAvailable: Boolean = false
        private set

    private val baseUrl: String
        get() = "http://127.0.0.1:$port"

    /**
     * 查找可用的端口。
     */
    private fun findFreePort(): Int {
        val socket = ServerSocket()
        try {
            socket.bind(InetSocketAddress("127.0.0.1", 0))
            return socket.localPort
        } finally {
            socket.close()
        }
    }

    /**
     * 启动 daemon 进程并等待就绪。
     */
    fun start(): Boolean {
        synchronized(this) {
            if (process != null && process!!.isAlive) {
                log.debug { "daemon 已运行: port=$port" }
                return true
            }
            try {
                val daemonPort = findFreePort()
                log.info { "启动 daemon: python=$pythonPath, port=$daemonPort" }

                val pb = ProcessBuilder(pythonPath, daemonScriptPath, "--port", daemonPort.toString())
                val env = pb.environment()
                env.clear()
                env.putAll(envProvider.getEnv())

                pb.redirectErrorStream(true)
                process = pb.start()

                // 读取 daemon 输出的 ready 信息（一行 JSON）
                val reader = BufferedReader(InputStreamReader(process!!.inputStream, "UTF-8"))
                val readyLine = reader.readLine()

                if (readyLine != null) {
                    try {
                        val msg = json.decodeFromString<ReadyMessage>(readyLine)
                        port = msg.port
                        ready = true
                        backendAvailable = msg.backendAvailable
                        if (!backendAvailable) {
                            log.error { "TVM import failed: ${msg.importError}" }
                        } else {
                            log.info { "daemon 就绪: port=$port, backendAvailable=$backendAvailable" }
                        }
                    } catch (e: Exception) {
                        log.error(e) { "解析就绪消息失败: '$readyLine'" }
                        destroy()
                        return false
                    }
                } else {
                    process?.waitFor(3, TimeUnit.SECONDS)
                    val exitCode = process?.exitValue()
                    log.error { "daemon 启动失败，退出码 $exitCode（无就绪消息）" }
                    destroy()
                    return false
                }

                retries = 0
                return true
            } catch (e: Exception) {
                log.error(e) { "启动 daemon 失败" }
                destroy()
                return false
            }
        }
    }

    /**
     * 发送 run 请求并等待结果。
     *
     * 底层使用 runBlocking + Ktor 发送 HTTP POST 请求，
     * 保持同步 API 签名，与 [Backend] 接口兼容。
     *
     * @param source Python 源码
     * @return 执行结果
     * @throws DaemonException 如果 daemon 崩溃或超时
     */
    fun sendAndWait(source: String): DaemonResult {
        ensureRunning()
        log.debug { "发送请求: source.length=${source.length}" }

        val requestBody = json.encodeToString(RunRequestBody(source = source))

        return runBlocking {
            try {
                val response: HttpResponse = httpClient().post("$baseUrl/run") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                val body = response.bodyAsText()
                val msg = json.decodeFromString<ResultMessage>(body)
                
                log.debug { "收到响应: success=${msg.success}, elapsed=${msg.elapsedMs}ms" }

                DaemonResult(
                    success = msg.success,
                    exitCode = msg.exitCode,
                    stdout = msg.stdout,
                    stderr = msg.stderr,
                    elapsedMs = msg.elapsedMs,
                )
            } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
                throw DaemonException("Request timed out after ${requestTimeoutMs}ms", e)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw DaemonException("Request timed out after ${requestTimeoutMs}ms", e)
            } catch (e: java.net.ConnectException) {
                synchronized(this@DaemonClient) { ready = false }
                throw DaemonException("Daemon connection refused (process died?)", e)
            } catch (e: DaemonException) {
                throw e
            } catch (e: Exception) {
                throw DaemonException("Failed to send request: ${e.message}", e)
            }
        }
    }

    /**
     * 检查 daemon 进程是否存活（进程级检查 + HTTP 健康检查）。
     */
    fun isAlive(): Boolean {
        if (process?.isAlive != true) return false
        return try {
            runBlocking {
                httpClient().get("$baseUrl/health").status == HttpStatusCode.OK
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 重启 daemon。
     * 同步方法，防止多个线程同时重启。
     */
    @Synchronized
    fun restart(): Boolean {
        destroy()
        return start()
    }

    /**
     * 关闭 daemon 进程。
     */
    override fun close() {
        log.info { "关闭 daemon" }
        destroy()
    }

    /**
     * 确保 daemon 正在运行，若未运行则重启。
     * 同步方法，防止多个线程同时进入重启逻辑。
     */
    @Synchronized
    private fun ensureRunning() {
        if (!isAlive() || !ready) {
            if (retries >= maxRetries) {
                throw DaemonException("Daemon not running and max retries ($maxRetries) exceeded")
            }
            retries++
            log.warn { "daemon 未运行，重启中 (重试 $retries/$maxRetries)" }
            val started = restart()
            if (!started) {
                throw DaemonException("Failed to restart daemon (retry $retries/$maxRetries)")
            }
        }
    }

    /**
     * 销毁 daemon 进程。
     * 同步方法，防止与 start() 竞争。
     */
    @Synchronized
    private fun destroy() {
        val p = process
        val oldPort = port
        process = null
        ready = false
        backendAvailable = false
        port = 0

        // 发送 HTTP shutdown（优雅关闭）
        if (oldPort > 0) {
            val baseUrl = "http://127.0.0.1:$oldPort"
            try {
                runBlocking {
                    httpClient().post("$baseUrl/shutdown") {
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

    // --- 序列化消息类型 ---

    @Serializable
    data class ReadyMessage(
        val type: String = "ready",
        @kotlinx.serialization.SerialName("backend_available")
        val backendAvailable: Boolean = false,
        val port: Int = 0,
        @kotlinx.serialization.SerialName("import_error")
        val importError: String = "",
        @kotlinx.serialization.SerialName("import_detail")
        val importDetail: String = "",
    )

    @Serializable
    data class RunRequestBody(
        val source: String,
    )

    @Serializable
    data class ResultMessage(
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

        env["PYTHONUTF8"] = "1"

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