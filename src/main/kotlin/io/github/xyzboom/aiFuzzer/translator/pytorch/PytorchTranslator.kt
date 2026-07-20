package io.github.xyzboom.aiFuzzer.translator.pytorch

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.translator.UirTranslator

private val log = KotlinLogging.logger {}

/**
 * UIR 到 PyTorch Python 代码的翻译器。
 *
 * 将 UirProgram 翻译为 `torch.nn.Module` + `torch.compile` 形式的 Python 代码。
 * 生成包含差异测试验证的完整脚本：
 * 1. 定义 nn.Module
 * 2. 生成随机输入（匹配 forward 参数形状）
 * 3. Eager 模式执行（ground truth）
 * 4. torch.compile 执行
 * 5. torch.allclose 比较，输出 VERIFY: PASS/FAIL
 *
 * @param dtype 默认数据类型
 * @param device 执行设备（"cpu" 或 "cuda"）
 * @param compileMode torch.compile 模式
 */
class PytorchTranslator(
    private val dtype: String = "float32",
    private val device: String = "cpu",
    private val compileMode: String = "default",
) : UirTranslator<UirProgram, String> {

    companion object {
        /**
         * UIR 算子到 PyTorch API 的映射。
         */
        val opMapping: Map<UirOpKind, String> = mapOf(
            // 元素级二元
            UirOpKind.ADD to "torch.add",
            UirOpKind.SUBTRACT to "torch.sub",
            UirOpKind.MULTIPLY to "torch.mul",
            UirOpKind.DIVIDE to "torch.div",
            UirOpKind.MAXIMUM to "torch.maximum",
            UirOpKind.MINIMUM to "torch.minimum",
            UirOpKind.POWER to "torch.pow",

            // 矩阵乘法
            UirOpKind.MATMUL to "torch.matmul",

            // 卷积
            UirOpKind.CONV2D to "F.conv2d",
            UirOpKind.MAX_POOL2D to "F.max_pool2d",
            UirOpKind.AVG_POOL2D to "F.avg_pool2d",

            // 归一化
            UirOpKind.LAYER_NORM to "F.layer_norm",
            UirOpKind.BATCH_NORM to "F.batch_norm",

            // 一元激活
            UirOpKind.RELU to "F.relu",
            UirOpKind.LEAKY_RELU to "F.leaky_relu",
            UirOpKind.ELU to "F.elu",
            UirOpKind.SELU to "F.selu",
            UirOpKind.MISH to "F.mish",
            UirOpKind.HARDTANH to "F.hardtanh",
            UirOpKind.SIGMOID to "torch.sigmoid",
            UirOpKind.TANH to "torch.tanh",
            UirOpKind.GELU to "F.gelu",
            UirOpKind.SILU to "F.silu",

            // 一元数学
            UirOpKind.NEG to "torch.neg",
            UirOpKind.ABS to "torch.abs",
            UirOpKind.SIGN to "torch.sign",
            UirOpKind.EXP to "torch.exp",
            UirOpKind.LOG to "torch.log",
            UirOpKind.LOG2 to "torch.log2",
            UirOpKind.SQRT to "torch.sqrt",
            UirOpKind.RSQRT to "torch.rsqrt",
            UirOpKind.RECIPROCAL to "torch.reciprocal",
            UirOpKind.CEIL to "torch.ceil",
            UirOpKind.FLOOR to "torch.floor",
            UirOpKind.ROUND to "torch.round",
            UirOpKind.CLAMP to "torch.clamp",

            // SOFTMAX
            UirOpKind.SOFTMAX to "F.softmax",
            UirOpKind.LOG_SOFTMAX to "F.log_softmax",

            // 归约
            UirOpKind.REDUCE_SUM to "torch.sum",
            UirOpKind.REDUCE_MEAN to "torch.mean",
            UirOpKind.REDUCE_MAX to "torch.max",
            UirOpKind.REDUCE_MIN to "torch.min",

            // 形状变换
            UirOpKind.RESHAPE to "torch.reshape",
            UirOpKind.TRANSPOSE to "torch.transpose",
            UirOpKind.SQUEEZE to "torch.squeeze",
            UirOpKind.UNSQUEEZE to "torch.unsqueeze",

            // 拼接/分割
            UirOpKind.CONCAT to "torch.cat",
            UirOpKind.SPLIT to "torch.split",

            // 索引
            UirOpKind.GATHER to "torch.gather",
            UirOpKind.STRIDED_SLICE to "torch.slice_scatter",

            // 广播/填充
            UirOpKind.BROADCAST_TO to "torch.broadcast_to",
            UirOpKind.TILE to "torch.tile",

            // 类型转换
            UirOpKind.CAST to "torch.Tensor.to",

            // 常数生成
            UirOpKind.ARANGE to "torch.arange",
            UirOpKind.FULL to "torch.full",
            UirOpKind.ONES to "torch.ones",
            UirOpKind.ZEROS to "torch.zeros",

            // 三角矩阵
            UirOpKind.TRIL to "torch.tril",
            UirOpKind.TRIU to "torch.triu",

            // P0: 累积操作
            UirOpKind.CUMSUM to "torch.cumsum",
            UirOpKind.CUMPROD to "torch.cumprod",
            UirOpKind.ARGMAX to "torch.argmax",
            UirOpKind.ARGMIN to "torch.argmin",

            // 插值
            UirOpKind.INTERPOLATE to "F.interpolate",
            UirOpKind.RESIZE2D to "F.interpolate",

            // 适配算子
            UirOpKind.EXPAND_DIMS to "torch.unsqueeze",
        )

        /**
         * PyTorch dtype 名称映射。
         */
        val dtypeMapping = mapOf(
            "float32" to "torch.float32",
            "float64" to "torch.float64",
            "float16" to "torch.float16",
            "int32" to "torch.int32",
            "int64" to "torch.int64",
            "int16" to "torch.int16",
            "int8" to "torch.int8",
            "uint8" to "torch.uint8",
            "bool" to "torch.bool",
        )
    }

    override fun translate(element: UirProgram): String {
        log.debug { "开始翻译 UIR 程序到 PyTorch: ${element.graphs.size} 个图" }
        val builder = StringBuilder()

        // 生成文件头
        builder.appendLine("# Auto-generated by aiFuzzer PyTorch translator")
        builder.appendLine()

        // 导入
        builder.appendLine("import torch")
        builder.appendLine("import torch.nn as nn")
        builder.appendLine("import torch.nn.functional as F")
        builder.appendLine()

        // 翻译每个图
        for ((idx, graph) in element.graphs.withIndex()) {
            translateGraph(builder, graph, idx)
        }

        // IMPORTANT: 不要使用 if __name__ == "__main__" 保护，因为 daemon 会直接 exec 脚本
        builder.appendLine("# Main execution — chained graphs")
        builder.appendLine()

        // 计算所有图的新增（fresh）输入 valueId（排除来自前图输出的值）
        val freshInputIds = mutableListOf<String>()
        val allOutputIds = mutableSetOf<String>()
        for ((_, graph) in element.graphs.withIndex()) {
            for (input in graph.inputs) {
                if (input.valueId !in allOutputIds) {
                    freshInputIds.add(input.valueId)
                }
            }
            graph.outputs.forEach { allOutputIds.add(it.valueId) }
        }

        // === 实例化所有 Module ===
        builder.appendLine("# Instantiate all modules")
        for ((idx, _) in element.graphs.withIndex()) {
            builder.appendLine("model_$idx = TestModule_$idx()")
        }
        builder.appendLine()

        // === 生成所有图的新增随机输入 ===
        builder.appendLine("# Generate random inputs")
        builder.appendLine("torch.manual_seed(42)")
        for (inputId in freshInputIds) {
            // 查找此 valueId 对应的 type
            val refType = findValueType(element, inputId) ?: continue
            val shapeStr = shapeToPython(refType.shape)
            val ptDtype = dtypeMapping[refType.dtype.name] ?: "torch.float32"
            builder.appendLine("$inputId = torch.rand($shapeStr, dtype=$ptDtype, device=\"$device\")")
        }
        builder.appendLine()

        // === Eager 模式执行（图间串联）===
        builder.appendLine("# Chained eager execution (ground truth)")
        builder.appendLine("with torch.no_grad():")
        val eagerOutputVars = mutableListOf<String>()  // track what vars are in scope
        eagerOutputVars.addAll(freshInputIds)
        for ((gIdx, graph) in element.graphs.withIndex()) {
            val inputArgs = graph.inputs.joinToString(", ") { it.valueId }
            val resultVar = "ref_out_$gIdx"
            builder.appendLine("    $resultVar = model_$gIdx($inputArgs)")
            // 解包输出
            if (graph.outputs.size > 1) {
                val outNames = graph.outputs.joinToString(", ") { it.valueId }
                builder.appendLine("    $outNames = $resultVar")
            } else {
                builder.appendLine("    ${graph.outputs[0].valueId} = $resultVar")
            }
            graph.outputs.forEach { eagerOutputVars.add(it.valueId) }
        }
        builder.appendLine()
        val lastGraph = element.graphs.last()
        val finalRefVar = lastGraph.outputs.singleOrNull()?.valueId ?: "ref_out_${element.graphs.size - 1}"

        // === Compiled 模式执行（整条链编译，后端自动融合跨图算子）===
        builder.appendLine("# Chained compiled execution (entire pipeline compiled together)")
        builder.appendLine("try:")
        builder.appendLine("    class ChainedModel(nn.Module):")
        builder.appendLine("        def __init__(self):")
        builder.appendLine("            super().__init__()")
        builder.appendLine("            self._mods = nn.ModuleList([")
        for ((idx, _) in element.graphs.withIndex()) {
            builder.appendLine("                TestModule_$idx(),")
        }
        builder.appendLine("            ])")
        // forward 参数列表 = 所有 fresh input valueId
        val forwardParams = freshInputIds.joinToString(", ")
        builder.appendLine("        def forward(self, $forwardParams):")
        // 追踪哪些参数已被消费（分配给图输入）
        var paramIndex = 0
        // 前面图（prevGraph）的输出集合，用于判断链入输入
        var prevGraphOutputIds = emptySet<String>()
        for ((gIdx, graph) in element.graphs.withIndex()) {
            if (gIdx == 0) {
                // graph_0: 所有输入都是 fresh params（位置对应）
                val moduleInputs = graph.inputs.indices.joinToString(", ") { i ->
                    if (i < freshInputIds.size) freshInputIds[i] else graph.inputs[i].valueId
                }
                builder.appendLine("            x = self._mods[0]($moduleInputs)")
                paramIndex = graph.inputs.size
            } else {
                // graph_i: 链入的输入来自 x（上一图输出），新增的来自 fresh params
                // 注意：只查找前一个图的输出，而不是所有图的输出（防止跨图误匹配）
                val chainInputIds = graph.inputs.filter { it.valueId in prevGraphOutputIds }
                val freshInputIdsForGraph = graph.inputs.filter { it.valueId !in prevGraphOutputIds }
                val callArgs = mutableListOf<String>()
                if (chainInputIds.size > 1) {
                    // 解包 x（可能是元组）
                    val chainNames = chainInputIds.map { "ch_${it.valueId}" }.joinToString(", ")
                    builder.appendLine("            $chainNames = x if isinstance(x, tuple) else (x,)")
                    chainInputIds.forEach { callArgs.add("ch_${it.valueId}") }
                } else {
                    callArgs.add("x")
                }
                // 追加新增输入
                freshInputIdsForGraph.forEach { callArgs.add(it.valueId) }
                builder.appendLine("            x = self._mods[$gIdx](" + callArgs.joinToString(", ") + ")")
            }
            // 更新 prevGraphOutputIds 为当前图的输出，供后续图判断链入输入
            prevGraphOutputIds = graph.outputs.map { it.valueId }.toSet()
        }
        builder.appendLine("            return x")
        builder.appendLine()
        builder.appendLine("    chained = torch.compile(ChainedModel(), mode=\"$compileMode\")")
        builder.appendLine("except Exception as e:")
        builder.appendLine("    print(f'torch.compile failed: {e}')")
        builder.appendLine("    raise")
        builder.appendLine()
        builder.appendLine("with torch.no_grad():")
        val allFreshArgs = freshInputIds.joinToString(", ")
        builder.appendLine("    cmp_output = chained($allFreshArgs)")
        builder.appendLine()

        // === 差异测试 ===
        builder.appendLine("# Differential testing: eager chain vs compiled chain")
        builder.appendLine("if isinstance($finalRefVar, tuple):")
        builder.appendLine("    all_match = all(torch.allclose(r.float(), c.float(), atol=1e-3, rtol=1e-3, equal_nan=True)")
        builder.appendLine("                    for r, c in zip($finalRefVar, cmp_output))")
        builder.appendLine("else:")
        builder.appendLine("    all_match = torch.allclose($finalRefVar.float(), cmp_output.float(), atol=1e-3, rtol=1e-3, equal_nan=True)")
        builder.appendLine()
        builder.appendLine("if all_match:")
        builder.appendLine("    print('VERIFY: PASS')")
        builder.appendLine("else:")
        builder.appendLine("    raise RuntimeError('VERIFY: FAIL')")
        builder.appendLine()
        builder.appendLine("print('Module built successfully')")

        return builder.toString()
    }

    /**
     * 翻译单个计算图为 nn.Module。
     */
    private fun translateGraph(builder: StringBuilder, graph: UirGraph, graphIdx: Int) {
        val className = "TestModule_$graphIdx"
        builder.appendLine("class $className(nn.Module):")

        // 构造函数
        builder.appendLine("    def __init__(self):")
        builder.appendLine("        super().__init__()")

        // forward 函数
        val params = graph.inputs.map { it.valueId }.joinToString(", ")
        builder.appendLine("    def forward(self, $params):")

        // 值映射：valueId -> Python 变量名
        val valueMap = mutableMapOf<String, String>()

        // 图输入映射
        for (input in graph.inputs) {
            valueMap[input.valueId] = input.valueId
        }

        // 翻译每个节点
        for (node in graph.nodes) {
            translateNode(builder, node, valueMap)
        }

        // 返回输出
        val outputVars = graph.outputs.map { output ->
            valueMap[output.valueId] ?: output.valueId
        }
        if (outputVars.size == 1) {
            builder.appendLine("        return ${outputVars[0]}")
        } else {
            builder.appendLine("        return (${outputVars.joinToString(", ")})")
        }

        builder.appendLine()
    }

    /**
     * 翻译单个节点。
     */
    private fun translateNode(builder: StringBuilder, node: UirNode, valueMap: MutableMap<String, String>) {
        val op = node.op
        val pytorchFunc = opMapping[op] ?: "torch.${op.name.lowercase()}"

        // 生成调用代码
        val call = when (op) {
            // ===== 一元激活 =====
            UirOpKind.RELU -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]})"
            UirOpKind.LEAKY_RELU -> {
                val negativeSlope = (node.attributes["negative_slope"] as? UirStringAttr)?.value?.toDoubleOrNull() ?: 0.01
                "F.leaky_relu(${valueMap[node.inputs[0].valueId]}.float(), negative_slope=$negativeSlope)"
            }
            UirOpKind.ELU -> {
                val alpha = (node.attributes["alpha"] as? UirStringAttr)?.value?.toDoubleOrNull() ?: 1.0
                "F.elu(${valueMap[node.inputs[0].valueId]}.float(), alpha=$alpha)"
            }
            UirOpKind.SELU -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float())"
            UirOpKind.MISH -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float())"
            UirOpKind.HARDTANH -> {
                val minVal = (node.attributes["min_val"] as? UirStringAttr)?.value?.toDoubleOrNull() ?: -1.0
                val maxVal = (node.attributes["max_val"] as? UirStringAttr)?.value?.toDoubleOrNull() ?: 1.0
                "F.hardtanh(${valueMap[node.inputs[0].valueId]}.float(), min_val=$minVal, max_val=$maxVal)"
            }
            UirOpKind.SIGMOID -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float())"
            UirOpKind.TANH -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float())"
            UirOpKind.GELU -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float())"
            UirOpKind.SILU -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float())"

            // ===== 一元数学 =====
            UirOpKind.NEG -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]})"
            UirOpKind.ABS -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]})"
            UirOpKind.SIGN -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]})"
            UirOpKind.EXP -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float())"
            UirOpKind.LOG -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float())"
            UirOpKind.LOG2 -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float())"
            UirOpKind.SQRT -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float())"
            UirOpKind.RSQRT -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float())"
            UirOpKind.RECIPROCAL -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float())"
            UirOpKind.CEIL -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]})"
            UirOpKind.FLOOR -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]})"
            UirOpKind.ROUND -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]})"
            UirOpKind.CLAMP -> {
                // torch.clamp(input, min=0.0, max=1.0) — min/max from attributes (stored as string)
                val inputVar = valueMap[node.inputs[0].valueId]!!
                val minVal = (node.attributes["min"] as? UirStringAttr)?.value?.toDoubleOrNull() ?: 0.0
                val maxVal = (node.attributes["max"] as? UirStringAttr)?.value?.toDoubleOrNull() ?: 1.0
                "torch.clamp($inputVar, min=$minVal, max=$maxVal)"
            }

            // ===== 二元运算（单输入模式保护 + 广播兼容） =====
            // Cast both inputs to float for dtype compatibility
            UirOpKind.ADD -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float(), ${valueMap[node.inputs.getOrElse(1) { node.inputs[0] }.valueId]}.float())"
            UirOpKind.SUBTRACT -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float(), ${valueMap[node.inputs.getOrElse(1) { node.inputs[0] }.valueId]}.float())"
            UirOpKind.MULTIPLY -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float(), ${valueMap[node.inputs.getOrElse(1) { node.inputs[0] }.valueId]}.float())"
            UirOpKind.DIVIDE -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float(), ${valueMap[node.inputs.getOrElse(1) { node.inputs[0] }.valueId]}.float())"
            UirOpKind.MAXIMUM -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float(), ${valueMap[node.inputs.getOrElse(1) { node.inputs[0] }.valueId]}.float())"
            UirOpKind.MINIMUM -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float(), ${valueMap[node.inputs.getOrElse(1) { node.inputs[0] }.valueId]}.float())"
            UirOpKind.POWER -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float(), ${valueMap[node.inputs.getOrElse(1) { node.inputs[0] }.valueId]}.float())"

            // ===== 矩阵乘法 =====
            UirOpKind.MATMUL -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]}, ${valueMap[node.inputs[1].valueId]})"

            // ===== 卷积 =====
            UirOpKind.CONV2D -> {
                val stride = (node.attributes["stride"] as? UirIntAttr)?.value ?: 1
                val padding = (node.attributes["padding"] as? UirIntAttr)?.value ?: 0
                val dilation = (node.attributes["dilation"] as? UirIntAttr)?.value ?: 1
                val groups = (node.attributes["groups"] as? UirIntAttr)?.value ?: 1
                val inputVar = valueMap[node.inputs[0].valueId]!!
                val weightVar = valueMap[node.inputs[1].valueId]!!
                // Generate weight at runtime with C_in matching input's C channel
                // This handles cases where ShapeInferer predicted wrong shape for intermediate ops
                // Weight shape: [C_out, C_in, kH, kW] where C_in = input.shape[1]
                "$pytorchFunc($inputVar, torch.zeros(max(${weightVar}.shape[0], 1), $inputVar.shape[1], min(${weightVar}.shape[2], $inputVar.shape[2]), min(${weightVar}.shape[3], $inputVar.shape[3]), device=\"$device\"), " +
                    "stride=$stride, padding=$padding, dilation=$dilation, groups=$groups)"
            }

            // ===== 池化 =====
            UirOpKind.MAX_POOL2D -> {
                var kernelSize = (node.attributes["kernel_size"] as? UirIntAttr)?.value ?: 2
                var stride = (node.attributes["stride"] as? UirIntAttr)?.value ?: kernelSize
                val padding = (node.attributes["padding"] as? UirIntAttr)?.value ?: 0
                val inputVar = valueMap[node.inputs[0].valueId]!!
                // Static guard: clamp kernel_size to IR-inferred spatial dims
                val inputShape = node.inputs[0].type.shape
                if (inputShape.dims.size >= 4) {
                    val h = inputShape.dims[2].value ?: kernelSize
                    val w = inputShape.dims[3].value ?: kernelSize
                    val minSpatial = minOf(h, w)
                    if (kernelSize > minSpatial) {
                        kernelSize = maxOf(1, minSpatial)
                        stride = minOf(stride, kernelSize)
                    }
                }
                // Runtime guard: clamp kernel_size to actual spatial dims via min().
                // This catches cases where the IR-inferred shape is incorrect
                // (e.g., due to GATHER shape inference limitations), ensuring
                // kernel_size never exceeds the real spatial dimensions at runtime.
                val runtimeKs = "max(1, min($kernelSize, $inputVar.shape[2], $inputVar.shape[3]))"
                "$pytorchFunc($inputVar, " +
                    "kernel_size=$runtimeKs, stride=min($stride, $runtimeKs), padding=$padding)"
            }
            UirOpKind.AVG_POOL2D -> {
                var kernelSize = (node.attributes["kernel_size"] as? UirIntAttr)?.value ?: 2
                var stride = (node.attributes["stride"] as? UirIntAttr)?.value ?: kernelSize
                val padding = (node.attributes["padding"] as? UirIntAttr)?.value ?: 0
                val inputVar = valueMap[node.inputs[0].valueId]!!
                // Static guard: clamp kernel_size to IR-inferred spatial dims
                val inputShape = node.inputs[0].type.shape
                if (inputShape.dims.size >= 4) {
                    val h = inputShape.dims[2].value ?: kernelSize
                    val w = inputShape.dims[3].value ?: kernelSize
                    val minSpatial = minOf(h, w)
                    if (kernelSize > minSpatial) {
                        kernelSize = maxOf(1, minSpatial)
                        stride = minOf(stride, kernelSize)
                    }
                }
                // Runtime guard: clamp kernel_size to actual spatial dims via min().
                // This catches cases where the IR-inferred shape is incorrect
                // (e.g., due to GATHER shape inference limitations), ensuring
                // kernel_size never exceeds the real spatial dimensions at runtime.
                val runtimeKs = "max(1, min($kernelSize, $inputVar.shape[2], $inputVar.shape[3]))"
                "$pytorchFunc($inputVar, " +
                    "kernel_size=$runtimeKs, stride=min($stride, $runtimeKs), padding=$padding)"
            }

            // ===== 归一化 =====
            UirOpKind.LAYER_NORM -> {
                val inputVar = valueMap[node.inputs[0].valueId]!!
                // Use runtime shape to get last dim, avoiding ShapeInferer/Translator mismatch
                // Also ensure float input (layer_norm not implemented for Int)
                "$pytorchFunc(${inputVar}.float(), (${inputVar}.shape[-1],))"
            }
            UirOpKind.BATCH_NORM -> {
                // BatchNorm needs running_mean, running_var, weight, bias
                val inputVar = valueMap[node.inputs[0].valueId]!!
                // Use runtime shape (not IR shape) — ShapeAdapter may have changed dimensions
                // Also ensure float input (batch_norm not implemented for Int/Long)
                "F.batch_norm(${inputVar}.float(), running_mean=torch.zeros(${inputVar}.shape[1], device=\"$device\"), running_var=torch.ones(${inputVar}.shape[1], device=\"$device\"), training=False)"
            }

            // ===== SOFTMAX =====
            UirOpKind.SOFTMAX -> {
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: -1
                "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float(), dim=$axis)"
            }
            UirOpKind.LOG_SOFTMAX -> {
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: -1
                "$pytorchFunc(${valueMap[node.inputs[0].valueId]}.float(), dim=$axis)"
            }

            // ===== 归约 =====
            UirOpKind.REDUCE_SUM -> {
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: -1
                val keepdims = (node.attributes["keepdims"] as? UirIntAttr)?.value?.let { it != 0 } ?: false
                val keepdimsStr = if (keepdims) "True" else "False"
                val dtypeAttr = node.attributes["dtype"] as? UirStringAttr
                val dtypeStr = if (dtypeAttr != null) ", dtype=torch.${dtypeAttr.value}" else ""
                val result = "$pytorchFunc(${valueMap[node.inputs[0].valueId]}, dim=$axis, keepdim=$keepdimsStr$dtypeStr)"
                // Cast back to float32 if non-float32 dtype was used — prevents downstream dtype mismatch
                if (dtypeAttr != null && dtypeAttr.value != "float32") "$result.float()" else result
            }
            UirOpKind.REDUCE_MEAN -> {
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: -1
                val keepdims = (node.attributes["keepdims"] as? UirIntAttr)?.value?.let { it != 0 } ?: false
                val keepdimsStr = if (keepdims) "True" else "False"
                val dtypeAttr = node.attributes["dtype"] as? UirStringAttr
                val dtypeStr = if (dtypeAttr != null) ", dtype=torch.${dtypeAttr.value}" else ""
                val inputVar = valueMap[node.inputs[0].valueId]!!
                // mean requires float input; cast back to float32 if non-float32 dtype was used
                if (dtypeAttr != null) {
                    val result = "$pytorchFunc($inputVar, dim=$axis, keepdim=$keepdimsStr$dtypeStr)"
                    if (dtypeAttr.value != "float32") "$result.float()" else result
                } else {
                    "$pytorchFunc($inputVar.float(), dim=$axis, keepdim=$keepdimsStr)"
                }
            }
            UirOpKind.REDUCE_MAX -> {
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: -1
                val keepdims = (node.attributes["keepdims"] as? UirIntAttr)?.value?.let { it != 0 } ?: false
                // torch.max 返回 (values, indices)，需要取 values
                val inputVar = valueMap[node.inputs[0].valueId]!!
                if (keepdims) {
                    "torch.max($inputVar, dim=$axis, keepdim=True).values"
                } else {
                    "torch.max($inputVar, dim=$axis, keepdim=False).values"
                }
            }
            UirOpKind.REDUCE_MIN -> {
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: -1
                val keepdims = (node.attributes["keepdims"] as? UirIntAttr)?.value?.let { it != 0 } ?: false
                val inputVar = valueMap[node.inputs[0].valueId]!!
                if (keepdims) {
                    "torch.min($inputVar, dim=$axis, keepdim=True).values"
                } else {
                    "torch.min($inputVar, dim=$axis, keepdim=False).values"
                }
            }

            // ===== P0: 累积操作（支持显式 dtype） =====
            UirOpKind.CUMSUM, UirOpKind.CUMPROD -> {
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: -1
                val dtypeAttr = node.attributes["dtype"] as? UirStringAttr
                val dtypeStr = if (dtypeAttr != null) ", dtype=torch.${dtypeAttr.value}" else ""
                val inputVar = valueMap[node.inputs[0].valueId]!!
                // CUMPROD requires float input (not implemented for int/Long)
                val inputExpr = if (op == UirOpKind.CUMPROD) "$inputVar.float()" else inputVar
                val result = "$pytorchFunc($inputExpr, dim=$axis$dtypeStr)"
                // Always cast result to float32 — int64 output from cumsum(dtype=int64) 
                // crashes downstream ops (conv2d, batch_norm, etc.) that require Float input.
                // Also cast float16/bfloat16 to float32 for consistency.
                if (dtypeAttr != null && dtypeAttr.value != "float32") "$result.float()" else result
            }

            UirOpKind.ARGMAX, UirOpKind.ARGMIN -> {
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: -1
                "$pytorchFunc(${valueMap[node.inputs[0].valueId]}, dim=$axis).float()"
            }

            // ===== P2: 插值/Resize =====
            UirOpKind.INTERPOLATE, UirOpKind.RESIZE2D -> {
                val inputVar = valueMap[node.inputs[0].valueId]!!
                "F.interpolate($inputVar, scale_factor=2.0, mode='nearest')"
            }

            // ===== 形状变换 =====
            UirOpKind.RESHAPE -> {
                // Use -1 for the first dim to let PyTorch infer it —
                // IR shape may have wrong element count from ShapeAdapter's default for unknown dims
                val outputShape = node.outputs[0].type.shape
                val ndim = outputShape.dims.size
                if (ndim <= 1) {
                    // 1D reshape: flatten
                    "$pytorchFunc(${valueMap[node.inputs[0].valueId]}, (-1,))"
                } else {
                    // Multi-dim reshape: use -1 for first dim, explicit for rest
                    val restDims = outputShape.dims.drop(1).map { dim ->
                        dim.value?.toString() ?: "-1"
                    }.joinToString(", ")
                    "$pytorchFunc(${valueMap[node.inputs[0].valueId]}, (-1, $restDims))"
                }
            }
            UirOpKind.TRANSPOSE -> {
                // 默认交换最后两个维度
                val ndim = node.inputs[0].type.shape.dims.size
                if (ndim >= 2) {
                    "$pytorchFunc(${valueMap[node.inputs[0].valueId]}, ${ndim - 2}, ${ndim - 1})"
                } else {
                    "$pytorchFunc(${valueMap[node.inputs[0].valueId]}, 0, 0)"
                }
            }
            UirOpKind.SQUEEZE -> {
                "$pytorchFunc(${valueMap[node.inputs[0].valueId]})"
            }
            UirOpKind.UNSQUEEZE -> {
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: 0
                val inputVar = valueMap[node.inputs[0].valueId]!!
                // Runtime guard: skip unsqueeze if input is already ≥4D
                // (downstream ops like conv2d, batch_norm, interpolate require ≤4D)
                "($inputVar if $inputVar.ndim >= 4 else torch.unsqueeze($inputVar, $axis))"
            }

            // ===== 拼接/分割 =====
            UirOpKind.CONCAT -> {
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: 0
                val inputs = node.inputs.map { valueMap[it.valueId]!! }.joinToString(", ")
                "$pytorchFunc([$inputs], dim=$axis)"
            }
            UirOpKind.SPLIT -> {
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: 0
                // 默认分成 2 份
                "$pytorchFunc(${valueMap[node.inputs[0].valueId]}, 2, dim=$axis)"
            }

            // ===== 索引 =====
            UirOpKind.GATHER -> {
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: 0
                val inputVar = valueMap[node.inputs[0].valueId]!!
                // 使用 torch.select 移除 axis 维度，与 TVM relax.op.take(scalar_index) 语义一致
                // 也与 ShapeInferer 对齐（标量索引移除 axis 维）
                "torch.select($inputVar, $axis, 0)"
            }
            UirOpKind.STRIDED_SLICE -> {
                // 简化实现：取前半部分，至少保留1个元素
                val inputVar = valueMap[node.inputs[0].valueId]!!
                "$inputVar[:max(1, ${inputVar}.shape[0]//2)]"
            }

            // ===== 广播/填充 =====
            UirOpKind.BROADCAST_TO -> {
                val outputShape = node.outputs[0].type.shape
                val shapeStr = shapeToPython(outputShape)
                "$pytorchFunc(${valueMap[node.inputs[0].valueId]}, ($shapeStr))"
            }
            UirOpKind.TILE -> {
                val inputVar = valueMap[node.inputs[0].valueId]!!
                val ndim = node.inputs[0].type.shape.dims.size
                "$pytorchFunc($inputVar, [1] * $ndim)"
            }

            // ===== 类型转换 =====
            UirOpKind.CAST -> {
                val outputDtype = node.outputs[0].type.dtype.name
                val targetDtype = dtypeMapping[outputDtype] ?: "torch.float32"
                "${valueMap[node.inputs[0].valueId]}.to($targetDtype)"
            }

            // ===== 常数生成 =====
            UirOpKind.ARANGE -> {
                val outputShape = node.outputs[0].type.shape
                val outputDtype = node.outputs[0].type.dtype.name
                val totalSize = outputShape.dims.fold(1) { acc, dim ->
                    acc * (if (dim.dimKind == UirDimKind.CONSTANT) (dim.value ?: 1) else 1)
                }
                "$pytorchFunc(0, $totalSize, dtype=${dtypeMapping[outputDtype] ?: "torch.float32"}, device=\"$device\")"
            }
            UirOpKind.FULL -> {
                val outputShape = node.outputs[0].type.shape
                val outputDtype = node.outputs[0].type.dtype.name
                val shapeStr = shapeToPython(outputShape)
                "$pytorchFunc(($shapeStr), 0.0, dtype=${dtypeMapping[outputDtype] ?: "torch.float32"}, device=\"$device\")"
            }
            UirOpKind.ONES -> {
                val outputShape = node.outputs[0].type.shape
                val outputDtype = node.outputs[0].type.dtype.name
                val shapeStr = shapeToPython(outputShape)
                "$pytorchFunc(($shapeStr), dtype=${dtypeMapping[outputDtype] ?: "torch.float32"}, device=\"$device\")"
            }
            UirOpKind.ZEROS -> {
                val outputShape = node.outputs[0].type.shape
                val outputDtype = node.outputs[0].type.dtype.name
                val shapeStr = shapeToPython(outputShape)
                "$pytorchFunc(($shapeStr), dtype=${dtypeMapping[outputDtype] ?: "torch.float32"}, device=\"$device\")"
            }

            // ===== 三角矩阵 =====
            UirOpKind.TRIL -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]})"
            UirOpKind.TRIU -> "$pytorchFunc(${valueMap[node.inputs[0].valueId]})"

            // ===== 适配算子 =====
            UirOpKind.EXPAND_DIMS -> {
                val axis = (node.attributes["axis"] as? UirIntAttr)?.value ?: 0
                val inputVar = valueMap[node.inputs[0].valueId]!!
                // Runtime guard: skip expand_dims if input is already ≥4D
                "($inputVar if $inputVar.ndim >= 4 else torch.unsqueeze($inputVar, $axis))"
            }

            // ===== 默认 =====
            else -> {
                log.warn { "未实现的算子: ${op.name}" }
                "torch.zeros(1)  # Unsupported op: ${op.name}"
            }
        }

        // 处理输出
        if (node.outputs.size == 1) {
            val outputVar = node.outputs[0].valueId
            builder.appendLine("        $outputVar = $call")
            valueMap[outputVar] = outputVar
        } else {
            // 多输出（如 SPLIT）
            val outputVars = node.outputs.map { it.valueId }
            if (outputVars.isNotEmpty()) {
                builder.appendLine("        ${outputVars.joinToString(", ")} = $call")
                outputVars.forEach { valueMap[it] = it }
            }
        }
    }

    /**
     * 在 UIR 程序中查找 valueId 对应的 tensor type。
     */
    private fun findValueType(program: UirProgram, valueId: String): UirTensorType? {
        for (graph in program.graphs) {
            for (input in graph.inputs) {
                if (input.valueId == valueId) return input.type
            }
            for (output in graph.outputs) {
                if (output.valueId == valueId) return output.type
            }
            for (node in graph.nodes) {
                for (input in node.inputs) {
                    if (input.valueId == valueId) return input.type
                }
                for (output in node.outputs) {
                    if (output.valueId == valueId) return output.type
                }
            }
        }
        return null
    }

    /**
     * 将 UirShape 转换为 Python 列表字符串。
     */
    private fun shapeToPython(shape: UirShape): String {
        return shape.dims.map { dim ->
            when (dim.dimKind) {
                UirDimKind.CONSTANT -> dim.value?.toString() ?: "1"
                UirDimKind.SYMBOLIC -> "1"  // 符号维度用 1 代替
                UirDimKind.UNKNOWN -> "1"
            }
        }.joinToString(", ")
    }
}