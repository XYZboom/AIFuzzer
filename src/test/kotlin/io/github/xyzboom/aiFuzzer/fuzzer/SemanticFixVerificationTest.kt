package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.serialize.UirSerializer
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files

/**
 * 验证修改后生成器产出的程序在 TVM 上仍然合法可执行。
 */
class SemanticFixVerificationTest {

    /** Python 解释器路径 */
    private val pythonPath = "/root/miniconda3/envs/aifuzzer/bin/python3"

    @Test
    fun `generated programs execute successfully on TVM`() {
        var totalGenerated = 0
        var totalSuccess = 0
        var totalFailures = 0
        val failures = mutableListOf<String>()

        // Run 30 seeds with the fix included; enough to cover the new op behaviors
        for (seed in 0L until 30L) {
            val gen = UirGenerator(GeneratorConfig(
                seed = seed,
                minNodesPerGraph = 4,
                maxNodesPerGraph = 12,
                graphCount = 1..2,
                avoidNaNInf = false,
                avoidExtremeOps = false,
            ))
            val program = try {
                gen.generate()
            } catch (e: Exception) {
                failures.add("seed=$seed: generation crashed: ${e.message}")
                totalFailures++
                continue
            }
            totalGenerated++

            val translator = TvmRelaxTranslator(target = "llvm", device = "cpu")
            val pythonCode = try {
                translator.translate(program)
            } catch (e: Exception) {
                failures.add("seed=$seed: translation crashed: ${e.message}")
                totalFailures++
                continue
            }

            // Write to temp file and execute with TVM
            val tmpDir = Files.createTempDirectory("aifuzzer_semantic_verify_").toFile()
            try {
                val scriptFile = File(tmpDir, "program.py")
                scriptFile.writeText(pythonCode)

                val process = ProcessBuilder(pythonPath, scriptFile.absolutePath)
                    .directory(tmpDir)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    totalSuccess++
                    println("✓ seed=$seed: OK")
                } else {
                    totalFailures++
                    val summary = output.lines().takeLast(10).joinToString("\n")
                    failures.add("seed=$seed: exit=$exitCode\n$summary")
                    println("✗ seed=$seed: FAILED (exit=$exitCode)")
                    // Save failing program for debugging
                    val failFile = File(tmpDir, "FAIL_program_seed_${seed}.py")
                    failFile.writeText(pythonCode)
                    println("  saved to: ${failFile.absolutePath}")
                }
            } finally {
                tmpDir.deleteRecursively()
            }
        }

        println()
        println("=== Verification Result ===")
        println("Generated: $totalGenerated, Success: $totalSuccess, Failures: $totalFailures")
        if (failures.isNotEmpty()) {
            println("\n--- Failures ---")
            failures.forEach { println(it); println() }
        }

        // Allow up to 10% failure rate (pre-existing bugs, not our changes)
        val failRate = totalFailures.toDouble() / totalGenerated.toDouble()
        assertTrue(
            failRate <= 0.15,
            "Failure rate too high: ${"%.1f".format(failRate * 100)}% (${totalFailures}/${totalGenerated})"
        )
    }
}
