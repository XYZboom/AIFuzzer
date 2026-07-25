package io.github.xyzboom.aiFuzzer.pattern

import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.types.UirIntAttr
import io.github.xyzboom.aiFuzzer.ir.types.UirStringAttr
import io.github.xyzboom.aiFuzzer.ir.Attribute

/**
 * Pattern 匹配器。
 *
 * 维护活跃 pattern 集合（Aho-Corasick 风格的前缀树追踪），
 * 每生成一个节点就检查一次，只检查当前活跃的 pattern 和所有 pattern 的首节点。
 *
 * 匹配规则：
 * 1. 只匹配 op 类型（不匹配名称）
 * 2. 只有完整匹配（所有节点 + 所有值的约束）才触发
 * 3. 前缀匹配只用于剪枝，不触发
 * 4. 值的约束按位置匹配（pattern 中第 i 个 input 对应实际节点的第 i 个 input），不按 ID 匹配
 */
class PatternMatcher(
    private val database: PatternDatabase,
    compiler: String? = null,
    target: String? = null,
) {
    private val activePatterns = mutableMapOf<PatternDef, ActivePattern>()
    private val patterns: List<PatternDef> = database.filter(compiler, target)
    private val singleOpPatterns: Map<String, List<PatternDef>> =
        patterns.filter { it.nodes.size == 1 }.groupBy { it.nodes[0].op }
    private val multiOpPatterns: List<PatternDef> =
        patterns.filter { it.nodes.size > 1 }

    var matchCount = 0; private set
    var totalNodesChecked = 0; private set

    private data class ActivePattern(
        val matchedIndex: Int,
        val matchedNodes: List<UirNode>,
    )

    /**
     * 当一个新节点生成后调用。
     * @param node 刚生成的节点
     * @param valueResolver 用于根据 valueId 查找实际 shape 信息的函数（备用，优先按位置匹配）
     * @return 匹配到的 pattern，或者 null（无匹配）
     */
    fun onNodeGenerated(
        node: UirNode,
        valueResolver: (String) -> UirValueRef?
    ): PatternDef? {
        totalNodesChecked++

        val newActive = mutableMapOf<PatternDef, ActivePattern>()
        for ((pattern, active) in activePatterns) {
            val nextIdx = active.matchedIndex + 1
            if (nextIdx >= pattern.nodes.size) continue

            if (matchNode(node, pattern.nodes[nextIdx])) {
                val allNodes = active.matchedNodes + node
                if (nextIdx == pattern.nodes.size - 1) {
                    if (checkAllValueConstraints(pattern, allNodes, valueResolver)) {
                        matchCount++
                        return pattern
                    }
                } else {
                    newActive[pattern] = ActivePattern(nextIdx, allNodes)
                }
            }
        }

        for (pattern in multiOpPatterns) {
            if (matchNode(node, pattern.nodes[0])) {
                val existing = newActive[pattern]
                if (existing == null || existing.matchedIndex < 0) {
                    newActive[pattern] = ActivePattern(0, listOf(node))
                }
            }
        }

        val singlePatterns = singleOpPatterns[node.op.name]
        if (singlePatterns != null) {
            for (pattern in singlePatterns) {
                if (checkAllValueConstraints(pattern, listOf(node), valueResolver)) {
                    matchCount++
                    return pattern
                }
            }
        }

        activePatterns.clear()
        activePatterns.putAll(newActive)
        return null
    }

    fun reset() { activePatterns.clear() }

    // ===== 匹配逻辑 =====

    private fun matchNode(node: UirNode, patternNode: PatternNodeDef): Boolean {
        if (node.op.name != patternNode.op) return false
        for ((key, matcher) in patternNode.attrs) {
            val actualAttr = node.attributes[key]
            if (actualAttr == null) {
                if (matcher !is AttrMatcher.AnyAttr) return false
                continue
            }
            val actualValue = when (actualAttr) {
                is UirIntAttr -> actualAttr.value
                is UirStringAttr -> actualAttr.value
                else -> actualAttr.toString()
            }
            if (!matcher.matches(actualValue)) return false
        }
        if (patternNode.inputs.size != node.inputs.size) return false
        if (patternNode.outputs.size != node.outputs.size) return false
        return true
    }

    /**
     * 检查所有值的约束条件。
     *
     * 匹配策略：**按位置匹配**。
     * pattern 中节点 n0 的第 i 个 input → 对应实际 nodes[0] 的第 i 个 input。
     * valueId 仅作为 pattern 内引用的键，不参与实际匹配。
     */
    private fun checkAllValueConstraints(
        pattern: PatternDef,
        nodes: List<UirNode>,
        valueResolver: (String) -> UirValueRef?
    ): Boolean {
        for ((valueId, patternValue) in pattern.values) {
            val actualRef = findActualRefByPosition(valueId, pattern, nodes, valueResolver)
            if (actualRef == null) {
                if (patternValue.shape.all { it is DimMatcher.Any } &&
                    patternValue.dtype is DtypeMatcher.AnyDtype &&
                    (patternValue.ndim == null || patternValue.ndim is DimMatcher.Any)) {
                    continue
                }
                return false
            }

            if (patternValue.ndim != null && !(patternValue.ndim is DimMatcher.Any)) {
                if (!patternValue.ndim.matches(actualRef.type.shape.dims.size)) return false
            }

            val actualDims = actualRef.type.shape.dims
            if (patternValue.shape.size > actualDims.size) return false
            for (i in patternValue.shape.indices) {
                if (i >= actualDims.size) {
                    if (!(patternValue.shape[i] is DimMatcher.Any)) return false
                    continue
                }
                if (!patternValue.shape[i].matches(actualDims[i].value)) return false
            }

            if (patternValue.dtype !is DtypeMatcher.AnyDtype) {
                val actualDtype = actualRef.type.dtype
                if (!patternValue.dtype.matches(actualDtype.name, actualDtype.bits)) return false
            }
        }
        return true
    }

    /**
     * 按位置查找 valueId 对应的实际值引用。
     *
     * 策略：遍历 pattern 的所有节点，找到含有该 valueId 的 input/output 位置，
     * 然后从实际 nodes 的对应位置取值。
     */
    private fun findActualRefByPosition(
        valueId: String,
        pattern: PatternDef,
        nodes: List<UirNode>,
        valueResolver: (String) -> UirValueRef?
    ): UirValueRef? {
        for ((pNodeIdx, pNode) in pattern.nodes.withIndex()) {
            if (pNodeIdx >= nodes.size) continue
            val actualNode = nodes[pNodeIdx]

            // 在 pattern 节点的 inputs 中按位置查找
            val inputPos = pNode.inputs.indexOf(valueId)
            if (inputPos >= 0 && inputPos < actualNode.inputs.size) {
                return actualNode.inputs[inputPos]
            }

            // 在 pattern 节点的 outputs 中按位置查找
            val outputPos = pNode.outputs.indexOf(valueId)
            if (outputPos >= 0 && outputPos < actualNode.outputs.size) {
                return actualNode.outputs[outputPos]
            }
        }
        return valueResolver(valueId)
    }
}