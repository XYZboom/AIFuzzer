package io.github.xyzboom.aiFuzzer.generator

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import io.github.xyzboom.aiFuzzer.pattern.PatternDatabase
import io.github.xyzboom.aiFuzzer.pattern.PatternParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * 验证 UirGenerator 的去重重试机制。
 *
 * Mock 策略：继承 UirGenerator，重写 generateNode() 控制输出，
 * 验证当生成匹配已知 bug pattern 的节点时，自动触发重试。
 */
class DedupIntegrationTest {

    private val patternJson = """{
  "format_version": "1.0",
  "patterns": [
    {
      "id": "tvm-20047",
      "compiler": "tvm",
      "target": "cuda",
      "description": "conv2d dlight FloorDiv gemv",
      "severity": "crash",
      "nodes": [
        {
          "match": "node", "id": "n0", "op": "CONV2D",
          "inputs": ["v_input", "v_weight"],
          "outputs": ["v_out"],
          "attrs": {"stride": 1, "padding": 0, "dilation": 1, "groups": 1}
        }
      ],
      "values": [
        {
          "match": "value", "id": "v_input",
          "ndim": 4,
          "shape": [{"${'$'}any": true}, {"${'$'}eq": 1}, {"${'$'}gte": 2}, {"${'$'}any": true}],
          "dtype": "float32"
        },
        {
          "match": "value", "id": "v_weight",
          "ndim": 4,
          "shape": [{"${'$'}eq": 1}, {"${'$'}eq": 1}, {"${'$'}eq": 1}, {"${'$'}eq": 2}],
          "dtype": "float32"
        }
      ]
    }
  ]
}"""

    private val database: PatternDatabase = PatternParser.parse(patternJson)

    private fun shapeOf(vararg dims: Int): UirShape = buildShape {
        dims.forEach { v -> this.dims.add(buildDim {
            dimKind = UirDimKind.CONSTANT; value = v
        })}
    }

    /** 构建匹配 pattern 的 conv2d 节点 */
    private fun conv2dNode(inputShape: UirShape, weightShape: UirShape): List<UirNode> {
        val float32 = buildDataType { this.name = "float32"; this.bits = 32 }
        val inputRef = buildValueRef {
            valueId = "v_input"  // 必须匹配 pattern 中的 value ID
            type = buildTensorType { typeKind = UirTypeKind.TENSOR; shape = inputShape; dtype = float32 }
        }
        val weightRef = buildValueRef {
            valueId = "v_weight"  // 必须匹配 pattern 中的 value ID
            type = buildTensorType { typeKind = UirTypeKind.TENSOR; shape = weightShape; dtype = float32 }
        }
        val outputRef = buildValueRef {
            valueId = "v_out"
            type = buildTensorType { typeKind = UirTypeKind.TENSOR; shape = shapeOf(1, 1, 3, 9); dtype = float32 }
        }
        val mainNode = buildNode {
            name = "conv2d_0"
            op = UirOpKind.CONV2D
            inputs.add(inputRef); inputs.add(weightRef)
            outputs.add(outputRef)
            attributes = mutableMapOf(
                "stride" to buildIntAttr { value = 1 },
                "padding" to buildIntAttr { value = 0 },
                "dilation" to buildIntAttr { value = 1 },
                "groups" to buildIntAttr { value = 1 },
            )
        }
        return listOf(mainNode)
    }

    /** Mock 生成器：第1次返回 bug 节点，后续返回安全节点 */
    private class MockGenerator(
        config: GeneratorConfig,
        val bugNode: () -> List<UirNode>,
        val safeNode: () -> List<UirNode>,
    ) : UirGenerator(config) {
        var callCount = 0
        override fun generateNode(
            nodeIndex: Int,
            availableValues: MutableList<String>,
            liveTips: Map<Int, String>,
            currentBranch: Int
        ): List<UirNode> {
            callCount++
            val nodes = if (callCount <= 1) bugNode() else safeNode()
            // 确保输出在 availableValues 中
            for (n in nodes) {
                for (o in n.outputs) {
                    if (o.valueId !in availableValues) {
                        availableValues.add(o.valueId)
                    }
                }
            }
            return nodes
        }
    }

    @Test
    fun `generator should retry when node matches known bug pattern`() {
        // bug 形状: conv2d([1,1,3,10], [1,1,1,2]) 匹配 #20047
        val bugShape = shapeOf(1, 1, 3, 10)
        val bugWeight = shapeOf(1, 1, 1, 2)
        // 安全形状: 不匹配任何 pattern
        val safeShape = shapeOf(1, 3, 6, 6)
        val safeWeight = shapeOf(4, 3, 3, 3)

        val config = GeneratorConfig(
            seed = 42,
            minNodesPerGraph = 1,
            maxNodesPerGraph = 1,
            graphCount = 1..1,
            minInputs = 2,
            maxInputs = 2,
            dedup = DedupConfig(
                enabled = true,
                patternDatabase = database,
                compiler = "tvm",
                target = "cuda",
                maxRetries = 3,
            ),
        )

        val gen = MockGenerator(
            config = config,
            bugNode = { conv2dNode(bugShape, bugWeight) },
            safeNode = { conv2dNode(safeShape, safeWeight) },
        )

        val program = gen.generate()
        val node = program.graphs.first().nodes.first()

        // 验证最终节点是安全的（不匹配 pattern）
        assertEquals(4, node.inputs[1].type.shape.dims[0].value!!, "权重应为 4x3x3x3")
        assertEquals(2, gen.callCount, "mock 被调用 2 次（1次bug + 1次安全）")
    }

    @Test
    fun `generator should not retry when node does not match`() {
        val safeShape = shapeOf(1, 3, 6, 6)
        val safeWeight = shapeOf(4, 3, 3, 3)

        val config = GeneratorConfig(
            seed = 42,
            minNodesPerGraph = 1,
            maxNodesPerGraph = 1,
            graphCount = 1..1,
            minInputs = 2,
            maxInputs = 2,
            dedup = DedupConfig(
                enabled = true,
                patternDatabase = database,
                compiler = "tvm",
                target = "cuda",
                maxRetries = 3,
            ),
        )

        val gen = MockGenerator(
            config = config,
            bugNode = { conv2dNode(safeShape, safeWeight) },
            safeNode = { conv2dNode(safeShape, safeWeight) },
        )

        gen.generate()
        assertEquals(1, gen.callCount, "安全节点应一次通过，无需重试")
    }

    @Test
    fun `generator should accept after max retries exhausted`() {
        val bugShape = shapeOf(1, 1, 3, 10)
        val bugWeight = shapeOf(1, 1, 1, 2)

        val config = GeneratorConfig(
            seed = 42,
            minNodesPerGraph = 1,
            maxNodesPerGraph = 1,
            graphCount = 1..1,
            minInputs = 2,
            maxInputs = 2,
            dedup = DedupConfig(
                enabled = true,
                patternDatabase = database,
                compiler = "tvm",
                target = "cuda",
                maxRetries = 3,  // 3次重试后接受
            ),
        )

        // 每次调用都返回 bug 节点
        val gen = MockGenerator(
            config = config,
            bugNode = { conv2dNode(bugShape, bugWeight) },
            safeNode = { conv2dNode(bugShape, bugWeight) },
        )

        gen.generate()
        assertEquals(3, gen.callCount, "重试3次耗尽后应接受")
        assertTrue(gen.callCount > 0, "即使匹配也应生成程序")
    }

    @Test
    fun `generator should not retry when dedup disabled`() {
        val bugShape = shapeOf(1, 1, 3, 10)
        val bugWeight = shapeOf(1, 1, 1, 2)

        val config = GeneratorConfig(
            seed = 42,
            minNodesPerGraph = 1,
            maxNodesPerGraph = 1,
            graphCount = 1..1,
            minInputs = 2,
            maxInputs = 2,
            dedup = DedupConfig(
                enabled = false,  // 去重关闭
                patternDatabase = database,
                compiler = "tvm",
                target = "cuda",
                maxRetries = 3,
            ),
        )

        val gen = MockGenerator(
            config = config,
            bugNode = { conv2dNode(bugShape, bugWeight) },
            safeNode = { conv2dNode(bugShape, bugWeight) },
        )

        gen.generate()
        assertEquals(1, gen.callCount, "去重关闭时不应重试")
    }
}