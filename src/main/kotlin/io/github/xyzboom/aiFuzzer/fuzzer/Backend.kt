package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.ir.UirProgram

/**
 * 编译器后端抽象。
 *
 * 定义如何在特定编译器中执行 UIR 程序并收集结果。
 * 每个目标编译器（TVM, ONNX, MLIR 等）实现此接口。
 *
 * 实现可以是：
 * - 每轮启动子进程的 [TvmBackend]
 * - 通过常驻 daemon 通信的 [TvmDaemonBackend]
 * - 未来新增的任何后端
 *
 * 线程安全：多线程并发时，每个线程应有独立的 Backend 实例。
 * 实现类应提供 [createCopy] 方法来创建副本。
 */
interface Backend<T : BackendResult> : AutoCloseable {
    /** 后端名称，用于标识和日志 */
    val name: String

    /** 临时工作目录 */
    val workDir: java.io.File

    /**
     * 将 UIR 程序编译/执行为后端特定的结果。
     */
    fun execute(program: UirProgram): T

    /**
     * 执行前是否需要先翻译为某种中间表示。
     * 默认实现先翻译为 Python/其他代码，再执行。
     */
    fun compile(program: UirProgram): CompilationArtifact

    /**
     * 检查环境是否就绪（编译器是否可用）。
     */
    fun checkEnvironment(): Boolean

    /**
     * 清理资源（关闭 daemon 进程、删除临时文件等）。
     * 在 FuzzingPipeline 结束后自动调用。
     */
    override fun close() {}

    /**
     * 创建 Backend 的独立副本（用于多线程）。
     * 默认实现返回 this（单线程场景）。
     * 多线程场景下，实现类应返回新的独立实例（新的 daemon、新的工作目录等）。
     */
    fun createCopy(): Backend<T> = this
}

/**
 * 编译产物（源文件、脚本等）。
 */
data class CompilationArtifact(
    val sourcePath: String,
    val sources: List<SourceFile>,
)

data class SourceFile(
    val fileName: String,
    val content: String,
)

/**
 * 后端执行结果的基类。
 */
abstract class BackendResult(
    open val success: Boolean,
    open val exitCode: Int,
    open val stdout: String,
    open val stderr: String,
    open val elapsedMs: Long,
)