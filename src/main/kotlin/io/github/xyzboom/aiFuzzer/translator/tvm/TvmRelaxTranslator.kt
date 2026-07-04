package io.github.xyzboom.aiFuzzer.translator.tvm

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.types.UirDataType
import io.github.xyzboom.aiFuzzer.ir.types.UirDim
import io.github.xyzboom.aiFuzzer.ir.types.UirIntAttr
import io.github.xyzboom.aiFuzzer.ir.types.UirShape
import io.github.xyzboom.aiFuzzer.ir.types.UirStringAttr
import io.github.xyzboom.aiFuzzer.ir.types.UirTensorType
import io.github.xyzboom.aiFuzzer.translator.UirTranslator

/**
 * 将 UIR 程序翻译为 TVM Relax Python 脚本。
 *
 * 输出：单文件 main.py，包含 Relax 函数定义 + 主入口。
 */
class TvmRelaxTranslator(
    private val dtypeMapping: Map<String, String> = defaultDtypeMapping,
    private val opNameMapping: Map<String, String> = defaultOpNameMapping,
) : UirTranslator<UirProgram, String> {

    override fun translate(element: UirProgram): String {
        return buildString {
            appendLine("import tvm")
            appendLine("from tvm import relax")
            appendLine("import tvm.relax.op as op")
            appendLine("from tvm.script import tir as T")
            appendLine()
            appendLine()
            appendLine("def build_mod():")
            appendLine("    bb = relax.BlockBuilder()")
            appendLine()
            for (graph in element.graphs) {
                translateGraph(graph)
            }
            appendLine("    return bb.get()")
            appendLine()
            appendLine()
            appendLine("if __name__ == '__main__':")
            appendLine("    mod = build_mod()")
            appendLine("    print(mod.script())")
        }
    }

    private fun StringBuilder.translateGraph(graph: UirGraph) {
        appendLine("    with bb.function(\"${graph.name}\"):")

        // 1. 声明输入参数
        val inputParams = graph.inputs.map { ref ->
            val paramName = sanitizeName(ref.valueId)
            // 查找类型信息（通过遍历 nodes 的 outputs 无法直接获取，先使用统一类型）
            val tensorType = inferInputType(ref, graph)
            appendLine("        $paramName = relax.Var(\"$paramName\", $tensorType)")
            paramName
        }

        // 2. 翻译每个节点，建立变量名映射
        val valueMap = mutableMapOf<String, String>()
        for (input in graph.inputs) {
            valueMap[input.valueId] = sanitizeName(input.valueId)
        }
        // 输出值也预留
        for (output in graph.outputs) {
            if (output.valueId !in valueMap) {
                valueMap[output.valueId] = sanitizeName(output.valueId)
            }
        }

        appendLine()
        for (node in graph.nodes) {
            translateNode(node, valueMap)
        }

        // 3. 定义输出
        val outputNames = graph.outputs.map { valueMap[it.valueId] ?: sanitizeName(it.valueId) }
        appendLine()
        appendLine("        bb.emit_func_output([${outputNames.joinToString(", ")}])")
        appendLine()
    }

    private fun StringBuilder.translateNode(node: UirNode, valueMap: MutableMap<String, String>) {
        val tvmOp = opNameMapping[node.op] ?: node.op
        val inputNames = node.inputs.map { valueMap[it.valueId] ?: sanitizeName(it.valueId) }
        val outputNames = node.outputs.map { valueMap[it.valueId] ?: sanitizeName(it.valueId) }

        // 构建属性字符串
        val attrStr = buildAttributeString(node.attributes)

        // 处理输出赋值
        // 单输出：out = bb.emit(op(...))
        // 多输出：out1 = bb.emit(op(...)) 或 out1, out2 = bb.emit(op(...))
        when {
            outputNames.isEmpty() -> {
                appendLine("        # op ${node.op} has no outputs")
            }
            outputNames.size == 1 -> {
                appendLine("        ${outputNames[0]} = bb.emit(relax.op.$tvmOp(${inputNames.joinToString(", ")}$attrStr))")
            }
            else -> {
                appendLine("        ${outputNames.joinToString(", ")} = bb.emit(relax.op.$tvmOp(${inputNames.joinToString(", ")}$attrStr))")
            }
        }
    }

    private fun buildAttributeString(attributes: Map<String, Attribute>): String {
        if (attributes.isEmpty()) return ""
        val sb = StringBuilder()
        for ((key, attr) in attributes) {
            when (attr) {
                is UirIntAttr -> sb.append(", $key=${attr.value}")
                is UirStringAttr -> sb.append(", $key=\"${attr.value}\"")
                // 其他属性类型暂不支持
                else -> {}
            }
        }
        return sb.toString()
    }

    private fun inferInputType(ref: UirValueRef, graph: UirGraph): String {
        // 根据命名推测类型，实际使用中需要更好的类型推断
        // 暂时输出一个动态形状的张量：R.Tensor(("?", "?"), "float32")
        return """R.Tensor(("any",), "float32")"""
    }

    private fun sanitizeName(name: String): String {
        // Python 中合法变量名，替换非法字符
        return if (name.isEmpty()) "v" else name
            .replace(Regex("[^a-zA-Z0-9_]"), "_")
            .let { if (it[0].isDigit()) "_$it" else it }
    }

    companion object {
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
            "dense" to "linear",  // relax op 名
            "attention" to "attention",
        )
    }
}