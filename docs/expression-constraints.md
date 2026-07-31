# Pattern 表达式约束 (ExpressionConstraint) 文档

## 概述

表达式约束用于对 pattern 中 value 的多个维度进行运算后检查结果，弥补单一维度 `DimMatcher` 无法表达跨维度关系的缺陷。

## 格式

```json
{
  "match": "value", "id": "v_in",
  "ndim": 4,
  "dtype": "float32",
  "expressionConstraints": [
    {"dimIndices": [1, 2], "op": "mul", "allowedValues": [10, 14]}
  ]
}
```

## 字段说明

| 字段 | 类型 | 必需 | 说明 |
|:----|:----|:----:|:-----|
| `dimIndices` | `[int]` | ✅ | 参与运算的维度索引（0-based） |
| `op` | `string` | ✅ | 运算类型：`mul`/`add`/`sub`/`mod` |
| `allowedValues` | `[int]` | ✅ | 允许的运算结果集合 |
| `divisor` | `int` | ❌ | 可选，每个维度先取整除以该值再运算 |

## 运算类型

### `mul` — 乘积

多个维度的值相乘。

```
dimIndices: [1, 2], op: "mul", allowedValues: [10, 14]
→ shape[1] × shape[2] ∈ {10, 14}

dimIndices: [1, 2, 3], op: "mul", allowedValues: [10, 14]
→ shape[1] × shape[2] × shape[3] ∈ {10, 14}
```

**示例：** silu-3d 的 C×W ∈ {10, 14}

```json
{
  "match": "value", "id": "v_in",
  "ndim": 3,
  "shape": [{"$eq": 4}],
  "dtype": "float32",
  "expressionConstraints": [
    {"dimIndices": [1, 2], "op": "mul", "allowedValues": [10, 14]}
  ]
}
```
→ 只匹配 N=4、C×W=10 或 14 的 SILU（共 6 种精确形状）

### `add` — 求和

多个维度的值相加。

```
dimIndices: [0, 1], op: "add", allowedValues: [5]
→ shape[0] + shape[1] ∈ {5}
```

### `sub` — 差值

仅支持两个维度，`shape[i] - shape[j]`。

```
dimIndices: [2, 3], op: "sub", allowedValues: [1]
→ shape[2] - shape[3] ∈ {1}
```

### `mod` — 取模

仅支持两个维度，`shape[i] % shape[j]`。除数为 0 时返回 null（不匹配）。

```
dimIndices: [0, 1], op: "mod", allowedValues: [0]
→ shape[0] % shape[1] ∈ {0}（即 shape[0] 是 shape[1] 的倍数）
```

## divisor — 取整除法

先对每个维度取整除法再运算。适用于 pool 的输出形状约束。

```
dimIndices: [2, 3], op: "mul", divisor: 2, allowedValues: [2, 3, 5, 10]
→ (shape[2]//2) × (shape[3]//2) ∈ {2, 3, 5, 10}
```

**示例：** AVG_POOL2D(k=2,s=2) 的输出 H×W

```json
{
  "match": "value", "id": "v_in",
  "ndim": 4,
  "dtype": "float32",
  "expressionConstraints": [
    {"dimIndices": [2, 3], "op": "mul", "divisor": 2, "allowedValues": [2, 3, 5, 10]}
  ]
}
```
→ k=2,s=2 的 pool 输出 H×W 为 (输入的 H//2)×(W//2)，限制结果在 {2,3,5,10} 内

## 常见模式

| 场景 | dimIndices | op | divisor | allowedValues | 含义 |
|:----|:----------:|:--:|:-------:|:-------------|:-----|
| silu-3d 触发 | [1,2] | mul | — | [10,14] | C×W∈{10,14} |
| silu-4d 触发 | [1,2,3] | mul | — | [10,14] | C×H×W∈{10,14} |
| avgpool(k=2,s=2) 输出 H×W | [2,3] | mul | 2 | [2,3,5,10] | (H//2)×(W//2)∈{2,3,5,10} |
| N+C 固定值 | [0,1] | add | — | [5] | N+C=5 |
| H 比 W 多 1 | [2,3] | sub | — | [1] | H-W=1 |
| H 是 W 的整数倍 | [2,3] | mod | — | [0] | H%W=0 |

## 注意事项

1. `dimIndices` 是 0-based 索引，从 shape[0] 开始
2. 索引超出 shape 维度范围 → 不匹配（返回 null）
3. 维度值为 null（动态形状） → 不匹配（返回 null）
4. 多个约束是 AND 关系，必须全部通过
5. `divisor` 的取整除法是**整数向下取整**（Kotlin 的 `/`），即 `5/2=2`