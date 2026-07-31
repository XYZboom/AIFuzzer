#!/usr/bin/env python3
"""Exhaustive standalone probe: AVG_POOL2D(k=2,s=2) LLVM shufflevector crash.
Tests ALL shape combinations within bounds to find exact trigger conditions."""
import tvm
from tvm import relax
import sys, json

def test_avgpool(shape):
    bb = relax.BlockBuilder()
    v = relax.Var('x', relax.TensorStructInfo(shape=relax.ShapeExpr(list(shape)), dtype='float32'))
    with bb.function('f', [v]):
        p = bb.emit(relax.op.nn.avg_pool2d(v, pool_size=[2, 2], strides=[2, 2], padding=[0, 0]))
        bb.emit_func_output(p)
    mod = bb.get()
    try:
        ex = relax.build(mod, target='llvm')
        return 'OK'
    except Exception as e:
        if 'shufflevector' in str(e) and 'does not dominate' in str(e):
            return 'CRASH'
        return None  # other error, skip

# Iterate over all reasonable shapes
# From the 4 known bug shapes, the ranges are:
# N: 1-6, C: 1-10, H: 2-12, W: 2-8
results = []
crash_shapes = []

for n in range(1, 7):
    for c in range(1, 11):
        for h in range(2, 13):
            for w in range(2, 9):
                expr = c * (h // 2) * (w // 2)
                if expr not in {10, 14, 18, 40}:
                    continue  # skip shapes that won't match pattern anyway
                shape = (n, c, h, w)
                r = test_avgpool(shape)
                label = 'CRASH' if r == 'CRASH' else 'OK'
                if r == 'CRASH':
                    crash_shapes.append(shape)
                print(f'{list(shape)}  expr={expr}  -> {label}')

print()
print('=' * 60)
print(f'CRASH shapes: {len(crash_shapes)}')
print('=' * 60)
for s in sorted(crash_shapes):
    c,h,w = s[1],s[2],s[3]
    expr = c*(h//2)*(w//2)
    print(f'  N={s[0]},C={c},H={h},W={w}  C*(H//2)*(W//2)={c}*({h}//2)*({w}//2)={expr}')