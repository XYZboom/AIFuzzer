package io.github.xyzboom.aiFuzzer.translator.tvm

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.types.UirIntAttr
import io.github.xyzboom.aiFuzzer.ir.types.UirStringAttr
import io.github.xyzboom.aiFuzzer.ir.visitors.UirDefaultVisitor
import io.github.xyzboom.aiFuzzer.translator.UirTranslator

/**
 * 将 UIR 程序翻译为 TVM Relax Python 脚本。
 *
 * 基于 Visitor 模式，由 IR 树驱动遍历。
 * 输出：单文件 main.py 字符串。
 */
class TvmRelaxTranslator(
    private val dtypeMapping: Map<String, String> = defaultDtypeMapping,
    private val opNameMapping: Map<String, String> = defaultOpNameMapping,
    private val shapeRank: Int = 3,
    private val dtype: String = "float32",
) : UirTranslator<UirProgram, String> {

    private val shapeStr: String = (-1 downTo -1).take(shapeRank).joinToString(", ") { "-1" }

    private class Data {
        val out = StringBuilder()
        var indent = 0
        var currentGraph: UirGraph? = null
        val valueMap = mutableMapOf<String, String>()
        // ndim 追踪：valueId → ndim（由生成器传递或翻译器推断）
        val ndimMap = mutableMapOf<String, Int>()

        fun appendLine(line: String = "") {
            repeat(indent) { out.append("    ") }
            out.appendLine(line)
        }

        fun indent(block: () -> Unit) {
            indent++
            block()
            indent--
        }

        /** 根据 ndim 生成 relax.ShapeExpr([-1, ...]) */
        fun dynamicShapeExpr(ndim: Int): String {
            if (ndim <= 0) return "relax.ShapeExpr([])"
            val dims = (1..ndim).joinToString(", ") { "-1" }
            return "relax.ShapeExpr([$dims])"
        }
    }

    override fun translate(element: UirProgram): String {
        val data = Data()
        val visitor = TranslatingVisitor()
        element.accept(visitor, data)
        return data.out.toString()
    }

    private inner class TranslatingVisitor : UirDefaultVisitor<Unit, Data>() {

        override fun visitElement(element: UirElement, data: Data) {
            element.acceptChildren(this, data)
        }

        override fun visitProgram(program: UirProgram, data: Data) {
            data.appendLine("import tvm")
            data.appendLine("from tvm import relax")
            data.appendLine("import tvm.relax.op as op")
            data.appendLine()
            data.appendLine()
            data.appendLine("def build_mod():")
            data.indent {
                data.appendLine("bb = relax.BlockBuilder()")
                data.appendLine()
                program.acceptChildren(this, data)
                data.appendLine("return bb.get()")
            }
            data.appendLine()
            data.appendLine()
            data.appendLine("if __name__ == '__main__':")
            data.indent {
                data.appendLine("mod = build_mod()")
                data.appendLine("print(mod.script())")
            }
        }

        override fun visitGraph(graph: UirGraph, data: Data) {
            data.currentGraph = graph
            data.valueMap.clear()
            data.ndimMap.clear()

            val inputVarNames = mutableListOf<String>()
            for ((i, input) in graph.inputs.withIndex()) {
                val name = sanitizeName(input.valueId)
                val varName = "${name}_var"
                val typeStr = inferInputType(input, graph, shapeStr, dtype)
                data.appendLine("$varName = relax.Var(\"$name\", $typeStr)")
                data.valueMap[input.valueId] = varName
                // 所有输入默认 2-D
                data.ndimMap[input.valueId] = 2
                inputVarNames.add(varName)
            }
            data.appendLine("with bb.function(\"${graph.name}\", [${inputVarNames.joinToString(", ")}]):")
            data.indent {
                for (output in graph.outputs) {
                    if (output.valueId !in data.valueMap) {
                        data.valueMap[output.valueId] = sanitizeName(output.valueId)
                    }
                }

                graph.acceptChildren(this, data)

                data.appendLine()
                val outputNames = graph.outputs.map {
                    data.valueMap[it.valueId] ?: sanitizeName(it.valueId)
                }
                when (outputNames.size) {
                    1 -> data.appendLine("bb.emit_func_output(${outputNames[0]})")
                    else -> data.appendLine("bb.emit_func_output([${outputNames.joinToString(", ")}])")
                }
            }
            data.appendLine()
            data.currentGraph = null
        }

        override fun visitNode(node: UirNode, data: Data) {
            val tvmOp = opNameMapping[node.op] ?: node.op
            val inputNames = node.inputs.map {
                data.valueMap[it.valueId] ?: sanitizeName(it.valueId)
            }
            val outputNames = node.outputs.map {
                data.valueMap[it.valueId] ?: sanitizeName(it.valueId)
            }
            val attrStr = buildAttributeString(node.attributes)

            val outResult = outputNames.joinToString(", ")
            val outPrefix = when {
                outputNames.isEmpty() -> return
                outputNames.size == 1 -> "${outputNames[0]} = "
                else -> "$outResult = "
            }

            val call = emitCall(tvmOp, node, inputNames, attrStr, data)
            data.appendLine("$outPrefix$call")

            // 追踪 ndim
            val inputNdims = node.inputs.mapNotNull { data.ndimMap[it.valueId] }
            val outputNdim = computeOutputNdimForTranslation(node.op, inputNdims)
            // 特殊处理翻译器输出的 ndim 与生成器不一致的情况
            val actualNdim = when (node.op) {
                "zeros", "ones", "full" -> 2  // 翻译器硬编码为 2-D
                else -> outputNdim
            }
            node.outputs.forEach { data.ndimMap[it.valueId] = actualNdim }
        }

        /**
         * 与生成器 computeOutputNdim 同步的 ndim 推断。
         */
        private fun computeOutputNdimForTranslation(op: String, inputNdims: List<Int>): Int {
            if (inputNdims.isEmpty()) return 1
            return when (op) {
                "reduce_sum", "reduce_mean", "reduce_max", "reduce_min", "max", "min" ->
                    (inputNdims.first() - 1).coerceAtLeast(0)
                "reshape" -> 1
                "squeeze" -> (inputNdims.first() - 1).coerceAtLeast(1)
                "unsqueeze" -> inputNdims.first() + 1
                "matmul" -> {
                    if (inputNdims.size < 2) return inputNdims.first()
                    val (a, b) = inputNdims
                    when {
                        a == 1 && b == 1 -> 0
                        a == 1 -> b - 1
                        b == 1 -> a - 1
                        else -> maxOf(a, b)
                    }
                }
                "split" -> inputNdims.first()
                "arange" -> 1
                "full", "zeros", "ones" -> 1
                "broadcast_to", "tile" -> inputNdims.first()
                "strided_slice" -> maxOf(1, inputNdims.first() - 1)
                "tril", "triu" -> inputNdims.first()
                "transpose" -> inputNdims.first()
                "concat" -> inputNdims.first()
                else -> inputNdims.first()
            }
        }

        private fun emitCall(tvmOp: String, node: UirNode, inputNames: List<String>, attrStr: String, data: Data): String {
            val attrs = node.attributes
            // 获取输入 ndim
            val inputNdims = node.inputs.mapNotNull { data.ndimMap[it.valueId] }

            return when (tvmOp) {
                // 元素级二元
                "add" -> "bb.emit(relax.op.add(${inputNames.joinToString(", ")}))"
                "subtract" -> "bb.emit(relax.op.subtract(${inputNames.joinToString(", ")}))"
                "multiply" -> "bb.emit(relax.op.multiply(${inputNames.joinToString(", ")}))"
                "divide" -> "bb.emit(relax.op.divide(${inputNames.joinToString(", ")}))"
                "maximum" -> "bb.emit(relax.op.maximum(${inputNames.joinToString(", ")}))"
                "minimum" -> "bb.emit(relax.op.minimum(${inputNames.joinToString(", ")}))"
                "power" -> "bb.emit(relax.op.power(${inputNames.joinToString(", ")}$attrStr))"

                // 矩阵乘法
                "matmul" -> {
                    // 如果任意输入是 1-D，先 expand 到 2-D 以确保 shape 兼容
                    val ndim0 = inputNdims.getOrElse(0) { 2 }
                    val ndim1 = inputNdims.getOrElse(1) { 2 }
                    val a = if (ndim0 <= 1) "bb.emit(relax.op.expand_dims(${inputNames[0]}, 0))" else inputNames[0]
                    val b = if (ndim1 <= 1) "bb.emit(relax.op.expand_dims(${inputNames[1]}, 1))" else inputNames[1]
                    "bb.emit(relax.op.matmul($a, $b))"
                }

                // 一元激活
                "nn.relu" -> "bb.emit(relax.op.nn.relu(${inputNames.first()}))"
                "sigmoid" -> "bb.emit(relax.op.sigmoid(${inputNames.first()}))"
                "tanh" -> "bb.emit(relax.op.tanh(${inputNames.first()}))"
                "nn.gelu" -> "bb.emit(relax.op.nn.gelu(${inputNames.first()}))"
                "nn.silu" -> "bb.emit(relax.op.nn.silu(${inputNames.first()}))"
                "nn.softmax" -> "bb.emit(relax.op.nn.softmax(${inputNames.first()}, axis=-1))"

                // 一元数学
                "negative" -> "bb.emit(relax.op.negative(${inputNames.first()}))"
                "abs" -> "bb.emit(relax.op.abs(${inputNames.first()}))"
                "exp" -> "bb.emit(relax.op.exp(${inputNames.first()}))"
                "log" -> "bb.emit(relax.op.log(${inputNames.first()}))"
                "sqrt" -> "bb.emit(relax.op.sqrt(${inputNames.first()}))"
                "ceil" -> "bb.emit(relax.op.ceil(${inputNames.first()}))"
                "floor" -> "bb.emit(relax.op.floor(${inputNames.first()}))"

                // 形状变换
                "reshape" -> {
                    val ndim = inputNdims.firstOrNull() ?: 2
                    if (ndim <= 1) {
                        "bb.emit(relax.op.reshape(${inputNames.first()}, relax.ShapeExpr([-1])))"
                    } else {
                        "bb.emit(relax.op.reshape(${inputNames.first()}, relax.ShapeExpr([-1])))"
                    }
                }
                "permute_dims" -> "bb.emit(relax.op.permute_dims(${inputNames.first()}))"
                "squeeze" -> "bb.emit(relax.op.squeeze(${inputNames.first()}))"
                "expand_dims" -> "bb.emit(relax.op.expand_dims(${inputNames.first()}, 0))"

                // 归约 — 用 keepdims=true 保持 ndim
                "sum" -> "bb.emit(relax.op.sum(${inputNames.first()}, axis=[-1], keepdims=True))"
                "mean" -> "bb.emit(relax.op.mean(${inputNames.first()}, axis=[-1], keepdims=True))"
                "max" -> "bb.emit(relax.op.max(${inputNames.first()}, axis=[-1], keepdims=True))"
                "min" -> "bb.emit(relax.op.min(${inputNames.first()}, axis=[-1], keepdims=True))"

                // 拼接/分割
                "concat" -> "bb.emit(relax.op.concat([${inputNames.joinToString(", ")}]))"
                "split" -> "bb.emit(relax.op.split(${inputNames.first()}, 2))"

                // strided_slice: 单输入 + axes/begin/end/strides 全部为列表
                "strided_slice" -> {
                    val b = (attrs["begin"] as? UirIntAttr)?.value ?: 1
                    val e = (attrs["end"] as? UirIntAttr)?.value ?: -1
                    val s = (attrs["strides"] as? UirIntAttr)?.value ?: 1
                    "bb.emit(relax.op.strided_slice(${inputNames.first()}, axes=[0], begin=[$b], end=[$e], strides=[$s]))"
                }

                // 三角
                "tril" -> "bb.emit(relax.op.tril(${inputNames.first()}, k=0))"
                "triu" -> "bb.emit(relax.op.triu(${inputNames.first()}, k=0))"

                // 常数生成
                "arange" -> {
                    // arange 本身就产生 1-D，不使用
                    val s = (attrs["start"] as? UirIntAttr)?.value ?: 0
                    val e = (attrs["stop"] as? UirIntAttr)?.value ?: 10
                    "bb.emit(relax.op.arange($s, $e, dtype=\"float32\"))"
                }
                "zeros" -> "bb.emit(relax.op.zeros(relax.ShapeExpr([16, 16]), dtype=\"float32\"))"
                "ones" -> "bb.emit(relax.op.ones(relax.ShapeExpr([16, 16]), dtype=\"float32\"))"
                "full" -> {
                    "bb.emit(relax.op.full(relax.ShapeExpr([16, 16]), relax.op.zeros(relax.ShapeExpr([]), dtype=\"float32\"), dtype=\"float32\"))"
                }

                // 类型转换
                "astype" -> "bb.emit(relax.op.astype(${inputNames.first()}, dtype=\"float32\"))"

                // 广播 — 用固定 shape 避免动态 vs 静态 shape 不兼容
                "broadcast_to" -> {
                    val ndim = inputNdims.firstOrNull() ?: 2
                    if (ndim <= 1) {
                        "bb.emit(relax.op.broadcast_to(${inputNames.first()}, relax.ShapeExpr([16])))"
                    } else {
                        "bb.emit(relax.op.broadcast_to(${inputNames.first()}, relax.ShapeExpr([16, 16])))"
                    }
                }
                "tile" -> "bb.emit(relax.op.tile(${inputNames.first()}, repeats=1))"

                // 索引
                "take" -> {
                    "bb.emit(relax.op.take(${inputNames.first()}, relax.op.zeros(relax.ShapeExpr([1]), dtype=\"int64\"), axis=0))"
                }

                // 默认：通过命名映射拼接
                else -> "bb.emit(relax.op.$tvmOp(${inputNames.joinToString(", ")}$attrStr))"
            }
        }
    }

    companion object {
        private fun buildAttributeString(attributes: Map<String, Attribute>): String {
            if (attributes.isEmpty()) return ""
            val sb = StringBuilder()
            for ((key, attr) in attributes) {
                when (attr) {
                    is UirIntAttr -> sb.append(", $key=${attr.value}")
                    is UirStringAttr -> sb.append(", $key=\"${attr.value}\"")
                    else -> {}
                }
            }
            return sb.toString()
        }

        private fun inferInputType(ref: UirValueRef, graph: UirGraph, shape: String, dtype: String): String {
            return """relax.TensorStructInfo(shape=relax.ShapeExpr([16, 16]), dtype="$dtype")"""
        }

        private fun sanitizeName(name: String): String {
            return if (name.isEmpty()) "v" else name
                .replace(Regex("[^a-zA-Z0-9_]"), "_")
                .let { if (it[0].isDigit()) "_$it" else it }
        }

        val defaultDtypeMapping = mapOf(
            "float32" to "float32",
            "float64" to "float64",
            "int32" to "int32",
            "int64" to "int64",
        )

        val defaultOpNameMapping = mapOf(
            "add" to "add",
            "subtract" to "subtract",
            "multiply" to "multiply",
            "divide" to "divide",
            "matmul" to "matmul",
            "relu" to "nn.relu",
            "sigmoid" to "sigmoid",
            "tanh" to "tanh",
            "gelu" to "nn.gelu",
            "silu" to "nn.silu",
            "softmax" to "nn.softmax",
            "abs" to "abs",
            "exp" to "exp",
            "log" to "log",
            "sqrt" to "sqrt",
            "neg" to "negative",
            "cast" to "astype",
            "conv2d" to "nn.conv2d",
            "conv1d" to "nn.conv1d",
            "max_pool2d" to "nn.max_pool2d",
            "avg_pool2d" to "nn.avg_pool2d",
            "reduce_sum" to "sum",
            "reduce_mean" to "mean",
            "reduce_max" to "max",
            "reduce_min" to "min",
            "reshape" to "reshape",
            "transpose" to "permute_dims",
            "concat" to "concat",
            "split" to "split",
            "squeeze" to "squeeze",
            "unsqueeze" to "expand_dims",
            "broadcast_to" to "broadcast_to",
            "tile" to "tile",
            "pad" to "nn.pad",
            "batch_norm" to "nn.batch_norm",
            "layer_norm" to "nn.layer_norm",
            "dropout" to "nn.dropout",
            "maximum" to "maximum",
            "minimum" to "minimum",
            "min" to "minimum",
            "max" to "maximum",
            "power" to "power",
            "gather" to "take",
            "scatter" to "scatter_elements",
            "topk" to "topk",
            "nonzero" to "nonzero",
            "one_hot" to "one_hot",
            "arange" to "arange",
            "full" to "full",
            "zeros" to "zeros",
            "ones" to "ones",
            "tril" to "tril",
            "triu" to "triu",
            "strided_slice" to "strided_slice",
            "dense" to "linear",
            "attention" to "attention",
        )
    }
}