package io.github.xyzboom.aiFuzzer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.MordantHelpFormatter
import io.github.xyzboom.aiFuzzer.cli.DedupEvalCommand
import io.github.xyzboom.aiFuzzer.cli.FuzzCommand
import io.github.xyzboom.aiFuzzer.cli.ReproduceCommand
import io.github.xyzboom.aiFuzzer.cli.ReduceCommand

/**
 * AiFuzzer CLI — AI Compiler Fuzzing Framework.
 *
 * 目标：通过随机生成计算图，自动发现 AI 编译器（TVM Relax / PyTorch / ONNX）中的缺陷。
 *
 * Usage:
 *   aiFuzzer fuzz [--config] [--runs]      Run fuzzing campaigns
 *   aiFuzzer reproduce --ir <path>         Re-run IR file(s)
 *   aiFuzzer reduce --ir <path>            Minimize IR file(s)
 */
class AiFuzzerCommand : CliktCommand(
    name = "aiFuzzer",
    help = "AI Compiler Fuzzing Framework",
) {
    init {
        context { helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) } }
        subcommands(FuzzCommand(), ReproduceCommand(), ReduceCommand(), DedupEvalCommand())
    }

    override fun run() {
        echo("aiFuzzer — AI Compiler Fuzzing Framework")
        echo("Run with a subcommand: fuzz, reproduce, or reduce")
        echo("Use --help on any subcommand for details.")
    }
}

fun main(args: Array<String>) = AiFuzzerCommand().main(args)
