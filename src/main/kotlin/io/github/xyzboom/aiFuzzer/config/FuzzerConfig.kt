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
    var minInputNdim: Int = 2,  // 至少 2D
    var maxInputNdim: Int = 4,
    var graphCount: Int = 1,
    var ops: OpsConfig = OpsConfig(),
    var strategy: String = "random",
    var mutation: MutationConfig = MutationConfig(),
    var dtype: String = "float32",
    var dtypeBits: Int = 32,
    /** 形状档位名称，控制形状大小以避免 OOM */
    var shapeTier: String = "tiny",
    /** 避免生成可能导致 NaN/Inf 的算子。默认开启 */
    var avoidNaNInf: Boolean = true,
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
            graphCount = graphCount,
            ops = resolvedOps,
            minNdim = minInputNdim,
            maxNdim = maxInputNdim,
            dtype = dtype,
            dtypeBits = dtypeBits,
            shapeTier = shapeTier,
            avoidNaNInf = avoidNaNInf,
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
    var pytorch: PytorchConfig = PytorchConfig(),
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

data class PytorchConfig(
    var python: String = "python3",
    /** 执行模式: "daemon" (常驻进程) */
    var mode: String = "daemon",
    var timeoutSeconds: Int = 120,
    var keepArtifacts: Boolean = false,
    var workDir: String = System.getProperty("java.io.tmpdir", "/tmp") + "/aiFuzzer_pytorch",
    var dtype: String = "float32",
    /** 执行设备: "cpu" 或 "cuda" */
    var device: String = "cpu",
    /** torch.compile 模式: "default", "reduce-overhead", "max-autotune" */
    var compileMode: String = "default",
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
    var failFast: Boolean = false,
    /** 缩减配置，不设置或 enabled=false 时禁用缩减 */
    var reducer: ReducerConfig = ReducerConfig(),
) {
    data class ReducerConfig(
        var enabled: Boolean = true,   // 默认开启自动缩减
    )

    fun toFuzzingConfig(): FuzzingPipeline.FuzzingConfig {
        val rc = if (reducer.enabled) {
            io.github.xyzboom.aiFuzzer.reducer.AutoReducer.ReducerConfig(enabled = true)
        } else null
        return FuzzingPipeline.FuzzingConfig(
            runTimeoutSeconds = runTimeoutSeconds,
            workers = workers,
            keepArtifacts = false,
            failFast = failFast,
            reducerConfig = rc,
        )
    }
}