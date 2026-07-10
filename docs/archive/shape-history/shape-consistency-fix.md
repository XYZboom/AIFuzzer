# Shape Consistency Fix Proposal

## Problem Analysis

### Current Architecture

```
LogicGenerator (generates DAG topology, op types, dependencies)
        ↓
    generates nodes with inputs/outputs but shapes are placeholders
        ↓
ShapeAdapter (shape inference + adaptation)
        ↓
    fills in shapes, may insert EXPAND_DIMS if needed
```

### The Problem

When `LogicGenerator` generates nodes, it **does not guarantee shape consistency**:

1. **Node generation is shape-agnostic**: `generateNode()` randomly selects an op and picks input values without knowing their eventual shapes
2. **Shape constraints are ignored**: For example, `MATMUL` requires ndim≥2, `TRIL/TRIU` require ndim≥2, `CONCAT` requires inputs with matching ndim
3. **Post-hoc adaptation is fragile**: `ShapeAdapter` tries to fix shape mismatches by inserting `EXPAND_DIMS`, but this approach:
   - May fail after multiple retries
   - Generates uninteresting test cases (expands everything to work)
   - Doesn't guarantee all shape constraints are satisfied

### Example Failure Scenarios

**Scenario 1: MATMUL with 1-D inputs**
```kotlin
// LogicGenerator picks MATMUL
// Input shapes: [16] and [32]  (both 1-D)
// ShapeAdapter tries to fix, but this is semantically wrong
```

**Scenario 2: CONCAT with mismatched ndim**
```kotlin
// LogicGenerator picks CONCAT
// Input shapes: [16, 32] and [8]  (2-D vs 1-D)
// ShapeAdapter expands the 1-D to [1, 8], but this changes semantics
```

**Scenario 3: TRIL on 1-D tensor**
```kotlin
// LogicGenerator picks TRIL
// Input shape: [16]  (1-D)
// ShapeAdapter expands to [1, 16], but TRIL on a "fake" 2-D tensor is meaningless
```

## Solution: Shape-Aware Generation

### Core Idea

**Move shape inference into the generation phase** instead of post-processing.

When generating each node:
1. Know the exact shape of all available values
2. Select an op that is compatible with available input shapes
3. Compute output shape immediately
4. Propagate shapes forward

### Benefits

1. **Guaranteed consistency**: Every generated node has valid shape semantics
2. **No adaptation needed**: ShapeAdapter becomes a simple verifier
3. **Better test coverage**: Generated programs are semantically meaningful
4. **Clearer responsibility**: One-phase generation instead of two-phase fix-up

### New Architecture

```
LogicGenerator (shape-aware)
    ├─ Maintains shape map: valueId -> Shape
    ├─ Generates graph inputs with random shapes
    ├─ For each node:
    │   ├─ Filter ops by shape constraints (e.g., MATMUL needs ndim≥2)
    │   ├─ Select compatible op
    │   ├─ Compute output shape using ShapeInferer
    │   └─ Update shape map
    └─ Produces fully-formed graph with consistent shapes

ShapeAdapter (simplified)
    └─ Verify shapes are consistent (optional, for debugging)
```

## Implementation Plan

### Phase 1: Refactor LogicGenerator

**File**: `LogicGenerator.kt`

**Changes**:
1. Add `shapeMap: MutableMap<String, UirShape>` to track shapes during generation
2. Modify `generateGraph()` to:
   - Generate random shapes for graph inputs
   - Propagate shapes through node generation
3. Modify `generateNode()` to:
   - Query available input shapes
   - Filter ops by shape requirements
   - Compute output shapes immediately
   - Store shapes in ValueRef.type

**Key constraint functions**:
```kotlin
// Check if op can be applied with given input shapes
fun isOpApplicable(op: UirOpKind, availableShapes: List<UirShape>): Boolean

// Get minimum ndim requirement for an op
fun minNdimForOp(op: UirOpKind): Int

// Check if two shapes are compatible for broadcasting
fun areBroadcastable(s1: UirShape, s2: UirShape): Boolean
```

### Phase 2: Extract Shape Constraint Registry

**New file**: `ShapeConstraints.kt`

Define clear constraints for each op:
- Minimum ndim
- Maximum ndim (if any)
- Input count requirements
- Shape compatibility rules

```kotlin
data class OpShapeConstraint(
    val minNdim: Int,
    val maxNdim: Int? = null,
    val numInputs: IntRange,
    val isApplicable: (List<UirShape>) -> Boolean
)

object ShapeConstraints {
    val MATMUL = OpShapeConstraint(
        minNdim = 2,
        numInputs = 2..2,
        isApplicable = { shapes -> 
            shapes.all { it.dims.size >= 2 }
        }
    )
    
    val TRIL = OpShapeConstraint(
        minNdim = 2,
        numInputs = 1..1,
        isApplicable = { shapes ->
            shapes.all { it.dims.size >= 2 }
        }
    )
    
    // ... other ops
}
```

### Phase 3: Simplify ShapeAdapter

**File**: `ShapeAdapter.kt`

After refactoring, `ShapeAdapter` becomes optional:
- Main use: verify shape consistency (debug mode)
- Fallback: if generation is buggy, attempt fix-up (with warning)

### Phase 4: Update UirGenerator

**File**: `UirGenerator.kt`

- Remove retry logic
- `ShapeAdapter.adapt()` becomes verification-only
- Add flag `verifyShapes: Boolean` for optional verification

## Migration Strategy

### Backward Compatibility

1. **Keep existing API**: `UirGenerator.generate()` returns same type
2. **Internal refactoring only**: LogicGenerator and ShapeAdapter changes are internal
3. **No breaking changes**: Tests continue to pass

### Rollout

1. **Step 1**: Add `ShapeConstraints.kt` with constraint definitions
2. **Step 2**: Refactor `LogicGenerator` to use shape-aware generation
3. **Step 3**: Simplify `ShapeAdapter` to verification-only
4. **Step 4**: Add comprehensive tests for shape consistency
5. **Step 5**: Remove dead code and retry logic

## Test Strategy

### New Tests Required

1. **Shape consistency tests**:
   - Every generated node has valid input shapes
   - Output shapes are correctly computed
   - No shape inference failures

2. **Op constraint tests**:
   - MATMUL only with ndim≥2 inputs
   - TRIL/TRIU only with ndim≥2 inputs
   - CONCAT inputs have matching ndim
   - etc.

3. **Stress tests**:
   - Generate 1000 programs, all should be shape-consistent
   - No `ShapeInferenceError` exceptions

### Test Metrics

- **Success rate**: % of generated programs with consistent shapes (target: 100%)
- **Op diversity**: Distribution of ops in generated programs
- **Shape diversity**: Distribution of ndim values

## Risks and Mitigations

### Risk 1: Reduced Op Diversity

**Problem**: Shape-aware generation may avoid certain ops that are hard to satisfy.

**Mitigation**:
- Generate high-dimension inputs when ops require them
- Use shape-aware input generation (favor ndim≥2 for complex ops)

### Risk 2: Performance Impact

**Problem**: Shape inference for every node adds overhead.

**Mitigation**:
- Shape inference is fast (simple arithmetic)
- Profile and optimize if needed

### Risk 3: Complex Shapes

**Problem**: Some ops (e.g., RESHAPE, GATHER) have complex shape rules.

**Mitigation**:
- Use `unknownDim()` for complex cases
- Focus on correctness over precision

## Timeline

- **Day 1**: Add `ShapeConstraints.kt`, update `LogicGenerator`
- **Day 2**: Simplify `ShapeAdapter`, add tests
- **Day 3**: Integration testing, edge case fixes
- **Day 4**: Documentation, code review

## Success Criteria

1. ✅ All existing tests pass
2. ✅ New shape consistency tests pass
3. ✅ No `ShapeInferenceError` in 1000 random seeds
4. ✅ Op diversity maintained (all ops still generatable)
5. ✅ Code is simpler (less retry logic, clearer responsibilities)
