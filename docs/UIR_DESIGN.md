# AiFuzzer — 统一中间表示（Unified Intermediate Representation, UIR）设计文档

> 版本: 0.1
> 日期: 2026-07-03
> 目标: 为 AI 编译器模糊测试提供一个与编译器无关的统一中间表示

---

## 1. 设计理念

### 1.1 为什么需要统一 IR

AI 编译器（如 Apache TVM、ONNX Runtime、IREE）各有自己的输入格式：
- TVM 使用 Python 构建 Relax IR 或 TIR
- ONNX 使用 protobuf 序列化的计算图
- IREE 使用 StableHLO / Torch-MLIR 作为输入

但这些编译器**处理的语义是相同的**：张量计算图。统一 IR 的核心思想是：

**定义一种与具体编译器无关的张量计算图中间表示，然后为每个目标编译器编写翻译器（translator），将统一 IR 翻译成该编译器的具体输入格式。**

这样一来，一套生成的测试用例可以同时测试多个 AI 编译器。

### 1.2 参考: CrossLangFuzzer 的 IR 设计

CrossLangFuzzer 的 IR 设计模式（位于 `tree/gen/` 下的 Kotlin 接口定义）：

```
IrElement (根接口)
├── IrType (类型系统)
│   ├── IrSimpleClassifier — 简单类型引用
│   ├── IrParameterizedClassifier — 泛型类型
│   ├── IrTypeParameter — 类型参数（含上界）
│   ├── IrNullableType — 可空类型包装
│   └── IrPlatformType — 平台类型包装
├── IrDeclaration (声明)
│   ├── IrClassDeclaration — 类（含超类型、类型参数、泛型参数映射）
│   ├── IrFunctionDeclaration — 函数（含参数列表、返回类型、类型参数）
│   └── IrPropertyDeclaration — 属性
├── IrExpression (表达式)
│   └── IrBlock — 代码块（表达式列表）
├── IrParameter — 参数（名、类型、默认值）
└── IrProgram — 程序（类 + 函数 + 属性 容器）
```

**借鉴的设计模式**：
- 接口驱动 + 树结构 + Visitor/Transformer
- 类型系统的分层（简单类型 → 参数化类型 → 类型参数）
- 容器化的元素组织（容器接口）

### 1.3 AiFuzzer IR 与 CrossLangFuzzer IR 的差异

| 维度 | CrossLangFuzzer IR | AiFuzzer IR |
|------|---------------------|-------------|
| 测试对象 | 类型系统编译器 | AI 编译器 |
| 核心元素 | 类、函数、属性、表达式 | 计算图、算子节点、张量、形状 |
| 类型系统 | 面向对象类型（类、泛型、可空） | 张量类型（形状、数据类型、布局） |
| 表达式 | 方法调用、赋值、控制流 | 算子调用、张量操作 |
| 程序结构 | 类层次 + 方法体 | DAG 计算图 |

---

## 2. 被测试的 AI 编译器

### 2.1 编译器选择

| # | 编译器 | 版本 | 输入格式 | 语言 |
|---|--------|------|----------|------|
| 1 | **Apache TVM** | 0.25.0 | Python + Relax IR | Python/C++ |
| 2 | **ONNX** | IR v7+ | Protobuf 计算图 | 跨语言 |
| 3 | **IREE** | latest | StableHLO / Torch-MLIR | MLIR |

**选择理由**：
1. **TVM**：已安装，可直接测试
2. **ONNX**：最广泛接受的 AI 模型中间格式，几乎所有框架都支持导出
3. **IREE**：基于 MLIR 的现代 AI 编译器，与 TVM 互补

### 2.2 交叉检查方法

对于每个目标编译器：
1. 列出该编译器支持的算子集合
2. 检查 UIR 的算子集能否覆盖其核心算子
3. 检查 UIR 的类型系统能否表达其张量类型
4. 确认翻译后的输入能被编译器接收

---

## 3. UIR 核心结构定义

### 3.1 总体架构

```
UirProgram (程序)
├── name: String
├── version: String
├── opset: String (算子集标识)
├── tensors: List<UirTensor> (定义全局常量张量)
├── graph: UirGraph (主计算图)
└── metadata: Map<String, String>
```

```
UirGraph (计算图)
├── name: String
├── inputs: List<UirValue> (图输入)
├── outputs: List<UirValue> (图输出)
├── nodes: List<UirNode> (计算节点列表，拓扑有序)
├── blocks: List<UirBlock> (子图块，用于条件/循环)
└── values: Map<String, UirValue> (所有中间值)
```

```
UirNode (计算节点 — 核心元素)
├── name: String (节点名)
├── op: String (算子名称，如 "conv2d"、"relu")
├── domain: String? (算子域，默认空表示标准算子集)
├── inputs: List<UirValueRef> (输入值引用)
├── outputs: List<UirValueRef> (输出值引用)
├── attributes: Map<String, UirAttribute> (算子属性)
└── doc: String? (描述)
```

### 3.2 类型系统

```
UirType (类型系统基类)
│
├── UirTensorType — 张量类型
│   ├── shape: UirShape (形状)
│   ├── dtype: UirDataType (数据类型)
│   ├── layout: UirLayout? (内存布局，可选)
│   └── encoding: UirEncoding? (编码，如稀疏格式)
│
├── UirSeqType — 序列类型
│   ├── elemType: UirType (元素类型)
│   └── length: Int? (可选长度)
│
├── UirTupleType — 元组类型
│   └── types: List<UirType> (各元素类型)
│
├── UirScalarType — 标量类型
│   └── dtype: UirDataType
│
├── UirStringType — 字符串类型
│
├── UirOptionalType — 可选类型
│   └── elemType: UirType
│
├── UirDynamicType — 动态类型（运行时决定）
│
└── UirFuncType — 函数类型
    ├── params: List<UirType>
    └── ret: UirType
```

### 3.3 形状系统

```
UirShape
├── dims: List<UirDim>
└── is_ranked: Boolean (是否已知维度数)

UirDim
├── kind: UirDimKind
├── value: Int? (当 kind = CONSTANT)
└── symbol: String? (当 kind = SYMBOLIC)
   
UirDimKind: CONSTANT (常数) | SYMBOLIC (符号) | UNKNOWN (未知)
```

### 3.4 数据类型

```
UirDataType
├── name: String
├── bits: Int
├── signed: Boolean? (仅对整数)
└── params: Map<String, String> (扩展参数)

预定义标准类型:
- float16, float32, float64
- int4, int8, int16, int32, int64
- uint8, uint16, uint32, uint64
- bfloat16
- bool
- complex64, complex128
- string
```

### 3.5 数据布局

```
UirLayout
├── name: String (如 "NCHW", "NHWC", "NCW", "OIHW")
├── axes: List<UirAxis> (各轴的语义)
└── minor: UirMinorOrder? (最小维度存储顺序)

UirAxis: N | C | H | W | D | G
UirMinorOrder: ROW_MAJOR | COL_MAJOR | CUSTOM
```

### 3.6 值引用与值

```
UirValue (值定义)
├── id: String (唯一标识)
├── type: UirType (值的类型)
├── name: String? (友好名称)
└── producer: UirNodeRef? (产生此值的节点)

UirValueRef (值引用)
└── valueId: String (指向 UirValue.id)
```

### 3.7 属性系统

```
UirAttribute (属性 — 算子参数)
│
├── UirIntAttr
│   └── value: Long
├── UirFloatAttr
│   └── value: Double
├── UirStringAttr
│   └── value: String
├── UirBoolAttr
│   └── value: Boolean
├── UirIntListAttr
│   └── values: List<Long>
├── UirFloatListAttr
│   └── values: List<Double>
├── UirTensorAttr
│   ├── shape: UirShape
│   ├── dtype: UirDataType
│   └── data: ByteArray (序列化的张量值)
├── UirTypeAttr
│   └── type: UirType
├── UirGraphAttr
│   └── graph: UirGraph (子图)
├── UirRefAttr
│   └── valueId: String (引用 UirValue)
└── UirSymbolicAttr
    ├── expr: String (符号表达式)
    └── symbols: List<String> (符号变量列表)
```

### 3.8 子图与控制流

```
UirBlock (子图块)
├── name: String
├── inputs: List<UirValue>
├── outputs: List<UirValue>
├── nodes: List<UirNode>
├── blocks: List<UirBlock> (嵌套子块)
└── values: Map<String, UirValue>

控制流算子（特殊节点）:
- "if" — 条件分支（含 then_block 和 else_block 属性）
- "loop" — 循环（含 body_block、cond_expr 属性）
- "scan" — 扫描（累加器模式的循环）
```

### 3.9 程序组织

```
UirProgram
├── name: String
├── version: String = "0.1"
├── opset: String (使用的算子集，如 "ai_fuzzer_ops_v1")
├── tensors: List<UirTensor> (全局常量张量)
├── graph: UirGraph (主计算图)
├── functions: List<UirGraph> (辅助函数，可选)
└── metadata: Map<String, String> = {
    "created_by": "AiFuzzer",
    "description": "..."
}
```

---

## 4. 算子集定义

### 4.1 算子分类

UIR 定义了一个标准算子集，覆盖 TVM relax.op.*、ONNX 标准算子、和 IREE 常见算子的交集。

#### 4.1.1 一元逐元素算子

| 算子名 | 输入 | 输出 | 属性 | 说明 |
|--------|------|------|------|------|
| `relu` | T:t | T:t | — | ReLU 激活 |
| `sigmoid` | T:t | T:t | — | Sigmoid |
| `tanh` | T:t | T:t | — | Tanh |
| `gelu` | T:t | T:t | approximate:str? | GELU |
| `silu` | T:t | T:t | — | SiLU (Swish) |
| `softmax` | T:t | T:t | axis:int=-1 | Softmax |
| `log_softmax` | T:t | T:t | axis:int=-1 | Log Softmax |
| `neg` | T:t | T:t | — | 取负 (TVM: negative)
| `abs` | T:t | T:t | — | 绝对值 |
| `sqrt` | T:t | T:t | — | 平方根 |
| `rsqrt` | T:t | T:t | — | 平方根倒数 |
| `exp` | T:t | T:t | — | 指数 |
| `log` | T:t | T:t | — | 自然对数 |
| `ceil` | T:t | T:t | — | 向上取整 |
| `floor` | T:t | T:t | — | 向下取整 |
| `round` | T:t | T:t | — | 四舍五入 |
| `sin` | T:t | T:t | — | 正弦 |
| `cos` | T:t | T:t | — | 余弦 |
| `sign` | T:t | T:t | — | 符号函数 |
| `cast` | T:t | T:target_dtype | target_dtype:UirDataType | 类型转换 |
| `isnan` | T:t | T:t | — | 检查 NaN |
| `round` | T:t | T:t | — | 四舍五入 |

#### 4.1.2 二元逐元素算子

| 算子名 | 输入 | 输出 | 属性 | 说明 |
|--------|------|------|------|------|
| `add` | T:t1, T:t2 | T:common | — | 加法 |
| `subtract` | T:t1, T:t2 | T:common | — | 减法 |
| `multiply` | T:t1, T:t2 | T:common | — | 逐元素乘 |
| `divide` | T:t1, T:t2 | T:common | — | 逐元素除 |
| `power` | T:t1, T:t2 | T:common | — | 幂 |
| `maximum` | T:t1, T:t2 | T:common | — | 逐元素 max |
| `minimum` | T:t1, T:t2 | T:common | — | 逐元素 min |
| `equal` | T:t1, T:t2 | T:bool | — | 等于 |
| `greater` | T:t1, T:t2 | T:bool | — | 大于 |
| `less` | T:t1, T:t2 | T:bool | — | 小于 |
| `and` | T:bool1, T:bool2 | T:bool | — | 逻辑与 |
| `or` | T:bool1, T:bool2 | T:bool | — | 逻辑或 |
| `xor` | T:bool1, T:bool2 | T:bool | — | 逻辑异或 |
| `mod` | T:t1, T:t2 | T:common | — | 取模 |

#### 4.1.3 归约算子

| 算子名 | 输入 | 输出 | 属性 | 说明 |
|--------|------|------|------|------|
| `reduce_sum` | T:t | T:reduced | axes:int[], keepdims:bool=false | 求和归约 |
| `reduce_mean` | T:t | T:reduced | axes:int[], keepdims:bool=false | 均值归约 |
| `reduce_max` | T:t | T:reduced | axes:int[], keepdims:bool=false | 最大值归约 |
| `reduce_min` | T:t | T:reduced | axes:int[], keepdims:bool=false | 最小值归约 |
| `reduce_prod` | T:t | T:reduced | axes:int[], keepdims:bool=false | 乘积归约 |
| `reduce_l2` | T:t | T:reduced | axes:int[], keepdims:bool=false | L2 范数归约 |
| `argmax` | T:t | T:int64 | axis:int, keepdims:bool=false | 最大值索引 |
| `argmin` | T:t | T:int64 | axis:int, keepdims:bool=false | 最小值索引 |

#### 4.1.4 形状操作算子

| 算子名 | 输入 | 输出 | 属性 | 说明 |
|--------|------|------|------|------|
| `reshape` | T:t, S:shape | T:reshaped | allowzero:bool=false | 张量重塑 |
| `squeeze` | T:t | T:squeezed | axes:int[] | 压缩 1 维 |
| `unsqueeze` | T:t | T:unsqueezed | axes:int[] | 扩展维度 |
| `flatten` | T:t | T:flat | axis:int=1 | 展平 |
| `transpose` | T:t | T:transposed | perm:int[] | 转置/轴重排 |
| `concat` | T[]:tensors | T:concatenated | axis:int | 沿轴拼接 |
| `split` | T:t | T[]:parts | axis:int, splits:int[] | 沿轴分割 |
| `slice` | T:t | T:sliced | axes:int[], starts:int[], ends:int[], steps:int[] | 切片 |
| `pad` | T:t | T:padded | pads:int[2*N], mode:str="constant", value:float=0 | 填充 |
| `broadcast_to` | T:t | T:expanded | shape:int[] | 广播扩展 (TVM: broadcast_to)
| `tile` | T:t | T:tiled | repeats:int[] | 重复平铺 |
| `gather` | T:data, T:indices | T:gathered | axis:int | 按索引收集 (TVM: take)
| `scatter` | T:data, T:indices, T:updates | T:scattered | axis:int | 按索引散放 (TVM: scatter_elements)
| `gather_nd` | T:data, T:indices | T:gathered | batch_dims:int=0 | ND 索引收集 |
| `scatter_nd` | T:data, T:indices, T:updates | T:scattered | reduction:str="none" | ND 索引散放 (TVM: scatter_nd)
| `topk` | T:t | T:values, T:indices | k:int, axis:int=-1, largest:bool=true, sorted:bool=true | TopK |
| `nonzero` | T:t | T:int64 | — | 非零元素索引 |
| `one_hot` | T:indices | T:onehot | depth:int, axis:int=-1, values:float[] | One-Hot |

#### 4.1.5 神经网络算子

| 算子名 | 输入 | 输出 | 属性 | 说明 |
|--------|------|------|------|------|
| `conv2d` | T:input, T:weight | T:output | strides:int[2], padding:int[4], dilation:int[2], groups:int=1, auto_pad:str="NOTSET" | 2D 卷积 |
| `conv1d` | T:input, T:weight | T:output | strides:int[1], padding:int[2], dilation:int[1], groups:int=1 | 1D 卷积 |
| `conv3d` | T:input, T:weight | T:output | strides:int[3], padding:int[6], dilation:int[3], groups:int=1 | 3D 卷积 |
| `conv_transpose2d` | T:input, T:weight | T:output | strides:int[2], padding:int[4], output_padding:int[2], dilation:int[2], groups:int=1 | 2D 转置卷积 |
| `max_pool2d` | T:input | T:output | kernel_shape:int[2], strides:int[2], padding:int[4], ceil_mode:bool=false | 2D 最大池化 |
| `avg_pool2d` | T:input | T:output | kernel_shape:int[2], strides:int[2], padding:int[4], ceil_mode:bool=false | 2D 平均池化 |
| `batch_norm` | T:input, T:scale, T:B, T:mean, T:var | T:output | epsilon:float=1e-5, momentum:float=0.9 | 批归一化 |
| `layer_norm` | T:input | T:output | normalized_shape:int[], epsilon:float=1e-5 | 层归一化 |
| `dropout` | T:input | T:output, T:mask | ratio:float=0.5 | Dropout |
| `matmul` | T:a, T:b | T:out | — | 矩阵乘法 |
| `linear` | T:input, T:weight, T:bias? | T:out | — | 全连接层 |
| `attention` | T:q, T:k, T:v | T:out | num_heads:int, causal:bool=false | 注意力机制 |
| `embedding` | T:indices, T:weight | T:out | — | Embedding 查找 |
| `pad` | T:input | T:output | pads:int[], mode:str="constant", value:float=0 | 填充 (TVM: nn.pad)

#### 4.1.6 归一化函数算子

| 算子名 | 输入 | 输出 | 属性 | 说明 |
|--------|------|------|------|------|
| `softmax` | T:t | T:out | axis:int=-1 | Softmax |
| `log_softmax` | T:t | T:out | axis:int=-1 | Log Softmax |
| `hardmax` | T:t | T:out | axis:int=-1 | Hardmax |
| `softplus` | T:t | T:out | — | Softplus |
| `softsign` | T:t | T:out | — | Softsign |
| `selu` | T:t | T:out | — | SELU |
| `prelu` | T:t, T:slope | T:out | — | PReLU |
| `leaky_relu` | T:t | T:out | alpha:float=0.01 | Leaky ReLU |
| `thresholded_relu` | T:t | T:out | alpha:float=1.0 | Thresholded ReLU |
| `hardsigmoid` | T:t | T:out | alpha:float=0.2, beta:float=0.5 | Hard Sigmoid |
| `hardswish` | T:t | T:out | — | Hard Swish |

#### 4.1.7 张量创建/常量算子

| 算子名 | 输入 | 输出 | 属性 | 说明 |
|--------|------|------|------|------|
| `constant` | — | T:out | value:UirTensor (内嵌或引用) | 常量张量 |
| `constant_of_shape` | T:shape | T:out | value:UirTensor (标量) | 按形状创建常量 |
| `eye_like` | T:t | T:out | k:int=0 | 类单位矩阵 |
| `zeros` | S:shape | T:out | dtype:UirDataType | 全零 |
| `ones` | S:shape | T:out | dtype:UirDataType | 全一 |
| `full` | S:shape | T:out | value:float, dtype:UirDataType | 填充值 |
| `arange` | — | T:out | start:float, limit:float, delta:float, dtype:UirDataType | 等差数列 |
| `tril` | T:t | T:out | k:int=0 | 下三角 |
| `triu` | T:t | T:out | k:int=0 | 上三角 |

#### 4.1.8 控制流/函数算子

| 算子名 | 输入 | 输出 | 属性 | 说明 |
|--------|------|------|------|------|
| `if` | T:cond | T[] | then_graph:UirGraph, else_graph:UirGraph | 条件分支 |
| `loop` | T:init_vars... | T:final_vars... | body_graph:UirGraph, max_trip_count:int? | 循环 |
| `call` | T:args... | T:ret | func_name:String | 调用函数 |
| `sequence` | T:inputs... | T:outputs | — | 时序语义标记 |

### 4.2 算子语义约定

1. **类型一致性**: 每个算子规定其输入输出张量的数据类型和形状约束
2. **维度广播**: 遵循 NumPy 广播语义（对应 TVM 和 ONNX 的行为）
3. **默认值**: 所有属性都有默认值（简化生成）
4. **错误语义**: 非法操作（如形状不匹配）定义编译时或运行时错误类别

---

## 5. 完整示例

### 5.1 YAML 序列化示例

生成一个简单的 ReLU 计算图：

```yaml
# 示例: 一个简单的卷积 + ReLU + MaxPool 网络
program:
  name: "simple_cnn"
  version: "0.1"
  opset: "ai_fuzzer_ops_v1"
  
  graph:
    name: "main"
    inputs:
      - id: "input"
        type:
          kind: "tensor"
          shape:
            dims:
              - { kind: "constant", value: 1 }
              - { kind: "constant", value: 3 }
              - { kind: "constant", value: 224 }
              - { kind: "constant", value: 224 }
          dtype:
            name: "float32"
            bits: 32
    
    values:
      - id: "input"
        type: { kind: "tensor", shape: { dims: [...] }, dtype: { name: "float32", bits: 32 } }
      - id: "weight_0"
        type: { kind: "tensor", shape: { dims: [{value: 16}, {value: 3}, {value: 3}, {value: 3}] }, dtype: { name: "float32", bits: 32 } }
      - id: "conv_out"
        type: { kind: "tensor", shape: { dims: [{value: 1}, {value: 16}, {value: 222}, {value: 222}] }, dtype: { name: "float32", bits: 32 } }
      - id: "relu_out"
        type: { kind: "tensor", shape: { dims: [{value: 1}, {value: 16}, {value: 222}, {value: 222}] }, dtype: { name: "float32", bits: 32 } }
      - id: "pool_out"
        type: { kind: "tensor", shape: { dims: [{value: 1}, {value: 16}, {value: 111}, {value: 111}] }, dtype: { name: "float32", bits: 32 } }

    nodes:
      - name: "conv_0"
        op: "conv2d"
        inputs: ["input", "weight_0"]
        outputs: ["conv_out"]
        attributes:
          strides: { kind: "int_list", values: [1, 1] }
          padding: { kind: "int_list", values: [0, 0, 0, 0] }
          dilation: { kind: "int_list", values: [1, 1] }
          groups: { kind: "int", value: 1 }
          auto_pad: { kind: "string", value: "NOTSET" }
          
      - name: "relu_0"
        op: "relu"
        inputs: ["conv_out"]
        outputs: ["relu_out"]
        attributes: {}
        
      - name: "pool_0"
        op: "max_pool2d"
        inputs: ["relu_out"]
        outputs: ["pool_out"]
        attributes:
          kernel_shape: { kind: "int_list", values: [3, 3] }
          strides: { kind: "int_list", values: [2, 2] }
          padding: { kind: "int_list", values: [0, 0, 0, 0] }
          ceil_mode: { kind: "bool", value: false }
    
    outputs: ["pool_out"]
```

### 5.2 JSON 序列化示例

```json
{
  "program": {
    "name": "simple_add",
    "version": "0.1",
    "opset": "ai_fuzzer_ops_v1",
    "graph": {
      "name": "main",
      "inputs": [{"id": "a"}, {"id": "b"}],
      "values": [
        {"id": "a", "type": {"kind": "tensor", "shape": {"dims": [{"kind": "symbolic", "symbol": "N"}, {"kind": "constant", "value": 64}]}, "dtype": {"name": "float32", "bits": 32}}},
        {"id": "b", "type": {"kind": "tensor", "shape": {"dims": [{"kind": "symbolic", "symbol": "N"}, {"kind": "constant", "value": 64}]}, "dtype": {"name": "float32", "bits": 32}}},
        {"id": "c", "type": {"kind": "tensor", "shape": {"dims": [{"kind": "symbolic", "symbol": "N"}, {"kind": "constant", "value": 64}]}, "dtype": {"name": "float32", "bits": 32}}}
      ],
      "nodes": [
        {"name": "add_0", "op": "add", "inputs": ["a", "b"], "outputs": ["c"], "attributes": {}}
      ],
      "outputs": ["c"]
    }
  }
}
```

---

## 6. 与目标编译器的交叉检查

### 6.1 Apache TVM

**TVM 的算子集**: `relax.op.*` 包含约 150+ 算子，覆盖：
- 一元操作 (abs, exp, log, sqrt, sigmoid 等) ✅
- 二元操作 (add, subtract, multiply, divide 等) ✅
- 归约操作 (sum, mean, min, max, std, var 等) ✅
- 形状操作 (reshape, squeeze, transpose, concat, split, slice 等) ✅
- NN 操作 (conv2d, pool, batch_norm, layer_norm, softmax, relu 等) ✅
- 创建操作 (zeros, ones, full, arange, eye 等) ✅
- 控制流 (if, loop) ✅ — TVM 有 Relax 的 if/loop 语义

**TVM StructInfo 类型系统**:
- `TensorStructInfo` (形状 + dtype) → `UirTensorType` ✅
- `ShapeStructInfo` → UIR 的 shape 字段 ✅
- `FuncStructInfo` → `UirFuncType` ✅
- `ObjectStructInfo` / `PrimStructInfo` → `UirDynamicType` / `UirScalarType` ✅
- TVM 的 `Call` → `UirNode` ✅
- TVM 的 `Var` → `UirValue` ✅

**翻译路径**: UIR → TVM Relax Python API (使用 BlockBuilder)
- 每个 UirNode → 对应的 relax.op.* 调用 (注意: UIR 算子名可能与 TVM API 名不同，如 neg→negative, gather→take, scatter→scatter_elements, pad→nn.pad, broadcast_to→broadcast_to)
- UirTensorType → relax.TensorStructInfo
- UirShape → relax.ShapeExpr
- 常量张量 → 作为 initializer 传入

### 6.2 ONNX

**ONNX 标准算子集**: ONNX v1.x 算子集包含约 200+ 算子。UIR 定义的算子都是 ONNX 算子的子集。检查：

| UIR 算子 | ONNX 对应 | 状态 |
|----------|-----------|------|
| relu | Relu | ✅ 存在 |
| sigmoid | Sigmoid | ✅ |
| add | Add | ✅ |
| matmul | MatMul | ✅ |
| conv2d | Conv | ✅ |
| max_pool2d | MaxPool | ✅ |
| batch_norm | BatchNormalization | ✅ |
| reshape | Reshape | ✅ |
| transpose | Transpose | ✅ |
| gather | Gather | ✅ |
| if | If | ✅ |
| loop | Loop | ✅ |

**ONNX 类型系统**:
- `TensorProto` → `UirTensorType` ✅
- `TypeProto.Tensor` 含 `Shape`、`DataType` → `UirTensorType` ✅
- `TypeProto.Sequence` → `UirSeqType` ✅
- `TypeProto.Map` → 可通过 `UirDynamicType` 近似
- `TypeProto.Optional` → `UirOptionalType` ✅

**翻译路径**: UIR → ONNX ModelProto
- 每个 UirNode → onnx.NodeProto
- 所有值 → onnx.ValueInfoProto
- 常量权重 → onnx.TensorProto (initializer)
- 使用 onnx.helper 构建

### 6.3 IREE

**IREE 输入**: IREE 接受 StableHLO (MLIR-based) 作为输入。StableHLO 算子集是 MHLO 的稳定子集，覆盖：
- 逐元素算子 (add, sub, mul, div, max, min, select 等) ✅
- 归约算子 (reduce, reduce_window) ✅
- 形状算子 (reshape, transpose, broadcast, slice, pad, concatenate 等) ✅
- 卷积/池化 (convolution, reduce_window 用于池化) ✅
- 控制流 (if, while, case) ✅
- 自定义调用 (custom_call) ✅

**IREE 类型系统** (StableHLO):
- `RankedTensorType` → `UirTensorType`（shape + dtype）✅
- `UnrankedTensorType` → 可通过 UirShape(dim_kind=UNKNOWN)
- `FunctionType` → `UirFuncType` ✅
- `TupleType` → `UirTupleType` ✅

**翻译路径**: UIR → StableHLO MLIR Module
- 每个 UirNode → StableHLO op
- UirTensorType → RankedTensorType
- 使用 MLIR Python bindings 或 StableHLO serialization

### 6.4 覆盖度分析

| 维度 | TVM | ONNX | IREE | UIR |
|------|-----|------|------|-----|
| 数据类型 | float32/64, int8/16/32/64, uint8, bool, bfloat16, complex | 同上 | 同上 | ✅ 全部覆盖 |
| 形状机制 | 静态/动态/符号 | 静态/动态 | 静态/动态/符号 | ✅ 静态/符号/未知 |
| 布局 | NCHW/NHWC/自定义 | NCHW/NHWC | NCHW/NHWC | ✅ 可扩展 |
| 一元算子 | ~30 | ~30 | ~30 | ✅ ~20 |
| 二元算子 | ~15 | ~15 | ~15 | ✅ ~13 |
| 归约算子 | ~10 | ~8 | ~10 | ✅ ~8 |
| 形状操作 | ~15 | ~20 | ~15 | ✅ ~17 |
| NN 算子 | ~20 | ~20 | ~15 | ✅ ~15 |
| 控制流 | if/loop | If/Loop | if/while/case | ✅ if/loop |
| 子图/函数 | call | FunctionProto | function | ✅ call |

---

## 7. IR 设计原则与扩展性

### 7.1 核心设计原则

1. **计算图模型**: UIR 使用有向无环图（DAG）表示张量计算，节点是算子，边是张量值
2. **SSA 形式**: 每个值只被定义一次，遵循静态单赋值形式
3. **类型化**: 每个张量值有明确的形状和数据类型
4. **算子中立**: 算子名使用标准名称，不绑定特定编译器
5. **可序列化**: UIR 设计支持 YAML/JSON 序列化，方便生成和调试

### 7.2 与 CrossLangFuzzer IR 的对比

```
CrossLangFuzzer IR (类型系统编译器)      AiFuzzer IR (AI 编译器)
───────────────────────────────          ───────────────────────
IrProgram → 类 + 函数 + 属性             UirProgram → 计算图 + 张量
IrClassDeclaration → 类定义               UirNode → 算子调用
IrFunctionDeclaration → 方法              UirGraph → 子图/函数
IrTypeParameter → 泛型类型参数             UirDim → 符号形状维
IrExpression → 表达式树                   算子调用 → 固定语义
Visitor/Transformer → 遍历+变换           Visitor/Transformer → 类似模式
```

### 7.3 扩展性设计

1. **新算子**: 直接在 opset 中添加算子定义，不影响核心 IR 结构
2. **新编译器**: 为每个新编译器实现 translator（将 UIR 翻译成目标输入）
3. **新数据类型**: 在 `UirDataType` 的定义集中添加
4. **新布局**: 在 `UirLayout` 中定义新的轴命名
5. **自定义属性**: 每个 UirNode 的 `attributes` 是字符串到 UirAttribute 的映射，可自由扩展

### 7.4 设计局限性

1. **不支持图重写/优化 pass**: UIR 只表示计算图，不包含调度/优化信息
2. **不支持自动微分**: UIR 不定义梯度算子（但可以通过 call 调用外部梯度函数）
3. **有限的控制流**: 目前只支持 if/loop，不处理异常/错误处理
4. **无状态概念**: 所有算子默认是纯函数，没有可变状态
5. **精度语义未定义**: 不同编译器对浮点精度保证不同，UIR 未约束

---

## 8. 模糊测试生成策略（与 UIR 的关系）

### 8.1 基于类型的生成

参考 Thalia 的 API 枚举和 CrossLangFuzzer 的 IR 生成:

1. **算子枚举**: 对每个算子 op(Type constraints)，枚举所有合法的组合
2. **形状组合**: 对每个算子枚举所有合法的形状组合（常量/符号混合）
3. **数据类型组合**: 枚举所有合法的数据类型组合
4. **布局组合**: 枚举布局变体（NCHW/NHWC 等）

### 8.2 测试 Oracle

| UIR 属性 | Oracle 类型 | 说明 |
|----------|-------------|------|
| well-typed | 编译成功 | 合法的 UIR 应被所有编译器正确编译 |
| ill-typed | 编译失败 | 非法的 UIR 应被编译器拒绝 |
| shape_infer | 形状推断正确 | 动态形状应被正确推断 |
| semantics | 数值等价 | 变换前后的输出应一致 (metamorphic) |

### 8.3 组合测试策略

```
UIR 生成器
├── 随机生成法: 从空图开始，随机添加算子节点
│   ├── 优先选择连接已有输出的合法算子
│   └── 确保所有图输入有对应的初始值
├── API 枚举法 (Thalia 思想):
│   ├── 对每个算子，枚举其输入类型的合法组合
│   └── 组合多个算子 → 复杂计算图
├── 模板实例化法:
│   └── 预定义图模板 → 用不同的形状/类型实例化
└── 变异法:
    ├── 对已有图做替换/插入/删除
    └── 改变形状/数据类型/属性
```

---

## 9. 参考

1. **CrossLangFuzzer 树结构**: `~/Code/kotlin/CrossLangFuzzer/tree/gen/`
2. **Thalia (POPL 2024)**: API-Driven Program Synthesis for Testing Static Typing Implementations. DOI: [10.1145/3632904](https://doi.org/10.1145/3632904)
3. **Apache TVM**: https://tvm.apache.org/docs/
4. **ONNX IR Specification**: https://github.com/onnx/onnx/blob/main/docs/IR.md
5. **StableHLO**: https://github.com/openxla/stablehlo
6. **Propilot (2026)**: Tensor Algebraic Property Skeletons (arXiv:2606.06747)