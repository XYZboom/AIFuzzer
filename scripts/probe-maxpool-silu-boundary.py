#!/usr/bin/env python3
"""
Systematic boundary probe for maxpool (k=2,s=2), maxpool (k=2,s=1), silu-2d.
Tests standalone shapes to find the actual trigger ranges for tvm-20015 shufflevector.
"""
import tvm
from tvm import relax
import itertools

def test_silu(shape):
    bb = relax.BlockBuilder()
    v = relax.Var("x", relax.TensorStructInfo(shape=relax.ShapeExpr(list(shape)), dtype="float32"))
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
        return f"OTHER({type(e).__name__}: {msg[:60]})"

def test_pool(op_name, shape, k, s):
    bb = relax.BlockBuilder()
    v = relax.Var("x", relax.TensorStructInfo(shape=relax.ShapeExpr(list(shape)), dtype="float32"))
    with bb.function("f", [v]):
        if op_name == "max_pool2d":
            y = bb.emit(relax.op.nn.max_pool2d(v, pool_size=[k,k], strides=[s,s], padding=[0,0]))
        elif op_name == "avg_pool2d":
            y = bb.emit(relax.op.nn.avg_pool2d(v, pool_size=[k,k], strides=[s,s], padding=[0,0]))
        bb.emit_func_output(y)
    mod = bb.get()
    try:
        relax.build(mod, target="llvm")
        return "OK"
    except Exception as e:
        msg = str(e)
        if "shufflevector" in msg and "does not dominate" in msg:
            return "CRASH"
        return f"OTHER({type(e).__name__}: {msg[:60]})"

print("=" * 70)
print("Boundary Probe: maxpool(k=2,s=2), maxpool(k=2,s=1), silu-2d")
print("=" * 70)

# --- Probe 1: max_pool2d k=2 s=2 ---
print("\n[1] MAX_POOL2D(k=2,s=2) — scanning N,C,H,W ranges")
mp22_crashes = []
mp22_oks = []
for N in [1, 2, 3, 4, 5]:
    for C in [1, 2, 3, 4, 5, 6]:
        for H, W in [(2,2), (2,3), (2,4), (2,5), (3,2), (3,3), (3,4), (3,5), (4,2), (4,3), (4,4), (4,5), (5,2), (5,3)]:
            shape = (N, C, H, W)
            r = test_pool("max_pool2d", shape, 2, 2)
            if r == "CRASH":
                mp22_crashes.append(shape)
            elif r == "OK":
                mp22_oks.append(shape)
print(f"  Crashes: {len(mp22_crashes)}")
for s in mp22_crashes:
    print(f"    {s}")

# --- Probe 2: max_pool2d k=2 s=1 ---
print("\n[2] MAX_POOL2D(k=2,s=1) — scanning N,C,H,W ranges")
mp21_crashes = []
for N in [1, 2, 3, 4]:
    for C in [1, 2, 3, 4, 5, 6]:
        for H, W in [(2,2), (2,3), (2,4), (2,5), (3,2), (3,3), (3,4), (3,5), (4,2), (4,3), (4,4), (4,5)]:
            shape = (N, C, H, W)
            r = test_pool("max_pool2d", shape, 2, 1)
            if r == "CRASH":
                mp21_crashes.append(shape)
print(f"  Crashes: {len(mp21_crashes)}")
for s in mp21_crashes:
    print(f"    {s}")

# --- Probe 3: silu 2D — narrow down N*C=10 case ---
print("\n[3] SILU 2D — scanning N,C pairs where N*C∈{10,14}")
silu_crashes = []
for N in range(1, 15):
    for C in range(1, 15):
        shape = (N, C)
        r = test_silu(shape)
        if r == "CRASH":
            silu_crashes.append(shape)
print(f"  Crashes: {len(silu_crashes)}")
for s in silu_crashes:
    print(f"    {s}")

# --- Probe 4: max_pool2d k=2 s=2 with C=2 — wider H,W range ---
print("\n[4] MAX_POOL2D(k=2,s=2) C=2 — wider H,W scan")
for N in [1, 3, 4]:
    for C in [2]:
        for H in range(2, 8):
            for W in range(2, 8):
                shape = (N, C, H, W)
                r = test_pool("max_pool2d", shape, 2, 2)
                if r == "CRASH":
                    print(f"    CRASH: {shape}")

# --- Probe 5: silu 2D — test [2,5] specifically ---
print("\n[5] SILU 2D [2,5] standalone test")
print(f"  SILU([2,5]) -> {test_silu((2,5))}")

# Summary
print("\n" + "=" * 70)
print("SUMMARY")
print("=" * 70)
print(f"max_pool2d(k=2,s=2) crashes: {len(mp22_crashes)} shapes")
print(f"max_pool2d(k=2,s=1) crashes: {len(mp21_crashes)} shapes")
print(f"silu 2D crashes: {len(silu_crashes)} shapes")
