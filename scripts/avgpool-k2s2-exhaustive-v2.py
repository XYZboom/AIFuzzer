#!/usr/bin/env python3
"""Exhaustive standalone probe: AVG_POOL2D(k=2,s=2) LLVM shufflevector crash.
NEW constraint: N*C*(H//2)*(W//2) ∈ {40,56,72}.
Tests ALL shapes where this expression matches, finds which actually crash."""
import tvm
from tvm import relax
import sys

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
        return None

# N*C*(H//2)*(W//2) ∈ {40,56,72}
allowed_expr = {40, 56, 72}
crash_shapes = []
ok_shapes = []

for n in range(1, 7):
    for c in range(1, 11):
        for h in range(2, 13):
            for w in range(2, 9):
                expr = n * c * (h // 2) * (w // 2)
                if expr not in allowed_expr:
                    continue
                shape = (n, c, h, w)
                r = test_avgpool(shape)
                if r == 'CRASH':
                    crash_shapes.append(shape)
                else:
                    ok_shapes.append(shape)

print(f'Total shapes matching N*C*(H//2)*(W//2) ∈ {sorted(allowed_expr)}: {len(crash_shapes) + len(ok_shapes)}')
print(f'CRASH: {len(crash_shapes)}')
print(f'OK:    {len(ok_shapes)}')
print()
print('=' * 60)
print('CRASH shapes:')
print('=' * 60)
for s in sorted(crash_shapes):
    n,c,h,w = s
    expr = n*c*(h//2)*(w//2)
    print(f'  N={n},C={c},H={h},W={w}  N*C*(H//2)*(W//2)={n}*{c}*({h}//2)*({w}//2)={expr}')

print()
print('=' * 60)
print('OK shapes (false positives):')
print('=' * 60)
for s in sorted(ok_shapes):
    n,c,h,w = s
    expr = n*c*(h//2)*(w//2)
    print(f'  N={n},C={c},H={h},W={w}  N*C*(H//2)*(W//2)={n}*{c}*({h}//2)*({w}//2)={expr}')

print()
print('=' * 60)
print('允许值的精确分析')
print('=' * 60)
# 按expr分组
by_expr = {}
for s in crash_shapes:
    n,c,h,w = s
    e = n*c*(h//2)*(w//2)
    by_expr.setdefault(e, []).append(('CRASH', s))
for s in ok_shapes:
    n,c,h,w = s
    e = n*c*(h//2)*(w//2)
    by_expr.setdefault(e, []).append(('OK', s))

for e in sorted(by_expr.keys()):
    crashes = [s for t,s in by_expr[e] if t == 'CRASH']
    oks = [s for t,s in by_expr[e] if t == 'OK']
    print(f'  expr={e}: crash={len(crashes)}, ok={len(oks)}  → {"✅ 纯" if len(oks)==0 else "❌ 有噪声" if len(crashes)==0 else "⚠️ 混合"}')