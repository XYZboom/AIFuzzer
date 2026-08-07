#!/usr/bin/env python3
"""Standalone test all FP shapes from 20k dedup-eval to identify over-broad patterns."""
import tvm
from tvm import relax

def test_silu(shape):
    bb = relax.BlockBuilder()
    v = relax.Var("x", relax.TensorStructInfo(shape=relax.ShapeExpr(list(shape) if shape else []), dtype="float32"))
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

def test_pool(op_name, shape, k, s):
    bb = relax.BlockBuilder()
    v = relax.Var("x", relax.TensorStructInfo(shape=relax.ShapeExpr(list(shape)), dtype="float32"))
    with bb.function("f", [v]):
        if op_name == "max_pool2d":
            y = bb.emit(relax.op.nn.max_pool2d(v, pool_size=(k,k), strides=(s,s), padding=(0,0)))
        elif op_name == "avg_pool2d":
            y = bb.emit(relax.op.nn.avg_pool2d(v, pool_size=(k,k), strides=(s,s), padding=(0,0)))
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

# All FP shapes from 20k run
fp_shapes = {
    "SILU-1d": [[10], [25], [39], [2]],
    "SILU-2d": [[2,5], [1,2], [1,5], [2,3], [2,6], [5,4], [5,5], [4,3]],
    "SILU-3d": [[4,4,5], [1,2,1], [1,1,3], [4,3,2], [1,2,5], [1,6,3], [2,4,6], [2,5,6], [2,6,5], [6,1,5], [6,2,6], [6,5,5], [6,6,5], [1,3,2]],
    "SILU-4d": [[1,1,1,1], [1,1,2,1], [1,1,3,2], [1,1,6,4], [1,3,3,5], [1,3,6,6], [1,4,6,5], [1,5,2,1], [1,5,4,6], [2,1,2,2], [2,6,6,10], [2,6,10,6], [4,4,4,1], [4,5,4,6], [4,5,5,3], [4,6,3,3], [4,6,4,1], [4,6,4,10], [5,3,12,4]],
    "MAX_POOL2D(k=2,s=1)": [[4,6,2,6], [4,6,3,3], [4,5,2,2], [4,5,3,3], [4,6,5,5], [4,6,2,2], [4,5,4,6], [4,5,6,6], [4,5,5,5], [4,6,6,3], [4,5,4,4]],
}

print("=" * 70)
print("Standalone FP Shape Test")
print("=" * 70)

all_crashes = []
all_oks = []

for group, shapes in fp_shapes.items():
    print(f"\n[{group}]")
    for sh in shapes:
        if group.startswith("SILU"):
            r = test_silu(sh)
        elif "MAX" in group:
            r = test_pool("max_pool2d", sh, 2, 1)
        elif "AVG" in group:
            r = test_pool("avg_pool2d", sh, 2, 2)
        status = "CRASH" if r == "CRASH" else "OK"
        if r == "CRASH":
            all_crashes.append((group, sh))
        else:
            all_oks.append((group, sh))
        if r == "CRASH":
            print(f"  CRASH: {sh}")
        elif r != "OK":
            print(f"  {r}: {sh}")

print("\n" + "=" * 70)
print(f"SUMMARY: {len(all_crashes)} standalone CRASH, {len(all_oks)} OK")
print("=" * 70)
if all_crashes:
    print("\nStandalone crashes (these ARE trigger shapes, pattern keeps them):")
    for g, s in all_crashes:
        print(f"  {g}: {s}")
