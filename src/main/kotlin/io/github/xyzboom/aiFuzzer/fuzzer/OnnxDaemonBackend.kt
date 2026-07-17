package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.config.OnnxConfig
import io.github.xyzboom.aiFuzzer.ir.UirProgram
import io.github.xyzboom.aiFuzzer.translator.onnx.OnnxTranslator
import java.io.File

private val log = KotlinLogging.logger {}

class OnnxDaemonBackend(
    pythonPath: String = "python3",
    daemonScriptPath: String = "onnx_daemon.py",
    private val opsetVersion: Int = 21,
    workDir: File = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aiFuzzer_onnx_daemon"),
) : DaemonBackend<OnnxBackend.OnnxResult>(
    pythonPath = pythonPath,
    daemonScriptPath = daemonScriptPath,
    workDir = workDir,
) {
    constructor(config: OnnxConfig) : this(
        pythonPath = config.python,
        opsetVersion = config.opsetVersion,
        workDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aiFuzzer_onnx_daemon"),
    )

    override val name = "ONNX Runtime (daemon)"
    override val translator: OnnxTranslator = OnnxTranslator(opsetVersion = opsetVersion)

    override fun createCopy(): Backend<OnnxBackend.OnnxResult> {
        return OnnxDaemonBackend(pythonPath, daemonScriptPath, opsetVersion,
            File(workDir.parent, "${workDir.name}_thread_${Thread.currentThread().id}"))
    }

    override fun checkEnvironment(): Boolean {
        val sf = File(daemonScriptPath)
        if (!sf.exists()) { log.error { "Daemon 脚本不存在: ${sf.absolutePath}" }; return false }
        return super.checkEnvironment() && daemon.ready
    }

    override fun execute(program: UirProgram): OnnxBackend.OnnxResult {
        val source = translator.translate(program)
        val dr = daemon.sendAndWait(source)
        val sf = File(workDir, "program.py"); sf.parentFile.mkdirs(); sf.writeText(source)
        val ei = ErrorAnalyzer.analyze(dr.stderr, dr.exitCode)
        return OnnxBackend.OnnxResult(dr.success, dr.exitCode, dr.stdout, dr.stderr, dr.elapsedMs, ei.category, ei.summary, sf.absolutePath)
    }

    override fun toResult(program: UirProgram, dr: DaemonResult): OnnxBackend.OnnxResult {
        val ei = ErrorAnalyzer.analyze(dr.stderr, dr.exitCode)
        val sf = File(workDir, "program.py"); sf.parentFile.mkdirs(); sf.writeText(translator.translate(program))
        return OnnxBackend.OnnxResult(dr.success, dr.exitCode, dr.stdout, dr.stderr, dr.elapsedMs, ei.category, ei.summary, sf.absolutePath)
    }
}
