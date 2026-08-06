#!/usr/bin/env python3
"""
Round 2: precise boundary probe.
1. silu-2d: scan N in [1..8], C in [1..20] to find exact trigger N value
2. maxpool: test WITH reduce_mean downstream (contextual trigger)
"""
import tvm
from tvm import relax

def test_silu_2d(N, C):
    bb = relax.BlockBuilder()
    v = relax.Var("x", relax.TensorStructInfo(shape=relax.ShapeExpr([N, C]), dtype="float32"))
    with bb.function("f", [v]):
        y = bb.emit(relax.op.nn.silu(v))
        bb.emit_func_output(y)
    mod = bb.get()
    try:
        relax.build(mod, target="llvm")
        return "OK"
    except Exception as e:
        msg = str(e)
        if "shufflevector" in msg and "does not dominate" in msg:
            return "CRASH"
        return f"OTHER({type(e).__name__})"

def test_maxpool_reduce_mean(shape, k, s):
    """maxpool + reduce_mean chain — the actual trigger pattern"""
    bb = relax.BlockBuilder()
    v = relax.Var("x", relax.TensorStructInfo(shape=relax.ShapeExpr(list(shape)), dtype="float32"))
    with bb.function("f", [v]):
        p = bb.emit(relax.op.nn.max_pool2d(v, pool_size=(k,k), strides=(s,s), padding=(0,0)))
        # reduce_mean along last axis
        r = bb.emit(relax.op.reduce_mean(p, axis=[-1], keepdims=False))
        bb.emit_func_output(r)
    mod = bb.get()
    try:
        relax.build(mod, target="llvm")
        return "OK"
    except Exception as e:
        msg = str(e)
        if "shufflevector" in msg and "does not dominate" in msg:
            return "CRASH"
        return f"OTHER({type(e).__name__})"

print("=" * 70)
print("Precise Boundary Probe")
print("=" * 70)

# [1] silu 2D: scan N=1..8, C=1..20
print("\n[1] SILU 2D — scanning N=1..8, C=1..20")
silu_crashes = []
for N in range(1, 9):
    for C in range(1, 21):
        r = test_silu_2d(N, C)
        if r == "CRASH":
            silu_crashes.append((N, C))
print(f"  Total crashes: {len(silu_crashes)}")
for N, C in sorted(set(silu_crashes)):
    print(f"    N={N}, C={C}, N*C={N*C}")

# [2] maxpool k=2 s=2 + reduce_mean: test known bug shapes + FP shapes
print("\n[2] MAX_POOL2D(k=2,s=2) + reduce_mean")
test_shapes_22 = [
    (4, 5, 2, 5),   # known bug from history
    (4, 6, 4, 5),   # known bug variant
    # FP shapes from dedup-eval
    (2, 5, 5, 2), (3, 4, 10, 12),
    (4, 4, 2, 2), (1, 4, 3, 4), (3, 1, 2, 5),
    (1, 1, 8, 12), (3, 2, 6, 2), (1, 5, 5, 3),
]
for shape in test_shapes_22:
    r = test_maxpool_reduce_mean(shape, 2, 2)
    mark = "❌" if r == "CRASH" else "✅"
    print(f"  {mark} {shape} -> {r}")

# [3] maxpool k=2 s=1 + reduce_mean
print("\n[3] MAX_POOL2D(k=2,s=1) + reduce_mean")
test_shapes_21 = [
    (4, 6, 4, 5),   # known bug
    (1, 4, 2, 5),   # known bug from pattern-fp-methodology
    # FP shapes
    (2, 5, 5, 2), (1, 1, 2, 2),
    (1, 2, 3, 5), (3, 5, 6, 2), (1, 3, 3, 5),
]
for shape in test_shapes_21:
    r = test_maxpool_reduce_mean(shape, 2, 1)
    mark = "❌" if r == "CRASH" else "✅"
    print(f"  {mark} {shape} -> {r}")

# [4] maxpool k=2 s=2 + reduce_mean: wider scan
print("\n[4] MAX_POOL2D(k=2,s=2) + reduce_mean — wider scan N=1..4, C=1..6, H/W=2..5")
mp22_crashes = []
for N in [1, 2, 3, 4]:
    for C in [1, 2, 3, 4, 5, 6]:
        for H in [2, 3, 4, 5]:
            for W in [2, 3, 4, 5]:
                shape = (N, C, H, W)
                r = test_maxpool_reduce_mean(shape, 2, 2)
                if r == "CRASH":
                    mp22_crashes.append(shape)
print(f"  Crashes: {len(mp22_crashes)}")
for s in mp22_crashes:
    print(f"    {s}")

# [5] maxpool k=2 s=1 + reduce_mean: wider scan
print("\n[5] MAX_POOL2D(k=2,s=1) + reduce_mean — wider scan N=1..4, C=1..6, H/W=2..5")
mp21_crashes = []
for N in [1, 2, 3, 4]:
    for C in [1, 2, 3, 4, 5, 6]:
        for H in [2, 3, 4, 5]:
            for W in [2, 3, 4, 5]:
                shape = (N, C, H, W)
                r = test_maxpool_reduce_mean(shape, 2, 1)
                if r == "CRASH":
                    mp21_crashes.append(shape)
print(f"  Crashes: {len(mp21_crashes)}")
for s in mp21_crashes:
    print(f"    {s}")

print("\n" + "=" * 70)
print("SUMMARY")
print("=" * 70)
print(f"silu-2d crashes: {len(silu_crashes)} shapes")
print(f"maxpool(k=2,s=2)+reduce_mean crashes: {len(mp22_crashes)} shapes")
print(f"maxpool(k=2,s=1)+reduce_mean crashes: {len(mp21_crashes)} shapes")
