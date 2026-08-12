package io.github.xyzboom.aiFuzzer.generator.adapter

import io.github.xyzboom.aiFuzzer.generator.OpShapeConstraint
import io.github.xyzboom.aiFuzzer.generator.ShapeConstraints
import io.github.xyzboom.aiFuzzer.generator.valueOrNull
import io.github.xyzboom.aiFuzzer.ir.Attribute
import io.github.xyzboom.aiFuzzer.ir.UirDimKind
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.builder.buildNode
import io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef
import io.github.xyzboom.aiFuzzer.ir.types.UirDim
import io.github.xyzboom.aiFuzzer.ir.types.UirIntAttr
import io.github.xyzboom.aiFuzzer.ir.types.UirShape
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDim
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildShape
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildStringAttr
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildTensorType
import io.github.xyzboom.aiFuzzer.ir.UirTypeKind
import kotlin.random.Random

internal fun adaptBinaryInputs(
    op: UirOpKind,
    inputValueRefs: List<UirValueRef>,
    inputShapes: List<UirShape>,
    valueShapes: MutableMap<String, UirShape>,
    valueCounter: Int,
    nodeCounter: Int,
    constraint: OpShapeConstraint,
    rand: Random = Random.Default
): ShapeAdapter.AdaptResult {
    val shape1 = inputShapes[0]
    val shape2 = inputShapes[1]
    val canBroadcast = canBroadcastTogether(shape1, shape2)
    val wrapperNodes = mutableListOf<UirNode>()
    val adaptedRefs = mutableListOf<UirValueRef>()
    val adaptedShapes = mutableListOf<UirShape>()
    var localValueCounter = valueCounter
    var localNodeCounter = nodeCounter
    if (canBroadcast) {
        val commonTargetShape = deriveCommonBroadcastTarget(shape1, shape2)
        for ((ref, originalShape) in inputValueRefs.zip(inputShapes)) {
            val (adaptedRef, nodes) = generateWrapperSequence(ref, originalShape, commonTargetShape, valueShapes, localValueCounter, localNodeCounter, rand)
            wrapperNodes.addAll(nodes)
            adaptedRefs.add(adaptedRef)
            adaptedShapes.add(valueShapes[adaptedRef.valueId]!!)
            localValueCounter += nodes.size
            localNodeCounter += nodes.size
        }
        return ShapeAdapter.AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
    } else {
        adaptedRefs.add(inputValueRefs[0])
        adaptedShapes.add(shape1)
        val (adaptedRef, nodes) = adaptWithElemCountMatch(inputValueRefs[1], shape2, shape1, valueShapes, localValueCounter, localNodeCounter, rand)
        wrapperNodes.addAll(nodes)
        adaptedRefs.add(adaptedRef)
        adaptedShapes.add(valueShapes[adaptedRef.valueId]!!)
        return ShapeAdapter.AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
    }
}

internal fun adaptNdimConstraint(
    inputValueRefs: List<UirValueRef>,
    inputShapes: List<UirShape>,
    valueShapes: MutableMap<String, UirShape>,
    valueCounter: Int,
    nodeCounter: Int,
    minNdim: Int,
    rand: Random = Random.Default
): ShapeAdapter.AdaptResult {
    val wrapperNodes = mutableListOf<UirNode>()
    val adaptedRefs = mutableListOf<UirValueRef>()
    val adaptedShapes = mutableListOf<UirShape>()
    var localValueCounter = valueCounter
    var localNodeCounter = nodeCounter
    for ((ref, shape) in inputValueRefs.zip(inputShapes)) {
        if (shape.dims.size < minNdim) {
            val (newRef, newNodes) = generateWrapperSequence(ref, shape, expandToMinNdim(shape, minNdim), valueShapes, localValueCounter, localNodeCounter, rand)
            wrapperNodes.addAll(newNodes)
            adaptedRefs.add(newRef)
            adaptedShapes.add(valueShapes[newRef.valueId]!!)
            localValueCounter += newNodes.size
            localNodeCounter += newNodes.size
        } else {
            adaptedRefs.add(ref)
            adaptedShapes.add(shape)
        }
    }
    return ShapeAdapter.AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
}

internal fun adaptGatherInputs(
    inputValueRefs: List<UirValueRef>,
    inputShapes: List<UirShape>,
    valueShapes: MutableMap<String, UirShape>,
    valueCounter: Int,
    nodeCounter: Int,
    rand: Random = Random.Default
): ShapeAdapter.AdaptResult {
    return adaptNdimConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, minNdim = 1, rand)
}

internal fun adaptStridedSliceInputs(
    inputValueRefs: List<UirValueRef>,
    inputShapes: List<UirShape>,
    valueShapes: MutableMap<String, UirShape>,
    valueCounter: Int,
    nodeCounter: Int,
    rand: Random = Random.Default
): ShapeAdapter.AdaptResult {
    return adaptNdimConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, minNdim = 2, rand)
}

internal fun adaptMatmulInputs(
    inputValueRefs: List<UirValueRef>,
    inputShapes: List<UirShape>,
    valueShapes: MutableMap<String, UirShape>,
    valueCounter: Int,
    nodeCounter: Int,
    rand: Random = Random.Default
): ShapeAdapter.AdaptResult {
    val shape1 = inputShapes[0]
    val shape2 = inputShapes[1]
    val wrapperNodes = mutableListOf<UirNode>()
    val adaptedRefs = mutableListOf<UirValueRef>()
    val adaptedShapes = mutableListOf<UirShape>()
    var localValueCounter = valueCounter
    var localNodeCounter = nodeCounter
    var currentShape1 = shape1
    if (shape1.dims.size < 2) {
        val targetNdim = 2
        val (newRef, newNodes) = generateWrapperSequence(inputValueRefs[0], shape1, expandToMinNdim(shape1, targetNdim), valueShapes, localValueCounter, localNodeCounter, rand)
        wrapperNodes.addAll(newNodes)
        adaptedRefs.add(newRef)
        currentShape1 = valueShapes[newRef.valueId]!!
        localValueCounter += newNodes.size
        localNodeCounter += newNodes.size
    } else {
        adaptedRefs.add(inputValueRefs[0])
    }
    adaptedShapes.add(currentShape1)
    var currentShape2 = shape2
    if (shape2.dims.size < 2) {
        val targetNdim = 2
        val (newRef, newNodes) = generateWrapperSequence(inputValueRefs[1], shape2, expandToMinNdim(shape2, targetNdim), valueShapes, localValueCounter, localNodeCounter, rand)
        wrapperNodes.addAll(newNodes)
        adaptedRefs.add(newRef)
        currentShape2 = valueShapes[newRef.valueId]!!
        localValueCounter += newNodes.size
        localNodeCounter += newNodes.size
    } else {
        adaptedRefs.add(inputValueRefs[1])
    }
    adaptedShapes.add(currentShape2)
    val k1 = currentShape1.dims.last().valueOrNull()
    val k2 = currentShape2.dims[currentShape2.dims.size - 2].valueOrNull()
    if (k1 != null && k2 != null && k1 != k2) {
        val (constRef, constNode) = generateConstantTensor(currentShape2, valueShapes, localValueCounter, localNodeCounter, rand)
        wrapperNodes.add(constNode)
        adaptedRefs[1] = constRef
        adaptedShapes[1] = valueShapes[constRef.valueId]!!
    }
    return ShapeAdapter.AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
}

internal fun adaptConcatInputs(
    inputValueRefs: List<UirValueRef>,
    inputShapes: List<UirShape>,
    valueShapes: MutableMap<String, UirShape>,
    valueCounter: Int,
    nodeCounter: Int,
    attributes: Map<String, Attribute> = emptyMap(),
    rand: Random = Random.Default
): ShapeAdapter.AdaptResult {
    val axis = (attributes["axis"] as? UirIntAttr)?.value ?: 0
    val maxNdim = inputShapes.maxOfOrNull { it.dims.size } ?: 1
    val normalizedAxis = axis.coerceIn(0, maxNdim - 1)
    val wrapperNodes = mutableListOf<UirNode>()
    val adaptedRefs = mutableListOf<UirValueRef>()
    val adaptedShapes = mutableListOf<UirShape>()
    var localValueCounter = valueCounter
    var localNodeCounter = nodeCounter
    for ((ref, shape) in inputValueRefs.zip(inputShapes)) {
        if (shape.dims.size < maxNdim) {
            val (newRef, newNodes) = generateWrapperSequence(ref, shape, expandToMinNdim(shape, maxNdim), valueShapes, localValueCounter, localNodeCounter, rand)
            wrapperNodes.addAll(newNodes)
            adaptedRefs.add(newRef)
            adaptedShapes.add(valueShapes[newRef.valueId]!!)
            localValueCounter += newNodes.size
            localNodeCounter += newNodes.size
        } else {
            adaptedRefs.add(ref)
            adaptedShapes.add(shape)
        }
    }
    val minDims = mutableListOf<UirDim>()
    for (d in 0 until maxNdim) {
        if (d == normalizedAxis) {
            minDims.add(adaptedShapes[0].dims[d])
        } else {
            val values = adaptedShapes.mapNotNull { s -> s.dims[d].valueOrNull() }
            if (values.isNotEmpty()) {
                val minVal = values.min()
                minDims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = minVal })
            } else {
                minDims.add(adaptedShapes[0].dims[d])
            }
        }
    }
    val minShape = buildShape { minDims.forEach { dims.add(it) } }
    for (i in adaptedShapes.indices) {
        val currentShape = adaptedShapes[i]
        if (shapesEqual(currentShape, minShape)) continue
        val needsClipping = (0 until maxNdim).any { d ->
            d != normalizedAxis &&
                (currentShape.dims[d].valueOrNull() ?: 0) > (minShape.dims[d].valueOrNull() ?: Int.MAX_VALUE)
        }
        if (needsClipping) {
            val (newRef, newNodes) = adaptWithElemCountMatch(adaptedRefs[i], currentShape, minShape, valueShapes, localValueCounter, localNodeCounter, rand)
            wrapperNodes.addAll(newNodes)
            adaptedRefs[i] = newRef
            adaptedShapes[i] = valueShapes[newRef.valueId]!!
            localValueCounter += newNodes.size
            localNodeCounter += newNodes.size
        }
    }
    return ShapeAdapter.AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
}

internal fun insertStridedSliceForConcat(
    inputRef: UirValueRef,
    inputShape: UirShape,
    targetShape: UirShape,
    axis: Int,
    valueShapes: MutableMap<String, UirShape>,
    valueIdCounter: Int,
    nodeIdCounter: Int,
    rand: Random = Random.Default
): Pair<UirValueRef, List<UirNode>> {
    val ndim = inputShape.dims.size
    val axesToClip = mutableListOf<Int>()
    val ends = mutableListOf<Int>()
    for (d in 0 until ndim) {
        if (d == axis) continue
        val curVal = inputShape.dims[d].valueOrNull()
        val tgtVal = targetShape.dims[d].valueOrNull()
        if (curVal != null && tgtVal != null && curVal > tgtVal) {
            axesToClip.add(d)
            ends.add(tgtVal)
        }
    }
    if (axesToClip.isEmpty()) {
        return Pair(inputRef, emptyList())
    }
    val outputDims = inputShape.dims.toMutableList()
    for (i in axesToClip.indices) {
        val d = axesToClip[i]
        outputDims[d] = buildDim { dimKind = UirDimKind.CONSTANT; value = ends[i] }
    }
    val outputShape = buildShape { outputDims.forEach { dims.add(it) } }
    val outputValueId = "v_${valueIdCounter}_${randomIdSuffix(rand)}"
    valueShapes[outputValueId] = outputShape
    val outputRef = buildValueRef {
        valueId = outputValueId
        type = buildTensorType {
            typeKind = UirTypeKind.TENSOR
            shape = outputShape
            dtype = inputRef.type.dtype
        }
    }
    val begins = axesToClip.map { "0" }
    val node = buildNode {
        name = "strided_slice_${nodeIdCounter}_${randomIdSuffix(rand)}"
        op = UirOpKind.STRIDED_SLICE
        inputs.add(inputRef)
        outputs.add(outputRef)
        attributes["axes"] = buildStringAttr { value = axesToClip.joinToString(",") }
        attributes["begin"] = buildStringAttr { value = begins.joinToString(",") }
        attributes["end"] = buildStringAttr { value = ends.joinToString(",") }
    }
    return Pair(outputRef, listOf(node))
}