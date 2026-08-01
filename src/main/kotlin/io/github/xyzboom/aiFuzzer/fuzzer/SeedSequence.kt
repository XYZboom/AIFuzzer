package io.github.xyzboom.aiFuzzer.fuzzer

import java.io.File

/**
 * 种子序列：抽象的种子来源。
 *
 * 支持两种模式：
 * - [Range]: 从 startSeed 开始的连续整数（startSeed, startSeed+1, ..., startSeed+size-1）
 * - [Explicit]: 从文件中读取的明确定义列表
 *
 * 两个 CLI 子命令（fuzz、dedup-eval）共享此逻辑。
 */
sealed class SeedSequence {
    /** 序列长度 */
    abstract val size: Int

    /** 获取第 index 个种子值 */
    abstract operator fun get(index: Int): Long

    companion object {
        /**
         * 从 startSeed 开始，连续 count 个种子。
         */
        fun range(startSeed: Long, count: Int): SeedSequence =
            Range(startSeed, count)

        /**
         * 从文件中读取种子列表（每行一个整数，空白行和 # 开头的行为注释）。
         *
         * @param file 种子文件
         * @param cap 最大种子数，超过时截取前 cap 个（默认 Int.MAX_VALUE 表示不截取）
         */
        fun fromFile(file: File, cap: Int = Int.MAX_VALUE): SeedSequence {
            val seeds = file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map {
                    it.toLongOrNull()
                        ?: error("无效的种子值: '$it' 在文件 ${file.name}")
                }
            val limited = if (cap < seeds.size) seeds.take(cap) else seeds
            return Explicit(limited, file)
        }
    }

    data class Range(
        val startSeed: Long,
        override val size: Int,
    ) : SeedSequence() {
        override fun get(index: Int): Long {
            require(index in 0 until size) { "索引越界: $index >= $size" }
            return startSeed + index
        }

        override fun toString(): String = "Range(start=$startSeed, count=$size)"
    }

    data class Explicit(
        val seeds: List<Long>,
        val sourceFile: File? = null,
    ) : SeedSequence() {
        override val size: Int get() = seeds.size

        override fun get(index: Int): Long = seeds[index]

        override fun toString(): String {
            val prefix = seeds.take(5)
            val suffix = if (seeds.size > 5) ", ..." else ""
            return if (sourceFile != null) {
                "Explicit(file=${sourceFile.name}, seeds=${prefix.joinToString(",")}" +
                    "$suffix, total=${seeds.size})"
            } else {
                "Explicit(seeds=${prefix.joinToString(",")}$suffix, total=${seeds.size})"
            }
        }
    }
}