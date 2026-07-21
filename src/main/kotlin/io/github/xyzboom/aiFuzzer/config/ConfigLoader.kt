package io.github.xyzboom.aiFuzzer.config

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileReader

/**
 * 配置文件加载器。
 *
 * 支持从 YAML/YML 文件加载配置，并支持 CLI 参数覆盖。
 * 无配置文件时使用代码默认值。
 */
object ConfigLoader {

    /**
     * 从文件路径加载配置。
     * @param path 配置文件路径（支持 .yaml .yml .json）
     * @param overrides CLI 覆盖参数的可选映射
     */
    fun load(path: String, overrides: Map<String, Any> = emptyMap()): FuzzerConfig {
        val file = File(path).absoluteFile
        if (!file.exists()) {
            throw IllegalArgumentException("Config file not found: $path")
        }

        val baseConfig = when {
            path.endsWith(".yaml") || path.endsWith(".yml") -> loadYaml(file)
            path.endsWith(".json") -> loadJson(file)
            else -> throw IllegalArgumentException("Unsupported config format: $path")
        }

        return applyOverrides(baseConfig, overrides)
    }

    /**
     * 加载默认配置（无外部文件时使用）。
     */
    fun default(): FuzzerConfig = FuzzerConfig()

    // ---- 内部实现 ----

    private fun loadYaml(file: File): FuzzerConfig {
        val yaml = Yaml()
        val rawMap = FileReader(file).use { reader ->
            yaml.load<Map<String, Any>>(reader)
        } ?: return FuzzerConfig()

        return parseMapToConfig(rawMap)
    }

    private fun loadJson(file: File): FuzzerConfig {
        // snakeyaml 的 JSON 兼容解析
        val yaml = Yaml()
        val rawMap = FileReader(file).use { reader ->
            yaml.load<Map<String, Any>>(reader)
        } ?: return FuzzerConfig()

        return parseMapToConfig(rawMap)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMapToConfig(rawMap: Map<String, Any>): FuzzerConfig {
        val config = FuzzerConfig()

        // 解析 run 部分
        (rawMap["run"] as? Map<String, Any>)?.let { runMap ->
            config.run.description = runMap["description"] as? String ?: config.run.description
            config.run.seed = runMap["seed"]?.toString() ?: config.run.seed
            config.run.outputDir = runMap["output_dir"] as? String ?: config.run.outputDir
            config.run.logLevel = runMap["log_level"] as? String ?: config.run.logLevel
        }

        // 解析 generator 部分
        (rawMap["generator"] as? Map<String, Any>)?.let { genMap ->
            config.generator.minNodesPerGraph = genMap["min_nodes_per_graph"] as? Int ?: config.generator.minNodesPerGraph
            config.generator.maxNodesPerGraph = genMap["max_nodes_per_graph"] as? Int ?: config.generator.maxNodesPerGraph
            config.generator.minInputs = genMap["min_inputs"] as? Int ?: config.generator.minInputs
            config.generator.maxInputs = genMap["max_inputs"] as? Int ?: config.generator.maxInputs
            config.generator.minInputNdim = genMap["min_input_ndim"] as? Int ?: config.generator.minInputNdim
            config.generator.maxInputNdim = genMap["max_input_ndim"] as? Int ?: config.generator.maxInputNdim
            config.generator.strategy = genMap["strategy"] as? String ?: config.generator.strategy
            // graph_count: 支持单整数（N..N）或 min/max 区间
            val singleCount = genMap["graph_count"] as? Int
            val minCount = genMap["graph_count_min"] as? Int
            val maxCount = genMap["graph_count_max"] as? Int
            config.generator.graphCount = when {
                minCount != null && maxCount != null -> minCount..maxCount
                minCount != null -> minCount..minCount
                maxCount != null -> 1..maxCount
                singleCount != null -> singleCount..singleCount
                else -> config.generator.graphCount
            }
            config.generator.shapeTier = genMap["shape_tier"] as? String ?: config.generator.shapeTier
            config.generator.avoidNaNInf = genMap["avoid_nan_inf"] as? Boolean ?: config.generator.avoidNaNInf
            config.generator.avoidExtremeOps = genMap["avoid_extreme_ops"] as? Boolean ?: config.generator.avoidExtremeOps

            // 解析 ops 子部分
            (genMap["ops"] as? Map<String, Any>)?.let { opsMap ->
                config.generator.ops.includeAll = opsMap["include_all"] as? Boolean ?: config.generator.ops.includeAll
                config.generator.ops.include = (opsMap["include"] as? List<*>)?.filterIsInstance<String>() ?: config.generator.ops.include
                config.generator.ops.exclude = (opsMap["exclude"] as? List<*>)?.filterIsInstance<String>() ?: config.generator.ops.exclude
            }
        }

        // 解析 backends 部分
        (rawMap["backends"] as? Map<String, Any>)?.let { backendMap ->
            config.backends.enabled = (backendMap["enabled"] as? List<*>)?.filterIsInstance<String>() ?: config.backends.enabled

            // 解析全局远程 SSH 配置（所有后端默认继承）
            val globalRemote = (backendMap["remote"] as? Map<String, Any>)?.let { remoteMap ->
                val remote = io.github.xyzboom.aiFuzzer.config.RemoteSshConfig()
                remote.host = remoteMap["host"] as? String ?: remote.host
                remote.port = (remoteMap["port"] as? Number)?.toInt() ?: remote.port
                remote.user = remoteMap["user"] as? String ?: remote.user
                remote.password = remoteMap["password"] as? String ?: remote.password
                remote.passwordEnv = remoteMap["password_env"] as? String ?: remote.passwordEnv
                remote.python = remoteMap["python"] as? String ?: remote.python
                remote.workDir = remoteMap["work_dir"] as? String ?: remote.workDir
                remote
            }
            config.backends.remote = globalRemote

            (backendMap["tvm"] as? Map<String, Any>)?.let { tvmMap ->
                config.backends.tvm.python = tvmMap["python"] as? String ?: config.backends.tvm.python
                config.backends.tvm.timeoutSeconds = tvmMap["timeout_seconds"] as? Int ?: config.backends.tvm.timeoutSeconds
                config.backends.tvm.keepArtifacts = tvmMap["keep_artifacts"] as? Boolean ?: config.backends.tvm.keepArtifacts
                config.backends.tvm.workDir = tvmMap["work_dir"] as? String ?: config.backends.tvm.workDir
                config.backends.tvm.dtype = tvmMap["dtype"] as? String ?: config.backends.tvm.dtype
                config.backends.tvm.shapeRank = tvmMap["shape_rank"] as? Int ?: config.backends.tvm.shapeRank
                config.backends.tvm.target = tvmMap["target"] as? String ?: config.backends.tvm.target
                config.backends.tvm.device = tvmMap["device"] as? String ?: config.backends.tvm.device
                // 解析后端远程 SSH 配置（覆盖全局）
                (tvmMap["remote"] as? Map<String, Any>)?.let { remoteMap ->
                    val remote = io.github.xyzboom.aiFuzzer.config.RemoteSshConfig()
                    remote.host = remoteMap["host"] as? String ?: remote.host
                    remote.port = (remoteMap["port"] as? Number)?.toInt() ?: remote.port
                    remote.user = remoteMap["user"] as? String ?: remote.user
                    remote.password = remoteMap["password"] as? String ?: remote.password
                    remote.passwordEnv = remoteMap["password_env"] as? String ?: remote.passwordEnv
                    remote.python = remoteMap["python"] as? String ?: remote.python
                    remote.workDir = remoteMap["work_dir"] as? String ?: remote.workDir
                    config.backends.tvm.remote = remote
                }
            }

            (backendMap["onnx"] as? Map<String, Any>)?.let { onnxMap ->
                config.backends.onnx.python = onnxMap["python"] as? String ?: config.backends.onnx.python
                config.backends.onnx.timeoutSeconds = onnxMap["timeout_seconds"] as? Int ?: config.backends.onnx.timeoutSeconds
                config.backends.onnx.opsetVersion = onnxMap["opset_version"] as? Int ?: config.backends.onnx.opsetVersion
                config.backends.onnx.irVersion = onnxMap["ir_version"] as? Int ?: config.backends.onnx.irVersion
                // 解析后端远程 SSH 配置（覆盖全局）
                (onnxMap["remote"] as? Map<String, Any>)?.let { remoteMap ->
                    val remote = io.github.xyzboom.aiFuzzer.config.RemoteSshConfig()
                    remote.host = remoteMap["host"] as? String ?: remote.host
                    remote.port = (remoteMap["port"] as? Number)?.toInt() ?: remote.port
                    remote.user = remoteMap["user"] as? String ?: remote.user
                    remote.password = remoteMap["password"] as? String ?: remote.password
                    remote.passwordEnv = remoteMap["password_env"] as? String ?: remote.passwordEnv
                    remote.python = remoteMap["python"] as? String ?: remote.python
                    remote.workDir = remoteMap["work_dir"] as? String ?: remote.workDir
                    config.backends.onnx.remote = remote
                }
            }

            (backendMap["iree"] as? Map<String, Any>)?.let { ireeMap ->
                config.backends.iree.timeoutSeconds = ireeMap["timeout_seconds"] as? Int ?: config.backends.iree.timeoutSeconds
                config.backends.iree.target = ireeMap["target"] as? String ?: config.backends.iree.target
                config.backends.iree.driver = ireeMap["driver"] as? String ?: config.backends.iree.driver
                config.backends.iree.mlirFlags = (ireeMap["mlir_flags"] as? List<*>)?.filterIsInstance<String>() ?: config.backends.iree.mlirFlags
            }

            (backendMap["pytorch"] as? Map<String, Any>)?.let { pytorchMap ->
                config.backends.pytorch.python = pytorchMap["python"] as? String ?: config.backends.pytorch.python
                config.backends.pytorch.timeoutSeconds = pytorchMap["timeout_seconds"] as? Int ?: config.backends.pytorch.timeoutSeconds
                config.backends.pytorch.keepArtifacts = pytorchMap["keep_artifacts"] as? Boolean ?: config.backends.pytorch.keepArtifacts
                config.backends.pytorch.workDir = pytorchMap["work_dir"] as? String ?: config.backends.pytorch.workDir
                config.backends.pytorch.dtype = pytorchMap["dtype"] as? String ?: config.backends.pytorch.dtype
                config.backends.pytorch.device = pytorchMap["device"] as? String ?: config.backends.pytorch.device
                config.backends.pytorch.compileMode = pytorchMap["compile_mode"] as? String ?: config.backends.pytorch.compileMode
                // 解析后端远程 SSH 配置（覆盖全局）
                (pytorchMap["remote"] as? Map<String, Any>)?.let { remoteMap ->
                    val remote = io.github.xyzboom.aiFuzzer.config.RemoteSshConfig()
                    remote.host = remoteMap["host"] as? String ?: remote.host
                    remote.port = (remoteMap["port"] as? Number)?.toInt() ?: remote.port
                    remote.user = remoteMap["user"] as? String ?: remote.user
                    remote.password = remoteMap["password"] as? String ?: remote.password
                    remote.passwordEnv = remoteMap["password_env"] as? String ?: remote.passwordEnv
                    remote.python = remoteMap["python"] as? String ?: remote.python
                    remote.workDir = remoteMap["work_dir"] as? String ?: remote.workDir
                    config.backends.pytorch.remote = remote
                }
            }
        }

        // 全局 remote 自动传播到没有单独设置 remote 的后端
        if (config.backends.remote != null) {
            if (config.backends.tvm.remote == null) config.backends.tvm.remote = config.backends.remote
            if (config.backends.onnx.remote == null) config.backends.onnx.remote = config.backends.remote
            if (config.backends.pytorch.remote == null) config.backends.pytorch.remote = config.backends.remote
        }

        // 解析环境变量密码：passwordEnv → password
        resolvePasswordFromEnv(config.backends.remote)
        resolvePasswordFromEnv(config.backends.tvm.remote)
        resolvePasswordFromEnv(config.backends.onnx.remote)
        resolvePasswordFromEnv(config.backends.pytorch.remote)

        // 解析 bug_collector 部分
        (rawMap["bug_collector"] as? Map<String, Any>)?.let { bcMap ->
            config.bugCollector.enabled = bcMap["enabled"] as? Boolean ?: config.bugCollector.enabled
            config.bugCollector.ignorePatterns = (bcMap["ignore_patterns"] as? List<*>)?.filterIsInstance<String>() ?: config.bugCollector.ignorePatterns
            config.bugCollector.outputDir = bcMap["output_dir"] as? String ?: config.bugCollector.outputDir
        }

        // 解析 pipeline 部分
        (rawMap["pipeline"] as? Map<String, Any>)?.let { plMap ->
            config.pipeline.workers = plMap["workers"] as? Int ?: config.pipeline.workers
            config.pipeline.batchSize = plMap["batch_size"] as? Int ?: config.pipeline.batchSize
            config.pipeline.reportInterval = plMap["report_interval"] as? Int ?: config.pipeline.reportInterval
            config.pipeline.runTimeoutSeconds = plMap["run_timeout_seconds"] as? Int ?: config.pipeline.runTimeoutSeconds
            config.pipeline.failFast = plMap["fail_fast"] as? Boolean ?: config.pipeline.failFast
            // 解析缩减配置
            (plMap["reducer"] as? Map<String, Any>)?.let { reduceMap ->
                config.pipeline.reducer.enabled = reduceMap["enabled"] as? Boolean ?: config.pipeline.reducer.enabled
            }
        }

        return config
    }

    /**
     * 从环境变量解析 SSH 密码。
     * 如果 passwordEnv 设置了环境变量名，则从 System.getenv 读取实际密码填入 password 字段。
     */
    private fun resolvePasswordFromEnv(remote: io.github.xyzboom.aiFuzzer.config.RemoteSshConfig?) {
        if (remote == null) return
        if (remote.passwordEnv.isNotBlank()) {
            val envPassword = System.getenv(remote.passwordEnv)
            if (envPassword != null) {
                remote.password = envPassword
            } else {
                System.err.println("[WARN] 环境变量 ${remote.passwordEnv} 未设置，SSH 将不使用密码认证")
            }
        }
    }

    /**
     * 应用 CLI 覆盖参数。
     */
    private fun applyOverrides(config: FuzzerConfig, overrides: Map<String, Any>): FuzzerConfig {
        if (overrides.isEmpty()) return config

        overrides["seed"]?.let { config.run.seed = it.toString() }
        overrides["runs"]?.let { config.pipeline.batchSize = (it as Number).toInt() }
        overrides["workers"]?.let { config.pipeline.workers = (it as Number).toInt() }
        overrides["ops"]?.let {
            val opsStr = it.toString()
            if (opsStr.isNotBlank()) {
                config.generator.ops.includeAll = false
                config.generator.ops.include = opsStr.split(",").map { s -> s.trim() }
            }
        }
        overrides["report"]?.let { config.run.outputDir = it.toString() }

        return config
    }
}