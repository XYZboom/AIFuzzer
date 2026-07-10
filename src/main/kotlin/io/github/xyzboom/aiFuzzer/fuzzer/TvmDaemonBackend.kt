package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.oshai.kotlinlogging.KotlinLogging
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
 */
class TvmDaemonBackend(
    pythonPath: String = "python3",
    daemonScriptPath: String = "tvm_daemon.py",
    private val daemonCount: Int = 1,
    private val shapeRank: Int = 3,
    private val dtype: String = "float32",
    workDir: File = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aiFuzzer_tvm_daemon"),
) : DaemonBackend<TvmBackend.TvmResult>(
    pythonPath = pythonPath,
    daemonScriptPath = daemonScriptPath,
    workDir = workDir,
) {

    constructor(config: TvmConfig) : this(
        pythonPath = config.python,
        daemonScriptPath = "tvm_daemon.py",
        daemonCount = config.daemonCount,
        shapeRank = config.shapeRank,
        dtype = config.dtype,
        workDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aiFuzzer_tvm_daemon"),
    )

    override val name = "TVM Relax (daemon)"

    override val translator: TvmRelaxTranslator = TvmRelaxTranslator(
        shapeRank = shapeRank,
        dtype = dtype,
    )

    override fun checkEnvironment(): Boolean {
        // 确保 daemon 脚本存在于工作目录相对路径上
        val scriptFile = File(daemonScriptPath)
        if (!scriptFile.exists()) {
            log.error { "Daemon 脚本不存在: ${scriptFile.absolutePath}" }
            return false
        }
        return super.checkEnvironment() && daemon.tvmAvailable
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