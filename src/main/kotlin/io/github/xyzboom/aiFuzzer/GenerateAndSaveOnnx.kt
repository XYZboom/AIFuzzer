package io.github.xyzboom.aiFuzzer

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.generator.UirGenerator
import io.github.xyzboom.aiFuzzer.translator.onnx.OnnxTranslator
import java.io.File

/**
 * 生成 aifuzzer 程序，翻译为 ONNX 模型构建代码，保存到文件。
 * Python 脚本将执行这些代码创建 ONNX 模型，并用 TVM ONNX frontend 编译执行。
 */
object GenerateAndSaveOnnx {
    @JvmStatic
    fun main(args: Array<String>) {
        val count = if (args.size > 0) args[0].toInt() else 50
        val outDir = File("/tmp/aifuzzer_onnx")
        outDir.mkdirs()
        
        val translator = OnnxTranslator()
        var success = 0
        var fail = 0
        
        for (seed in 0L until count) {
            try {
                val gen = UirGenerator(GeneratorConfig(
                    seed = seed,
                    graphCount = 1..1,
                    minNodesPerGraph = 3,
                    maxNodesPerGraph = 6,
                    fallbackConstProbability = 0.3,
                    shapePreservingChainRange = 0..3,
                ))
                val program = gen.generate()
                val onnxCode = translator.translate(program)
                
                val file = File(outDir, "program_${seed}.py")
                file.writeText(onnxCode)
                success++
            } catch (e: Exception) {
                fail++
            }
        }
        
        println("Generated $success onnx programs, $fail failures")
        println("Files saved to $outDir")
    }
}