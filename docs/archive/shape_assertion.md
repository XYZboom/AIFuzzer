# TVM 翻译器形状断言功能

## 概述

在 TVM Relax 翻译器中添加了形状断言功能，用于验证每步输出的形状与推断的形状一致。

## 实现细节

### 位置
- 文件：`src/main/kotlin/io/github/xyzboom/aiFuzzer/translator/tvm/TvmRelaxTranslator.kt`

### 新增方法

1. **`addShapeAssertion(builder, outputVar, expectedShape)`**
   - 在每个节点输出后添加形状断言
   - 格式：`assert var.struct_info.shape == relax.ShapeExpr([...]), "Shape mismatch: ..."`

2. **`generateShapeAssertionExpr(shape)`**
   - 生成用于断言的形状表达式字符串
   - 处理 CONSTANT、SYMBOLIC 和 UNKNOWN 维度类型

### 断言格式

生成的 Python 代码示例：
```python
# input: [80, 3]
# output: [80]
v_2_cvjvegj9 = bb.emit(relax.op.min(v_1_mc5jszfl_var, axis=[-1], keepdims=False))
assert v_2_cvjvegj9.struct_info.shape == relax.ShapeExpr([80]), "Shape mismatch: expected relax.ShapeExpr([80]), got {v_2_cvjvegj9.struct_info.shape}"
```

### 支持情况

- **单输出节点**：在每个输出变量后添加一个断言
- **多输出节点**：为每个输出变量添加独立的断言

### 测试验证

- 测试类：`ShapeAssertionTest`
- 测试方法：`test shape assertion generation`
- 测试验证：断言数量 > 0，代码生成成功

## 用途

1. **调试形状推导**：断言失败时可以快速定位形状推导错误
2. **验证编译器行为**：确保 TVM 编译器的实际行为与预期一致
3. **提高测试覆盖率**：在 fuzzing 过程中捕获形状不一致的问题

## 注意事项

- 断言使用 Python 的 `assert` 语句，在运行时检查
- 符号维度和未知维度暂时用默认值 `shapeRank` (16) 替代
- 断言错误信息包含期望形状和实际形状的对比