package io.github.xyzboom.aiFuzzer.generator.adapter

import io.github.xyzboom.aiFuzzer.generator.OpShapeConstraint
import io.github.xyzboom.aiFuzzer.generator.valueOrNull
import io.github.xyzboom.aiFuzzer.ir.UirDimKind
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.UirTypeKind
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.builder.buildNode
import io.github.xyzboom.aiFuzzer.ir.builder.buildValueRef
import io.github.xyzboom.aiFuzzer.ir.types.UirDim
import io.github.xyzboom.aiFuzzer.ir.types.UirShape
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDataType
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDim
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildIntAttr
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildShape
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildStringAttr
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildTensorType
import kotlin.random.Random

internal fun randomIdSuffix(rand: Random = Random.Default): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..8).map { chars.random(rand) }.joinToString("")
}

internal fun generateConstantTensor(
    targetShape: UirShape,
    valueShapes: MutableMap<String, UirShape>,
    valueIdCounter: Int,
    nodeIdCounter: Int,
    rand: Random = Random.Default
): Pair<UirValueRef, UirNode> {
    val outputValueId = "v_${valueIdCounter}_${randomIdSuffix(rand)}"
    valueShapes[outputValueId] = targetShape
    val outputRef = buildValueRef {
        valueId = outputValueId
        type = buildTensorType {
            typeKind = UirTypeKind.TENSOR
            shape = targetShape
            dtype = buildDataType { name = "float32"; bits = 32 }
        }
    }
    val node = buildNode {
        name = "zeros_${nodeIdCounter}_${randomIdSuffix(rand)}"
        op = UirOpKind.ZEROS
        inputs.clear()
        outputs.add(outputRef)
    }
    return Pair(outputRef, node)
}

internal fun generateWrapperSequence(
    inputRef: UirValueRef,
    originalShape: UirShape,
    targetShape: UirShape,
    valueShapes: MutableMap<String, UirShape>,
    valueCounter: Int,
    nodeCounter: Int,
    rand: Random = Random.Default
): Pair<UirValueRef, List<UirNode>> {
    val wrapperNodes = mutableListOf<UirNode>()
    var currentRef = inputRef
    var currentShape = originalShape
    var counter = valueCounter
    val originalNdim = currentShape.dims.size
    val targetNdim = targetShape.dims.size
    if (originalNdim < targetNdim) {
        val numDimsToAdd = targetNdim - originalNdim
        for (i in 0 until numDimsToAdd) {
            val (newRef, newNode) = insertSingleExpandDims(currentRef, currentShape, axis = 0, valueShapes, counter++, nodeCounter + wrapperNodes.size, rand)
            wrapperNodes.add(newNode)
            currentRef = newRef
            currentShape = valueShapes[newRef.valueId]!!
        }
    } else if (originalNdim > targetNdim) {
        val (newRef, newNode) = insertReshapeForDimReduce(currentRef, currentShape, targetNdim, valueShapes, counter++, nodeCounter + wrapperNodes.size, rand)
        wrapperNodes.add(newNode)
        currentRef = newRef
        currentShape = valueShapes[newRef.valueId]!!
    }
    if (currentShape.dims.size == targetNdim && !shapesEqual(currentShape, targetShape)) {
        val canBroadcast = currentShape.dims.zip(targetShape.dims).all { (curDim, tgtDim) ->
            val curVal = curDim.valueOrNull()
            val tgtVal = tgtDim.valueOrNull()
            curVal == null || tgtVal == null || curVal == tgtVal || curVal == 1
        }
        if (canBroadcast) {
            val (newRef, newNode) = insertBroadcastTo(currentRef, currentShape, targetShape, valueShapes, counter++, nodeCounter + wrapperNodes.size, rand)
            wrapperNodes.add(newNode)
            currentRef = newRef
            currentShape = valueShapes[newRef.valueId]!!
        } else {
            val (newRef, nodes) = adaptWithElemCountMatch(currentRef, currentShape, targetShape, valueShapes, counter, nodeCounter + wrapperNodes.size, rand)
            wrapperNodes.addAll(nodes)
            currentRef = newRef
            currentShape = valueShapes[newRef.valueId]!!
            counter += nodes.size
        }
    }
    return Pair(currentRef, wrapperNodes)
}

internal fun adaptWithElemCountMatch(
    inputRef: UirValueRef,
    inputShape: UirShape,
    targetShape: UirShape,
    valueShapes: MutableMap<String, UirShape>,
    valueCounter: Int,
    nodeCounter: Int,
    rand: Random = Random.Default
): Pair<UirValueRef, List<UirNode>> {
    val wrapperNodes = mutableListOf<UirNode>()
    var currentRef = inputRef
    var currentShape = inputShape
    var counter = valueCounter
    var nodeOffset = 0
    val totalElements = inputShape.dims.mapNotNull { it.valueOrNull() }.fold(1L) { acc, v -> acc * v }
    val targetTotalElements = targetShape.dims.mapNotNull { it.valueOrNull() }.fold(1L) { acc, v -> acc * v }
    if (totalElements <= 0 || targetTotalElements <= 0) {
        return generateWrapperSequence(inputRef, inputShape, targetShape, valueShapes, valueCounter, nodeCounter, rand)
    }
    val flatShape = buildShape { dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = totalElements.toInt() }) }
    val (flattenedRef, flattenNode) = insertReshape(currentRef, currentShape, flatShape, valueShapes, counter++, nodeOffset++, rand)
    wrapperNodes.add(flattenNode)
    currentRef = flattenedRef
    currentShape = flatShape
    if (totalElements < targetTotalElements) {
        val actualRepeats = ((targetTotalElements + totalElements - 1) / totalElements).toInt()
        val tiledShape = buildShape { dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = (totalElements * actualRepeats).toInt() }) }
        val (tiledRef, tileNode) = insertTile(currentRef, currentShape, tiledShape, valueShapes, counter++, nodeOffset++, rand)
        wrapperNodes.add(tileNode)
        currentRef = tiledRef
        currentShape = tiledShape
        if (totalElements * actualRepeats > targetTotalElements) {
            val croppedShape = buildShape { dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = targetTotalElements.toInt() }) }
            val (croppedRef, cropNode) = insertStridedSlice1D(currentRef, currentShape, croppedShape, valueShapes, counter++, nodeOffset++, rand)
            wrapperNodes.add(cropNode)
            currentRef = croppedRef
            currentShape = croppedShape
        }
    } else if (totalElements > targetTotalElements) {
        val croppedShape = buildShape { dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = targetTotalElements.toInt() }) }
        val (croppedRef, cropNode) = insertStridedSlice1D(currentRef, currentShape, croppedShape, valueShapes, counter++, nodeOffset++, rand)
        wrapperNodes.add(cropNode)
        currentRef = croppedRef
        currentShape = croppedShape
    }
    val (reshapedRef, reshapeNode) = insertReshape(currentRef, currentShape, targetShape, valueShapes, counter++, nodeOffset++, rand)
    wrapperNodes.add(reshapeNode)
    currentRef = reshapedRef
    return Pair(currentRef, wrapperNodes)
}

internal fun insertSingleExpandDims(
    inputRef: UirValueRef, inputShape: UirShape, axis: Int,
    valueShapes: MutableMap<String, UirShape>, valueIdCounter: Int, nodeIdCounter: Int,
    rand: Random = Random.Default
): Pair<UirValueRef, UirNode> {
    val normalizedAxis = axis.coerceIn(0, inputShape.dims.size)
    val outputShape = buildShape {
        for (i in 0 until normalizedAxis) { dims.add(inputShape.dims[i]) }
        dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = 1 })
        for (i in normalizedAxis until inputShape.dims.size) { dims.add(inputShape.dims[i]) }
    }
    val outputValueId = "v_${valueIdCounter}_${randomIdSuffix(rand)}"
    valueShapes[outputValueId] = outputShape
    val outputRef = buildValueRef {
        valueId = outputValueId; type = buildTensorType { typeKind = UirTypeKind.TENSOR; shape = outputShape; dtype = inputRef.type.dtype }
    }
    val node = buildNode {
        name = "expand_dims_${nodeIdCounter}_${randomIdSuffix(rand)}"
        op = UirOpKind.EXPAND_DIMS; inputs.add(inputRef); outputs.add(outputRef)
        attributes["axis"] = buildIntAttr { value = normalizedAxis }
    }
    return Pair(outputRef, node)
}

internal fun insertReshapeForDimReduce(
    inputRef: UirValueRef, inputShape: UirShape, targetNdim: Int,
    valueShapes: MutableMap<String, UirShape>, valueIdCounter: Int, nodeIdCounter: Int,
    rand: Random = Random.Default
): Pair<UirValueRef, UirNode> {
    val currentNdim = inputShape.dims.size
    val flattenCount = currentNdim - targetNdim + 1
    val outputDims = mutableListOf<UirDim>()
    var product = 1
    for (i in 0 until flattenCount) { product *= inputShape.dims[i].valueOrNull() ?: 16 }
    outputDims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = product })
    for (i in flattenCount until currentNdim) { outputDims.add(inputShape.dims[i]) }
    val outputShape = buildShape { outputDims.forEach { dims.add(it) } }
    val outputValueId = "v_${valueIdCounter}_${randomIdSuffix(rand)}"
    valueShapes[outputValueId] = outputShape
    val outputRef = buildValueRef { valueId = outputValueId; type = buildTensorType { typeKind = UirTypeKind.TENSOR; shape = outputShape; dtype = inputRef.type.dtype } }
    val node = buildNode { name = "reshape_${nodeIdCounter}_${randomIdSuffix(rand)}"; op = UirOpKind.RESHAPE; inputs.add(inputRef); outputs.add(outputRef) }
    return Pair(outputRef, node)
}

internal fun insertReshape(
    inputRef: UirValueRef, inputShape: UirShape, targetShape: UirShape,
    valueShapes: MutableMap<String, UirShape>, valueIdCounter: Int, nodeIdCounter: Int,
    rand: Random = Random.Default
): Pair<UirValueRef, UirNode> {
    val inEl = inputShape.dims.mapNotNull { it.valueOrNull() }.fold(1L) { a, v -> a * v }
    val tgtEl = targetShape.dims.mapNotNull { it.valueOrNull() }.fold(1L) { a, v -> a * v }
    val actualTarget = if (inEl > 0 && tgtEl > 0 && inEl != tgtEl) {
        buildShape { val ndim = targetShape.dims.size; val factors = factorizeToNdimShape(inEl.toInt(), ndim, rand); if (factors.isNotEmpty()) factors.forEach { dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = it }) } else { dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = inEl.toInt() }) } }
    } else targetShape
    val outputValueId = "v_${valueIdCounter}_${randomIdSuffix(rand)}"
    valueShapes[outputValueId] = actualTarget
    val outputRef = buildValueRef { valueId = outputValueId; type = buildTensorType { typeKind = UirTypeKind.TENSOR; shape = actualTarget; dtype = inputRef.type.dtype } }
    val node = buildNode { name = "reshape_${nodeIdCounter}_${randomIdSuffix(rand)}"; op = UirOpKind.RESHAPE; inputs.add(inputRef); outputs.add(outputRef) }
    return Pair(outputRef, node)
}

internal fun insertBroadcastTo(
    inputRef: UirValueRef, inputShape: UirShape, targetShape: UirShape,
    valueShapes: MutableMap<String, UirShape>, valueIdCounter: Int, nodeIdCounter: Int,
    rand: Random = Random.Default
): Pair<UirValueRef, UirNode> {
    val outputValueId = "v_${valueIdCounter}_${randomIdSuffix(rand)}"
    valueShapes[outputValueId] = targetShape
    val outputRef = buildValueRef { valueId = outputValueId; type = buildTensorType { typeKind = UirTypeKind.TENSOR; shape = targetShape; dtype = inputRef.type.dtype } }
    val node = buildNode { name = "broadcast_to_${nodeIdCounter}_${randomIdSuffix(rand)}"; op = UirOpKind.BROADCAST_TO; inputs.add(inputRef); outputs.add(outputRef) }
    return Pair(outputRef, node)
}

internal fun insertTile(
    inputRef: UirValueRef, inputShape: UirShape, targetShape: UirShape,
    valueShapes: MutableMap<String, UirShape>, valueIdCounter: Int, nodeIdCounter: Int,
    rand: Random = Random.Default
): Pair<UirValueRef, UirNode> {
    val outputValueId = "v_${valueIdCounter}_${randomIdSuffix(rand)}"
    valueShapes[outputValueId] = targetShape
    val outputRef = buildValueRef { valueId = outputValueId; type = buildTensorType { typeKind = UirTypeKind.TENSOR; shape = targetShape; dtype = inputRef.type.dtype } }
    val node = buildNode { name = "tile_${nodeIdCounter}_${randomIdSuffix(rand)}"; op = UirOpKind.TILE; inputs.add(inputRef); outputs.add(outputRef) }
    return Pair(outputRef, node)
}

internal fun insertStridedSlice1D(
    inputRef: UirValueRef, inputShape: UirShape, targetShape: UirShape,
    valueShapes: MutableMap<String, UirShape>, valueIdCounter: Int, nodeIdCounter: Int,
    rand: Random = Random.Default
): Pair<UirValueRef, UirNode> {
    val targetSize = targetShape.dims.firstOrNull()?.valueOrNull() ?: 1
    val outputValueId = "v_${valueIdCounter}_${randomIdSuffix(rand)}"
    valueShapes[outputValueId] = targetShape
    val outputRef = buildValueRef { valueId = outputValueId; type = buildTensorType { typeKind = UirTypeKind.TENSOR; shape = targetShape; dtype = inputRef.type.dtype } }
    val node = buildNode { name = "strided_slice_${nodeIdCounter}_${randomIdSuffix(rand)}"; op = UirOpKind.STRIDED_SLICE; inputs.add(inputRef); outputs.add(outputRef); attributes["axes"] = buildStringAttr { value = "0" }; attributes["begin"] = buildStringAttr { value = "0" }; attributes["end"] = buildStringAttr { value = targetSize.toString() } }
    return Pair(outputRef, node)
}

internal fun canBroadcastTogether(shape1: UirShape, shape2: UirShape): Boolean {
    val maxNdim = maxOf(shape1.dims.size, shape2.dims.size)
    val padded1 = expandToMinNdim(shape1, maxNdim)
    val padded2 = expandToMinNdim(shape2, maxNdim)
    for (i in 0 until maxNdim) {
        val v1 = padded1.dims[i].valueOrNull()
        val v2 = padded2.dims[i].valueOrNull()
        if (v1 != null && v2 != null && v1 != v2 && v1 != 1 && v2 != 1) return false
    }
    return true
}

internal fun deriveCommonBroadcastTarget(shape1: UirShape, shape2: UirShape): UirShape {
    val targetNdim = maxOf(shape1.dims.size, shape2.dims.size)
    val padded1 = expandToMinNdim(shape1, targetNdim)
    val padded2 = expandToMinNdim(shape2, targetNdim)
    val targetDims = padded1.dims.zip(padded2.dims).map { (d1, d2) ->
        val v1 = d1.valueOrNull(); val v2 = d2.valueOrNull()
        when {
            v1 == null || v2 == null -> buildDim { dimKind = UirDimKind.UNKNOWN; value = null }
            v1 == v2 -> buildDim { dimKind = UirDimKind.CONSTANT; value = v1 }
            v1 == 1 -> buildDim { dimKind = UirDimKind.CONSTANT; value = v2 }
            v2 == 1 -> buildDim { dimKind = UirDimKind.CONSTANT; value = v1 }
            else -> buildDim { dimKind = UirDimKind.CONSTANT; value = maxOf(v1, v2) }
        }
    }
    return buildShape { targetDims.forEach { dims.add(it) } }
}

internal fun deriveTargetShape(op: UirOpKind, currentShape: UirShape, allInputShapes: List<UirShape>, constraint: OpShapeConstraint): UirShape {
    val currentNdim = currentShape.dims.size
    if (currentNdim < constraint.minNdim) return expandToMinNdim(currentShape, constraint.minNdim)
    when (op) {
        UirOpKind.MATMUL -> {
            if (allInputShapes.size == 2) {
                val currentIndex = allInputShapes.indexOf(currentShape)
                val otherIndex = if (currentIndex == 0) 1 else 0
                return deriveMatmulCompatibleShape(currentShape, allInputShapes[otherIndex])
            }
        }
        UirOpKind.ADD, UirOpKind.SUBTRACT, UirOpKind.MULTIPLY, UirOpKind.DIVIDE, UirOpKind.MAXIMUM, UirOpKind.MINIMUM, UirOpKind.POWER -> {
            if (allInputShapes.size == 2) {
                val currentIndex = allInputShapes.indexOf(currentShape)
                val otherIndex = if (currentIndex == 0) 1 else 0
                return deriveBroadcastableShape(currentShape, allInputShapes[otherIndex])
            }
        }
        UirOpKind.TRANSPOSE, UirOpKind.TRIL, UirOpKind.TRIU, UirOpKind.STRIDED_SLICE -> {
            if (currentNdim < 2) return expandToMinNdim(currentShape, 2)
        }
        UirOpKind.CONCAT -> {
            val targetNdim = allInputShapes.maxOfOrNull { it.dims.size } ?: currentNdim
            if (currentNdim < targetNdim) return expandToMinNdim(currentShape, targetNdim)
        }
        else -> {}
    }
    return currentShape
}

internal fun expandToMinNdim(shape: UirShape, minNdim: Int): UirShape {
    val currentNdim = shape.dims.size
    if (currentNdim >= minNdim) return shape
    val extraDims = (1..(minNdim - currentNdim)).map { buildDim { dimKind = UirDimKind.CONSTANT; value = 1 } }
    return buildShape { extraDims.forEach { dims.add(it) }; shape.dims.forEach { dims.add(it) } }
}

internal fun deriveMatmulCompatibleShape(currentShape: UirShape, otherShape: UirShape): UirShape {
    val paddedCurrent = if (currentShape.dims.size < 2) expandToMinNdim(currentShape, 2) else currentShape
    val paddedOther = if (otherShape.dims.size < 2) expandToMinNdim(otherShape, 2) else otherShape
    val currentK = paddedCurrent.dims.last().valueOrNull()
    val otherK = paddedOther.dims[paddedOther.dims.size - 2].valueOrNull()
    if (currentK != null && otherK != null && currentK != otherK) {
        val newDims = paddedCurrent.dims.toMutableList()
        newDims[newDims.size - 1] = buildDim { dimKind = UirDimKind.CONSTANT; value = otherK }
        return buildShape { newDims.forEach { dims.add(it) } }
    }
    return paddedCurrent
}

internal fun deriveBroadcastableShape(currentShape: UirShape, otherShape: UirShape): UirShape {
    val targetNdim = maxOf(currentShape.dims.size, otherShape.dims.size)
    val paddedCurrent = expandToMinNdim(currentShape, targetNdim)
    val paddedOther = expandToMinNdim(otherShape, targetNdim)
    val newDims = paddedCurrent.dims.mapIndexed { i, dim ->
        val otherDim = paddedOther.dims[i]; val currentVal = dim.valueOrNull(); val otherVal = otherDim.valueOrNull()
        if (currentVal != null && otherVal != null && currentVal != otherVal && currentVal != 1 && otherVal != 1)
            buildDim { dimKind = UirDimKind.CONSTANT; value = 1 }
        else dim
    }
    return buildShape { newDims.forEach { dims.add(it) } }
}

internal fun shapesEqual(s1: UirShape, s2: UirShape): Boolean {
    if (s1.dims.size != s2.dims.size) return false
    return s1.dims.zip(s2.dims).all { (d1, d2) -> val v1 = d1.valueOrNull(); val v2 = d2.valueOrNull(); v1 == null || v2 == null || v1 == v2 }
}

internal fun factorizeToNdimShape(total: Int, targetNdim: Int, rand: Random = Random.Default): List<Int> {
    if (total <= 0 || targetNdim <= 0) return emptyList()
    val factors = mutableListOf<Int>()
    var remaining = total
    for (i in 0 until targetNdim - 1) {
        if (remaining <= 1) { factors.add(1); continue }
        val candidates = (2..remaining).filter { remaining % it == 0 }
        factors.add(if (candidates.isNotEmpty()) candidates.random(rand) else 1)
        remaining /= factors.last()
    }
    factors.add(remaining)
    return factors
}