package io.github.xyzboom.aiFuzzer.pattern

import io.github.xyzboom.aiFuzzer.ir.*
import io.github.xyzboom.aiFuzzer.ir.builder.*
import io.github.xyzboom.aiFuzzer.ir.types.*
import io.github.xyzboom.aiFuzzer.ir.types.builder.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import java.io.File

/**
 * 对所有 20 个已知 bug pattern 的完整测试。
 *
 * 每个 pattern 测试三种情况：
 * 1. 正例：匹配 op + 触发形状 → 应该匹配
 * 2. 反例-不同算子：不同 op → 不匹配
 * 3. 反例-不同形状：匹配 op 但形状不触发 → 不匹配
 */
class AllPatternsTest {

    companion object {
        private val allPatterns: List<PatternDef> by lazy { loadAllPatterns() }

        private fun loadAllPatterns(): List<PatternDef> {
            val patternsDir = File("src/main/resources/patterns")
            val files = patternsDir.listFiles { f -> f.extension == "json" }
                ?: error("Patterns directory not found: ${patternsDir.absolutePath}")
            return files.flatMap { file ->
                val json = file.readText()
                val db = PatternParser.parse(json)
                db.patterns
            }.also { println("Loaded ${it.size} patterns from ${files.size} files") }
        }
    }

    // ===== 辅助函数 =====

    private fun shapeOf(vararg dims: Int): UirShape = buildShape {
        dims.forEach { v ->
            this.dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = v })
        }
    }

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
                    valueId = "v_in_${name}_$i"
                    type = buildTensorType {
                        typeKind = UirTypeKind.TENSOR; this.shape = shape; this.dtype = float32
                    }
                })
            }
            for ((i, shape) in outputShapes.withIndex()) {
                outputs.add(buildValueRef {
                    valueId = "v_out_${name}_$i"
                    type = buildTensorType {
                        typeKind = UirTypeKind.TENSOR; this.shape = shape; this.dtype = float32
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

    private fun resolverOf(node: UirNode): (String) -> UirValueRef? = { vid ->
        node.inputs.find { it.valueId == vid } ?: node.outputs.find { it.valueId == vid }
    }

    // ===== 测试 =====

    @Test
    fun `load all 20 patterns from resources`() {
        assertEquals(22, allPatterns.size)
        val ids = allPatterns.map { it.id }.sorted()
        println("Pattern IDs: $ids")
        assertEquals("onnx-8203", ids[0])
        assertEquals("tvm-20048", ids.last())
    }

    @Test
    fun `tvm-20015 avg_pool2d LLVM shufflevector`() {
        val pattern = allPatterns.first { it.id == "tvm-20015" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "tvm", "llvm")

        // 正例: C=4, H=6 even, W=3, kernel_size=2
        val pos = mockNode("avg_pool2d", UirOpKind.AVG_POOL2D,
            listOf(shapeOf(1, 4, 6, 3)), listOf(shapeOf(1, 4, 5, 2)),
            mapOf("kernel_size" to 2, "stride" to 1, "padding" to 0))
        assertNotNull(matcher.onNodeGenerated(pos, resolverOf(pos)), "tvm-20015 positive")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("max_pool2d", UirOpKind.MAX_POOL2D,
            listOf(shapeOf(1, 4, 6, 3)), listOf(shapeOf(1, 4, 5, 2)),
            mapOf("kernel_size" to 2, "stride" to 1, "padding" to 0))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "tvm-20015 diff op")

        // 反例-不同形状: C=3 (not 4)
        matcher.reset()
        val wrongC = mockNode("avg_pool2d", UirOpKind.AVG_POOL2D,
            listOf(shapeOf(1, 3, 6, 3)), listOf(shapeOf(1, 3, 5, 2)),
            mapOf("kernel_size" to 2, "stride" to 1, "padding" to 0))
        assertNull(matcher.onNodeGenerated(wrongC, resolverOf(wrongC)), "tvm-20015 C=3")

        // 反例-不同形状: W=4 (not 3)
        matcher.reset()
        val wrongW = mockNode("avg_pool2d", UirOpKind.AVG_POOL2D,
            listOf(shapeOf(1, 4, 6, 4)), listOf(shapeOf(1, 4, 5, 3)),
            mapOf("kernel_size" to 2, "stride" to 1, "padding" to 0))
        assertNull(matcher.onNodeGenerated(wrongW, resolverOf(wrongW)), "tvm-20015 W=4")

        // 反例-不同形状: H=5 odd (not even)
        matcher.reset()
        val oddH = mockNode("avg_pool2d", UirOpKind.AVG_POOL2D,
            listOf(shapeOf(1, 4, 5, 3)), listOf(shapeOf(1, 4, 4, 2)),
            mapOf("kernel_size" to 2, "stride" to 1, "padding" to 0))
        assertNull(matcher.onNodeGenerated(oddH, resolverOf(oddH)), "tvm-20015 H=5 odd")
    }

    @Test
    fun `tvm-20015-variant-silu-2d silu 2D shufflevector`() {
        val pattern = allPatterns.first { it.id == "tvm-20015-variant-silu-2d" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "tvm", "llvm")

        // 正例: 2D [4,5]
        val pos = mockNode("silu", UirOpKind.SILU,
            listOf(shapeOf(4, 5)), listOf(shapeOf(4, 5)))
        assertNotNull(matcher.onNodeGenerated(pos, resolverOf(pos)), "tvm-20015-variant-silu-2d positive [4,5]")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("relu", UirOpKind.RELU,
            listOf(shapeOf(4, 5)), listOf(shapeOf(4, 5)))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "tvm-20015-variant-silu-2d diff op")

        // 反例-不同ndim: 3D [4,5,2] (not 2D)
        matcher.reset()
        val ndim3 = mockNode("silu", UirOpKind.SILU,
            listOf(shapeOf(4, 5, 2)), listOf(shapeOf(4, 5, 2)))
        assertNull(matcher.onNodeGenerated(ndim3, resolverOf(ndim3)), "tvm-20015-variant-silu-2d 3D")
    }

    @Test
    fun `tvm-20015-variant-silu-3d silu 3D shufflevector`() {
        val pattern = allPatterns.first { it.id == "tvm-20015-variant-silu-3d" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "tvm", "llvm")

        // 正例: 3D [4,5,2]
        val pos = mockNode("silu", UirOpKind.SILU,
            listOf(shapeOf(4, 5, 2)), listOf(shapeOf(4, 5, 2)))
        assertNotNull(matcher.onNodeGenerated(pos, resolverOf(pos)), "tvm-20015-variant-silu-3d positive [4,5,2]")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("relu", UirOpKind.RELU,
            listOf(shapeOf(4, 5, 2)), listOf(shapeOf(4, 5, 2)))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "tvm-20015-variant-silu-3d diff op")

        // 反例-不同ndim: 2D [4,5] (not 3D)
        matcher.reset()
        val ndim2 = mockNode("silu", UirOpKind.SILU,
            listOf(shapeOf(4, 5)), listOf(shapeOf(4, 5)))
        assertNull(matcher.onNodeGenerated(ndim2, resolverOf(ndim2)), "tvm-20015-variant-silu-3d 2D")
    }

    @Test
    fun `tvm-20015-variant-silu-4d silu 4D shufflevector`() {
        val pattern = allPatterns.first { it.id == "tvm-20015-variant-silu-4d" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "tvm", "llvm")

        // 正例: 4D [4,5,2,1]
        val pos = mockNode("silu", UirOpKind.SILU,
            listOf(shapeOf(4, 5, 2, 1)), listOf(shapeOf(4, 5, 2, 1)))
        assertNotNull(matcher.onNodeGenerated(pos, resolverOf(pos)), "tvm-20015-variant-silu-4d positive [4,5,2,1]")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("relu", UirOpKind.RELU,
            listOf(shapeOf(4, 5, 2, 1)), listOf(shapeOf(4, 5, 2, 1)))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "tvm-20015-variant-silu-4d diff op")

        // 反例-不同ndim: 3D [4,5,2] (not 4D)
        matcher.reset()
        val ndim3 = mockNode("silu", UirOpKind.SILU,
            listOf(shapeOf(4, 5, 2)), listOf(shapeOf(4, 5, 2)))
        assertNull(matcher.onNodeGenerated(ndim3, resolverOf(ndim3)), "tvm-20015-variant-silu-4d 3D")
    }

    @Test
    fun `tvm-20015-variant-avgpool avg_pool2d LLVM shufflevector`() {
        val pattern = allPatterns.first { it.id == "tvm-20015-variant-avgpool" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "tvm", "llvm")

        // 正例: [4,1,10,4] — N=4, C=1, kernel=2, stride=2
        val pos = mockNode("avg_pool2d", UirOpKind.AVG_POOL2D,
            listOf(shapeOf(4, 1, 10, 4)), listOf(shapeOf(4, 1, 5, 2)),
            mapOf("kernel_size" to 2, "stride" to 2, "padding" to 0))
        assertNotNull(matcher.onNodeGenerated(pos, resolverOf(pos)), "tvm-20015-variant-avgpool positive")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("max_pool2d", UirOpKind.MAX_POOL2D,
            listOf(shapeOf(4, 1, 10, 4)), listOf(shapeOf(4, 1, 5, 2)),
            mapOf("kernel_size" to 2, "stride" to 2, "padding" to 0))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "tvm-20015-variant-avgpool diff op")

        // 正例: N=2 (any N now matches)
        matcher.reset()
        val n2 = mockNode("avg_pool2d", UirOpKind.AVG_POOL2D,
            listOf(shapeOf(2, 1, 10, 4)), listOf(shapeOf(2, 1, 5, 2)),
            mapOf("kernel_size" to 2, "stride" to 2, "padding" to 0))
        assertNotNull(matcher.onNodeGenerated(n2, resolverOf(n2)), "tvm-20015-variant-avgpool N=2")

        // 正例: N=1 (any N now matches)
        matcher.reset()
        val n1 = mockNode("avg_pool2d", UirOpKind.AVG_POOL2D,
            listOf(shapeOf(1, 1, 10, 4)), listOf(shapeOf(1, 1, 5, 2)),
            mapOf("kernel_size" to 2, "stride" to 2, "padding" to 0))
        assertNotNull(matcher.onNodeGenerated(n1, resolverOf(n1)), "tvm-20015-variant-avgpool N=1")
    }

    @Test
    fun `tvm-20015-variant-avgpool-stride1 avg_pool2d k=2 stride=1`() {
        val pattern = allPatterns.first { it.id == "tvm-20015-variant-avgpool-stride1" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "tvm", "llvm")

        // 正例: [1,4,3,6] — C=4, k=2, stride=1
        val pos = mockNode("avg_pool2d", UirOpKind.AVG_POOL2D,
            listOf(shapeOf(1, 4, 3, 6)), listOf(shapeOf(1, 4, 2, 5)),
            mapOf("kernel_size" to 2, "stride" to 1, "padding" to 0))
        assertNotNull(matcher.onNodeGenerated(pos, resolverOf(pos)), "tvm-20015-variant-avgpool-stride1 positive")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("max_pool2d", UirOpKind.MAX_POOL2D,
            listOf(shapeOf(1, 4, 3, 6)), listOf(shapeOf(1, 4, 2, 5)),
            mapOf("kernel_size" to 2, "stride" to 1, "padding" to 0))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "tvm-20015-variant-avgpool-stride1 diff op")

        // 反例-不同形状: C=3 (not 4)
        matcher.reset()
        val wrongC = mockNode("avg_pool2d", UirOpKind.AVG_POOL2D,
            listOf(shapeOf(1, 3, 3, 6)), listOf(shapeOf(1, 3, 2, 5)),
            mapOf("kernel_size" to 2, "stride" to 1, "padding" to 0))
        assertNull(matcher.onNodeGenerated(wrongC, resolverOf(wrongC)), "tvm-20015-variant-avgpool-stride1 C=3")

        // 反例-不同形状: 3D (ndim=3, not 4)
        matcher.reset()
        val ndim3 = mockNode("avg_pool2d", UirOpKind.AVG_POOL2D,
            listOf(shapeOf(4, 3, 6)), listOf(shapeOf(4, 2, 5)),
            mapOf("kernel_size" to 2, "stride" to 1, "padding" to 0))
        assertNull(matcher.onNodeGenerated(ndim3, resolverOf(ndim3)), "tvm-20015-variant-avgpool-stride1 3D")
    }

    @Test
    fun `tvm-20015-variant-silu-1d silu 1D shufflevector`() {
        val pattern = allPatterns.first { it.id == "tvm-20015-variant-silu-1d" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "tvm", "llvm")

        // 正例: [1] — 1D silu
        val pos = mockNode("silu", UirOpKind.SILU,
            listOf(shapeOf(1)), listOf(shapeOf(1)))
        assertNotNull(matcher.onNodeGenerated(pos, resolverOf(pos)), "tvm-20015-variant-silu-1d positive")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("relu", UirOpKind.RELU,
            listOf(shapeOf(1)), listOf(shapeOf(1)))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "tvm-20015-variant-silu-1d diff op")

        // 反例-不同形状: 2D [1,1] (ndim=2, not 1)
        matcher.reset()
        val ndim2 = mockNode("silu", UirOpKind.SILU,
            listOf(shapeOf(1, 1)), listOf(shapeOf(1, 1)))
        assertNull(matcher.onNodeGenerated(ndim2, resolverOf(ndim2)), "tvm-20015-variant-silu-1d 2D")
    }

    @Test
    fun `tvm-20036 batch_norm CUDA buffer_red`() {
        val pattern = allPatterns.first { it.id == "tvm-20036" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "tvm", "cuda")

        // 正例: [1, N, 1]
        val pos = mockNode("batch_norm", UirOpKind.BATCH_NORM,
            listOf(shapeOf(1, 2, 1), shapeOf(2), shapeOf(2), shapeOf(2), shapeOf(2)),
            listOf(shapeOf(1, 2, 1)))
        val resolver: (String) -> UirValueRef? = { if (it == "v_in") pos.inputs[0] else null }
        assertNotNull(matcher.onNodeGenerated(pos, resolver), "tvm-20036 positive")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("layer_norm", UirOpKind.LAYER_NORM,
            listOf(shapeOf(1, 2, 1)), listOf(shapeOf(1, 2, 1)))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "tvm-20036 diff op")

        // 反例-不同形状: batch=2
        matcher.reset()
        val batch2 = mockNode("batch_norm", UirOpKind.BATCH_NORM,
            listOf(shapeOf(2, 2, 1), shapeOf(2), shapeOf(2), shapeOf(2), shapeOf(2)),
            listOf(shapeOf(2, 2, 1)))
        val r2: (String) -> UirValueRef? = { if (it == "v_in") batch2.inputs[0] else null }
        assertNull(matcher.onNodeGenerated(batch2, r2), "tvm-20036 batch=2")

        // 反例-不同形状: last dim=2
        matcher.reset()
        val last2 = mockNode("batch_norm", UirOpKind.BATCH_NORM,
            listOf(shapeOf(1, 2, 2), shapeOf(2), shapeOf(2), shapeOf(2), shapeOf(2)),
            listOf(shapeOf(1, 2, 2)))
        val r3: (String) -> UirValueRef? = { if (it == "v_in") last2.inputs[0] else null }
        assertNull(matcher.onNodeGenerated(last2, r3), "tvm-20036 last dim=2")
    }

    @Test
    fun `tvm-20047 conv2d FloorDiv gemv`() {
        val pattern = allPatterns.first { it.id == "tvm-20047" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "tvm", "cuda")

        // 正例: [1,1,3,10] × [1,1,1,2]
        val pos = mockNode("conv2d", UirOpKind.CONV2D,
            listOf(shapeOf(1, 1, 3, 10), shapeOf(1, 1, 1, 2)),
            listOf(shapeOf(1, 1, 3, 9)),
            mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1))
        assertNotNull(matcher.onNodeGenerated(pos, resolverOf(pos)), "tvm-20047 positive")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("relu", UirOpKind.RELU,
            listOf(shapeOf(1, 1, 3, 10)), listOf(shapeOf(1, 1, 3, 10)))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "tvm-20047 diff op")

        // 反例-不同形状: input_channels=3 not 1
        matcher.reset()
        val ci3 = mockNode("conv2d", UirOpKind.CONV2D,
            listOf(shapeOf(1, 3, 3, 10), shapeOf(4, 3, 3, 3)),
            listOf(shapeOf(1, 4, 1, 8)),
            mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1))
        assertNull(matcher.onNodeGenerated(ci3, resolverOf(ci3)), "tvm-20047 in_ch=3")

        // 反例-不同形状: kernel_width=1 not 2
        matcher.reset()
        val kw1 = mockNode("conv2d", UirOpKind.CONV2D,
            listOf(shapeOf(1, 1, 3, 10), shapeOf(1, 1, 1, 1)),
            listOf(shapeOf(1, 1, 3, 10)),
            mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1))
        assertNull(matcher.onNodeGenerated(kw1, resolverOf(kw1)), "tvm-20047 kw=1")
    }

    @Test
    fun `tvm-20047-variant-assert conv2d AssertionError gemv`() {
        val pattern = allPatterns.first { it.id == "tvm-20047-variant-assert" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "tvm", "cuda")

        // 正例: [1,1,6,8] × [1,1,1,3] — N=1, C_in=1, C_out=1, H≥6, kW≥2
        val pos = mockNode("conv2d", UirOpKind.CONV2D,
            listOf(shapeOf(1, 1, 6, 8), shapeOf(1, 1, 1, 3)),
            listOf(shapeOf(1, 1, 6, 6)),
            mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1))
        assertNotNull(matcher.onNodeGenerated(pos, resolverOf(pos)), "tvm-20047-variant-assert positive")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("relu", UirOpKind.RELU,
            listOf(shapeOf(1, 1, 6, 8)), listOf(shapeOf(1, 1, 6, 8)))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "tvm-20047-variant-assert diff op")

        // 反例-不同形状: N=2 not 1
        matcher.reset()
        val n2 = mockNode("conv2d", UirOpKind.CONV2D,
            listOf(shapeOf(2, 1, 6, 8), shapeOf(1, 1, 1, 3)),
            listOf(shapeOf(2, 1, 6, 6)),
            mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1))
        assertNull(matcher.onNodeGenerated(n2, resolverOf(n2)), "tvm-20047-variant-assert N=2")

        // 反例-不同形状: H=4 (below 6)
        matcher.reset()
        val h4 = mockNode("conv2d", UirOpKind.CONV2D,
            listOf(shapeOf(1, 1, 4, 8), shapeOf(1, 1, 1, 3)),
            listOf(shapeOf(1, 1, 4, 6)),
            mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1))
        assertNull(matcher.onNodeGenerated(h4, resolverOf(h4)), "tvm-20047-variant-assert H=4")

        // 反例-不同形状: C_in=3 not 1
        matcher.reset()
        val ci3 = mockNode("conv2d", UirOpKind.CONV2D,
            listOf(shapeOf(1, 3, 6, 8), shapeOf(4, 3, 3, 3)),
            listOf(shapeOf(1, 4, 4, 6)),
            mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1))
        assertNull(matcher.onNodeGenerated(ci3, resolverOf(ci3)), "tvm-20047-variant-assert C_in=3")

        // 反例-不同形状: kernel W=1 (below 2)
        matcher.reset()
        val kw1 = mockNode("conv2d", UirOpKind.CONV2D,
            listOf(shapeOf(1, 1, 6, 8), shapeOf(1, 1, 1, 1)),
            listOf(shapeOf(1, 1, 6, 8)),
            mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1))
        assertNull(matcher.onNodeGenerated(kw1, resolverOf(kw1)), "tvm-20047-variant-assert kW=1")
    }

    @Test
    fun `tvm-20048 conv2d reduction bind`() {
        val pattern = allPatterns.first { it.id == "tvm-20048" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "tvm", "cuda")

        // 正例: [2,1,4,20] × [1,1,3,1]
        val pos = mockNode("conv2d", UirOpKind.CONV2D,
            listOf(shapeOf(2, 1, 4, 20), shapeOf(1, 1, 3, 1)),
            listOf(shapeOf(2, 1, 2, 20)),
            mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1))
        assertNotNull(matcher.onNodeGenerated(pos, resolverOf(pos)), "tvm-20048 positive")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("avg_pool2d", UirOpKind.AVG_POOL2D,
            listOf(shapeOf(2, 1, 4, 20)), listOf(shapeOf(2, 1, 2, 20)),
            mapOf("kernel_size" to 2, "stride" to 1, "padding" to 0))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "tvm-20048 diff op")

        // 反例-不同形状: N=1 (below threshold)
        matcher.reset()
        val n1 = mockNode("conv2d", UirOpKind.CONV2D,
            listOf(shapeOf(1, 1, 4, 20), shapeOf(1, 1, 3, 1)),
            listOf(shapeOf(1, 1, 2, 20)),
            mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1))
        assertNull(matcher.onNodeGenerated(n1, resolverOf(n1)), "tvm-20048 N=1")

        // 反例-不同形状: W=16 (below 18)
        matcher.reset()
        val w16 = mockNode("conv2d", UirOpKind.CONV2D,
            listOf(shapeOf(2, 1, 4, 16), shapeOf(1, 1, 3, 1)),
            listOf(shapeOf(2, 1, 2, 16)),
            mapOf("stride" to 1, "padding" to 0, "dilation" to 1, "groups" to 1))
        assertNull(matcher.onNodeGenerated(w16, resolverOf(w16)), "tvm-20048 W=16")
    }

    @Test
    fun `pt-189787 softmax-sum-floor`() {
        val pattern = allPatterns.first { it.id == "pt-189787" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "pytorch", "inductor")

        // 正例: 链式匹配3个节点
        val softmax = mockNode("softmax", UirOpKind.SOFTMAX,
            listOf(shapeOf(43, 59)), listOf(shapeOf(43, 59)))
        val r1 = matcher.onNodeGenerated(softmax, resolverOf(softmax))
        assertNull(r1, "pt-189787 should not match on first node only")

        val sum = mockNode("sum", UirOpKind.REDUCE_SUM,
            listOf(shapeOf(43, 59)), listOf(shapeOf(43)),
            mapOf("axis" to -1, "keepdims" to 0))
        val r2 = matcher.onNodeGenerated(sum, resolverOf(sum))
        assertNull(r2, "pt-189787 should not match on second node")

        val floor = mockNode("floor", UirOpKind.FLOOR,
            listOf(shapeOf(43)), listOf(shapeOf(43)))
        val r3 = matcher.onNodeGenerated(floor, resolverOf(floor))
        assertNotNull(r3, "pt-189787 should match on complete chain")
        assertEquals("pt-189787", r3?.id)

        // 反例: softmax → sum → relu (not floor)
        matcher.reset()
        matcher.onNodeGenerated(mockNode("softmax", UirOpKind.SOFTMAX,
            listOf(shapeOf(43, 59)), listOf(shapeOf(43, 59))), resolverOf(softmax))
        matcher.onNodeGenerated(mockNode("sum", UirOpKind.REDUCE_SUM,
            listOf(shapeOf(43, 59)), listOf(shapeOf(43)),
            mapOf("axis" to -1, "keepdims" to 0)), resolverOf(sum))
        val relu = mockNode("relu", UirOpKind.RELU,
            listOf(shapeOf(43)), listOf(shapeOf(43)))
        assertNull(matcher.onNodeGenerated(relu, resolverOf(relu)), "pt-189787 different third op")
    }

    @Test
    fun `pt-189799 add negative zero`() {
        val pattern = allPatterns.first { it.id == "pt-189799" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "pytorch", "inductor")

        // 正例: 任何 ADD 就会匹配（这是宽泛 pattern）
        val pos = mockNode("add", UirOpKind.ADD,
            listOf(shapeOf(1), shapeOf(1)), listOf(shapeOf(1)))
        assertNotNull(matcher.onNodeGenerated(pos, resolverOf(pos)), "pt-189799 positive")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("subtract", UirOpKind.SUBTRACT,
            listOf(shapeOf(1), shapeOf(1)), listOf(shapeOf(1)))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "pt-189799 diff op")
    }

    @Test
    fun `pt-189801 ELU-div zero`() {
        val pattern = allPatterns.first { it.id == "pt-189801" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "pytorch", "inductor")

        // 正例: ELU → DIVIDE
        val elu = mockNode("elu", UirOpKind.ELU,
            listOf(shapeOf(1)), listOf(shapeOf(1)),
            mapOf("alpha" to "0.52"))
        assertNull(matcher.onNodeGenerated(elu, resolverOf(elu)), "pt-189801 first node only")

        val div = mockNode("div", UirOpKind.DIVIDE,
            listOf(shapeOf(1), shapeOf(1)), listOf(shapeOf(1)))
        assertNotNull(matcher.onNodeGenerated(div, resolverOf(div)), "pt-189801 complete chain")

        // 反例: ELU → SUBTRACT
        matcher.reset()
        matcher.onNodeGenerated(mockNode("elu", UirOpKind.ELU,
            listOf(shapeOf(1)), listOf(shapeOf(1)),
            mapOf("alpha" to "0.52")), resolverOf(elu))
        val sub = mockNode("sub", UirOpKind.SUBTRACT,
            listOf(shapeOf(1), shapeOf(1)), listOf(shapeOf(1)))
        assertNull(matcher.onNodeGenerated(sub, resolverOf(sub)), "pt-189801 different second op")
    }

    @Test
    fun `pt-189803 reciprocal(elu+exp(elu))`() {
        val pattern = allPatterns.first { it.id == "pt-189803" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "pytorch", "inductor")

        // 正例: 4节点链
        val elu = mockNode("elu", UirOpKind.ELU,
            listOf(shapeOf(24, 57, 37)), listOf(shapeOf(24, 57, 37)),
            mapOf("alpha" to "1.24"))
        assertNull(matcher.onNodeGenerated(elu, resolverOf(elu)))
        val exp = mockNode("exp", UirOpKind.EXP,
            listOf(shapeOf(24, 57, 37)), listOf(shapeOf(24, 57, 37)))
        assertNull(matcher.onNodeGenerated(exp, resolverOf(exp)))
        val add = mockNode("add", UirOpKind.ADD,
            listOf(shapeOf(24, 57, 37), shapeOf(24, 57, 37)), listOf(shapeOf(24, 57, 37)))
        assertNull(matcher.onNodeGenerated(add, resolverOf(add)))
        val recip = mockNode("reciprocal", UirOpKind.RECIPROCAL,
            listOf(shapeOf(24, 57, 37)), listOf(shapeOf(24, 57, 37)))
        assertNotNull(matcher.onNodeGenerated(recip, resolverOf(recip)), "pt-189803 complete chain")

        // 反例: 前3步匹配，第4步不同
        matcher.reset()
        matcher.onNodeGenerated(elu, resolverOf(elu))
        matcher.onNodeGenerated(exp, resolverOf(exp))
        matcher.onNodeGenerated(add, resolverOf(add))
        val sigmoid = mockNode("sigmoid", UirOpKind.SIGMOID,
            listOf(shapeOf(24, 57, 37)), listOf(shapeOf(24, 57, 37)))
        assertNull(matcher.onNodeGenerated(sigmoid, resolverOf(sigmoid)), "pt-189803 different fourth op")
    }

    @Test
    fun `pt-189804 GELU sign`() {
        val pattern = allPatterns.first { it.id == "pt-189804" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "pytorch", "inductor")

        // 正例: GELU → SIGN
        val gelu = mockNode("gelu", UirOpKind.GELU,
            listOf(shapeOf(23, 64, 62)), listOf(shapeOf(23, 64, 62)))
        assertNull(matcher.onNodeGenerated(gelu, resolverOf(gelu)))
        val sign = mockNode("sign", UirOpKind.SIGN,
            listOf(shapeOf(23, 64, 62)), listOf(shapeOf(23, 64, 62)))
        assertNotNull(matcher.onNodeGenerated(sign, resolverOf(sign)), "pt-189804 complete chain")

        // 反例: GELU → RELU
        matcher.reset()
        matcher.onNodeGenerated(gelu, resolverOf(gelu))
        val relu = mockNode("relu", UirOpKind.RELU,
            listOf(shapeOf(23, 64, 62)), listOf(shapeOf(23, 64, 62)))
        assertNull(matcher.onNodeGenerated(relu, resolverOf(relu)), "pt-189804 different second op")
    }

    @Test
    fun `pt-189808 sqrt negative NaN`() {
        val pattern = allPatterns.first { it.id == "pt-189808" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "pytorch", "inductor")

        // 正例: 任何 SQRT
        val pos = mockNode("sqrt", UirOpKind.SQRT,
            listOf(shapeOf(24, 23, 13)), listOf(shapeOf(24, 23, 13)))
        assertNotNull(matcher.onNodeGenerated(pos, resolverOf(pos)), "pt-189808 positive")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("rsqrt", UirOpKind.RSQRT,
            listOf(shapeOf(24, 23, 13)), listOf(shapeOf(24, 23, 13)))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "pt-189808 diff op")
    }

    @Test
    fun `pt-190417 zeros-log2-layer_norm`() {
        val pattern = allPatterns.first { it.id == "pt-190417" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "pytorch", "inductor")

        // 正例: 3节点链
        val zeros = mockNode("zeros", UirOpKind.ZEROS,
            emptyList(), listOf(shapeOf(2, 1)))
        assertNull(matcher.onNodeGenerated(zeros, resolverOf(zeros)))
        val log2 = mockNode("log2", UirOpKind.LOG2,
            listOf(shapeOf(2, 1)), listOf(shapeOf(2, 1)))
        assertNull(matcher.onNodeGenerated(log2, resolverOf(log2)))
        val ln = mockNode("layer_norm", UirOpKind.LAYER_NORM,
            listOf(shapeOf(2, 1)), listOf(shapeOf(2, 1)),
            mapOf("eps" to 1))
        assertNotNull(matcher.onNodeGenerated(ln, resolverOf(ln)), "pt-190417 complete chain")

        // 反例: ZEROS → LOG2 → RELU (not LAYER_NORM)
        matcher.reset()
        matcher.onNodeGenerated(zeros, resolverOf(zeros))
        matcher.onNodeGenerated(log2, resolverOf(log2))
        val relu = mockNode("relu", UirOpKind.RELU,
            listOf(shapeOf(2, 1)), listOf(shapeOf(2, 1)))
        assertNull(matcher.onNodeGenerated(relu, resolverOf(relu)), "pt-190417 different third op")

        // 反例-不同形状: ZEROS shape [3,1] not [2,1]
        matcher.reset()
        val zeros2 = mockNode("zeros", UirOpKind.ZEROS, emptyList(), listOf(shapeOf(3, 1)))
        assertNull(matcher.onNodeGenerated(zeros2, resolverOf(zeros2)), "pt-190417 shape [3,1]")
    }

    @Test
    fun `pt-190418 mul-0D-log_softmax`() {
        val pattern = allPatterns.first { it.id == "pt-190418" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "pytorch", "inductor")

        // 正例: MUL(0D) → LOG_SOFTMAX(0D)
        val mul = mockNode("mul", UirOpKind.MULTIPLY,
            listOf(shapeOf(), shapeOf()), listOf(shapeOf()))
        assertNull(matcher.onNodeGenerated(mul, resolverOf(mul)))
        val ls = mockNode("log_softmax", UirOpKind.LOG_SOFTMAX,
            listOf(shapeOf()), listOf(shapeOf()),
            mapOf("axis" to -1))
        assertNotNull(matcher.onNodeGenerated(ls, resolverOf(ls)), "pt-190418 complete chain")

        // 反例-不同算子
        matcher.reset()
        val diffOp = mockNode("softmax", UirOpKind.SOFTMAX,
            listOf(shapeOf()), listOf(shapeOf()),
            mapOf("axis" to -1))
        assertNull(matcher.onNodeGenerated(diffOp, resolverOf(diffOp)), "pt-190418 diff op")
    }

    @Test
    fun `pt-190421 log-sum-int32 overflow`() {
        val pattern = allPatterns.first { it.id == "pt-190421" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "pytorch", "inductor")

        // 正例: LOG → REDUCE_SUM with dtype=int32
        val log = mockNode("log", UirOpKind.LOG,
            listOf(shapeOf(1, 4, 3, 1)), listOf(shapeOf(1, 4, 3, 1)))
        assertNull(matcher.onNodeGenerated(log, resolverOf(log)))
        val sum = mockNode("sum", UirOpKind.REDUCE_SUM,
            listOf(shapeOf(1, 4, 3, 1)), listOf(shapeOf(1, 4, 3)),
            mapOf("axis" to -1, "keepdims" to 0, "dtype" to "int32"))
        assertNotNull(matcher.onNodeGenerated(sum, resolverOf(sum)), "pt-190421 complete chain")

        // 反例: LOG → REDUCE_SUM without dtype=int32
        matcher.reset()
        matcher.onNodeGenerated(log, resolverOf(log))
        val sumFloat = mockNode("sum", UirOpKind.REDUCE_SUM,
            listOf(shapeOf(1, 4, 3, 1)), listOf(shapeOf(1, 4, 3)),
            mapOf("axis" to -1, "keepdims" to 0))
        assertNull(matcher.onNodeGenerated(sumFloat, resolverOf(sumFloat)), "pt-190421 no dtype=int32")
    }

    @Test
    fun `onnx-8203 SimplifiedLayerNormFusion`() {
        val pattern = allPatterns.first { it.id == "onnx-8203" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "onnx", "level2_optimizer")

        // 正例: 8节点 chains
        val steps = listOf(
            mockNode("reduce_mean", UirOpKind.REDUCE_MEAN,
                listOf(shapeOf(1, 1)), listOf(shapeOf(1, 1)),
                mapOf("axis" to -1, "keepdims" to 1)),
            mockNode("sub", UirOpKind.SUBTRACT,
                listOf(shapeOf(1, 1), shapeOf(1, 1)), listOf(shapeOf(1, 1))),
            mockNode("pow", UirOpKind.POWER,
                listOf(shapeOf(1, 1), shapeOf(1)), listOf(shapeOf(1, 1))),
            mockNode("reduce_mean2", UirOpKind.REDUCE_MEAN,
                listOf(shapeOf(1, 1)), listOf(shapeOf(1, 1)),
                mapOf("axis" to -1, "keepdims" to 1)),
            mockNode("add", UirOpKind.ADD,
                listOf(shapeOf(1, 1), shapeOf(1)), listOf(shapeOf(1, 1))),
            mockNode("sqrt", UirOpKind.SQRT,
                listOf(shapeOf(1, 1)), listOf(shapeOf(1, 1))),
            mockNode("div", UirOpKind.DIVIDE,
                listOf(shapeOf(1, 1), shapeOf(1, 1)), listOf(shapeOf(1, 1))),
            mockNode("mul", UirOpKind.MULTIPLY,
                listOf(shapeOf(2), shapeOf(1, 1)), listOf(shapeOf(1, 2)),
                mapOf()),
        )
        for ((i, step) in steps.withIndex()) {
            val result = matcher.onNodeGenerated(step, resolverOf(step))
            if (i < 7) {
                assertNull(result, "onnx-8203 should not match before last node (step $i)")
            } else {
                assertNotNull(result, "onnx-8203 should match on complete chain")
            }
        }
    }

    @Test
    fun `onnx-8204 keepdims=0 opset11`() {
        val pattern = allPatterns.first { it.id == "onnx-8204" }
        val matcher = PatternMatcher(PatternDatabase(patterns = listOf(pattern)), "onnx", "opset11")

        // 正例: 8节点链 with keepdims=0
        val steps = listOf(
            mockNode("reduce_mean", UirOpKind.REDUCE_MEAN,
                listOf(shapeOf(2)), listOf(shapeOf()),
                mapOf("axis" to -1, "keepdims" to 0)),
            mockNode("sub", UirOpKind.SUBTRACT,
                listOf(shapeOf(2), shapeOf()), listOf(shapeOf(2))),
            mockNode("pow", UirOpKind.POWER,
                listOf(shapeOf(2), shapeOf(1)), listOf(shapeOf(2))),
            mockNode("reduce_mean2", UirOpKind.REDUCE_MEAN,
                listOf(shapeOf(2)), listOf(shapeOf()),
                mapOf("axis" to -1, "keepdims" to 0)),
            mockNode("add", UirOpKind.ADD,
                listOf(shapeOf(), shapeOf()), listOf(shapeOf())),
            mockNode("sqrt", UirOpKind.SQRT,
                listOf(shapeOf()), listOf(shapeOf())),
            mockNode("div", UirOpKind.DIVIDE,
                listOf(shapeOf(2), shapeOf()), listOf(shapeOf(2))),
            mockNode("mul", UirOpKind.MULTIPLY,
                listOf(shapeOf(2), shapeOf(2, 1)), listOf(shapeOf(2, 2))),
        )
        for ((i, step) in steps.withIndex()) {
            val result = matcher.onNodeGenerated(step, resolverOf(step))
            if (i < 7) {
                assertNull(result, "onnx-8204 should not match before last node (step $i)")
            } else {
                assertNotNull(result, "onnx-8204 should match on complete chain")
            }
        }
    }

    @Test
    fun `all patterns should only match their own compiler-target`() {
        // 验证每个 pattern 只在自己的 compiler+target 下匹配
        for (pattern in allPatterns) {
            // 用错误的 compiler 不应匹配
            val wrongCompiler = when (pattern.compiler) {
                "tvm" -> "pytorch"
                "pytorch" -> "onnx"
                "onnx" -> "tvm"
                else -> "tvm"
            }
            val wrongMatcher = PatternMatcher(
                PatternDatabase(patterns = listOf(pattern)),
                wrongCompiler, pattern.target
            )
            // 创建一个简单的匹配节点
            val opName = pattern.nodes.first().op
            val op = try { UirOpKind.valueOf(opName) } catch (_: Exception) { continue }
            val node = mockNode(opName.lowercase(), op,
                (1..pattern.nodes.first().inputs.size).map { shapeOf(1) },
                (1..pattern.nodes.first().outputs.size).map { shapeOf(1) })
            assertNull(wrongMatcher.onNodeGenerated(node, resolverOf(node)),
                "Pattern ${pattern.id} should not match under compiler=$wrongCompiler")
        }
    }
}