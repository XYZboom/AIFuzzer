package io.github.xyzboom.aiFuzzer

import io.github.xyzboom.aiFuzzer.bench.TvmBench

/**
 * AiFuzzer CLI 入口。
 *
 * Usage:
 *   ./gradlew run --args="<count> <startSeed>"
 *   ./gradlew run --args="100 20001"     # 跑 100 次，从 seed=20001 开始
 */
fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "bench", "tvm" -> {
            val count = args.getOrNull(1)?.toIntOrNull() ?: 50
            val startSeed = args.getOrNull(2)?.toLongOrNull() ?: 10001L
            TvmBench().run(count = count, startSeed = startSeed)
        }
        null, "help" -> {
            println("Usage:")
            println("  bench <count> <startSeed>  — 跑 TVM 编译器 Fuzzing 测试")
            println("  help                       — 显示此帮助")
        }
        else -> {
            System.err.println("Unknown command: ${args[0]}")
            println("Use 'help' for usage.")
        }
    }
}