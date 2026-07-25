package io.github.xyzboom.aiFuzzer.pattern

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Pattern 匹配器的测试。
 *
 * 模拟生成器逐个生成节点，验证 pattern 匹配的正确性。
 */
class PatternMatcherTest {

    // ===== 辅助函数 =====

    private fun shapeOf(vararg dims: Int): UirShape = buildShape {
        dims.forEach { v ->
            this.dims.add(buildDim {
                dimKind = UirDimKind.CONSTANT
                value = v
            })
        }
    }

    private fun tensorType(vararg dims: Int): UirTensorType = buildTensorType {
        typeKind = UirTypeKind.TENSOR
        shape = shapeOf(*dims)
        dtype = buildDataType { name = "float32"; bits = 32 }
    }

    /** 构建一个模拟的 UirNode */
    private fun mockNode(
        name: String,
        op: UirOpKind,
        inputShapes: List<UirShape>,
        outputShapes: List<UirShape>,
        attrs: Map<String, Any> = emptyMap(),
    ): UirNode {
        val float32 = buildDataType { this.name = "float32"; this.bits = 32 }
        return buildNode {
            this.name = name
            this.op = op
            for ((i, shape) in inputShapes.withIndex()) {
                inputs.add(buildValueRef {
                    valueId = "v_in_${name}_${i}"
                    type = buildTensorType {
                        typeKind = UirTypeKind.TENSOR
                        this.shape = shape
                        this.dtype = float32
                    }
                })
            }
            for ((i, shape) in outputShapes.withIndex()) {
                outputs.add(buildValueRef {
                    valueId = "v_out_${name}_${i}"
                    type = buildTensorType {
                        typeKind = UirTypeKind.TENSOR
                        this.shape = shape
                        this.dtype = float32
                    }
                })
            }
            this.attributes = attrs.mapValues { (_, v) ->
                when (v) {
                    is Int -> buildIntAttr { value = v }
                    is String -> buildStringAttr { value = v }
                    is Long -> buildIntAttr { value = v.toInt() }
                    else -> buildStringAttr { value = v.toString() }
                }
            }.toMutableMap()
        }
    }

    /**
     * Pattern 数据库 JSON。
     * 使用 raw string 避免 $ 转义问题。
     */
    private val patternJson = """{
  "format_version": "1.0",
  "patterns": [
    {
      "id": "tvm-20047",
      "compiler": "tvm",
      "target": "cuda",
      "description": "conv2d dlight FloorDiv gemv assertion",
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
    },
    {
      "id": "tvm-20048",
      "compiler": "tvm",
      "target": "cuda",
      "description": "conv2d dlight reduction bind",
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
          "shape": [{"${'$'}gte": 2}, {"${'$'}any": true}, {"${'$'}gte": 4}, {"${'$'}gte": 18}],
          "dtype": "float32"
        },
        {
          "match": "value", "id": "v_weight",
          "ndim": 4,
          "shape": [{"${'$'}eq": 1}, {"${'$'}any": true}, {"${'$'}gte": 2}, {"${'$'}eq": 1}],
          "dtype": "float32"
        }
      ]
    },
    {
      "id": "tvm-20036",
      "compiler": "tvm",
      "target": "cuda",
      "description": "batch_norm CUDA buffer_red",
      "severity": "crash",
      "nodes": [
        {
          "match": "node", "id": "n0", "op": "BATCH_NORM",
          "inputs": ["v_in", "v_gamma", "v_beta", "v_mean", "v_var"],
          "outputs": ["v_out"],
          "attrs": {}
        }
      ],
      "values": [
        {
          "match": "value", "id": "v_in",
          "ndim": 3,
          "shape": [{"${'$'}eq": 1}, {"${'$'}any": true}, {"${'$'}eq": 1}],
          "dtype": "float32"
        }
      ]
    }
  ]
}"""

    private lateinit var database: PatternDatabase
    private lateinit var matcher: PatternMatcher

    @BeforeEach
    fun setUp() {
        database = PatternParser.parse(patternJson)
        matcher = PatternMatcher(database, compiler = "tvm", target = "cuda")
    }

    // ===== 测试: 单算子 pattern 匹配 =====

    @Test
    fun `single op pattern should match when shape matches exactly`() {
        // TVM #20047: conv2d([1,1,3,10], [1,1,1,2]) on CUDA
        val node = mockNode(
            name = "conv2d_0",
            op = UirOpKind.CONV2D,
            inputShapes = listOf(shapeOf(1, 1, 3, 10), shapeOf(1, 1, 1, 2)),
            outputShapes = listOf(shapeOf(1, 1, 3, 9)),
            attrs = mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1),
        )
        val resolver: (String) -> UirValueRef? = { vid ->
            node.inputs.find { it.valueId == vid } ?: node.outputs.find { it.valueId == vid }
        }
        val result = matcher.onNodeGenerated(node, resolver)
        assertNotNull(result, "Pattern #20047 should match for conv2d([1,1,3,10], [1,1,1,2])")
        assertEquals("tvm-20047", result?.id)
    }

    @Test
    fun `single op pattern should not match when shape is different`() {
        val node = mockNode(
            name = "conv2d_0",
            op = UirOpKind.CONV2D,
            inputShapes = listOf(shapeOf(1, 3, 6, 6), shapeOf(4, 3, 3, 3)),
            outputShapes = listOf(shapeOf(1, 4, 4, 4)),
            attrs = mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1),
        )
        val resolver: (String) -> UirValueRef? = { vid ->
            node.inputs.find { it.valueId == vid } ?: node.outputs.find { it.valueId == vid }
        }
        val result = matcher.onNodeGenerated(node, resolver)
        assertNull(result, "Pattern #20047 should NOT match for different shape")
    }

    @Test
    fun `single op pattern should not match when op is different`() {
        val node = mockNode(
            name = "relu_0",
            op = UirOpKind.RELU,
            inputShapes = listOf(shapeOf(1, 1, 3, 10)),
            outputShapes = listOf(shapeOf(1, 1, 3, 10)),
        )
        val resolver: (String) -> UirValueRef? = { vid ->
            node.inputs.find { it.valueId == vid } ?: node.outputs.find { it.valueId == vid }
        }
        val result = matcher.onNodeGenerated(node, resolver)
        assertNull(result, "No pattern should match for RELU")
    }

    @Test
    fun `single op pattern should match with boundary shapes`() {
        // #20048: N>=2, H>=4, W>=18, KH>=2, KW=1
        val node = mockNode(
            name = "conv2d_0",
            op = UirOpKind.CONV2D,
            inputShapes = listOf(shapeOf(2, 1, 4, 20), shapeOf(1, 1, 3, 1)),
            outputShapes = listOf(shapeOf(2, 1, 2, 20)),
            attrs = mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1),
        )
        val resolver: (String) -> UirValueRef? = { vid ->
            node.inputs.find { it.valueId == vid } ?: node.outputs.find { it.valueId == vid }
        }
        val result = matcher.onNodeGenerated(node, resolver)
        assertNotNull(result, "Pattern #20048 should match for conv2d([2,1,4,20], [1,1,3,1])")
        assertEquals("tvm-20048", result?.id)
    }

    @Test
    fun `single op pattern with range should not match below threshold`() {
        // #20048: N>=2, but N=1 here
        val node = mockNode(
            name = "conv2d_0",
            op = UirOpKind.CONV2D,
            inputShapes = listOf(shapeOf(1, 1, 4, 20), shapeOf(1, 1, 3, 1)),
            outputShapes = listOf(shapeOf(1, 1, 2, 20)),
            attrs = mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1),
        )
        val resolver: (String) -> UirValueRef? = { vid ->
            node.inputs.find { it.valueId == vid } ?: node.outputs.find { it.valueId == vid }
        }
        val result = matcher.onNodeGenerated(node, resolver)
        assertNull(result, "#20048 should NOT match when N=1 (below threshold)")
    }

    @Test
    fun `batch_norm pattern should match with trigger shape`() {
        // #20036: batch_norm on [1,N,1]
        val node = mockNode(
            name = "batch_norm_0",
            op = UirOpKind.BATCH_NORM,
            inputShapes = listOf(shapeOf(1, 2, 1), shapeOf(2), shapeOf(2), shapeOf(2), shapeOf(2)),
            outputShapes = listOf(shapeOf(1, 2, 1)),
        )
        val resolver: (String) -> UirValueRef? = { vid ->
            if (vid == "v_in") node.inputs[0] else null
        }
        val result = matcher.onNodeGenerated(node, resolver)
        assertNotNull(result, "#20036 should match for batch_norm([1,2,1])")
        assertEquals("tvm-20036", result?.id)
    }

    @Test
    fun `batch_norm pattern should not match when batch != 1`() {
        val node = mockNode(
            name = "batch_norm_0",
            op = UirOpKind.BATCH_NORM,
            inputShapes = listOf(shapeOf(2, 2, 1), shapeOf(2), shapeOf(2), shapeOf(2), shapeOf(2)),
            outputShapes = listOf(shapeOf(2, 2, 1)),
        )
        val resolver: (String) -> UirValueRef? = { vid ->
            if (vid == "v_in") node.inputs[0] else null
        }
        val result = matcher.onNodeGenerated(node, resolver)
        assertNull(result, "#20036 should NOT match when batch=2")
    }

    // ===== 测试: 多算子匹配 =====

    @Test
    fun `single-op pattern should match on first conv2d`() {
        // #20047 是单算子 pattern，匹配 CONV2D
        val node = mockNode(
            name = "conv2d_0",
            op = UirOpKind.CONV2D,
            inputShapes = listOf(shapeOf(1, 1, 3, 10), shapeOf(1, 1, 1, 2)),
            outputShapes = listOf(shapeOf(1, 1, 3, 9)),
            attrs = mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1),
        )
        val resolver: (String) -> UirValueRef? = { vid ->
            node.inputs.find { it.valueId == vid } ?: node.outputs.find { it.valueId == vid }
        }
        val result = matcher.onNodeGenerated(node, resolver)
        assertNotNull(result, "Single-op pattern #20047 should match")
        assertEquals("tvm-20047", result?.id)
    }

    @Test
    fun `non-matching ops should not trigger any pattern`() {
        val ops = listOf(
            UirOpKind.RELU to shapeOf(1, 3, 6, 6),
            UirOpKind.SIGMOID to shapeOf(1, 3, 6, 6),
            UirOpKind.TANH to shapeOf(1, 3, 6, 6),
            UirOpKind.SOFTMAX to shapeOf(1, 3, 6, 6),
        )
        for ((op, shape) in ops) {
            matcher.reset()
            val node = mockNode(
                name = "${op.name.lowercase()}_0",
                op = op,
                inputShapes = listOf(shape),
                outputShapes = listOf(shape),
            )
            val resolver: (String) -> UirValueRef? = { vid ->
                node.inputs.find { it.valueId == vid } ?: node.outputs.find { it.valueId == vid }
            }
            val result = matcher.onNodeGenerated(node, resolver)
            assertNull(result, "No pattern should match for ${op.name}")
        }
    }

    // ===== 测试: 生成器集成模拟 =====

    @Test
    fun `generator simulation should avoid known bug patterns`() {
        // 模拟生成器：生成 conv2d → 匹配 #20047 → 重新生成
        matcher.reset()

        val node = mockNode(
            name = "conv2d_0",
            op = UirOpKind.CONV2D,
            inputShapes = listOf(shapeOf(1, 1, 3, 10), shapeOf(1, 1, 1, 2)),
            outputShapes = listOf(shapeOf(1, 1, 3, 9)),
            attrs = mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1),
        )
        val resolver: (String) -> UirValueRef? = { vid ->
            node.inputs.find { it.valueId == vid } ?: node.outputs.find { it.valueId == vid }
        }
        val matched = matcher.onNodeGenerated(node, resolver)
        assertNotNull(matched, "Should match #20047")

        // 重新生成（改为不同的形状）
        matcher.reset()
        val safeNode = mockNode(
            name = "conv2d_0_retry",
            op = UirOpKind.CONV2D,
            inputShapes = listOf(shapeOf(1, 3, 6, 6), shapeOf(4, 3, 3, 3)),
            outputShapes = listOf(shapeOf(1, 4, 4, 4)),
            attrs = mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1),
        )
        val safeResolver: (String) -> UirValueRef? = { vid ->
            safeNode.inputs.find { it.valueId == vid } ?: safeNode.outputs.find { it.valueId == vid }
        }
        val safeResult = matcher.onNodeGenerated(safeNode, safeResolver)
        assertNull(safeResult, "Safe conv2d should not match any pattern")
    }

    // ===== 测试: pattern 解析 =====

    @Test
    fun `pattern database should parse correctly`() {
        assertEquals(3, database.patterns.size)
        assertEquals("tvm-20047", database.patterns[0].id)
        assertEquals("tvm-20048", database.patterns[1].id)
        assertEquals("tvm-20036", database.patterns[2].id)
    }

    @Test
    fun `filter should return only matching patterns`() {
        val tvmPatterns = database.filter(compiler = "tvm")
        assertEquals(3, tvmPatterns.size)

        val cudaPatterns = database.filter(compiler = "tvm", target = "cuda")
        assertEquals(3, cudaPatterns.size)

        val pytorchPatterns = database.filter(compiler = "pytorch")
        assertEquals(0, pytorchPatterns.size)
    }
}