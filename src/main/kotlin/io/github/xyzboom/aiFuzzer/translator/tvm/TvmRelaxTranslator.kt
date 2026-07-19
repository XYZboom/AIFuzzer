package io.github.xyzboom.aiFuzzer.translator.tvm

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.translator.UirTranslator

private val log = KotlinLogging.logger {}

/**
 * UIR 到 TVM Relax Python 代码的翻译器。
 *
 * 设计原则：
 * 1. 直接读取 ValueRef.type.shape，不再维护独立的 ndimMap
 * 2. 形状推导由 ShapeInferer 统一完成，翻译器只负责代码生成
 * 3. 不需要 clamp axis，因为生成器已保证语义合法
 *
 * @param shapeRank 默认形状秩（用于生成默认 shape，当形状未知时使用）
 * @param dtype 默认数据类型
 * @param opNameMapping 自定义算子名映射（可选）
 * @param dtypeMapping 自定义数据类型映射（可选）
 */
class TvmRelaxTranslator(
    private val shapeRank: Int = 16,
    private val dtype: String = "float32",
    private val opNameMapping: Map<UirOpKind, String> = defaultOpNameMapping,
    private val dtypeMapping: Map<String, String> = emptyMap(),
    /** TVM build target，如 "llvm" 或 "cuda" */
    private val target: String = "llvm",
    /** TVM 设备，如 "cpu" 或 "cuda"，对应 tvm.cpu() / tvm.cuda() */
    private val device: String = "cpu",
) : UirTranslator<UirProgram, String> {

    companion object {
        /**
         * 默认算子名映射：UIR 算子 -> TVM Relax 算子路径。
         */
        val defaultOpNameMapping: Map<UirOpKind, String> = mapOf(
            // 一元激活
            UirOpKind.RELU to "relax.op.nn.relu",
            UirOpKind.LEAKY_RELU to "relax.op.nn.leaky_relu",
            UirOpKind.ELU to "relax.op.nn.elu",
            UirOpKind.SELU to "relax.op.nn.selu",
            UirOpKind.MISH to "relax.op.nn.mish",
            UirOpKind.HARDTANH to "relax.op.nn.hardtanh",
            UirOpKind.SIGMOID to "relax.op.sigmoid",
            UirOpKind.TANH to "relax.op.tanh",
            UirOpKind.GELU to "relax.op.nn.gelu",
            UirOpKind.SILU to "relax.op.nn.silu",

            // 一元数学
            UirOpKind.NEG to "relax.op.negative",
            UirOpKind.ABS to "relax.op.abs",
            UirOpKind.SIGN to "relax.op.sign",
            UirOpKind.EXP to "relax.op.exp",
            UirOpKind.LOG to "relax.op.log",
            UirOpKind.LOG2 to "relax.op.log2",
            UirOpKind.SQRT to "relax.op.sqrt",
            UirOpKind.RSQRT to "relax.op.rsqrt",
            UirOpKind.RECIPROCAL to "relax.op.reciprocal",
            UirOpKind.CEIL to "relax.op.ceil",
            UirOpKind.FLOOR to "relax.op.floor",
            UirOpKind.ROUND to "relax.op.round",
            UirOpKind.CLAMP to "relax.op.clip",

            // 二元运算
            UirOpKind.ADD to "relax.op.add",
            UirOpKind.SUBTRACT to "relax.op.subtract",
            UirOpKind.MULTIPLY to "relax.op.multiply",
            UirOpKind.DIVIDE to "relax.op.divide",
            UirOpKind.MAXIMUM to "relax.op.maximum",
            UirOpKind.MINIMUM to "relax.op.minimum",
            UirOpKind.POWER to "relax.op.power",

            // 矩阵乘法
            UirOpKind.MATMUL to "relax.op.matmul",

            // 卷积
            UirOpKind.CONV2D to "relax.op.nn.conv2d",
            UirOpKind.MAX_POOL2D to "relax.op.nn.max_pool2d",
            UirOpKind.AVG_POOL2D to "relax.op.nn.avg_pool2d",

            // 归一化
            UirOpKind.LAYER_NORM to "relax.op.nn.layer_norm",
            UirOpKind.BATCH_NORM to "relax.op.nn.batch_norm",

            // SOFTMAX
            UirOpKind.SOFTMAX to "relax.op.nn.softmax",
            UirOpKind.LOG_SOFTMAX to "relax.op.nn.log_softmax",

            // 归约
            UirOpKind.REDUCE_SUM to "relax.op.sum",
            UirOpKind.REDUCE_MEAN to "relax.op.mean",
            UirOpKind.REDUCE_MAX to "relax.op.max",
            UirOpKind.REDUCE_MIN to "relax.op.min",

            // 形状变换
            UirOpKind.RESHAPE to "relax.op.reshape",
            UirOpKind.TRANSPOSE to "relax.op.permute_dims",
            UirOpKind.SQUEEZE to "relax.op.squeeze",
            UirOpKind.UNSQUEEZE to "relax.op.expand_dims",

            // 拼接/分割
            UirOpKind.CONCAT to "relax.op.concat",
            UirOpKind.SPLIT to "relax.op.split",

            // 索引
            UirOpKind.GATHER to "relax.op.take",
            UirOpKind.STRIDED_SLICE to "relax.op.strided_slice",

            // 三角矩阵
            UirOpKind.TRIL to "relax.op.tril",
            UirOpKind.TRIU to "relax.op.triu",

            // 广播/填充
            UirOpKind.BROADCAST_TO to "relax.op.broadcast_to",
            UirOpKind.TILE to "relax.op.tile",

            // 类型转换
            UirOpKind.CAST to "relax.op.astype",

            // 常数生成
            UirOpKind.ARANGE to "relax.op.arange",
            UirOpKind.FULL to "relax.op.full",
            UirOpKind.ONES to "relax.op.ones",
            UirOpKind.ZEROS to "relax.op.zeros",

            // 适配算子
            UirOpKind.EXPAND_DIMS to "relax.op.expand_dims"
        )
    }

    /**
     * 将 UIR 程序翻译为 TVM Relax Python 代码。
     */
    override fun translate(element: UirProgram): String {
        log.debug { "开始翻译 UIR 程序: ${element.graphs.size} 个图" }
        val startTime = System.currentTimeMillis()
        val builder = StringBuilder()

        // 生成 Python 模板
        builder.appendLine("# Auto-generated by aiFuzzer")
        builder.appendLine()
        builder.appendLine("import tvm")
        builder.appendLine("from tvm import relax")
        builder.appendLine("from tvm.ir.module import IRModule")
        builder.appendLine("import numpy as np")
        builder.appendLine()
        builder.appendLine("def build_mod():")
        builder.appendLine("    bb = relax.BlockBuilder()")
        builder.appendLine()

        // 翻译每个图
        for ((idx, graph) in element.graphs.withIndex()) {
            log.trace { "翻译图 $idx: ${graph.name}" }
            translateGraph(builder, graph, idx)
        }

        builder.appendLine("    mod = bb.get()")
        builder.appendLine("    return mod")
        builder.appendLine()
        builder.appendLine()
        // IMPORTANT: we should not use __name__ == "__main__" guard here, because the daemon will execute this script directly.
        builder.appendLine("mod = build_mod()")
        builder.appendLine("print('Module built successfully')")
        builder.appendLine("# print(mod)")
        builder.appendLine()
        // ===== 执行编译后的TVM模块 =====
        builder.appendLine()
        builder.appendLine("# === Execute compiled module ===")
        builder.appendLine("np.random.seed(42)")
        builder.appendLine("ex = relax.build(mod, target=\"$target\")")
        builder.appendLine("vm = relax.VirtualMachine(ex, tvm.$device())")
        
        for ((gIdx, graph) in element.graphs.withIndex()) {
            val funcName = graph.name.ifBlank { "func_$gIdx" }
            val resultVar = "tvm_result_$gIdx"
            
            // 生成 numpy 输入
            if (graph.inputs.isNotEmpty()) {
                builder.appendLine()
                builder.appendLine("# Generate inputs for ${graph.name}")
                for (input in graph.inputs) {
                    val shape = input.type.shape
                    val shapeStr = shape.dims.joinToString(", ") { dim ->
                        when (dim.dimKind) {
                            UirDimKind.CONSTANT -> dim.value?.toString() ?: shapeRank.toString()
                            else -> shapeRank.toString()
                        }
                    }
                    builder.appendLine("np_${input.valueId} = np.random.uniform(0.0, 1.0, size=($shapeStr)).astype(np.float32)")
                }
            }
            
            // 执行
            val inputArgs = graph.inputs.joinToString(", ") { "np_${it.valueId}" }
            builder.appendLine()
            builder.appendLine("$resultVar = vm[\"$funcName\"]($inputArgs)")
            
            // 打印结果
            builder.appendLine("# Print output")
            builder.appendLine("if hasattr($resultVar, 'numpy'):")
            builder.appendLine("    _arr = $resultVar.numpy()")
            builder.appendLine("    print(f\"[TVM-OUT] graph_${gIdx}: shape={list(_arr.shape)}\")")
            builder.appendLine("else:")
            builder.appendLine("    for _tvmi in range(len($resultVar)):")
            builder.appendLine("        _arr = $resultVar[_tvmi].numpy()")
            builder.appendLine("        print(f\"[TVM-OUT] graph_${gIdx}[{_tvmi}]: shape={list(_arr.shape)}\")")
        }
        
        builder.appendLine("print(\"Execution: OK\")")

        val elapsed = System.currentTimeMillis() - startTime
        log.debug { "翻译完成，耗时 ${elapsed}ms，输出 ${builder.length} 字符" }
        return builder.toString()
    }

    /**
     * 翻译单个计算图。
     */
    private fun translateGraph(builder: StringBuilder, graph: UirGraph, graphIdx: Int) {
        val funcName = graph.name.ifBlank { "func_$graphIdx" }

        // 生成输入变量的 relax.Var
        for (input in graph.inputs) {
            val varName = "${input.valueId}_var"
            val shapeExpr = generateShapeExpr(input.type.shape)
            builder.appendLine("    $varName = relax.Var(\"${input.valueId}\", relax.TensorStructInfo(shape=$shapeExpr, dtype=\"$dtype\"))")
        }

        // 开始函数定义
        val params = graph.inputs.map { "${it.valueId}_var" }.joinToString(", ")
        builder.appendLine("    with bb.function(\"$funcName\", [$params]):")

        // 值映射：valueId -> Python 变量名
        val valueMap = mutableMapOf<String, String>()

        // 图输入映射
        for (input in graph.inputs) {
            valueMap[input.valueId] = "${input.valueId}_var"
        }

        // 翻译每个节点
        for (node in graph.nodes) {
            translateNode(builder, node, valueMap)
        }

        // 生成函数输出
        val outputVars = graph.outputs.map { output ->
            valueMap[output.valueId] ?: "${output.valueId}_var"
        }

        if (outputVars.size == 1) {
            builder.appendLine("        bb.emit_func_output(${outputVars[0]})")
        } else {
            builder.appendLine("        bb.emit_func_output((${outputVars.joinToString(", ")}))")
        }

        builder.appendLine()
    }

    /**
     * 翻译单个节点。
     */
    private fun translateNode(builder: StringBuilder, node: UirNode, valueMap: MutableMap<String, String>) {
        // 获取输入变量
        val inputVars = node.inputs.map { input ->
            valueMap[input.valueId] ?: "${input.valueId}_var"
        }

        // 生成 TVM Relax 调用
        val relaxCall = generateRelaxCall(node.op, inputVars, node.attributes, node.inputs.map { it.type.shape }, node.outputs.map { it.type.shape })

        builder.appendLine("        # input: ${node.inputs.map { it.type.shape }.joinToString { it.rawShapeString() }}")
        builder.appendLine("        # output: ${node.outputs.map { it.type.shape }.joinToString { it.rawShapeString() }}")

        // 特殊处理：BATCH_NORM 返回元组，需要 TupleGetItem 提取第一个元素
        if (relaxCall.startsWith("BATCH_NORM:")) {
            val parts = relaxCall.split(":")
            val inputVar = parts[1]
            val numChannels = parts[2]
            val outputVar = node.outputs[0].valueId
            val tupleVar = "${outputVar}_bn_tuple"
            builder.appendLine("        $tupleVar = bb.emit(relax.op.nn.batch_norm($inputVar, gamma=relax.op.ones(relax.ShapeExpr([$numChannels]), dtype=\"float32\"), beta=relax.op.zeros(relax.ShapeExpr([$numChannels]), dtype=\"float32\"), moving_mean=relax.op.zeros(relax.ShapeExpr([$numChannels]), dtype=\"float32\"), moving_var=relax.op.ones(relax.ShapeExpr([$numChannels]), dtype=\"float32\"), axis=1))")
            builder.appendLine("        $outputVar = bb.emit(relax.TupleGetItem($tupleVar, 0))")
            valueMap[outputVar] = outputVar
            val expectedShape = node.outputs[0].type.shape
            addShapeAssertion(builder, outputVar, expectedShape)
            return
        }

        // 处理输出
        if (node.outputs.size == 1) {
            val outputVar = node.outputs[0].valueId
            builder.appendLine("        $outputVar = bb.emit($relaxCall)")
            valueMap[outputVar] = outputVar
            
            // 添加形状断言
            val expectedShape = node.outputs[0].type.shape
            addShapeAssertion(builder, outputVar, expectedShape)
        } else {
            // 多输出
            val outputVars = node.outputs.map { it.valueId }
            builder.appendLine("        ${outputVars.joinToString(", ")} = bb.emit($relaxCall)")
            outputVars.forEach { valueMap[it] = it }
            
            // 为每个输出添加形状断言
            for (i in node.outputs.indices) {
                val outputVar = outputVars[i]
                val expectedShape = node.outputs[i].type.shape
                addShapeAssertion(builder, outputVar, expectedShape)
            }
        }
    }

    /**
     * 生成 TVM Relax 算子调用。
     */
    private fun generateRelaxCall(
        op: UirOpKind,
        inputVars: List<String>,
        attributes: Map<String, Attribute>,
        inputShapes: List<UirShape>,
        outputShapes: List<UirShape>
    ): String {
        return when (op) {
            // ===== 一元激活 =====
            UirOpKind.RELU -> "relax.op.nn.relu(${inputVars[0]})"
            UirOpKind.LEAKY_RELU -> {
                val negativeSlope = (attributes["negative_slope"] as? UirStringAttr)?.value?.toDoubleOrNull() ?: 0.01
                // TVM uses 'leakyrelu' (no underscore) and 'alpha' parameter
                "relax.op.nn.leakyrelu(${inputVars[0]}, alpha=$negativeSlope)"
            }
            UirOpKind.ELU -> {
                val alpha = (attributes["alpha"] as? UirStringAttr)?.value?.toDoubleOrNull() ?: 1.0
                // TVM Relax has no relax.op.nn.elu; implement using where:
                // ELU(x) = x if x >= 0, else alpha * (exp(x) - 1)
                "relax.op.where(relax.op.greater_equal(${inputVars[0]}, relax.const(0, dtype=\"float32\")), ${inputVars[0]}, relax.op.multiply(relax.const($alpha, dtype=\"float32\"), relax.op.subtract(relax.op.exp(${inputVars[0]}), relax.const(1.0, dtype=\"float32\"))))"
            }
            UirOpKind.SELU -> "relax.op.nn.selu(${inputVars[0]})"
            UirOpKind.MISH -> {
                // TVM Relax has no relax.op.nn.mish; implement as: x * tanh(softplus(x))
                // softplus(x) = log(1 + exp(x))
                "relax.op.multiply(${inputVars[0]}, relax.op.tanh(relax.op.log(relax.op.add(relax.const(1.0, dtype=\"float32\"), relax.op.exp(${inputVars[0]})))))"
            }
            UirOpKind.HARDTANH -> {
                val minVal = (attributes["min_val"] as? UirStringAttr)?.value?.toDoubleOrNull() ?: -1.0
                val maxVal = (attributes["max_val"] as? UirStringAttr)?.value?.toDoubleOrNull() ?: 1.0
                // TVM Relax has no relax.op.nn.hardtanh; use relax.op.clip instead
                "relax.op.clip(${inputVars[0]}, $minVal, $maxVal)"
            }
            UirOpKind.SIGMOID -> "relax.op.sigmoid(${inputVars[0]})"
            UirOpKind.TANH -> "relax.op.tanh(${inputVars[0]})"
            UirOpKind.GELU -> "relax.op.nn.gelu(${inputVars[0]})"
            UirOpKind.SILU -> "relax.op.nn.silu(${inputVars[0]})"

            // ===== 一元数学 =====
            UirOpKind.NEG -> "relax.op.negative(${inputVars[0]})"
            UirOpKind.ABS -> "relax.op.abs(${inputVars[0]})"
            UirOpKind.SIGN -> "relax.op.sign(${inputVars[0]})"
            UirOpKind.EXP -> "relax.op.exp(relax.op.astype(${inputVars[0]}, dtype=\"float32\"))"
            UirOpKind.LOG -> "relax.op.log(relax.op.astype(${inputVars[0]}, dtype=\"float32\"))"
            UirOpKind.LOG2 -> {
                // TVM Relax has no relax.op.log2; implement as log(x) / log(2) = log(x) / 0.6931471805599453
                "relax.op.divide(relax.op.log(relax.op.astype(${inputVars[0]}, dtype=\"float32\")), relax.const(0.6931471805599453, dtype=\"float32\"))"
            }
            UirOpKind.SQRT -> "relax.op.sqrt(relax.op.astype(${inputVars[0]}, dtype=\"float32\"))"
            UirOpKind.RSQRT -> "relax.op.rsqrt(relax.op.astype(${inputVars[0]}, dtype=\"float32\"))"
            UirOpKind.RECIPROCAL -> {
                // TVM Relax has no relax.op.reciprocal; implement as 1/x = divide(1, x)
                "relax.op.divide(relax.const(1.0, dtype=\"float32\"), relax.op.astype(${inputVars[0]}, dtype=\"float32\"))"
            }
            UirOpKind.CEIL -> "relax.op.ceil(${inputVars[0]})"
            UirOpKind.FLOOR -> "relax.op.floor(${inputVars[0]})"
            UirOpKind.ROUND -> "relax.op.round(${inputVars[0]})"
            UirOpKind.CLAMP -> {
                // TVM uses relax.op.clip — read min/max from attributes
                val minVal = (attributes["min"] as? UirStringAttr)?.value?.toDoubleOrNull()?.toInt() ?: 0
                val maxVal = (attributes["max"] as? UirStringAttr)?.value?.toDoubleOrNull()?.toInt() ?: 1
                "relax.op.clip(${inputVars[0]}, $minVal, $maxVal)"
            }

            // ===== 二元运算 =====
            UirOpKind.ADD -> "relax.op.add(relax.op.astype(${inputVars[0]}, dtype=\"float32\"), relax.op.astype(${inputVars.getOrElse(1) { inputVars[0] }}, dtype=\"float32\"))"
            UirOpKind.SUBTRACT -> "relax.op.subtract(relax.op.astype(${inputVars[0]}, dtype=\"float32\"), relax.op.astype(${inputVars.getOrElse(1) { inputVars[0] }}, dtype=\"float32\"))"
            UirOpKind.MULTIPLY -> "relax.op.multiply(relax.op.astype(${inputVars[0]}, dtype=\"float32\"), relax.op.astype(${inputVars.getOrElse(1) { inputVars[0] }}, dtype=\"float32\"))"
            UirOpKind.DIVIDE -> "relax.op.divide(relax.op.astype(${inputVars[0]}, dtype=\"float32\"), relax.op.astype(${inputVars.getOrElse(1) { inputVars[0] }}, dtype=\"float32\"))"
            UirOpKind.MAXIMUM -> "relax.op.maximum(relax.op.astype(${inputVars[0]}, dtype=\"float32\"), relax.op.astype(${inputVars.getOrElse(1) { inputVars[0] }}, dtype=\"float32\"))"
            UirOpKind.MINIMUM -> "relax.op.minimum(relax.op.astype(${inputVars[0]}, dtype=\"float32\"), relax.op.astype(${inputVars.getOrElse(1) { inputVars[0] }}, dtype=\"float32\"))"
            UirOpKind.POWER -> "relax.op.power(relax.op.astype(${inputVars[0]}, dtype=\"float32\"), relax.op.astype(${inputVars.getOrElse(1) { inputVars[0] }}, dtype=\"float32\"))"

            // ===== 矩阵乘法 =====
            UirOpKind.MATMUL -> {
                // 为了避免形状不兼容，使用 full 替换（类似旧实现）
                // 当输入形状已知且为 2-D 时，直接调用 matmul
                // 否则使用固定的 full 替换
                "relax.op.matmul(${inputVars[0]}, ${inputVars[1]})"
            }

            // ===== 卷积 =====
            UirOpKind.CONV2D -> {
                val stride = (attributes["stride"] as? UirIntAttr)?.value ?: 1
                val padding = (attributes["padding"] as? UirIntAttr)?.value ?: 0
                val dilation = (attributes["dilation"] as? UirIntAttr)?.value ?: 1
                val groups = (attributes["groups"] as? UirIntAttr)?.value ?: 1
                "relax.op.nn.conv2d(${inputVars[0]}, ${inputVars[1]}, " +
                    "strides=[$stride, $stride], " +
                    "padding=[$padding, $padding], " +
                    "dilation=[$dilation, $dilation], " +
                    "groups=$groups)"
            }

            // ===== 池化 =====
            UirOpKind.MAX_POOL2D -> {
                var kernelSize = (attributes["kernel_size"] as? UirIntAttr)?.value ?: 2
                var stride = (attributes["stride"] as? UirIntAttr)?.value ?: kernelSize
                val padding = (attributes["padding"] as? UirIntAttr)?.value ?: 0
                // 确保 kernel_size 不超过输入的空间维度（与 PytorchTranslator 一致）
                val poolInputShape = inputShapes[0]
                if (poolInputShape.dims.size >= 4) {
                    val h = poolInputShape.dims[2].value ?: kernelSize
                    val w = poolInputShape.dims[3].value ?: kernelSize
                    val minSpatial = minOf(h, w)
                    if (kernelSize > minSpatial) {
                        kernelSize = maxOf(1, minSpatial)
                        stride = minOf(stride, kernelSize)
                    }
                }
                "relax.op.nn.max_pool2d(${inputVars[0]}, " +
                    "pool_size=[$kernelSize, $kernelSize], " +
                    "strides=[$stride, $stride], " +
                    "padding=[$padding, $padding])"
            }
            UirOpKind.AVG_POOL2D -> {
                var kernelSize = (attributes["kernel_size"] as? UirIntAttr)?.value ?: 2
                var stride = (attributes["stride"] as? UirIntAttr)?.value ?: kernelSize
                val padding = (attributes["padding"] as? UirIntAttr)?.value ?: 0
                // 确保 kernel_size 不超过输入的空间维度（与 PytorchTranslator 一致）
                val poolInputShape = inputShapes[0]
                if (poolInputShape.dims.size >= 4) {
                    val h = poolInputShape.dims[2].value ?: kernelSize
                    val w = poolInputShape.dims[3].value ?: kernelSize
                    val minSpatial = minOf(h, w)
                    if (kernelSize > minSpatial) {
                        kernelSize = maxOf(1, minSpatial)
                        stride = minOf(stride, kernelSize)
                    }
                }
                "relax.op.nn.avg_pool2d(${inputVars[0]}, " +
                    "pool_size=[$kernelSize, $kernelSize], " +
                    "strides=[$stride, $stride], " +
                    "padding=[$padding, $padding])"
            }

            // ===== 归一化 =====
            UirOpKind.LAYER_NORM -> {
                // TVM Relax layer_norm requires gamma, beta, and axes parameters
                val inputShape = inputShapes[0]
                val lastDim = inputShape.dims.lastOrNull()?.value ?: 1
                "relax.op.nn.layer_norm(${inputVars[0]}, gamma=relax.op.ones(relax.ShapeExpr([$lastDim]), dtype=\"float32\"), beta=relax.op.zeros(relax.ShapeExpr([$lastDim]), dtype=\"float32\"), axes=[-1])"
            }
            UirOpKind.BATCH_NORM -> {
                // TVM Relax batch_norm returns (output, moving_mean, moving_var) tuple
                // We need to emit the batch_norm call, then extract the first element
                // This is handled specially in translateNode() - just return the call here
                val inputShape = inputShapes[0]
                val numChannels = inputShape.dims.getOrNull(1)?.value ?: 1
                "BATCH_NORM:${inputVars[0]}:$numChannels"
            }

            // ===== SOFTMAX =====
            UirOpKind.SOFTMAX -> {
                val ndim = inputShapes[0].dims.size
                if (ndim == 0) {
                    // 0-D tensor: softmax(scalar)=scalar, TVM 不支持 axis=-1
                    "relax.op.astype(${inputVars[0]}, dtype=\"float32\")"
                } else {
                    val axis = (attributes["axis"] as? UirIntAttr)?.value ?: -1
                    "relax.op.nn.softmax(relax.op.astype(${inputVars[0]}, dtype=\"float32\"), axis=$axis)"
                }
            }
            UirOpKind.LOG_SOFTMAX -> {
                val ndim = inputShapes[0].dims.size
                if (ndim == 0) {
                    // 0-D tensor: log_softmax(scalar)=scalar, TVM 不支持 axis=-1
                    "relax.op.astype(${inputVars[0]}, dtype=\"float32\")"
                } else {
                    val axis = (attributes["axis"] as? UirIntAttr)?.value ?: -1
                    "relax.op.nn.log_softmax(relax.op.astype(${inputVars[0]}, dtype=\"float32\"), axis=$axis)"
                }
            }

            // ===== 归约 =====
            UirOpKind.REDUCE_SUM -> {
                val axis = (attributes["axis"] as? UirIntAttr)?.value ?: -1
                val keepdims = (attributes["keepdims"] as? UirIntAttr)?.value?.let { it != 0 } ?: false
                "relax.op.sum(${inputVars[0]}, axis=[$axis], keepdims=${
                    keepdims.toString().replaceFirstChar { it.uppercase() }
                })"
            }

            UirOpKind.REDUCE_MEAN -> {
                val axis = (attributes["axis"] as? UirIntAttr)?.value ?: -1
                val keepdims = (attributes["keepdims"] as? UirIntAttr)?.value?.let { it != 0 } ?: false
                "relax.op.mean(${inputVars[0]}, axis=[$axis], keepdims=${
                    keepdims.toString().replaceFirstChar { it.uppercase() }
                })"
            }

            UirOpKind.REDUCE_MAX -> {
                val axis = (attributes["axis"] as? UirIntAttr)?.value ?: -1
                val keepdims = (attributes["keepdims"] as? UirIntAttr)?.value?.let { it != 0 } ?: false
                "relax.op.max(${inputVars[0]}, axis=[$axis], keepdims=${
                    keepdims.toString().replaceFirstChar { it.uppercase() }
                })"
            }

            UirOpKind.REDUCE_MIN -> {
                val axis = (attributes["axis"] as? UirIntAttr)?.value ?: -1
                val keepdims = (attributes["keepdims"] as? UirIntAttr)?.value?.let { it != 0 } ?: false
                "relax.op.min(${inputVars[0]}, axis=[$axis], keepdims=${
                    keepdims.toString().replaceFirstChar { it.uppercase() }
                })"
            }

            // ===== 形状变换 =====
            UirOpKind.RESHAPE -> {
                // Use actual output shape from the node instead of hardcoded [-1]
                val targetShape = outputShapes[0]
                val shapeStr = targetShape.dims.map { dim ->
                    when (dim.dimKind) {
                        UirDimKind.CONSTANT -> dim.value?.toString() ?: "-1"
                        else -> "-1"
                    }
                }.joinToString(", ")
                "relax.op.reshape(${inputVars[0]}, relax.ShapeExpr([$shapeStr]))"
            }

            UirOpKind.TRANSPOSE -> {
                // 与 ShapeInferer 一致：交换最后两个维度
                val ndim = inputShapes[0].dims.size
                if (ndim >= 2) {
                    val finalPerm = (0 until ndim).map { i ->
                        when (i) {
                            ndim - 2 -> ndim - 1
                            ndim - 1 -> ndim - 2
                            else -> i
                        }
                    }.joinToString(", ")
                    "relax.op.permute_dims(${inputVars[0]}, axes=[$finalPerm])"
                } else {
                    "relax.op.permute_dims(${inputVars[0]})"
                }
            }

            UirOpKind.SQUEEZE -> {
                "relax.op.squeeze(${inputVars[0]})"
            }

            UirOpKind.UNSQUEEZE -> {
                val axis = (attributes["axis"] as? UirIntAttr)?.value ?: 0
                "relax.op.expand_dims(${inputVars[0]}, axis=$axis)"
            }

            // ===== 拼接/分割 =====
            UirOpKind.CONCAT -> {
                val axis = (attributes["axis"] as? UirIntAttr)?.value ?: 0
                "relax.op.concat([${inputVars.joinToString(", ")}], axis=$axis)"
            }

            UirOpKind.SPLIT -> {
                val axis = (attributes["axis"] as? UirIntAttr)?.value ?: 0
                // Split into 2 equal sections (must evenly divide the dimension)
                val inputShape = inputShapes[0]
                val dimSize = inputShape.dims.getOrNull(axis)?.value ?: 2
                // Ensure even division
                val halfSize = dimSize / 2
                if (halfSize > 0) {
                    "relax.op.split(${inputVars[0]}, [$halfSize], axis=$axis)"
                } else {
                    "relax.op.split(${inputVars[0]}, 2, axis=$axis)"
                }
            }

            // ===== 索引 =====
            UirOpKind.GATHER -> {
                val axis = (attributes["axis"] as? UirIntAttr)?.value ?: 0
                // 使用标量索引 0，移除 axis 维度（与 ShapeInferer 对齐）
                "relax.op.take(${inputVars[0]}, relax.const(0, dtype=\"int64\"), axis=$axis)"
            }

            UirOpKind.STRIDED_SLICE -> {
                // 与 ShapeInferer 一致：只对 axis=0 做切片，[:shape[0]//2]
                val inputShape = inputShapes[0]
                val dim0Val = inputShape.dims.getOrNull(0)?.value ?: 1
                val end = maxOf(1, dim0Val / 2)
                "relax.op.strided_slice(${inputVars[0]}, axes=[0], begin=[0], end=[$end])"
            }

            // ===== 三角矩阵 =====
            UirOpKind.TRIL -> "relax.op.tril(${inputVars[0]})"
            UirOpKind.TRIU -> "relax.op.triu(${inputVars[0]})"

            // ===== 广播/填充 =====
            UirOpKind.BROADCAST_TO -> {
                // 从节点输出读取目标形状
                val targetShape = outputShapes[0]
                val shapeStr = targetShape.dims.map { dim ->
                    when (dim.dimKind) {
                        UirDimKind.CONSTANT -> dim.value.toString()
                        else -> shapeRank.toString()  // 未知维度用默认值
                    }
                }.joinToString(", ")
                "relax.op.broadcast_to(${inputVars[0]}, relax.ShapeExpr([$shapeStr]))"
            }

            UirOpKind.TILE -> {
                // Use actual reps from output shape / input shape
                val inputShape = inputShapes[0]
                val targetShape = outputShapes[0]
                val reps = targetShape.dims.mapIndexed { i, outDim ->
                    val inVal = inputShape.dims.getOrNull(i)?.value ?: 1
                    val outVal = outDim.value ?: 1
                    if (inVal > 0) outVal / inVal else 1
                }.joinToString(", ")
                "relax.op.tile(${inputVars[0]}, [$reps])"
            }

            // ===== 类型转换 =====
            UirOpKind.CAST -> {
                // Use actual output dtype from the node instead of default dtype
                val outputDtype = outputShapes.firstOrNull()?.let {
                    // outputShapes is actually output shapes, need to get dtype from node
                    // But we don't have direct access to node here. Use attributes or default.
                    attributes["dtype"] as? UirStringAttr
                }
                val targetDtype = outputDtype?.value ?: dtype
                "relax.op.astype(${inputVars[0]}, dtype=\"$targetDtype\")"
            }

            // ===== 常数生成 =====
            UirOpKind.ARANGE -> {
                // 计算输出张量的总元素数量
                val targetShape = outputShapes[0]
                val totalSize = targetShape.dims.fold(1) { acc, dim ->
                    acc * (if (dim.dimKind == UirDimKind.CONSTANT) dim.value ?: 1 else 1)
                }
                "relax.op.arange(0, $totalSize, dtype=\"$dtype\")"
            }

            UirOpKind.FULL -> {
                val targetShape = outputShapes[0]
                val shapeStr = targetShape.dims.map { dim ->
                    when (dim.dimKind) {
                        UirDimKind.CONSTANT -> dim.value.toString()
                        else -> shapeRank.toString()  // 未知维度用默认值
                    }
                }.joinToString(", ")
                "relax.op.full(relax.ShapeExpr([$shapeStr]), relax.const(0, dtype=\"$dtype\"))"
            }

            UirOpKind.ONES -> {
                val targetShape = outputShapes[0]
                val shapeStr = targetShape.dims.map { dim ->
                    when (dim.dimKind) {
                        UirDimKind.CONSTANT -> dim.value.toString()
                        else -> shapeRank.toString()  // 未知维度用默认值
                    }
                }.joinToString(", ")
                "relax.op.ones(relax.ShapeExpr([$shapeStr]), dtype=\"$dtype\")"
            }

            UirOpKind.ZEROS -> {
                val targetShape = outputShapes[0]
                val shapeStr = targetShape.dims.map { dim ->
                    when (dim.dimKind) {
                        UirDimKind.CONSTANT -> dim.value.toString()
                        else -> shapeRank.toString()  // 未知维度用默认值
                    }
                }.joinToString(", ")
                "relax.op.zeros(relax.ShapeExpr([$shapeStr]), dtype=\"$dtype\")"
            }

            // ===== P0: 累积操作 =====
            UirOpKind.CUMSUM -> {
                val axis = (attributes["axis"] as? UirIntAttr)?.value ?: -1
                val cumsumInput = inputVars[0]
                val cumsumShape = inputShapes[0]
                if (cumsumShape.dims.isEmpty()) {
                    // 标量输入: cumsum(scalar) = scalar, TVM doesn't support cumsum on 0-D tensors
                    "relax.op.astype($cumsumInput, dtype=\"float32\")"
                } else {
                    "relax.op.cumsum($cumsumInput, axis=$axis)"
                }
            }

            UirOpKind.CUMPROD -> {
                val axis = (attributes["axis"] as? UirIntAttr)?.value ?: -1
                val cumprodInput = inputVars[0]
                val cumprodShape = inputShapes[0]
                if (cumprodShape.dims.isEmpty()) {
                    // 标量输入: cumprod(scalar) = scalar, TVM doesn't support cumprod on 0-D tensors
                    "relax.op.astype($cumprodInput, dtype=\"float32\")"
                } else {
                    "relax.op.cumprod($cumprodInput, axis=$axis)"
                }
            }

            UirOpKind.ARGMAX -> {
                val ndim = inputShapes[0].dims.size
                if (ndim == 0) {
                    // 0-D tensor: argmax(scalar)=scalar, TVM 不支持轴参数
                    "relax.op.astype(${inputVars[0]}, dtype=\"float32\")"
                } else {
                    val axis = (attributes["axis"] as? UirIntAttr)?.value ?: -1
                    // argmax returns int64; cast to float32 for compatibility with subsequent ops
                    "relax.op.astype(relax.op.argmax(${inputVars[0]}, axis=$axis), dtype=\"float32\")"
                }
            }

            UirOpKind.ARGMIN -> {
                val ndim = inputShapes[0].dims.size
                if (ndim == 0) {
                    // 0-D tensor: argmin(scalar)=scalar, TVM 不支持轴参数
                    "relax.op.astype(${inputVars[0]}, dtype=\"float32\")"
                } else {
                    val axis = (attributes["axis"] as? UirIntAttr)?.value ?: -1
                    // argmin returns int64; cast to float32 for compatibility with subsequent ops
                    "relax.op.astype(relax.op.argmin(${inputVars[0]}, axis=$axis), dtype=\"float32\")"
                }
            }

            // ===== P2: 插值/Resize =====
            UirOpKind.INTERPOLATE -> {
                // TVM Relax has no relax.op.nn.interpolate; use relax.op.image.resize2d instead
                // Need 4D input (NCHW), compute output spatial dims from outputShapes
                val targetShape = outputShapes[0]
                val outH = targetShape.dims.getOrNull(2)?.value ?: 2
                val outW = targetShape.dims.getOrNull(3)?.value ?: 2
                "relax.op.image.resize2d(${inputVars[0]}, relax.ShapeExpr([$outH, $outW]), layout=\"NCHW\")"
            }
            UirOpKind.RESIZE2D -> {
                val targetShape = outputShapes[0]
                val outH = targetShape.dims.getOrNull(2)?.value ?: 2
                val outW = targetShape.dims.getOrNull(3)?.value ?: 2
                "relax.op.image.resize2d(${inputVars[0]}, relax.ShapeExpr([$outH, $outW]), layout=\"NCHW\")"
            }

            // ===== 适配算子 =====
            UirOpKind.EXPAND_DIMS -> {
                val axis = (attributes["axis"] as? UirIntAttr)?.value ?: 0
                "relax.op.expand_dims(${inputVars[0]}, axis=$axis)"
            }
        }
    }

    /**
     * 添加形状断言。
     * 断言输出的实际形状与推断的形状一致。
     */
    private fun addShapeAssertion(builder: StringBuilder, outputVar: String, expectedShape: UirShape) {
        // 生成期望形状的字符串表示
        val expectedShapeStr = generateShapeAssertionExpr(expectedShape)
        
        // 使用 relax 的结构信息检查
        builder.appendLine("        assert str($outputVar.struct_info.shape) == \"$expectedShapeStr\", f\"Shape mismatch: expected $expectedShapeStr, got {$outputVar.struct_info.shape}\"")
    }

    /**
     * 生成用于断言的形状表达式字符串。
     */
    private fun generateShapeAssertionExpr(shape: UirShape): String {
        if (shape.dims.isEmpty()) {
            return "R.shape([])"
        }

        val dims = shape.dims.map { dim ->
            when (dim.dimKind) {
                UirDimKind.CONSTANT -> dim.value?.toString() ?: shapeRank.toString()
                UirDimKind.SYMBOLIC -> shapeRank.toString()  // 符号维度暂时用固定值
                UirDimKind.UNKNOWN -> shapeRank.toString()    // 未知维度暂时用固定值
            }
        }

        return "R.shape([${dims.joinToString(", ")}])"
    }

    /**
     * 生成 ShapeExpr 字符串。
     */
    private fun generateShapeExpr(shape: UirShape): String {
        if (shape.dims.isEmpty()) {
            return "relax.ShapeExpr([])"
        }

        return "relax.ShapeExpr(${shape.rawShapeString()})"
    }

    private fun UirShape.rawShapeString(): String {
        val dims = dims.map { dim ->
            when (dim.dimKind) {
                UirDimKind.CONSTANT -> dim.value?.toString() ?: shapeRank.toString()
                UirDimKind.SYMBOLIC -> shapeRank.toString()  // 符号维度暂时用固定值
                UirDimKind.UNKNOWN -> shapeRank.toString()    // 未知维度暂时用固定值
            }
        }

        return "[${dims.joinToString(", ")}]"
    }
}