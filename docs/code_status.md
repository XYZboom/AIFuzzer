# AiFuzzer 代码组织状态

> 更新: 2026-07-04

---

## 已完成 ✓

### 项目结构

- [x] `tree/` 子模块创建，包含 `src/`（手写代码）和 `gen/`（生成代码）
- [x] `tree/build.gradle.kts` — 参考 CrossLangFuzzer，定义 generateTree 任务
- [x] `tree/tree-generator/` — 生成器代码（复制自 CrossLangFuzzer 模式），可接收 args 输出路径
- [x] 根 `build.gradle.kts` — 依赖 `:tree` 模块
- [x] 根 `settings.gradle.kts` — include `:tree` 和 `:tree:tree-generator`

### 生成代码 (tree/gen/) — 已编译通过

- [x] `UirElement` — 根接口
- [x] `UirProgram` — 程序（graphs + metadata）
- [x] `UirGraph` — 计算图（nodes + inputs + outputs）
- [x] `UirNode` — 算子节点（op + inputs + outputs + attributes）
- [x] `UirValueRef` — 值引用
- [x] `UirNamedElement` — 命名元素接口
- [x] `UirType` — 类型基类
- [x] `UirTensorType` — 张量类型（shape + dtype）
- [x] `UirShape` — 形状（dims 列表）
- [x] `UirDim` — 维度（dimKind + value）
- [x] `UirDataType` — 数据类型（name + bits）
- [x] `UirAttribute` — 属性基类
- [x] `UirIntAttr` — 整数属性
- [x] `UirStringAttr` — 字符串属性
- [x] 全部 `*Impl.kt` 实现类
- [x] 全部 `*Builder.kt` 构造器
- [x] `UirVisitor` / `UirVisitorVoid` — 访问者模式
- [x] `UirDefaultVisitor` / `UirDefaultVisitorVoid` — 默认访问者
- [x] `UirTransformer` — 变换器模式

### 手写代码 (tree/src/) — 已编译通过

- [x] `UirAttrKind` 枚举
- [x] `UirBlockKind` 枚举
- [x] `UirDimKind` 枚举
- [x] `UirTypeKind` 枚举
- [x] `UirPureAbstractElement` 标记接口
- [x] `Attribute` typealias
- [x] `BuilderDsl` 注解
- [x] `transformInplace` 工具函数

### Phase 2 (翻译器)

- [x] `translator/` 目录结构建立
- [x] `UirTranslator` 接口
- [x] TVM Relax 翻译器（Visitor 模式，单文件输出）
- [ ] ONNX 翻译器
- [ ] IREE StableHLO 翻译器

### Phase 3 (模糊测试)

- [x] 随机 UIR 生成器（UirGenerator + GeneratorConfig）
- [x] 算子枚举 + ndim 类型约束（shape tracking）
- [x] 后端编译执行框架（Backend + TvmBackend）
- [x] FuzzingPipeline（串行/并行 fuzzing）
- [x] BugCollector（自动分类 + 过滤误报 + 保存证据）
- [x] ErrorAnalyzer（错误分类：SyntaxError/TypeError/TVM_ERROR 等）
- [x] 大规模测试：200 轮 fuzzing，**100% 成功率**（2026-07-04 验证）
- [ ] Oracle 诊断（编译、形状、数值）
- [ ] 变异引擎
- [ ] 缩减引擎（DDMin）
- [ ] CLI 入口

### Shape Tracking（2026-07-04 新增）

- [x] 生成器层 ndim 追踪（valueId → ndim 映射）
- [x] 输入随机 ndim（1-3 维可配置）
- [x] 算子兼容性检查（`isOpCompatibleWithNdims`）
- [x] ndim 兼容输入选择（`selectCompatibleInputs`）
- [x] 输出 ndim 计算（`computeOutputNdim`）
- [x] 翻译器动态 shape 支持（`shape=(-1, -1, -1)`）
- [x] 全部算子启用：add, subtract, multiply, divide, matmul, relu, sigmoid, tanh, softmax, abs, exp, log, sqrt, reshape, transpose, concat, reduce_sum, reduce_mean

---

## 待完成

### Phase 1 剩余项

- [ ] **扩展 IR 类型系统** — 添加更多属性类型（UirFloatAttr, UirBoolAttr, UirIntListAttr, UirTensorAttr 等，参考 UIR_DESIGN.md 3.7）
- [ ] **扩展算子结构** — 添加更多 IR 元素（UirBlock, UirValue, UirLayout 等）
- [ ] **JSON 序列化/反序列化** — `tree/src/io/github/xyzboom/aiFuzzer/ir/serialize/` 下实现
- [ ] **手工构建示例** — 构建一个最小 UIR 程序（relu + add）
- [ ] **DAG 拓扑排序** — UirGraph 的工具方法

### Phase 2 (翻译器)

- [ ] ONNX 翻译器
- [ ] IREE StableHLO 翻译器

### Phase 3 (模糊测试)

- [ ] Oracle 诊断（编译、形状、数值）
- [ ] 变异引擎
- [ ] 缩减引擎（DDMin）
- [ ] CLI 入口

---

## 生成方式

```bash
# 重新生成 IR 代码（修改 TreeBuilder.kt 后执行）
./gradlew :tree:generateTree

# 编译整个项目
./gradlew build

# 运行全部测试
./gradlew test
```