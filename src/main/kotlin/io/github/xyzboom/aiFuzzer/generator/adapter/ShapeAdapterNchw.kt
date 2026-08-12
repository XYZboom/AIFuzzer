package io.github.xyzboom.aiFuzzer.generator.adapter

import io.github.xyzboom.aiFuzzer.generator.valueOrNull
import io.github.xyzboom.aiFuzzer.ir.UirDimKind
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.types.UirShape
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildDim
import io.github.xyzboom.aiFuzzer.ir.types.builder.buildShape
import kotlin.random.Random

internal fun adaptNchwConstraint(
    inputValueRefs: List<UirValueRef>,
    inputShapes: List<UirShape>,
    valueShapes: MutableMap<String, UirShape>,
    valueCounter: Int,
    nodeCounter: Int,
    op: UirOpKind? = null,
    rand: Random = Random.Default
): ShapeAdapter.AdaptResult {
    val wrapperNodes = mutableListOf<UirNode>()
    val adaptedRefs = mutableListOf<UirValueRef>()
    val adaptedShapes = mutableListOf<UirShape>()
    var localValueCounter = valueCounter
    var localNodeCounter = nodeCounter
    for ((ref, shape) in inputValueRefs.zip(inputShapes)) {
        val ndim = shape.dims.size
        val adapted: Pair<UirValueRef, List<UirNode>> = when {
            ndim < 4 -> {
                val target = expandToMinNdim(shape, 4)
                generateWrapperSequence(ref, shape, target, valueShapes, localValueCounter, localNodeCounter, rand)
            }
            ndim > 4 -> {
                val extra = ndim - 4
                val mergedBatch = shape.dims.take(extra + 1)
                    .mapNotNull { it.valueOrNull() }
                    .filter { it > 0 }
                    .fold(1L) { acc, v -> acc * v }
                    .toInt()
                val targetShape = buildShape {
                    dims.add(buildDim {
                        dimKind = UirDimKind.CONSTANT
                        value = mergedBatch.coerceAtLeast(1)
                    })
                    for (i in (extra + 1) until ndim) {
                        dims.add(shape.dims[i])
                    }
                }
                generateWrapperSequence(ref, shape, targetShape, valueShapes, localValueCounter, localNodeCounter, rand)
            }
            else -> Pair(ref, emptyList())
        }
        wrapperNodes.addAll(adapted.second)
        adaptedRefs.add(adapted.first)
        adaptedShapes.add(valueShapes[adapted.first.valueId]!!)
        localValueCounter += adapted.second.size
        localNodeCounter += adapted.second.size
    }
    if ((op == UirOpKind.MAX_POOL2D || op == UirOpKind.AVG_POOL2D) && adaptedShapes.isNotEmpty()) {
        val shape = adaptedShapes[0]
        if (shape.dims.size == 4) {
            val h = shape.dims[2].valueOrNull() ?: 1
            val w = shape.dims[3].valueOrNull() ?: 1
            if (h < 2 || w < 2) {
                val targetShape = buildShape {
                    dims.add(shape.dims[0])
                    dims.add(shape.dims[1])
                    dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = maxOf(h, 2) })
                    dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = maxOf(w, 2) })
                }
                val (newRef, newNodes) = generateWrapperSequence(adaptedRefs[0], shape, targetShape, valueShapes, localValueCounter, localNodeCounter, rand)
                wrapperNodes.addAll(newNodes)
                adaptedRefs[0] = newRef
                adaptedShapes[0] = valueShapes[newRef.valueId]!!
                localValueCounter += newNodes.size
                localNodeCounter += newNodes.size
            }
        }
    }
    if (op == UirOpKind.CONV2D && adaptedShapes.size == 2) {
        val inputC = adaptedShapes[0].dims.getOrNull(1)?.valueOrNull()
        val weightCIn = adaptedShapes[1].dims.getOrNull(1)?.valueOrNull()
        val weightCOut = adaptedShapes[1].dims.getOrNull(0)?.valueOrNull()
        val inputH = adaptedShapes[0].dims.getOrNull(2)?.valueOrNull() ?: 1
        val inputW = adaptedShapes[0].dims.getOrNull(3)?.valueOrNull() ?: 1
        val cInMismatch = (inputC != null && weightCIn != null && inputC != weightCIn) ||
            (inputC != null && weightCIn == null) ||
            (inputC == null && weightCIn != null)
        val origKH = adaptedShapes[1].dims.getOrNull(2)?.valueOrNull() ?: 3
        val origKW = adaptedShapes[1].dims.getOrNull(3)?.valueOrNull() ?: 3
        val kHTooBig = origKH > inputH
        val kWTooBig = origKW > inputW
        if (cInMismatch || kHTooBig || kWTooBig) {
            val cOut = weightCOut ?: 1
            val cIn = inputC ?: 1
            val kH = minOf(origKH, inputH).coerceAtLeast(1)
            val kW = minOf(origKW, inputW).coerceAtLeast(1)
            val weightShape = buildShape {
                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = cOut })
                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = cIn })
                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = kH })
                dims.add(buildDim { dimKind = UirDimKind.CONSTANT; value = kW })
            }
            val (constRef, constNode) = generateConstantTensor(weightShape, valueShapes, localValueCounter, localNodeCounter, rand)
            wrapperNodes.add(constNode)
            adaptedRefs[1] = constRef
            adaptedShapes[1] = valueShapes[constRef.valueId]!!
        }
    }
    return ShapeAdapter.AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
}

internal fun adaptInterpolateConstraint(
    inputValueRefs: List<UirValueRef>,
    inputShapes: List<UirShape>,
    valueShapes: MutableMap<String, UirShape>,
    valueCounter: Int,
    nodeCounter: Int,
    rand: Random = Random.Default
): ShapeAdapter.AdaptResult {
    val wrapperNodes = mutableListOf<UirNode>()
    val adaptedRefs = mutableListOf<UirValueRef>()
    val adaptedShapes = mutableListOf<UirShape>()
    var localValueCounter = valueCounter
    var localNodeCounter = nodeCounter
    for ((ref, shape) in inputValueRefs.zip(inputShapes)) {
        val ndim = shape.dims.size
        val adapted: Pair<UirValueRef, List<UirNode>> = when {
            ndim < 3 -> {
                val target = expandToMinNdim(shape, 3)
                generateWrapperSequence(ref, shape, target, valueShapes, localValueCounter, localNodeCounter, rand)
            }
            ndim > 5 -> {
                val extra = ndim - 5
                val mergedBatch = shape.dims.take(extra + 1)
                    .mapNotNull { it.valueOrNull() }
                    .filter { it > 0 }
                    .fold(1L) { acc, v -> acc * v }
                    .toInt()
                val targetShape = buildShape {
                    dims.add(buildDim {
                        dimKind = UirDimKind.CONSTANT
                        value = mergedBatch.coerceAtLeast(1)
                    })
                    for (i in (extra + 1) until ndim) {
                        dims.add(shape.dims[i])
                    }
                }
                generateWrapperSequence(ref, shape, targetShape, valueShapes, localValueCounter, localNodeCounter, rand)
            }
            else -> Pair(ref, emptyList())
        }
        wrapperNodes.addAll(adapted.second)
        adaptedRefs.add(adapted.first)
        adaptedShapes.add(valueShapes[adapted.first.valueId]!!)
        localValueCounter += adapted.second.size
        localNodeCounter += adapted.second.size
    }
    return ShapeAdapter.AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
}

internal fun adaptBatchNormConstraint(
    inputValueRefs: List<UirValueRef>,
    inputShapes: List<UirShape>,
    valueShapes: MutableMap<String, UirShape>,
    valueCounter: Int,
    nodeCounter: Int,
    rand: Random = Random.Default
): ShapeAdapter.AdaptResult {
    val wrapperNodes = mutableListOf<UirNode>()
    val adaptedRefs = mutableListOf<UirValueRef>()
    val adaptedShapes = mutableListOf<UirShape>()
    var localValueCounter = valueCounter
    var localNodeCounter = nodeCounter
    for ((ref, shape) in inputValueRefs.zip(inputShapes)) {
        val ndim = shape.dims.size
        val adapted: Pair<UirValueRef, List<UirNode>> = when {
            ndim < 3 -> {
                val target = buildShape {
                    for (dim in shape.dims) {
                        dims.add(dim)
                    }
                    repeat(3 - ndim) {
                        dims.add(buildDim {
                            dimKind = UirDimKind.CONSTANT
                            value = 1
                        })
                    }
                }
                generateWrapperSequence(ref, shape, target, valueShapes, localValueCounter, localNodeCounter, rand)
            }
            ndim > 4 -> {
                val extra = ndim - 4
                val mergedBatch = shape.dims.take(extra + 1)
                    .mapNotNull { it.valueOrNull() }
                    .filter { it > 0 }
                    .fold(1L) { acc, v -> acc * v }
                    .toInt()
                val targetShape = buildShape {
                    dims.add(buildDim {
                        dimKind = UirDimKind.CONSTANT
                        value = mergedBatch.coerceAtLeast(1)
                    })
                    for (i in (extra + 1) until ndim) {
                        dims.add(shape.dims[i])
                    }
                }
                generateWrapperSequence(ref, shape, targetShape, valueShapes, localValueCounter, localNodeCounter, rand)
            }
            else -> Pair(ref, emptyList())
        }
        wrapperNodes.addAll(adapted.second)
        adaptedRefs.add(adapted.first)
        adaptedShapes.add(valueShapes[adapted.first.valueId]!!)
        localValueCounter += adapted.second.size
        localNodeCounter += adapted.second.size
    }
    return ShapeAdapter.AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
}