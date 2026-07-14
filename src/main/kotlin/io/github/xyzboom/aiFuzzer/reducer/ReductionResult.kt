package io.github.xyzboom.aiFuzzer.reducer

import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirProgram

/**
 * 属性检查器接口：判断缩减后的程序是否仍然触发原始 bug。
 * 每个 [check] 调用传入缩减中的当前程序（含仅剩的节点）。
 */
interface PropertyChecker {
    fun check(program: UirProgram): Boolean
    fun bugSignature(): String
}

data class ReductionResult(
    val originalProgram: UirProgram,
    val minifiedProgram: UirProgram?,
    val reductionRatio: Double,
    val propertyPreserved: Boolean,
    val steps: List<ReductionStep>,
    val errorMessage: String? = null,
) {
    companion object {
        fun failed(program: UirProgram, error: String): ReductionResult {
            return ReductionResult(program, null, 0.0, false, emptyList(), error)
        }
        fun unchanged(program: UirProgram): ReductionResult {
            return ReductionResult(program, program, 0.0, true, emptyList())
        }
    }
}

data class ReductionStep(
    val type: StepType,
    val description: String,
    val removedNodes: List<String>,
    val remainingNodeCount: Int,
)

enum class StepType {
    DDMIN_REMOVE, WIRE_AROUND, CONST_REPLACE, DEFAULT_REPLACE,
    DEAD_CODE_ELIMINATION, CASCADE_DELETE,
}