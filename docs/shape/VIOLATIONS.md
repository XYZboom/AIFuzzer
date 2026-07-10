# TVM Relax 违规情况汇总

## 测试成功率
- **当前成功率**: 19%-39%（最新测试）
- **平均成功率**: ~28%
- **目标**: 100% 合法代码生成

## 违规分类统计（基于30轮测试）

### 主要违规：二元运算形状不匹配（100%）

**错误类型**: 所有失败都是形状不匹配

**涉及算子**:
- `add` (7个案例)
- `divide` (8个案例)
- `maximum` (5个案例)
- `minimum` (4个案例)
- `multiply` (5个案例)
- `power` (7个案例)
- `subtract` (4个案例)

**总计**: ~40个失败案例，全部为二元运算形状不匹配

### 1. API 使用错误

**已修复** - 不再出现

---

### 2. 形状约束违规

#### 2.1 二元运算形状不匹配（多个案例）
**错误信息**:
```
ValueError: the first input shape at dim X is T.int64(A) 
and the second input shape at dim Y is T.int64(B), which are not broadcastable
```

**典型案例**:

##### 案例 1: 维度值不匹配
```python
v_0: [42, 57, 88]  # 3D
v_3: [98, 122]     # 2D (mean 后降维)
v_3_expanded: expand_dims([98, 122], axis=0) → [1, 98, 122]

subtract([42, 57, 88], [1, 98, 122])
# dim 2: 88 vs 122 → 不匹配！
```

##### 案例 2: 0输入算子固定形状
```python
v_6 = ones([3])  # 固定形状 [3]
v_0: [89, 6]    # 2D

multiply([89, 6], [1, 3])  # expand_dims 后
# dim -1: 6 vs 3 → 不匹配！
```

**根本原因**:
- 生成器允许不兼容的形状参与二元运算
- `keepdims=False` 导致维度降低
- 0输入算子生成固定形状

**解决方案**:
- 生成器层面：严格检查形状兼容性
- 翻译器层面：使用 `broadcast_to` 或 `pad`（但 TVM Relax 没有 pad）

---

#### 2.2 `tril`/`triu` 需要 2D 输入（1个案例）
**错误信息**:
```
ValueError: relax.triu requires the input tensor to have at least two dimensions.
However, the given input has 1 dimension(s).
```

**错误代码**:
```python
v_5 = take(v_3, axis=0)  # 降维为 1D
v_9 = tril(v_5)  # 错误：需要 2D
```

**根本原因**: 其他算子（如 `take`）可能降维

---

### 3. 轴约束违规

#### 3.1 0D 张量上的轴越界
**错误信息**:
```
ValueError: the input axis -1 is out of range. 
The input tensor has 0 dimensions, so axis should be in range [0, 0).
```

**典型案例**:
```python
v_1: [57]  # 1D
v_3 = min(v_1, axis=-1, keepdims=False)  # 结果：0D 标量
v_7 = sum(v_3, axis=-1)  # 错误：标量没有轴
```

**解决方案**: 已修复（强制 `keepdims=True`）

---

## 问题根源分析

### 生成器层面
1. **形状约束不足**
   - `findBroadcastablePair` 只检查 ndim，不检查具体维度值
   - 允许不兼容的形状参与运算

2. **形状推导不准确**
   - `keepdims=False` 导致形状变化，但约束系统未及时更新

3. **0输入算子问题**
   - `ones([3])` / `zeros([3])` 生成固定形状
   - 与现有输入形状不匹配

### 翻译器层面
1. **API 使用错误**
   - `TensorType` vs `TensorStructInfo`
   - `full` 参数类型错误

2. **缺少形状适配逻辑**
   - TVM Relax 没有 `pad` 算子
   - 无法在翻译时修复形状不匹配

---

### 修复记录

#### 已修复
- ✅ `TensorType` → `TensorStructInfo`
- ✅ `full` 参数类型（使用 `relax.const()`）
- ✅ 0D 张量归约/softmax（强制 `keepdims=True`）
- ✅ Python boolean 关键字
- ✅ 翻译器 API 错误

#### 未修复（核心问题）
- ❌ **二元运算形状不匹配**（100% 失败原因）
  - `findBroadcastablePair` 未检查具体维度值
  - 0输入算子生成固定形状
  - `keepdims=False` 导致形状变化

---

## 建议改进方向

### 短期（翻译器层面）
1. 修复 `full` 参数类型
2. 完善 API 调用验证

### 中期（生成器层面）
1. 改进 `findBroadcastablePair`：检查具体维度值
2. 实时形状跟踪：`keepdims=False` 后更新形状
3. 限制 0 输入算子：仅用于单输入模式

### 长期（架构层面）
1. 引入形状符号系统
2. 完善约束求解器
3. 语义验证层

---

## 测试数据

- **测试轮次**: 30
- **成功范围**: 19%-39%
- **平均成功率**: ~28%
- **主要失败**: 二元运算形状不匹配（100%）
- **根本问题**: 所有失败由本项目 bug 导致

## 违规详细案例

### 案例 1: 维度值不匹配
```python
v_0: [42, 57, 88]  # 3D
v_3: [98, 122]     # 2D (mean 后)
subtract([42, 57, 88], [1, 98, 122])
# dim 2: 88 vs 122 → 不匹配
```

### 案例 2: 0输入算子固定形状
```python
v_6 = ones([3])  # 固定形状
v_0: [89, 6]
multiply([89, 6], [1, 3])
# dim -1: 6 vs 3 → 不匹配
```

### 案例 3: 相同维度数但值不兼容
```python
v_1: [103, 13]
v_2: [78, 54]
add([103, 13], [78, 54])
# dim 0: 103 vs 78 → 不匹配
# dim 1: 13 vs 54 → 不匹配
```
