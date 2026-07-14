package io.github.xyzboom.aiFuzzer.reducer

import io.github.xyzboom.aiFuzzer.ir.UirGraph
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirOpKind

/**
 * 死代码消除器：删除图中不可达的节点。
 *
 * 从 graph.outputs 反向遍历，标记所有可达节点。
 * 不在可达路径上的节点标记为 dead code 并删除。
 */
object DeadCodeEliminator {

    /**
     * 删除 [graph] 中所有不可达节点（原地修改）。
     */
    fun eliminate(graph: UirGraph, dependencyGraph: UirDependencyGraph): List<UirNode> {
        val reachable = dependencyGraph.reachableNodes()
        val removed = mutableListOf<UirNode>()
        val iterator = graph.nodes.iterator()
        while (iterator.hasNext()) {
            val node = iterator.next()
            if (node !in reachable) {
                removed.add(node)
                iterator.remove()
            }
        }
        return removed
    }

    /**
     * 迭代消除直到不动点。
     */
    fun eliminateToFixpoint(graph: UirGraph): List<UirNode> {
        val allRemoved = mutableListOf<UirNode>()
        while (true) {
            val depGraph = UirDependencyGraph(graph)
            val removed = eliminate(graph, depGraph)
            if (removed.isEmpty()) break
            allRemoved.addAll(removed)
        }
        return allRemoved
    }
}