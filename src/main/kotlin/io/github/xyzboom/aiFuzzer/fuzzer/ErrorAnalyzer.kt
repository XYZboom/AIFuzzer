package io.github.xyzboom.aiFuzzer.fuzzer

/**
 * 错误分类和简短描述。
 */
enum class ErrorCategory {
    // 编译/执行成功
    NONE,
    // Python 语法错误
    SYNTAX_ERROR,
    // 运行时类型错误（Python TypeError）
    TYPE_ERROR,
    // 属性不存在
    ATTRIBUTE_ERROR,
    // 变量未定义（常见于翻译器 bug）
    NAME_ERROR,
    // PyTorch 运行时错误（形状不匹配、内存分配等）
    PYTORCH_RUNTIME_ERROR,
    // PyTorch 编译错误（torch.compile 失败）
    PYTORCH_COMPILE_ERROR,
    // 验证失败（eager vs compile 结果不一致）
    VERIFY_FAIL,
    // TVM 内部错误
    TVM_ERROR,
    // TVM 算子未实现
    OP_NOT_IMPLEMENTED,
    // 其他 TVM 编译错误
    TVM_COMPILE_ERROR,
    // Dynamo 捕获错误（PyTorch）
    DYNAMO_ERROR,
    // Inductor 编译错误（PyTorch）
    INDUCTOR_ERROR,
    // 超时
    TIMEOUT,
    // 内存不足
    OOM_ERROR,
    // 未分类错误
    UNKNOWN,
}

data class ErrorInfo(
    val category: ErrorCategory,
    val summary: String,
)

/**
 * 错误分析器：解析编译器输出，提取错误分类和摘要。
 */
object ErrorAnalyzer {

    fun analyze(stderr: String, exitCode: Int): ErrorInfo {
        if (exitCode == 0 && stderr.isBlank()) {
            return ErrorInfo(ErrorCategory.NONE, "success")
        }

        return when {
            stderr.contains("SyntaxError") -> {
                val msg = extractLine(stderr, "SyntaxError")
                ErrorInfo(ErrorCategory.SYNTAX_ERROR, msg)
            }
            stderr.contains("TypeError") -> {
                val msg = extractLine(stderr, "TypeError")
                ErrorInfo(ErrorCategory.TYPE_ERROR, msg)
            }
            stderr.contains("AttributeError") -> {
                val msg = extractLine(stderr, "AttributeError")
                ErrorInfo(ErrorCategory.ATTRIBUTE_ERROR, msg)
            }
            stderr.contains("NameError") -> {
                val msg = extractLine(stderr, "NameError")
                ErrorInfo(ErrorCategory.NAME_ERROR, msg)
            }
            stderr.contains("VERIFY: FAIL") -> {
                ErrorInfo(ErrorCategory.VERIFY_FAIL, "eager vs compile mismatch")
            }
            stderr.contains("torch.compile failed") -> {
                val msg = extractLine(stderr, "torch.compile failed")
                ErrorInfo(ErrorCategory.PYTORCH_COMPILE_ERROR, msg)
            }
            stderr.contains("InductorError") -> {
                // torch._inductor crashes (e.g., "sympy expression is NaN" from nan inputs)
                val msg = extractLine(stderr, "InductorError")
                ErrorInfo(ErrorCategory.PYTORCH_COMPILE_ERROR, msg)
            }
            stderr.contains("RuntimeError") -> {
                val msg = extractLine(stderr, "RuntimeError")
                // 区分 OOM 和其他 RuntimeError
                if (stderr.contains("can't allocate memory") || stderr.contains("CUDA out of memory")) {
                    ErrorInfo(ErrorCategory.OOM_ERROR, msg)
                } else {
                    ErrorInfo(ErrorCategory.PYTORCH_RUNTIME_ERROR, msg)
                }
            }
            stderr.contains("IndexError") -> {
                val msg = extractLine(stderr, "IndexError")
                ErrorInfo(ErrorCategory.PYTORCH_RUNTIME_ERROR, msg)
            }
            stderr.contains("ValueError") -> {
                val msg = extractLine(stderr, "ValueError")
                ErrorInfo(ErrorCategory.PYTORCH_RUNTIME_ERROR, msg)
            }
            stderr.contains("NotImplementedError") -> {
                val msg = extractLine(stderr, "NotImplementedError")
                ErrorInfo(ErrorCategory.PYTORCH_RUNTIME_ERROR, msg)
            }
            stderr.contains("OpNotImplemented") -> {
                val msg = extractLine(stderr, "OpNotImplemented")
                ErrorInfo(ErrorCategory.OP_NOT_IMPLEMENTED, msg)
            }
            stderr.contains("TVMError") || stderr.contains("tvm.error") -> {
                val msg = when {
                    stderr.contains("TVMError") -> extractLine(stderr, "TVMError")
                    else -> extractLine(stderr, "tvm.error")
                }
                ErrorInfo(ErrorCategory.TVM_ERROR, msg)
            }
            stderr.contains("Check failed") -> {
                val msg = extractLine(stderr, "Check failed")
                ErrorInfo(ErrorCategory.TVM_COMPILE_ERROR, msg)
            }
            stderr.contains("ImportError") -> {
                val msg = extractLine(stderr, "ImportError")
                ErrorInfo(ErrorCategory.UNKNOWN, msg)
            }
            // PyTorch Dynamo 错误
            stderr.contains("TorchDynamo") || stderr.contains("torch._dynamo") || stderr.contains("torch._guards") -> {
                val msg = when {
                    stderr.contains("TorchDynamo") -> extractLine(stderr, "TorchDynamo")
                    stderr.contains("torch._dynamo") -> extractLine(stderr, "torch._dynamo")
                    else -> extractLine(stderr, "torch._guards")
                }
                ErrorInfo(ErrorCategory.DYNAMO_ERROR, msg)
            }
            // PyTorch Inductor 错误
            stderr.contains("Inductor") || stderr.contains("torch._inductor") -> {
                val msg = when {
                    stderr.contains("Inductor") -> extractLine(stderr, "Inductor")
                    else -> extractLine(stderr, "torch._inductor")
                }
                ErrorInfo(ErrorCategory.INDUCTOR_ERROR, msg)
            }
            // PyTorch 运行时错误
            stderr.contains("RuntimeError") && (stderr.contains("torch") || stderr.contains("cuda") || stderr.contains("CUDA")) -> {
                val msg = extractLine(stderr, "RuntimeError")
                ErrorInfo(ErrorCategory.PYTORCH_RUNTIME_ERROR, msg)
            }
            else -> {
                ErrorInfo(ErrorCategory.UNKNOWN, stderr.take(200))
            }
        }
    }

    private fun extractLine(text: String, prefix: String): String {
        return text.lines()
            .firstOrNull { prefix in it }
            ?.trim()
            ?: "$prefix (details not found)"
    }
}