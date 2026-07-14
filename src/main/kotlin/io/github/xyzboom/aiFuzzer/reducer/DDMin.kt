package io.github.xyzboom.aiFuzzer.reducer

import kotlin.math.max
import kotlin.math.min

/**
 * DDMin（Delta-Debugging Minimization）通用算法实现。
 *
 * 参考 CrossLangFuzzer 的 DDMin 实现。
 */
class DDMin<T>(private val testFunc: (List<T>) -> Boolean) {

    fun execute(input: List<T>): List<T> {
        if (input.size <= 1) return input
        return executeRecursive(input, 2)
    }

    private fun executeRecursive(input: List<T>, n: Int): List<T> {
        if (input.isEmpty()) return input
        if (input.size == 1) {
            if (testFunc(emptyList())) return emptyList()
            return input
        }
        val parts = partition(input, n)
        for (i in parts.indices) {
            val complement = getComplement(parts, i)
            if (testFunc(complement)) {
                return executeRecursive(complement, max(n - 1, 2))
            }
        }
        return if (n < input.size) {
            executeRecursive(input, min(n * 2, input.size))
        } else {
            input
        }
    }

    private fun <T> partition(list: List<T>, n: Int): List<List<T>> {
        val result = mutableListOf<List<T>>()
        val chunkSize = list.size / n
        var remainder = list.size % n
        var start = 0
        for (i in 0 until n) {
            val currentChunkSize = chunkSize + if (remainder > 0) 1 else 0
            remainder--
            if (start < list.size) {
                val end = minOf(start + currentChunkSize, list.size)
                result.add(list.subList(start, end))
                start = end
            }
        }
        return result
    }

    private fun <T> getComplement(parts: List<List<T>>, excludeIndex: Int): List<T> {
        val result = mutableListOf<T>()
        for (i in parts.indices) {
            if (i != excludeIndex) {
                result.addAll(parts[i])
            }
        }
        return result
    }
}