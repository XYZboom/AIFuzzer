package io.github.xyzboom.aiFuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.translator.tvm.TvmRelaxTranslator
import java.io.File

/**
 * 生成 aifuzzer 程序，翻译为 TVM Relax Python 代码，保存到文件。
 * Python 脚本将读取这些文件并用 TVM CUDA 运行。
 */
object GenerateAndSavePrograms {
    @JvmStatic
    fun main(args: Array<String>) {
        val count = if (args.size > 0) args[0].toInt() else 50
        val outDir = File("/tmp/aifuzzer_programs")
        outDir.mkdirs()
        
        val translator = TvmRelaxTranslator(target = "cuda", device = "cuda")
        var success = 0
        var fail = 0
        
        for (seed in 0L until count) {
            try {
                val gen = UirGenerator(GeneratorConfig(
                    seed = seed,
                    graphCount = 3..5,
                    minNodesPerGraph = 3,
                    maxNodesPerGraph = 6,
                    fallbackConstProbability = 0.3,
                    shapePreservingChainRange = 0..3,
                ))
                val program = gen.generate()
                val pythonCode = translator.translate(program)
                
                val file = File(outDir, "program_${seed}.py")
                file.writeText(pythonCode)
                success++
            } catch (e: Exception) {
                fail++
            }
        }
        
        println("Generated $success programs, $fail failures")
        println("Files saved to $outDir")
    }
}