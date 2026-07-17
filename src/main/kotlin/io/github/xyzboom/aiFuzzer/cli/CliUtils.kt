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
