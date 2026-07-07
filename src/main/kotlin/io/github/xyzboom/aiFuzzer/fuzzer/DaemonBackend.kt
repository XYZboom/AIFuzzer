package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.translator.UirTranslator
import java.io.File

/**
 * 基于常驻 daemon 进程的编译器后端基类。
 *
 * 子类只需指定：
 * - [translator]：将 UIR 翻译为目标编译器源码
 * - [daemonScript]：daemon Python 脚本路径
 * - [pythonPath]：Python 可执行文件路径
 *
 * daemon 的启动、通信、心跳检测、崩溃恢复由基类统一处理。
 *
 * @param T 执行结果的类型
 * @param pythonPath Python 可执行文件路径
 * @param daemonScriptPath daemon Python 脚本路径（绝对路径或相对路径）
 * @param workDir 工作目录
 * @param envProvider 环境变量提供者
 */
abstract class DaemonBackend<T : BackendResult>(
    val pythonPath: String,
    val daemonScriptPath: String,
    override val workDir: File = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aiFuzzer_daemon"),
    private val envProvider: DaemonEnvProvider = DefaultDaemonEnvProvider(pythonPath),
) : Backend<T> {

    protected abstract val translator: UirTranslator<UirProgram, String>

    /** daemon 客户端 */
    protected val daemon: DaemonClient = DaemonClient(
        pythonPath = pythonPath,
        daemonScriptPath = daemonScriptPath,
        maxRetries = 3,
        requestTimeoutMs = 120_000,
        envProvider = envProvider,
    )

    override fun checkEnvironment(): Boolean {
        if (!daemon.isAlive()) {
            return daemon.start()
        }
        return daemon.isAlive() && daemon.tvmAvailable
    }

    override fun compile(program: UirProgram): CompilationArtifact {
        val pythonCode = translator.translate(program)
        return CompilationArtifact(
            sourcePath = workDir.absolutePath,
            sources = listOf(SourceFile("program.py", pythonCode)),
        )
    }

    override fun close() {
        daemon.close()
    }

    /**
     * 将 daemon 结果转换为具体后端的 T 类型。
     */
    protected abstract fun toResult(
        program: UirProgram,
        daemonResult: DaemonResult,
    ): T
}