package io.github.xyzboom.aiFuzzer.reducer

import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirValueRef

/**
 * UIR 的依赖图，封装了节点间的 def-use 关系查询。
 *
 * UIR 是计算图 IR，每个 [UirNode] 的 inputs/outputs 通过 [UirValueRef.valueId]
 * 形成 def-use 链。本类提供前向/后向遍历和可达性分析。
 */
class UirDependencyGraph(private val graph: UirGraph) {

    private val producerMap: Map<String, UirNode>
    private val consumerMap: Map<String, List<UirNode>>
    private val protectedValueIds: Set<String>

    init {
        val producers = mutableMapOf<String, UirNode>()
        for (node in graph.nodes) {
            for (out in node.outputs) {
                producers[out.valueId] = node
            }
        }
        val consumers = mutableMapOf<String, MutableList<UirNode>>()
        for (node in graph.nodes) {
            for (input in node.inputs) {
                consumers.getOrPut(input.valueId) { mutableListOf() }.add(node)
            }
        }
        producerMap = producers
        consumerMap = consumers
        protectedValueIds = (graph.inputs.map { it.valueId } + graph.outputs.map { it.valueId }).toSet()
    }

    fun producerOf(valueId: String): UirNode? = producerMap[valueId]
    fun consumersOf(valueId: String): List<UirNode> = consumerMap[valueId] ?: emptyList()

    fun downstreamOf(node: UirNode): Set<UirNode> =
        node.outputs.flatMap { consumersOf(it.valueId) }.toSet()

    fun upstreamOf(node: UirNode): Set<UirNode> =
        node.inputs.mapNotNull { producerOf(it.valueId) }.toSet()

    fun isProtected(valueId: String): Boolean = valueId in protectedValueIds

    /**
     * 从图 outputs 反向遍历，返回所有可达节点。
     */
    fun reachableNodes(): Set<UirNode> {
        val visited = mutableSetOf<UirNode>()
        val worklist = ArrayDeque<UirNode>()

        for (outputRef in graph.outputs) {
            val producer = producerOf(outputRef.valueId)
            if (producer != null) worklist.add(producer)
        }
        for (inputRef in graph.inputs) {
            worklist.addAll(consumersOf(inputRef.valueId))
        }

        while (worklist.isNotEmpty()) {
            val node = worklist.removeFirst()
            if (node in visited) continue
            visited.add(node)
            for (inputRef in node.inputs) {
                val producer = producerOf(inputRef.valueId)
                if (producer != null && producer !in visited) {
                    worklist.add(producer)
                }
            }
        }
        return visited
    }

    /**
     * 删除节点后，返回所有需要级联删除的下游节点。
     */
    fun cascadingDeletions(removedNode: UirNode): Set<UirNode> {
        val removed = mutableSetOf<UirNode>()
        val worklist = ArrayDeque<UirNode>()
        worklist.add(removedNode)

        while (worklist.isNotEmpty()) {
            val node = worklist.removeFirst()
            if (node in removed) continue
            removed.add(node)
            for (downstream in downstreamOf(node)) {
                if (downstream in removed) continue
                val remainingInputs = downstream.inputs.filter { input ->
                    val producer = producerOf(input.valueId)
                    producer != null && producer !in removed
                }
                if (remainingInputs.isEmpty()) {
                    worklist.add(downstream)
                }
            }
        }
        return removed
    }
}