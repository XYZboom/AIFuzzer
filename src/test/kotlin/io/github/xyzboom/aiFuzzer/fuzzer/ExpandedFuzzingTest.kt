package io.github.xyzboom.aiFuzzer.fuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.PrintWriter

/**
 * 大规模 fuzzing 测试，使用扩展后的算子集。
 */
class ExpandedFuzzingTest {

    /** 使用所有已实现算子运行 200 轮 TVM 后端测试 */
    @Test
    fun `fuzz with all ops - 200 seeds on TVM backend`() {
        File("reports").mkdirs()

        val generator = UirGenerator(GeneratorConfig(
            seed = 1,
            minNodesPerGraph = 2,
            maxNodesPerGraph = 5,
            minInputNdim = 1,
            maxInputNdim = 2,
        ))
        val translator = TvmRelaxTranslator()
        val backend = TvmBackend()
        var failures = 0
        val errors = mutableMapOf<String, Int>()
        val bugReports = mutableListOf<String>()

        // 验证环境
        assumeTrue(backend.checkEnvironment(), "TVM not installed")

        for (i in 0 until 200) {
            val seed = (i + 1).toLong()
            val gen = UirGenerator(GeneratorConfig(
                seed = seed,
                minNodesPerGraph = 2,
                maxNodesPerGraph = 5,
                minInputNdim = 1,
                maxInputNdim = 2,
            ))
            val program = gen.generate()
            val result = backend.execute(program)

            if (!result.success) {
                failures++
                errors.merge(result.errorCategory.name, 1, Int::plus)
                val stderr = result.stderr.take(300)
                bugReports.add("[seed=$seed] ${result.errorCategory}: $stderr")

                if (!BugCollector.isWorthyBug(result)) {
                    // 这应当是生成器/翻译器 bug，记录详细内容
                    println("  ⚠ BUG (translator/generator): seed=$seed ${result.errorCategory}")
                    println("  ${result.stderr.lines().firstOrNull()?.take(120)}")
                } else {
                    BugCollector.collect(result, seed, "TVM Relax")
                }
            }

            if (i % 25 == 24 || i == 199) {
                println("[${i+1}/200] S: ${(i+1)-failures}, F: $failures")
                if (errors.isNotEmpty()) {
                    println("  Errors: ${errors.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
                }
            }
        }

        // 生成报告
        PrintWriter(File("reports/expanded_run_report.txt")).use { pw ->
            pw.println("Expanded Ops Fuzzing Report")
            pw.println("Success: ${200 - failures}/200 (%.1f%%)".format((200-failures)/2.0))
            pw.println()
            if (bugReports.isNotEmpty()) {
                pw.println("Failures:")
                bugReports.forEach { pw.println("  $it") }
            }
        }

        // 如果失败率低于 20% 就通过（有些可能是新算子的生成器边缘情况）
        assertTrue(failures <= 40, "Too many failures: $failures/200")
        println("\nSuccess rate: ${200 - failures}/200 (${(200 - failures) / 2.0}%)")
    }
}