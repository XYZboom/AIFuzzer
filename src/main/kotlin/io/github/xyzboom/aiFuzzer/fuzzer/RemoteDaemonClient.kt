package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.config.RemoteSshConfig
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.scp.client.ScpClientCreator
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * 通过 SSH 隧道连接远程 daemon 的客户端。
 *
 * 使用 Apache MINA SSHD（纯 Java SSH 库）实现 SSH 连接、命令执行、
 * SCP 文件上传和本地端口转发，不依赖任何外部命令（sshpass/ssh/scp）。
 * 跨平台，可在任何 Java 运行环境上工作。
 *
 * 工作流程：
 * 1. 使用 SCP 将 daemon Python 脚本上传到远程主机
 * 2. SSH 在远程主机上启动 daemon HTTP 服务
 * 3. SSH 本地端口转发：localPort → remoteHost:remotePort
 * 4. 本地 HTTP 请求通过隧道发往远程 daemon，无需从远程拷贝文件
 *
 * aiFuzzer 完全运行在本机，远程主机只负责执行 Python 代码。
 */
class RemoteDaemonClient(
    pythonPath: String,
    daemonScriptPath: String,
    maxRetries: Int = 3,
    requestTimeoutMs: Long = 120_000,
    /** SSH 远程主机配置 */
    private val sshConfig: RemoteSshConfig,
) : DaemonClient(
    pythonPath = pythonPath,
    daemonScriptPath = daemonScriptPath,
    maxRetries = maxRetries,
    requestTimeoutMs = requestTimeoutMs,
) {

    /** MINA SSHD 客户端实例 */
    private var sshClient: SshClient? = null
    /** SSH 会话 */
    private var session: ClientSession? = null

    /**
     * 建立 SSH 连接并认证。
     */
    private fun connect(): ClientSession {
        // 如果已有活动会话，直接返回
        session?.let { s ->
            if (s.isOpen) return s
        }

        // 创建并启动 SSH 客户端
        val client = SshClient.setUpDefaultClient()
        client.start()
        sshClient = client

        // 连接
        val s = client.connect(sshConfig.user, sshConfig.host, sshConfig.port)
            .verify(10, TimeUnit.SECONDS)
            .session

        // 密码认证
        if (sshConfig.password.isNotBlank()) {
            s.addPasswordIdentity(sshConfig.password)
        }

        s.auth().verify(10, TimeUnit.SECONDS)
        session = s
        return s
    }

    /**
     * 执行 SSH 远程命令并返回 stdout。
     */
    private fun execSsh(command: String): String {
        val s = connect()
        val out = ByteArrayOutputStream()
        try {
            val channel = s.createExecChannel(command)
            channel.out = out
            channel.err = ByteArrayOutputStream()
            channel.open().verify(30, TimeUnit.SECONDS)
            // 等待 channel 关闭（命令执行完成）
            channel.close(false).await(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            log.warn(e) { "远程命令异常: $command" }
        }
        return out.toString()
    }

    /**
     * 上传文件到远程主机（使用 SCP）。
     */
    private fun uploadFile(localPath: String, remotePath: String) {
        val s = connect()
        val scp = ScpClientCreator.instance().createScpClient(s)
        scp.upload(localPath, remotePath)
    }

    /**
     * 启动 SSH 本地端口转发，创建隧道。
     */
    private fun startPortForwarding(localPort: Int, remotePort: Int) {
        val s = connect()
        val localAddr = SshdSocketAddress("127.0.0.1", localPort)
        val remoteAddr = SshdSocketAddress("127.0.0.1", remotePort)
        s.startLocalPortForwarding(localAddr, remoteAddr)
    }

    /**
     * 停止端口转发。
     */
    private fun stopPortForwarding(localPort: Int) {
        try {
            val localAddr = SshdSocketAddress("127.0.0.1", localPort)
            session?.stopLocalPortForwarding(localAddr)
        } catch (_: Exception) {}
    }

    /**
     * 等待远程 daemon 的健康检查通过。
     * @param maxWaitMs 最大等待时间（毫秒）
     */
    private fun waitForRemoteDaemonReady(maxWaitMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val body = runBlocking {
                    httpClient().get("$baseUrl/health").bodyAsText()
                }
                log.info { "远程 daemon 就绪: $body" }
                return true
            } catch (_: Exception) {
                // 连接尚未就绪，继续等待
            }
            Thread.sleep(500)
        }
        return false
    }

    override fun start(): Boolean {
        synchronized(this) {
            if (session?.isOpen == true) {
                log.debug { "SSH session 已连接: port=$port" }
                return true
            }
            try {
                val scriptLocalPath = java.io.File(daemonScriptPath)
                if (!scriptLocalPath.exists()) {
                    log.error { "本地 daemon 脚本不存在: ${scriptLocalPath.absolutePath}" }
                    return false
                }
                val remoteScriptName = scriptLocalPath.name
                val remoteWorkDir = sshConfig.workDir
                val remoteScriptPath = "$remoteWorkDir/$remoteScriptName"
                val remotePython = sshConfig.python.ifBlank { "python3" }

                // 1. 创建远程工作目录并清理旧 daemon
                execSsh("mkdir -p $remoteWorkDir")
                execSsh("pkill -f 'pytorch_daemon.py' 2>/dev/null; pkill -f 'onnx_daemon.py' 2>/dev/null; pkill -f 'tvm_daemon.py' 2>/dev/null; true")

                // 2. 上传 daemon 脚本
                log.info { "上传 daemon 脚本到远程: $remoteScriptPath" }
                uploadFile(scriptLocalPath.absolutePath, remoteScriptPath)

                // 3. 远程查询空闲端口
                val remotePort = execSsh("$remotePython -c \"import socket; s=socket.socket(); s.bind(('127.0.0.1',0)); print(s.getsockname()[1]); s.close()\"").trim().toIntOrNull()
                    ?: (10000 + (Math.random() * 50000).toInt())
                val localPort = findFreePort()

                log.info { "启动远程 daemon: host=${sshConfig.host}:${sshConfig.port}, " +
                        "remotePort=$remotePort, localPort=$localPort, python=$remotePython" }

                // 3a. 启动远程 daemon（后台运行）
                val daemonCmd = "cd $remoteWorkDir && nohup $remotePython $remoteScriptPath --port $remotePort > $remoteWorkDir/daemon_${remotePort}.log 2>&1 &"
                execSsh(daemonCmd)
                log.info { "远程 daemon 已启动，等待就绪..." }

                // 3b. 创建 SSH 隧道（本地端口转发到远程 daemon 端口）
                log.info { "创建 SSH 隧道: localhost:$localPort -> ${sshConfig.host}:$remotePort" }
                startPortForwarding(localPort, remotePort)
                process = null
                port = localPort

                // 3c. 等待远程 daemon 就绪（通过健康检查）
                log.info { "开始健康检查: http://127.0.0.1:$localPort/health" }
                if (!waitForRemoteDaemonReady(30_000)) {
                    log.error { "远程 daemon 启动超时（30s）" }
                    val remoteLog = execSsh("cat $remoteWorkDir/daemon_${remotePort}.log 2>/dev/null || echo 'no log'")
                    log.error { "远程 daemon 日志:\n$remoteLog" }
                    destroy()
                    return false
                }

                this.ready = true
                backendAvailable = true
                retries = 0
                log.info { "远程 daemon 就绪: localhost:$localPort -> ${sshConfig.host}:$remotePort" }
                return true
            } catch (e: Exception) {
                log.error(e) { "启动远程 daemon 失败" }
                destroy()
                return false
            }
        }
    }

    override fun isAlive(): Boolean {
        // SSHD 管理的隧道没有 OS 进程可检查，直接通过 HTTP 健康检查判断
        return try {
            runBlocking {
                httpClient().get("$baseUrl/health").status == HttpStatusCode.OK
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun emergencyRestart() {
        log.warn { "远程 daemon 请求超时，紧急重启 (port=$port)" }
        destroy()
        closeHttpClient()
        retries = 0
        val started = start()
        if (!started) {
            log.error { "紧急重启远程 daemon 失败" }
        } else {
            log.info { "紧急重启远程 daemon 成功: port=$port" }
        }
    }

    override fun destroy() {
        val oldPort = port
        this.ready = false
        backendAvailable = false
        port = 0

        // 1. 先关闭 HTTP 客户端，防止 pending 请求阻塞
        closeHttpClient()

        // 2. 通过 SSH 杀死远程 daemon 进程（在关闭 session 之前）
        try {
            val s = session
            if (s != null && s.isOpen) {
                val cmd = "pkill -f 'pytorch_daemon.py' 2>/dev/null; " +
                        "pkill -f 'onnx_daemon.py' 2>/dev/null; " +
                        "pkill -f 'tvm_daemon.py' 2>/dev/null; " +
                        "pkill -f 'compile_worker' 2>/dev/null; true"
                val channel = s.createExecChannel(cmd)
                channel.open().verify(5, TimeUnit.SECONDS)
                channel.close(false).await(5, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {}

        // 3. 停止端口转发
        if (oldPort > 0) {
            stopPortForwarding(oldPort)
        }

        // 4. 关闭 SSH 会话（带超时）
        try {
            session?.close(false)?.await(5, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        session = null

        // 5. 停止 SSH 客户端
        try {
            sshClient?.stop()
        } catch (_: Exception) {}
        sshClient = null
    }
}