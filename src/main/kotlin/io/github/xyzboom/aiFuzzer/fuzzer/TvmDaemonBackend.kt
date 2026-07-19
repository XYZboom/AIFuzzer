package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.config.RemoteSshConfig
import io.github.xyzboom.aiFuzzer.config.TvmConfig
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import java.io.File

private val log = KotlinLogging.logger {}

/**
 * 基于常驻 daemon 的 TVM Relax 后端。
 *
 * 通过 [DaemonClient] 与常驻 Python 进程通信，
 * 避免每次测试都 fork 子进程 + import tvm。
 *
 * @param pythonPath Python 可执行文件路径（conda 环境中的 python）
 * @param daemonScriptPath daemon Python 脚本路径
 * @param daemonCount daemon 实例数（与 workers 数匹配时最高效）
 * @param shapeRank 生成 TensorStructInfo 时使用的 shape 维度数
 * @param dtype Tensor 默认数据类型
 * @param remoteConfig 远程 SSH 配置（可选），设置后 daemon 在远程主机上运行
 */
class TvmDaemonBackend(
    pythonPath: String = "python3",
    daemonScriptPath: String = "daemon/tvm_daemon.py",
    private val daemonCount: Int = 1,
    private val shapeRank: Int = 3,
    private val dtype: String = "float32",
    private val target: String = "llvm",
    private val device: String = "cpu",
    workDir: File = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aiFuzzer_tvm_daemon"),
    /** 远程 SSH 配置（可选），设置后 daemon 在远程主机上运行 */
    private val remoteConfig: RemoteSshConfig? = null,
) : DaemonBackend<TvmBackend.TvmResult>(
    pythonPath = pythonPath,
    daemonScriptPath = daemonScriptPath,
    workDir = workDir,
    customDaemonClient = if (remoteConfig != null) {
        RemoteDaemonClient(
            pythonPath = remoteConfig.python.ifBlank { pythonPath },
            daemonScriptPath = daemonScriptPath,
            requestTimeoutMs = 120_000,
            sshConfig = remoteConfig,
        )
    } else null,
) {

    constructor(config: TvmConfig) : this(
        pythonPath = config.python,
        daemonScriptPath = "daemon/tvm_daemon.py",
        daemonCount = config.daemonCount,
        shapeRank = config.shapeRank,
        dtype = config.dtype,
        target = config.target,
        device = config.device,
        workDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aiFuzzer_tvm_daemon"),
        remoteConfig = config.remote,
    )

    override val name = "TVM Relax (daemon)"

    override val translator: TvmRelaxTranslator = TvmRelaxTranslator(
        shapeRank = shapeRank,
        dtype = dtype,
        target = target,
        device = device,
    )

    /**
     * 创建 Backend 的独立副本（新的 daemon 实例）。
     * 用于多线程场景，每个 worker 线程有独立的 daemon。
     */
    override fun createCopy(): Backend<TvmBackend.TvmResult> {
        val newWorkDir = File(
            workDir.parent,
            "${workDir.name}_thread_${Thread.currentThread().id}"
        )
        return TvmDaemonBackend(
            pythonPath = pythonPath,
            daemonScriptPath = daemonScriptPath,
            daemonCount = daemonCount,
            shapeRank = shapeRank,
            dtype = dtype,
            target = target,
            device = device,
            workDir = newWorkDir,
            remoteConfig = remoteConfig,
        )
    }

    override fun checkEnvironment(): Boolean {
        // 确保 daemon 脚本存在于工作目录相对路径上
        val scriptFile = File(daemonScriptPath)
        if (!scriptFile.exists()) {
            log.error { "Daemon 脚本不存在: ${scriptFile.absolutePath}" }
            return false
        }
        return super.checkEnvironment() && daemon.backendAvailable
    }

    override fun execute(program: UirProgram): TvmBackend.TvmResult {
        log.debug { "执行 TVM 程序" }
        val startTime = System.currentTimeMillis()
        
        val source = translator.translate(program)
        log.trace { "翻译完成，源码长度: ${source.length}" }
        
        val daemonResult = daemon.sendAndWait(source)
        log.debug { "daemon 返回: success=${daemonResult.success}, elapsed=${daemonResult.elapsedMs}ms" }

        // 捕获源码用于 bug 报告
        val sourceFile = File(workDir, "program.py")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(source)

        val errorInfo = ErrorAnalyzer.analyze(daemonResult.stderr, daemonResult.exitCode)
        
        if (!daemonResult.success) {
            log.warn { "执行失败: ${errorInfo.category} - ${errorInfo.summary}" }
        }

        return TvmBackend.TvmResult(
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

    override fun toResult(program: UirProgram, daemonResult: DaemonResult): TvmBackend.TvmResult {
        val errorInfo = ErrorAnalyzer.analyze(daemonResult.stderr, daemonResult.exitCode)
        val source = translator.translate(program)
        val sourceFile = File(workDir, "program.py")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(source)

        return TvmBackend.TvmResult(
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
}