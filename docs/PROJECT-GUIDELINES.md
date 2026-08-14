# aiFuzzer 项目准则

## 翻译器（Translator）核心约束

### 禁止翻译器自作主张

Translator 必须忠实反映 IR 的全部信息，**不得添加 IR 中不存在的行为**。

### 禁止翻译器推导形状

Translator 不得在运行时对 shape 进行动态计算（max/min/if-else 等形状推导逻辑）。如果 IR 中某算子的输出 shape 是已知常量，翻译结果必须直接使用该常量，不得引用运行时变量的 `.shape` 属性。

### 禁止翻译器修改形状

Translator 不得改变 IR 中声明或推导出的形状信息。输入/输出/中间值的 shape 必须与 IR 的 type.shape 一致。

### 反面示例（违反规则）

**场景**：IR 中 CONV2D 的 weight 输入 shape 为 `[2, 2, 1, 6]`（全部 CONSTANT 维度），但 translator 生成：

```kotlin
// ❌ 违反：推导形状 + 修改形状
"torch.zeros(max(${weightVar}.shape[0], 1), $inputVar.shape[1], " +
    "min(${weightVar}.shape[2], $inputVar.shape[2]), " +
    "min(${weightVar}.shape[3], $inputVar.shape[3]), device=self.device)"
```

即使 `max(2,1)=2`、`min(1,5)=1`、`min(6,6)=6` 全部可静态计算，也使用动态 shape 表达式，违反了"禁止推导形状"原则。

### 正确做法

**情况 A**：shape 全部为 CONSTANT 维度 → 直接使用字面常量：

```kotlin
// ✅ 正确：忠实反映 IR 常量 shape
"torch.zeros(2, 2, 1, 6, device=self.device)"
```

**情况 B**：shape 包含 SYMBOLIC 或 UNKNOWN 维度 → 使用该维度的变量引用（这是 IR 中存在的信息），但不得添加 IR 中不存在的 max/min 推导：

```kotlin
// ✅ 正确：仅引用 IR 中存在的维度，不添加推导
"torch.zeros(${weightVar}.shape[0], ${weightVar}.shape[1], " +
    "${weightVar}.shape[2], ${weightVar}.shape[3], device=self.device)"
```

### 为什么会有"形状不知道"的情况

IR 中 shape 为 `UNKNOWN` 维度有三种来源：

1. **广播操作**：`torch.add(v_3, v_5)` 中 v_3 的 dim=3 和 v_5 的 dim=5 进行 broadcast，运行时决定哪个维度广播，编译期无法静态确定 → ShapeInferer 标记为 UNKNOWN
2. **动态算子**：`torch.arange(0, N)` 的长度取决于 N 的值，不是从输入 shape 推导的 → 输出 shape 为 UNKNOWN（1-D 未知长度）
3. **条件切片**：`v[0:end]` 中 end 是另一个算子的输出值，无法在 IR 层面静态确定 → 输出维度为 UNKNOWN

这些情况下翻译器**必须**使用动态表达式（如 `v.shape[0]`），因为编译器在运行时才知道实际值。这是合理且必要的。

**但 CONV2D 的 weight 不属于这些情况**。weight 的 shape `[2, 2, 1, 6]` 在 IR 中全部是 CONSTANT 维度。翻译器生成 `torch.zeros(max(v.shape[0],1), ...)` 的动态表达式不是"因为不知道 shape"，而是因为防御性设计（注释原文："ShapeInferer predicted wrong shape"）。这违反了"禁止翻译器推导形状"原则——**翻译器不该替 ShapeInferer 兜底**。如果 ShapeInferer 有 bug，应该修 ShapeInferer，而不是让 translator 加运行时推导。

### 总结

| shape 情况 | 翻译器行为 | 依据 |
|-----------|----------|------|
| 全 CONSTANT（如 `[2,2,1,6]`） | 字面常量 `torch.zeros(2,2,1,6)` | IR 已知道，不需推导 |
| 含 UNKNOWN/SYMBOLIC | 运行时引用 `v.shape[0]` | IR 不知道，必须让编译器运行时确定 |