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
            config.generator.graphCount = genMap["graph_count"] as? Int ?: config.generator.graphCount
            config.generator.strategy = genMap["strategy"] as? String ?: config.generator.strategy

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

            (backendMap["tvm"] as? Map<String, Any>)?.let { tvmMap ->
                config.backends.tvm.python = tvmMap["python"] as? String ?: config.backends.tvm.python
                config.backends.tvm.timeoutSeconds = tvmMap["timeout_seconds"] as? Int ?: config.backends.tvm.timeoutSeconds
                config.backends.tvm.keepArtifacts = tvmMap["keep_artifacts"] as? Boolean ?: config.backends.tvm.keepArtifacts
                config.backends.tvm.workDir = tvmMap["work_dir"] as? String ?: config.backends.tvm.workDir
                config.backends.tvm.dtype = tvmMap["dtype"] as? String ?: config.backends.tvm.dtype
                config.backends.tvm.shapeRank = tvmMap["shape_rank"] as? Int ?: config.backends.tvm.shapeRank
            }

            (backendMap["onnx"] as? Map<String, Any>)?.let { onnxMap ->
                config.backends.onnx.python = onnxMap["python"] as? String ?: config.backends.onnx.python
                config.backends.onnx.timeoutSeconds = onnxMap["timeout_seconds"] as? Int ?: config.backends.onnx.timeoutSeconds
                config.backends.onnx.opsetVersion = onnxMap["opset_version"] as? Int ?: config.backends.onnx.opsetVersion
                config.backends.onnx.irVersion = onnxMap["ir_version"] as? Int ?: config.backends.onnx.irVersion
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
            }
        }

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
        }

        return config
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