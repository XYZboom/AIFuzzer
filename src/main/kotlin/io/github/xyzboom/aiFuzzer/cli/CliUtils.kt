package io.github.xyzboom.aiFuzzer.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.output.MordantHelpFormatter
import io.github.xyzboom.aiFuzzer.config.FuzzerConfig
import io.github.xyzboom.aiFuzzer.fuzzer.*
import java.io.File

/** Shared CLI utilities for subcommands. */
object CliUtils {
    fun helpFormatter(): (Context) -> HelpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
}

/** Initialize backends based on config. */
fun initBackends(config: FuzzerConfig): List<Backend<*>> {
    val backends = mutableListOf<Backend<*>>()
    if ("tvm" in config.backends.enabled) {
        val tvmCfg = config.backends.tvm
        if (tvmCfg.mode == "daemon") {
            backends.add(TvmDaemonBackend(tvmCfg))
        } else {
            System.err.println(
                "[WARN] TVM 后端 `mode: \"process\"` 已废弃！" +
                "每次 testcase 启动子进程 + import tvm 需 5 秒，性能极差。" +
                "请改用 `mode: \"daemon\"`（默认值），常驻进程约 100ms/次。"
            )
            backends.add(TvmBackend(File(tvmCfg.workDir), tvmCfg))
        }
    }
    if ("pytorch" in config.backends.enabled) {
        backends.add(PytorchDaemonBackend(config.backends.pytorch))
    }
    if ("onnx" in config.backends.enabled) {
        backends.add(OnnxDaemonBackend(config.backends.onnx))
    }
    return backends
}
