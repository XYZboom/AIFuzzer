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
    var graphCount: IntRange = 3..5,
    var ops: OpsConfig = OpsConfig(),
    var strategy: String = "random",
    var mutation: MutationConfig = MutationConfig(),
    var dtype: String = "float32",
    var dtypeBits: Int = 32,
    /** 形状档位名称，控制形状大小以避免 OOM */
    var shapeTier: String = "tiny",
    /** 避免生成可能导致 NaN/Inf 的算子。默认开启 */
    var avoidNaNInf: Boolean = true,
    /** 避免生成向上/向下取整、argmin/argmax 等极端算子。当前排除: CEIL, FLOOR, ROUND, ARGMAX, ARGMIN, SIGN, CUMSUM, REDUCE_SUM, REDUCE_MEAN。默认开启。 */
    var avoidExtremeOps: Boolean = true,
    /** 去重配置 */
    var dedup: PipelineConfig.DedupConfig = PipelineConfig.DedupConfig(),
) {
    /** 转换为 backend 使用的 GeneratorConfig */
    fun toGeneratorConfig(seed: Long): GeneratorConfig {
        val resolvedOps = resolveOps()
        val patternDb = if (dedup.enabled) {
            if (dedup.patternDir.isNotBlank()) {
                try {
                    val file = java.io.File(dedup.patternDir)
                    if (file.exists()) {
                        val db = io.github.xyzboom.aiFuzzer.pattern.PatternParser.parse(file.readText())
                        System.err.println("[INFO] 加载 pattern 数据库: ${db.patterns.size} 个 pattern (${dedup.compiler}/${dedup.target})")
                        db
                    } else {
                        System.err.println("[WARN] pattern 文件不存在: ${dedup.patternDir}")
                        null
                    }
                } catch (e: Exception) {
                    System.err.println("[WARN] 加载 pattern 数据库失败: ${e.message}")
                    null
                }
            } else {
                // 从 classpath resources/patterns/ 加载
                try {
                    val resource = this::class.java.classLoader.getResource("patterns")
                    if (resource != null) {
                        val dir = java.io.File(resource.toURI())
                        if (dir.isDirectory) {
                            val allPatterns = mutableListOf<io.github.xyzboom.aiFuzzer.pattern.PatternDef>()
                            val files = dir.listFiles { f -> f.extension == "json" } ?: emptyArray()
                            for (file in files) {
                                try {
                                    val json = file.readText()
                                    val db = io.github.xyzboom.aiFuzzer.pattern.PatternParser.parse(json)
                                    allPatterns.addAll(db.patterns)
                                } catch (e: Exception) {
                                    System.err.println("[WARN] 加载 pattern 文件 ${file.name} 失败: ${e.message}")
                                }
                            }
                            System.err.println("[INFO] 从 classpath 加载了 ${allPatterns.size} 个 pattern (${files.size} 个文件)")
                            io.github.xyzboom.aiFuzzer.pattern.PatternDatabase(patterns = allPatterns)
                        } else {
                            null
                        }
                    } else {
                        System.err.println("[WARN] resources/patterns 目录未找到")
                        null
                    }
                } catch (e: Exception) {
                    System.err.println("[WARN] 从 classpath 加载 pattern 失败: ${e.message}")
                    null
                }
            }
        } else {
            null
        }
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
            avoidExtremeOps = avoidExtremeOps,
            dedup = io.github.xyzboom.aiFuzzer.generator.DedupConfig(
                enabled = dedup.enabled,
                patternDatabase = patternDb,
                compiler = dedup.compiler,
                target = dedup.target,
                maxRetries = 5,
            ),
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
    /** 全局远程 SSH 主机配置。设置后所有后端自动继承，除非后端单独覆盖 */
    var remote: RemoteSshConfig? = null,
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
    /** TVM build target，如 "llvm" 或 "cuda" */
    var target: String = "llvm",
    /** TVM 设备，如 "cpu" 或 "cuda"，对应 tvm.cpu() / tvm.cuda() */
    var device: String = "cpu",
    /** 远程 SSH 主机配置（可选）。设置后 daemon 在远程主机上运行 */
    var remote: RemoteSshConfig? = null,
)

data class OnnxConfig(
    var python: String = "python3",
    var timeoutSeconds: Int = 60,
    var opsetVersion: Int = 21,
    var irVersion: Int = 8,
    /** 远程 SSH 主机配置（可选）。设置后 daemon 在远程主机上运行 */
    var remote: RemoteSshConfig? = null,
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
    /** 远程 SSH 主机配置（可选）。设置后 daemon 在远程主机上运行 */
    var remote: RemoteSshConfig? = null,
    var timeoutSeconds: Int = 120,
    var keepArtifacts: Boolean = false,
    var workDir: String = System.getProperty("java.io.tmpdir", "/tmp") + "/aiFuzzer_pytorch",
    var dtype: String = "float32",
    /** 执行设备: "cpu" 或 "cuda" */
    var device: String = "cpu",
    /** torch.compile 模式: "default", "reduce-overhead", "max-autotune" */
    var compileMode: String = "default",
)

/**
 * 远程 SSH 主机配置。
 * 设置后，daemon 将在远程主机上启动，通过 SSH 隧道通信。
 */
data class RemoteSshConfig(
    var host: String = "",
    var port: Int = 22,
    var user: String = "root",
    /**
     * SSH 环境变量名。
     * 配置文件中指定环境变量名称，实际密码从该环境变量读取。
     * 例如设置 password_env: "AIFUZZER_GPU_PASSWORD"，
     * 运行时通过 System.getenv("AIFUZZER_GPU_PASSWORD") 获取密码。
     * 留空则不使用密码（使用 SSH key 认证）。
     */
    var passwordEnv: String = "",
    /** SSH 密码（运行时由环境变量解析填充，不直接从 YAML 读取） */
    var password: String = "",
    /** 远程 Python 路径（如 /root/miniconda3/bin/python） */
    var python: String = "python3",
    /** 远程工作目录，daemon 脚本将上传至此 */
    var workDir: String = "/tmp/aiFuzzer_remote",
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
    /** 去重配置：生成阶段规避已知 bug pattern */
    var dedup: DedupConfig = DedupConfig(),
) {
    data class ReducerConfig(
        var enabled: Boolean = true,   // 默认开启自动缩减
    )

    data class DedupConfig(
        var enabled: Boolean = false,
        /** pattern 数据库路径，支持目录或单个文件 */
        var patternDir: String = "",
        /** 编译器名称，用于筛选 pattern */
        var compiler: String = "tvm",
        /** 编译目标，用于筛选 pattern */
        var target: String = "llvm",
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