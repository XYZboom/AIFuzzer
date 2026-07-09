package io.github.xyzboom.aiFuzzer.config

import io.github.xyzboom.aiFuzzer.generator.GeneratorConfig
import io.github.xyzboom.aiFuzzer.fuzzer.FuzzingPipeline

/**
 * AiFuzzer 顶层配置。
 *
 * data class 使用 var 以便 ConfigLoader 可以赋值。
 * 所有字段都有默认值，可直接从 YAML 文件反序列化。
 */
data class FuzzerConfig(
    var run: RunConfig = RunConfig(),
    var generator: FuzzerGenConfig = FuzzerGenConfig(),
    var backends: BackendsConfig = BackendsConfig(),
    var bugCollector: BugCollectorConfig = BugCollectorConfig(),
    var pipeline: PipelineConfig = PipelineConfig(),
)

data class RunConfig(
    var description: String = "AiFuzzer default run",
    var seed: String? = null, // null = 系统时间; "42" = 指定值
    var outputDir: String = "./reports",
    var logLevel: String = "info",
)

/** 生成器配置（面向外部配置的版本，与 generator.GeneratorConfig 独立） */
data class FuzzerGenConfig(
    var minNodesPerGraph: Int = 3,
    var maxNodesPerGraph: Int = 8,
    var minInputs: Int = 1,
    var maxInputs: Int = 3,
    var minInputNdim: Int = 1,
    var maxInputNdim: Int = 3,
    var graphCount: Int = 1,
    var ops: OpsConfig = OpsConfig(),
    var strategy: String = "random",
    var mutation: MutationConfig = MutationConfig(),
) {
    /** 转换为 backend 使用的 GeneratorConfig */
    fun toGeneratorConfig(seed: Long): GeneratorConfig {
        val resolvedOps = resolveOps()
        return GeneratorConfig(
            seed = seed,
            minNodesPerGraph = minNodesPerGraph,
            maxNodesPerGraph = maxNodesPerGraph,
            minInputs = minInputs,
            maxInputs = maxInputs,
            minInputNdim = minInputNdim,
            maxInputNdim = maxInputNdim,
            graphCount = graphCount,
            ops = resolvedOps,
        )
    }

    private fun resolveOps(): List<String> {
        val defaultOps: List<String> = io.github.xyzboom.aiFuzzer.generator.DefaultOps.map { it.name }
        if (ops.includeAll) {
            return ops.include.ifEmpty { defaultOps }
                .filter { it !in ops.exclude }
        }
        return ops.include.ifEmpty { defaultOps }
            .filter { it !in ops.exclude }
    }
}

data class OpsConfig(
    var includeAll: Boolean = true,
    var include: List<String> = emptyList(),
    var exclude: List<String> = emptyList(),
)

data class MutationConfig(
    var enabled: Boolean = false,
    var rate: Double = 0.1,
)

data class BackendsConfig(
    var enabled: List<String> = listOf("tvm"),
    var tvm: TvmConfig = TvmConfig(),
    var onnx: OnnxConfig = OnnxConfig(),
    var iree: IreeConfig = IreeConfig(),
)

data class TvmConfig(
    var python: String = "python3",
    /** 执行模式: "process" (每轮独立进程) 或 "daemon" (常驻进程) */
    var mode: String = "daemon",
    /** daemon 实例数（= workers 时最高效）*/
    var daemonCount: Int = 1,
    var timeoutSeconds: Int = 60,
    var keepArtifacts: Boolean = false,
    var workDir: String = System.getProperty("java.io.tmpdir", "/tmp") + "/aiFuzzer_tvm",
    var dtype: String = "float32",
    var shapeRank: Int = 3,
)

data class OnnxConfig(
    var python: String = "python3",
    var timeoutSeconds: Int = 60,
    var opsetVersion: Int = 21,
    var irVersion: Int = 8,
)

data class IreeConfig(
    var timeoutSeconds: Int = 120,
    var target: String = "llvm-cpu",
    var driver: String = "local-sync",
    var mlirFlags: List<String> = emptyList(),
)

data class BugCollectorConfig(
    var enabled: Boolean = true,
    var ignorePatterns: List<String> = listOf(
        "SyntaxError", "IndentationError", "ImportError",
        "ModuleNotFoundError", "AttributeError", "OpNotImplemented",
    ),
    var outputDir: String = "./reports",
)

data class PipelineConfig(
    var workers: Int = 1,
    var batchSize: Int = 100,
    var reportInterval: Int = 10,
    var runTimeoutSeconds: Int = 60,
) {
    fun toFuzzingConfig(): FuzzingPipeline.FuzzingConfig {
        return FuzzingPipeline.FuzzingConfig(
            runTimeoutSeconds = runTimeoutSeconds,
            workers = workers,
            keepArtifacts = false,
        )
    }
}