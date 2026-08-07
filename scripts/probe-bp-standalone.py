#!/usr/bin/env python3
"""Test Bug prevented shapes standalone vs the full graph context."""
import tvm
from tvm import relax
import json, os

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

base = "/root/Code/kotlin/aifuzzer/reports"
# 48 Bug prevented seeds (from 20k)
bp_seeds = [818,1597,2249,2556,2818,3657,4423,4730,4744,5366,5549,6327,7644,7653,8050,9231,9876,9745,9907,9960,10135,10547,10583,10730,10786,10784,10791,11296,11830,12027,12908,13625,13801,14149,14581,15233,15428,15555,15633,15855,16129,16389,16713,16777,17131,17287,17781,18137,19953]

# Collect all bug-prevented triggering ops+shapes
triggers = {}
crashes = set()

for seed in bp_seeds:
    for d in os.listdir(base):
        if d.startswith("bug_") and f"seed{seed}_" in d and "TVM" in d:
            ir_path = os.path.join(base, d, "ir.jsonl")
            if not os.path.exists(ir_path):
                continue
            with open(ir_path) as f:
                values = {}
                nodes = []
                for line in f:
                    d2 = json.loads(line)
                    if d2.get('kind') == 'visitValue':
                        sh = []
                        for s in d2.get('shape', []):
                            sh.append(s.get('value','?') if isinstance(s,dict) else s)
                        values[d2['id']] = sh
                    elif d2.get('kind') == 'visitNode':
                        nodes.append(d2)
            
            for n in nodes:
                op = n.get('op','?')
                attrs = n.get('attrs',{})
                k = attrs.get('kernel_size')
                s = attrs.get('stride')
                for vid in n.get('inputIds',[]):
                    if vid in values:
                        sh = values[vid]
                        key = (op, tuple(sh), k, s)
                        triggers[key] = triggers.get(key,0)+1
            break

print("Bug prevented triggering op+shapes (unique):")
standalone_crash = 0
standalone_ok = 0
for (op, shape, k, s), cnt in sorted(triggers.items()):
    if op == 'SILU':
        r = test_silu(list(shape))
    elif op == 'MAX_POOL2D':
        r = test_pool('max_pool2d', list(shape), k, s)
    elif op == 'AVG_POOL2D':
        r = test_pool('avg_pool2d', list(shape), k, s)
    else:
        continue
    s_status = "STANDALONE_CRASH" if r=="CRASH" else "OK"
    if r == "CRASH":
        standalone_crash += 1
    else:
        standalone_ok += 1
    print(f"  {op}({list(shape)}) k={k} s={s} -> {s_status}  (count={cnt})")

print(f"\nStandalone crash: {standalone_crash}, OK: {standalone_ok}")
