package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.config.PytorchConfig
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.translator.pytorch.PytorchTranslator
import java.io.File

private val log = KotlinLogging.logger {}

/**
 * 基于常驻 daemon 的 PyTorch.compile 后端。
 *
 * 通过 [DaemonClient] 与常驻 Python 进程通信，
 * 避免每次测试都 fork 子进程 + import torch。
 *
 * @param pythonPath Python 可执行文件路径
 * @param daemonScriptPath daemon Python 脚本路径
 * @param dtype Tensor 默认数据类型
 * @param device 执行设备（"cpu" 或 "cuda"）
 * @param compileMode torch.compile 模式
 * @param workDir 工作目录
 */
class PytorchDaemonBackend(
    pythonPath: String = "python3",
    daemonScriptPath: String = "pytorch_daemon.py",
    private val dtype: String = "float32",
    private val device: String = "cpu",
    private val compileMode: String = "default",
    override val workDir: File = File(
        System.getProperty("java.io.tmpdir") ?: "/tmp",
        "aiFuzzer_pytorch_daemon"
    ),
) : DaemonBackend<PytorchDaemonBackend.PytorchResult>(
    pythonPath = pythonPath,
    daemonScriptPath = daemonScriptPath,
    workDir = workDir,
) {

    constructor(config: PytorchConfig) : this(
        pythonPath = config.python,
        daemonScriptPath = "pytorch_daemon.py",
        dtype = config.dtype,
        device = config.device,
        compileMode = config.compileMode,
        workDir = File(
            System.getProperty("java.io.tmpdir") ?: "/tmp",
            config.workDir.split("/").lastOrNull()
                ?: "aiFuzzer_pytorch_daemon"
        ),
    )

    override val name = "PyTorch.compile (daemon)"

    override val translator: PytorchTranslator = PytorchTranslator(
        dtype = dtype,
        device = device,
        compileMode = compileMode,
    )

    /**
     * 创建 Backend 的独立副本（新的 daemon 实例）。
     * 用于多线程场景，每个 worker 线程有独立的 daemon。
     */
    override fun createCopy(): Backend<PytorchResult> {
        val newWorkDir = File(
            workDir.parent,
            "${workDir.name}_thread_${Thread.currentThread().id}"
        )
        return PytorchDaemonBackend(
            pythonPath = pythonPath,
            daemonScriptPath = daemonScriptPath,
            dtype = dtype,
            device = device,
            compileMode = compileMode,
            workDir = newWorkDir,
        )
    }

    override fun checkEnvironment(): Boolean {
        // 确保 daemon 脚本存在于工作目录相对路径上
        val scriptFile = File(daemonScriptPath)
        if (!scriptFile.exists()) {
            log.error { "Daemon 脚本不存在: ${scriptFile.absolutePath}" }
            return false
        }
        // DaemonClient.tvmAvailable 会被 PyTorch daemon 的 torch_available 字段填充
        // 因为 ready 消息的 JSON 字段名相同
        return super.checkEnvironment() && daemon.tvmAvailable
    }

    override fun execute(program: UirProgram): PytorchResult {
        log.debug { "执行 PyTorch.compile 程序" }
        val startTime = System.currentTimeMillis()

        val source = translator.translate(program)
        log.trace { "翻译完成，源码长度: ${source.length}" }

        val daemonResult = daemon.sendAndWait(source)
        log.debug { "daemon 返回: success=${daemonResult.success}, elapsed=${daemonResult.elapsedMs}ms" }

        // 保存源码用于 bug 报告
        val sourceFile = File(workDir, "program.py")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(source)

        val errorInfo = ErrorAnalyzer.analyze(daemonResult.stderr, daemonResult.exitCode)

        if (!daemonResult.success) {
            log.warn { "执行失败: ${errorInfo.category} - ${errorInfo.summary}" }
        }

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

    /**
     * PyTorch.compile 执行结果。
     */
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