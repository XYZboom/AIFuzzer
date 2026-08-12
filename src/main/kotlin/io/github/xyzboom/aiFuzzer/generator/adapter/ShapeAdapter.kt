package io.github.xyzboom.aiFuzzer.generator.adapter

import io.github.xyzboom.aiFuzzer.generator.ShapeConstraints
import io.github.xyzboom.aiFuzzer.ir.Attribute
import io.github.xyzboom.aiFuzzer.ir.UirNode
import io.github.xyzboom.aiFuzzer.ir.UirOpKind
import io.github.xyzboom.aiFuzzer.ir.UirValueRef
import io.github.xyzboom.aiFuzzer.ir.types.UirShape
import kotlin.random.Random

object ShapeAdapter {

    data class AdaptResult(
        val adaptedRefs: List<UirValueRef>,
        val wrapperNodes: List<UirNode>,
        val adaptedShapes: List<UirShape>
    )

    fun adaptInputs(
        op: UirOpKind,
        inputValueRefs: List<UirValueRef>,
        valueShapes: MutableMap<String, UirShape>,
        valueCounter: Int,
        nodeCounter: Int,
        attributes: Map<String, Attribute> = emptyMap(),
        rand: Random = Random.Default
    ): AdaptResult {
        if (inputValueRefs.isEmpty()) {
            return AdaptResult(emptyList(), emptyList(), emptyList())
        }
        val constraint = ShapeConstraints.getConstraint(op)
        val inputShapes = inputValueRefs.map { ref -> valueShapes[ref.valueId]!! }
        if (op in setOf(UirOpKind.CONV2D, UirOpKind.MAX_POOL2D, UirOpKind.AVG_POOL2D)) {
            return adaptNchwConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, op, rand)
        }
        if (op == UirOpKind.INTERPOLATE) {
            return adaptNchwConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, op, rand)
        }
        if (op == UirOpKind.RESIZE2D) {
            return adaptNchwConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, op, rand)
        }
        if (op == UirOpKind.BATCH_NORM) {
            return adaptBatchNormConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, rand)
        }
        if (op == UirOpKind.MATMUL && inputShapes.size == 2) {
            return adaptMatmulInputs(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, rand)
        }
        if (op == UirOpKind.CONCAT && inputShapes.size >= 2) {
            return adaptConcatInputs(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, attributes, rand)
        }
        if (ShapeConstraints.isApplicable(op, inputShapes)) {
            return AdaptResult(inputValueRefs, emptyList(), inputShapes)
        }
        if (op in UirOpKind.binaryInputOps && inputShapes.size == 2) {
            return adaptBinaryInputs(op, inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, constraint, rand)
        }
        if (op in setOf(UirOpKind.TRANSPOSE, UirOpKind.TRIL, UirOpKind.TRIU, UirOpKind.STRIDED_SLICE)) {
            return adaptNdimConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, minNdim = 2, rand)
        }
        if (op == UirOpKind.GATHER) {
            return adaptGatherInputs(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, rand)
        }
        if (op == UirOpKind.STRIDED_SLICE) {
            return adaptStridedSliceInputs(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, rand)
        }
        if (constraint.minNdim > 0) {
            return adaptNdimConstraint(inputValueRefs, inputShapes, valueShapes, valueCounter, nodeCounter, constraint.minNdim, rand)
        }
        val wrapperNodes = mutableListOf<UirNode>()
        val adaptedRefs = mutableListOf<UirValueRef>()
        val adaptedShapes = mutableListOf<UirShape>()
        var localValueCounter = valueCounter
        var localNodeCounter = nodeCounter
        for ((ref, originalShape) in inputValueRefs.zip(inputShapes)) {
            val targetShape = deriveTargetShape(op, originalShape, inputShapes, constraint)
            val (adaptedRef, nodes) = generateWrapperSequence(ref, originalShape, targetShape, valueShapes, localValueCounter, localNodeCounter, rand)
            wrapperNodes.addAll(nodes)
            adaptedRefs.add(adaptedRef)
            adaptedShapes.add(valueShapes[adaptedRef.valueId]!!)
            localValueCounter += nodes.size
            localNodeCounter += nodes.size
        }
        return AdaptResult(adaptedRefs, wrapperNodes, adaptedShapes)
    }
}