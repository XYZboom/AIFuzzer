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

---

## 待完成

### Phase 1 剩余项

- [ ] **扩展 IR 类型系统** — 添加更多属性类型（UirFloatAttr, UirBoolAttr, UirIntListAttr, UirTensorAttr 等，参考 UIR_DESIGN.md 3.7）
- [ ] **扩展算子结构** — 添加更多 IR 元素（UirBlock, UirValue, UirLayout 等）
- [ ] **JSON 序列化/反序列化** — `tree/src/io/github/xyzboom/aiFuzzer/ir/serialize/` 下实现
- [ ] **手工构建示例** — 构建一个最小 UIR 程序（relu + add）
- [ ] **DAG 拓扑排序** — UirGraph 的工具方法

### Phase 2 (翻译器)

- [ ] `translator/` 目录结构建立
- [ ] `UirTranslator` 接口
- [ ] TVM Relax 翻译器
- [ ] ONNX 翻译器
- [ ] IREE StableHLO 翻译器

### Phase 3 (模糊测试)

- [ ] 随机 UIR 生成器
- [ ] 算子枚举 + 类型约束
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
```