# zaozi 迁移到 Scala Native 的讨论笔记

主线影响统计以 `f1a5359d` 为准。生产源码的工作量见[按功能复核的交付清单](#按功能复核的交付清单)，分支的最终实测见[迁移验证记录](#迁移验证记录)。各次审计的运行结果按当时状态保留。

## 2026-11-18

### 已达成的设计决定

目标是让 zaozi generator 在 Scala Native 上直接调用 MLIR/CIRCT，同时把 `mlirlib` 做成第三方也能使用的 Scala Native MLIR 库。构建时，Scala 宏和编译器插件仍可运行在 JVM 上。

**一次插件调用就是活 IR 的生命周期边界。** 插件作者声明所需的 dialect，实现类似 `def transform(module: Module): Unit` 的方法。一次调用内可以把 `Module`、`Operation` 和 `Value` 传给其他函数，也可以在同一模块上连续运行多个 pass。框架负责原生资源的分配和释放。普通 generator 和插件作者都无需管理 `Zone` 或配对调用 `create/destroy`。

**插件之间传 MLIR bytecode（`.mlirbc`）。** 每个插件只解析一次输入、写出一次结果。保留输入后，可以用相同插件、配置和 dialect 单独重跑该阶段。独立分发的插件采用短命令行进程：stdin 读 bytecode，stdout 写 bytecode，stderr 写诊断，退出码报告成败。内置阶段也可以在进程内通过内存缓冲区传递同一种格式。各阶段需要能够读写所交换的 dialect。[MLIR bytecode 格式与兼容性](https://mlir.llvm.org/docs/BytecodeFormat/)

| 一次调用的时点 | 框架负责的资源 |
| --- | --- |
| 读入 | 创建本次调用的 context，注册所需的 dialect，按地址和显式字节长度解析模块。 |
| 最后一次 pass 返回 | 可以销毁 pass manager。Scala 闭包和回调状态要保留到 manager 的析构回调结束。 |
| 写出 | 保持 module、context 和 dialect 实现可用，直至 bytecode 完整写出。随后销毁 module 和 context。 |
| 任一步骤失败 | 释放已经取得的资源并报告错误。下游只消费成功完成的输出。 |

**命名和诊断采用 Scala Native 的 API 和惯例。** 自动生成的名字、哈希算法、异常类型和消息格式都可以随 Native 实现选择。

### 为什么选择这个边界

现有 Panama 版已经把生命周期藏在默认 generator 入口里。`designImpl` 创建 `Arena.ofConfined()` 和 MLIR context，通过 `given` 传给子模块。`Elaborate` 可以返回一个活模块，调用方在同一个外层作用域内继续验证、打印。FFM 的 `arena.close()` 负责释放 arena 分配的内存，MLIR 自己创建的对象另有析构函数。当前默认入口的 `context.destroy()` 位于正常返回路径，迁移时还要覆盖抛异常的路径。[默认入口](../zaozi/src/default/GeneratorApi.scala) · [Elaborate](../zaozi/src/default/Elaborate.scala) · [现有 pass manager 的销毁顺序](../mlirlib/src/capi/pass/given_PassManagerApi.scala)

讨论过为每个 IR handle 维护 owner 链、用 Cleaner 延长模块寿命，以及在 pass 后通过版本号让旧视图失效。这些机制解决的是让活 IR 跨运行存留的问题。选定 bytecode 作为边界后，下游从字节重建自己的 IR，上游插件可以立即退出。代价是每个边界多一次写出和读回，因此边界放在插件之间，插件内部可以合并多个 pass。

### 绑定和工具链

- 旧分支 `origin/sn` 将 `sn-bindgen` 接入 Nix，当时只改了 flake 和 overlay。迁移从当前 zaozi 主线起步。[旧分支提交](https://github.com/xinpian-tech/zaozi/commit/c4394b240c6787020d194a2063d99a2f0698de47)
- 绑定生成器采用上游 `main`，由 `flake.lock` 固定验证过的 revision。讨论时查到的分支头为 `7f00cb27`。先在该提交上复现问题，再决定是否附带小补丁并向上游修复。[上游相对 0.4.5 的改动](https://github.com/indoorvivants/sn-bindgen/compare/v0.4.5...7f00cb2766ff88a49e1f6a5497312f4f26807257)
- Scala Native runtime 独立选版，可以为需要的特性更新 Scala Native 或 Scala。Scala 3.8.4 有对应的 0.5.12 插件；包含外部线程进入 Scala 支持的 `0.5.13-20261114-143517b-SNAPSHOT` 是待验证的候选。该特性与 MLIR 工作线程调用 Scala external pass 直接相关。[上游线程回调改动](https://github.com/scala-native/scala-native/pull/5115)

### 实验记录和待验证问题

- **已实测到 C 目标文件。** 用项目锁定的 MLIR/CIRCT 头和 sn-bindgen 0.4.5 生成绑定，`mlirOperationStateGet` 和 `circtFirrtlCreateLinkCircuitsPass` 都得到了按值 struct 的 C 包装。`mlirSymbolTableWalkSymbolTables` 的函数指针参数名被写错位置；临时修正后，这组选定头生成的 C 成功编成了 `.o`。上游 `main` 随后的改动处理的是无参数原型的函数指针，这个具体问题仍需复测。完整 Scala Native 链接和 elaboration 也待验证。
- **读写链路。** 仓库已有 bytecode 写出和从字节读入的用例。需要验证两个独立插件的往返，包括含零字节的输入和失败输出。当前 `Array[Byte]` 转 `MlirStringRef` 会先多做一次 C 字符串转换，二进制路径应直接使用地址和长度。[现有写出](../zaozi/src/default/Elaborate.scala) · [读入用例](../circtlib/tests/src/OmSmoke.scala) · [字符串与字节转换](../mlirlib/src/capi/support/given_StringRefApi.scala)
- **回调与成本。** 验证按值 `MlirStringRef` 的反向调用，以及 external pass 在 MLIR 工作线程上的回调和析构顺序。用同一设计测量 bytecode 写出、读回和独立进程启动各占多少时间，再决定哪些内置 pass 适合编成同一条 pipeline。[ExternalPassLifetimeSpec](../mlirlib/tests/src/ExternalPassLifetimeSpec.scala) · [ExternalPassWalk](../zaozi/lit/tests/ExternalPassWalk.scala)

### eDSL、宏与 IDE 审计

基线为 `master@f1a5359d`、Scala 3.8.4 和 Mill 1.1.2。本次核对了源码、测试断言、发布的依赖 JAR 及 flake 锁定的语言服务器。以下是迁移影响和建议的验收方式；Native generator 和编辑器端到端行为仍待运行验证。

#### 一个字段访问经过三层

以 `io.payload.valid` 为例，外层 `Interface[T]` 通过端口表取 `Value`，内层 `Referable[T]` 通过字段引用访问硬件值。宏从 Bundle 声明推导返回类型；批编译插件为源码位置补字段符号；交互编译器插件让光标落到字段声明。硬件结果、跨文件引用和编辑中跳转有各自独立的验收条件。[Interface](../zaozi/src/reftpe/Interface.scala) · [Referable](../zaozi/src/reftpe/Referable.scala) · [编译器插件](../zaozi-compiler-plugin/src/me/jiuyang/zaozi/plugin/ZaoziSemanticDBPlugin.scala)

| 当前能力 | Native 迁移的关键修改或验证 |
| --- | --- |
| `@generator` 支持 `Generator` 和 `VerilogWrapper`，生成 `interface/probe/layers`，处理参数解析、默认值和 CLI 入口。前三个方法禁止覆盖，`parseParameter/main` 允许覆盖。 | 保留宿主 scalac 上的宏展开和下游 `-experimental`。编译一个独立下游设计，确认从发布物能加载宏、读取 TASTy、为展开的代码产生 NIR，并能链接和调用实际入口。宏依赖 `SingletonTypeTree` 的字符串形状及默认参数 getter 的名字，升 Scala 时优先回归。[宏定义](../zaozi/src/magic/macros/Generator.scala) · [参数负例](../zaozi/lit/tests/IllegalParameterType.scala) · [覆盖入口](../zaozi/lit/tests/OverriddenMain.scala) |
| 动态字段有普通、嵌套和可选字段（`Option[Ref[T]]`）。Vec 下标、Bits 取位和切片支持位置及命名参数；Record 另有显式按名访问。 | `Dynamic.scala` 的 Referable 路径直接 summon 并生成 `java.lang.foreign.Arena`，调用 Vec/Bits 时还按顺序拼接 contextual arguments。将这些路径和 typeclass 签名一起迁移。在 Native 目标上检查字段类型推导，以及编译器能否在调用处指出无效字段和参数。直接在 `Interface` 上选字段只读端口表，可继续单独处理。[动态宏](../zaozi/src/magic/macros/Dynamic.scala) · [Vec 正负例](../zaozi/tests/src/VecSpec.scala) · [Record 语义](../zaozi/tests/src/RecordSpec.scala) |
| `InstanceChoice` 从内联的 `label -> generator.architecture(parameter)` 提取候选，允许共享接口的不同参数类型，生成 FIRRTL option 和选择实例。`DVInterface.summonLayers` 根据宏调用位置找到层类型。 | 同步修改 `Generator.architecture`、候选函数类型、宏匹配以及生成的 `dumpMlirbc/instantiateChoice` 调用。保留 case 对上下文的限制、参数单次求值，以及用户给出的 label 与目标实现的对应关系。保留 layer 推导并回归实际 probe 的层级。[InstanceChoice 宏](../zaozi/src/magic/macros/InstanceChoice.scala) · [包含两种参数类型的 RTL 用例](../zaozi/lit/tests/InstanceChoice.scala) · [LayerSpec](../zaozi/tests/src/LayerSpec.scala) |
| 硬件类型与运算包括 Bits/Bool/UInt/SInt、Clock/Reset、Vec/Bundle/Record、Wire/Reg/RegInit/Node、when/otherwise、位宽、符号、Const 传播；连接区分 Readable/Writable、方向、bulk connect、DontCare 和 probe。 | 保留 Scala typeclass、match type、扩展方法及 `:=`、`:<>=`、`:<=`、`:>=` 的用户语义。迁移它们使用的 Context、Block、Type 和 Value 接口。实际端口是多个 SSA Value，MLIR 类型查询和 connectability 依赖 CIRCT；在 elaboration 内持续使用同一个活模块，只在插件边界导出字节。现有 `FlowMatrixSpec`、`WidthSpec`、`ConstPropSpec` 分别约束方向、位宽和返回类型。[DSL API](../zaozi/src/Api.scala) · [连接实现](../zaozi/src/default/Connect.scala) · [FlowMatrixSpec](../zaozi/tests/src/FlowMatrixSpec.scala) |
| 验证 DSL 包括 layer、RProbe/RWProbe、SVA/LTL、assert/assume/cover，以及带结果和 require/ensure 的 contract。 | `ContractApi.operationIsNull` 越过 Scala 接口读取 jextract 的 `MlirOperation.ptr(op.segment).address`；将空句柄判断放回 `mlirlib`。保留 contract 的嵌套、region 内 operation 的移动及结果顺序。进程内复用时也要审查文件级可变 `contractScopes`；不能把活动 IR 带到下次调用。[Contract 实现](../zaozi/src/default/ContractApi.scala) · [ContractSpec](../zaozi/tests/src/ContractSpec.scala) · [SVASpec](../zaozi/tests/src/SVASpec.scala) |
| 实例化、按模块名去重、参数化 extmodule，以及两种输出路径。默认 `config` 写 JSON，`design` 在 `ZAOZI_OUTDIR` 写逐模块 bytecode；`Elaborate(top,p)` 可收集层次、内存内链接并返回活模块。 | 保留这两种现有调用行为；插件入口在当前运行内对已链接模块做一次序列化。逐模块路径的 `Files.written` 是进程级集合，内存路径使用 `DynamicVariable` 和本次的表。进程内运行两次时要验证相同名字、不同输出目录的行为。验证外部 Verilog 参数顺序和实例去重。`MultipleInstances` 的 firld 命令硬编码了 JVM 生成的顶层名；Native 用例可为测试顶层显式命名，再检查实例是否链接到正确模块。[Elaborate 实现](../zaozi/src/default/Elaborate.scala) · [多实例用例](../zaozi/lit/tests/MultipleInstances.scala) · [extmodule 和去重](../zaozi/lit/tests/Elaborate.scala) |

#### IDE 是两条编译链

- **已保存源码的索引。** 批编译靠 `-Xsemanticdb`、`-sourceroot` 和 JVM 上的 `zaozi-compiler-plugin`。插件在 SemanticDB 提取后、`posttyper` 前补字段定义并改写引用，识别 `selectDynamic/applyDynamic/applyDynamicNamed`。锁定的语言服务器用这些索引实现工作区符号、引用和跨文件 rename。Native Mill target 应向 BSP 暴露自己的源码、classpath、插件参数和 SemanticDB 输出目录；继续验证单独重编使用方、指定 `-semanticdb-target` 和同名字段。[构建接线](../build.mill) · [插件与 fixture 的测试](../zaozi-compiler-plugin/tests/src/PluginSpec.scala)
- **尚未保存的编辑缓冲区。** presentation compiler 的导航 phase 在 `typer` 后、`SetRootTree` 前重写 `Inlined.call`，使普通和嵌套 `io.field` 的定义跳转、hover 找到声明。它明确跳过 `applyDynamic/applyDynamicNamed`；索引能处理下标或切片的字段符号，并不能证明编辑中的相同位置也能跳转。补全和签名帮助来自语言服务器；仓库里的插件测试检查符号和编译器树，没有对所有编辑器操作逐一验收。[交互 phase](../zaozi-compiler-plugin/src/me/jiuyang/zaozi/plugin/ZaoziPcNavPhase.scala) · [锁定的语言服务器](https://github.com/xinpian-tech/scala3-bsp-semantic-ls/tree/b0af17cbfdbc22f247e6c4dc6e31ed4b041beafe)
- **版本与交付。** 当前 Linux Nix shell 把 Zed 接到 `scala3-bsp-semantic-ls`，服务端自带的 PC 固定 Scala 3.8.4，并从 BSP 接收项目的 `-Xplugin`。若升级 Scala，需同时核对该 PC、插件使用的 Dotty 私有 SemanticDB API 和 phase 名。保留编译器插件为 JVM JAR；在真实 Native target 上验证它与 Scala Native 代码生成插件共存、编辑器能处理实际 scalacOptions、开关导航插件不改变批编译产物。运行时 MLIR pass 插件只接触 bytecode，与上述编译器插件独立。[项目 flake](../flake.nix) · [Dotty 内部适配](../zaozi-compiler-plugin/src/dotty/tools/dotc/semanticdb/ZaoziSemanticdbFacade.scala) · [服务端版本](https://github.com/xinpian-tech/scala3-bsp-semantic-ls/blob/b0af17cbfdbc22f247e6c4dc6e31ed4b041beafe/build.mill) · [插件加载方式](https://github.com/xinpian-tech/scala3-bsp-semantic-ls/blob/b0af17cbfdbc22f247e6c4dc6e31ed4b041beafe/docs/plugin-spi.md)

#### 直接影响实现的发现

- **公开签名。** `zaozi/src` 有 53/59 个 Scala 文件直接引用 `java.lang.foreign`，`stdlib/src` 有 17/28 个。数量含 import；`architecture`、`Referable.refer`、`DynamicSubfield` 和 typeclass 方法签名是已确认的实际耦合。建议由当前 elaboration 隐式提供平台无关的运行上下文，保留有语义的 block、clock、reset 等嵌套作用域。普通 `architecture(parameter)` 可以保留原写法，显式声明 `using Arena` 的第三方扩展需要迁移。[公共 API](../zaozi/src/Api.scala) · [字段扩展入口](../zaozi/src/magic/DynamicSubfield.scala)
- **Native API 替换点。** `Naming.scala` 的 JNDI 异常和 `PLADecoder` 的 Java 摘要调用可以直接换成适合目标平台的命名和报错实现。验收检查模块引用可解析、无效输入能报告错误。已核对 Native 版 sourcecode 0.4.2、mainargs 0.7.6、uPickle 4.0.2 的发布 JAR 均包含 class、TASTy 和 NIR。[命名与源码位置](../zaozi/src/default/Naming.scala) · [PLA 模块名](../stdlib/src/default/PLADecoder.scala) · [核对的 javalib 构件](https://repo.maven.apache.org/maven2/org/scala-native/javalib_native0.5_3/0.5.12/)
- **测试入口。** 现有 lit 从 scala-cli 注入 JVM classpath 和 Panama 选项；插件 fixture 的 JVM scalac 也取 `zaozi.runClasspath`，源码里显式出现 `Arena`。分别准备宿主编译用的 classpath 和 Native 运行目标；给下游发布可展开宏且包含 NIR 的库，并用 Native 入口执行 generator。Mill 的 Native 目标需要按平台解析依赖；JDK 25 输出设置和 JAR 的 FFM manifest 属于现有 JVM 启动路径。负例覆盖宏展开、elaboration 和 firtool/pass 三个阶段，按 Native 工具提供的诊断检查失败原因。JMH 仍可作 JVM 基线，Native 运行时间和重新链接时间需分别测量。[测试构建](../zaozi/package.mill) · [插件 fixture 接线](../zaozi-compiler-plugin/package.mill) · [firtool 负例](../zaozi/lit/tests/IllegalConnectFlow.scala) · [诊断收集](../zaozi/src/default/Diagnostics.scala)

建议的第一条贯通验收是用同一份下游设计声明嵌套 Bundle、访问 `io.payload.valid`、连接端口并实例化两次：宿主编译展开宏；记录已保存源码的字段引用、rename 及未保存缓冲区的定义跳转；Native 运行 `config/design`，校验 JSON、实例引用和去重后的 IR，再检查 firtool 输出的 RTL。再分别跑命名下标负例、InstanceChoice、probe/layer 和 Contract/SVA。这样可以定位第一次偏差发生在编辑器、编译器、elaboration 还是下游 pass。

### 工程量复核

审计基线为 `master@f1a5359d`。本次覆盖 generator 作者使用的 eDSL、第三方编写变换所需的 `mlirlib`，以及两者的构建和编辑器支持。

#### 现有代码的改动面

| 生产源码 | 审计结果 | 对实现的影响 |
| --- | --- | --- |
| `mlirlib/src` | 54 个 Scala 文件，51 个提及 FFM。含 33 个 `given_*.scala`、15 个 `CAPIDefinition.scala`、2 个 `CAPIImplementation.scala`。 | Scala handle 定义、公共 typeclass 和调用 C 的实现需要配套切换。资源和 callback 的行为集中在其中一部分文件。 |
| `circtlib/src` | 81 个 Scala 文件，73 个提及 FFM。对应三类文件分别有 40、22、5 个。 | 大批 pass 注册与创建可沿用同一个转换模式。FIRRTL/OM/HW 等 dialect 里的 struct 字段、变长参数、遍历回调要按实际 C 原型处理。 |
| `zaozi/src` | 去掉注释和 import 后，59 个文件中有 53 个仍出现 `Arena`，共 484 处；其中 181 处在 `Api.scala`。 | 集中修改公共签名能覆盖大批重复改动。单独审查把上下文写进宏树的地方、跨模块持有的 IR，以及穿透封装读取 jextract 布局的地方。 |
| `stdlib/src` | 28 个文件中有 17 个提及 FFM，16 个在 import 和注释外使用 `Arena`。 | 使用 DSL 的模块和扩展方法跟随公共签名迁移。通过 Queue、IOMux、RegMap、PLADecoder 等真实组合验证下游写法。 |

MLIR 和 CIRCT 的函数清单分别有 1,662 和 669 个不同名称。用清单匹配各模块生产 Scala 源码，分别有 273 和 351 个名称出现过，含 import。前一组决定生成器的输入范围，后一组帮助找到已有接线；手写适配要看 C 参数布局和所有权。同样是一个名字，创建 conversion pass 通常只需转交返回值，`mlirCreateExternalPass` 则涉及多种回调和跨线程状态。[MLIR 清单](https://github.com/xinpian-tech/zaozi/blob/f1a5359d/mlirlib/capi/includeFunctions.txt) · [CIRCT 清单](https://github.com/xinpian-tech/zaozi/blob/f1a5359d/circtlib/capi/includeFunctions.txt) · [Conversion 包装](../circtlib/src/capi/conversion/given_ConversionCreateApi.scala) · [External pass 包装](../mlirlib/src/capi/pass/given_ExternalPassApi.scala)

#### eDSL 有哪些实质修改

| 改动单位 | 可以沿用的部分 | 需要动手的部分与完成条件 |
| --- | --- | --- |
| 公共签名和默认实现 | generator 的 `architecture(parameter)` 写法，Data/Ref 类型层次，位宽、读写能力、连接方向的规则。 | 对齐 `Generator.architecture`、`Referable.refer`、typeclass、默认实现和第三方扩展入口。由框架提供本次 elaboration 的上下文，底层按 C 调用需要分配临时内存。保留可嵌套的 Block、clock、reset、layer 语义。验收普通 generator 无需写 `Zone`，自定义扩展也能取得正确的插入 block。[公共 API](../zaozi/src/Api.scala) · [Referable](../zaozi/src/reftpe/Referable.scala) |
| 字段和下标宏 | 从 Bundle/Record 声明解析字段、推导 `Ref[T]` 或可选字段的类型。 | `io.payload.valid` 的第一步从 `Interface._ports` 取已有 SSA value；内层 `Referable` 选择字段时需要在当前 block 建 IR。`Dynamic.scala` 在宏展开时查找 `Arena`，并在生成的代码里显式提供它，对 Vec/Bits 还按位置拼接上下文参数。同步修改宏和被调用方法；用嵌套和可选字段、Vec 下标、Bits 取位/切片及命名参数验证返回类型和产生的 IR。[Interface](../zaozi/src/reftpe/Interface.scala) · [Dynamic 宏](../zaozi/src/magic/macros/Dynamic.scala) |
| Generator、实例和层宏 | 宏在宿主 JVM 上展开。`@generator` 生成接口、probe、layers 和 CLI 等已有规则。`summonLayers` 从调用处推导层类型。 | 用独立下游编译 `@generator` 和 `VerilogWrapper`，覆盖默认参数、允许覆盖的入口和拒绝的声明。`InstanceChoice` 把含 `Arena` 的整个 architecture 函数类型写进 quote，匹配调用树，还显式给生成的 `dumpMlirbc` 传参。声明、匹配、展开结果三处一起改。用不同参数类型的两个候选检查 label、目标模块、参数单次求值和最终 RTL。[Generator 宏](../zaozi/src/magic/macros/Generator.scala) · [InstanceChoice 宏](../zaozi/src/magic/macros/InstanceChoice.scala) · [实例用例](../zaozi/lit/tests/InstanceChoice.scala) |
| 硬件操作和验证 DSL | Bits/UInt/SInt、Vec、Wire/Reg、when、bulk connect、probe、SVA/Contract 的 Scala 层语义和现有测试设计。 | 类型查询、创建 operation、端口索引、region 插入和结果顺序需接到 Native handle。特别是 `ContractApi` 当前直接读取 `MlirOperation.ptr(op.segment).address`，且在文件级可变栈存活动 clause。空值判断收回绑定层，嵌套 clause 按本次 elaboration 管理。验收覆盖连接方向、位宽、常量、layer/probe 以及嵌套 contract 的结果。[Connect](../zaozi/src/default/Connect.scala) · [Contract](../zaozi/src/default/ContractApi.scala) · [核心测试](../zaozi/tests/src) |
| 层次、链接和默认 CLI | `config` 写参数 JSON，`design` 写逐模块 `.mlirbc`，内存内 elaboration 能得到一个已链接的电路。 | 文件输出使用进程级 `Files.written` 去重；内存模式则逐个从旧 module 取出 circuit，先 detach，再转交给新 module，最后运行 link pass。去重表按调用隔离，明确转移前后的销毁责任，分别关闭输出流、pass manager、旧 module 和已链接 module。用多实例、同名设计连续输出到不同目录、参数化 extmodule 和失败后的再次调用验收。插件对已链接电路输出一份 bytecode。[Elaborate](../zaozi/src/default/Elaborate.scala) · [多实例](../zaozi/lit/tests/MultipleInstances.scala) · [链接与 extmodule](../zaozi/lit/tests/Elaborate.scala) |

#### 构建、公共 MLIR 库和变换插件

1. **绑定生成和发布。** 把 Mill/Nix 的 jextract 输入接到固定 revision 的 sn-bindgen，产出 Scala 声明和所需 C 包装，编译后同项目锁定的 MLIR/CIRCT 链接。`circtlib` 现在合并了 MLIR 的若干声明清单，两个库之间需要共享同一套 `MlirContext/MlirType/MlirValue` 表示，让下游把 MLIR 值直接传给 CIRCT。交付能独立依赖的 Native 库，包含下游 scalac 需要的宏实现与 TASTy、Native 链接需要的 NIR、C 包装与库配置；另外构建可执行的 generator。验收从一个独立工程引用产物、链接、创建 context、调用 CIRCT 并退出。先在 sn-bindgen 的上游 `main` 重现旧版回调声明问题，再依据结果决定是否需要补丁。[构建](../build.mill) · [CIRCT 构建输入](../circtlib/package.mill) · [现有 Nix 产物](../nix/zaozi/zaozi.nix)
2. **`mlirlib` 的 C ABI。** 先处理所有简单 wrapper 共用的 handle、空值、enum、按值传递/返回，再核对有特殊布局的接口。举例，创建 operation 时 operands 是连续的 `MlirValue` struct；`HWModulePort` 的 name/type 是内嵌字段；JIT 的 `invokePacked` 收到的是一列地址（`void **`）。三条路径要分别覆盖。bytecode 使用地址加长度，回调中复制每一段字节；打印和诊断另有字符串和结果语义。以构建并 verify 一个含 operand 的 operation、分段输出带零字节的数据并重新读入、实际执行 JIT 并取回结果作为检验。[数组转换](../mlirlib/src/capi/support/CAPIImplementation.scala) · [HW 字段](../circtlib/src/capi/dialect/hw/CAPIImplementation.scala) · [ExecutionEngineSpec](../mlirlib/tests/src/ExecutionEngineSpec.scala)
3. **第三方变换入口。** 框架按插件声明注册 dialect，读入 module，调用作者的 `transform(module)`，写完结果后释放资源。普通同步调用的临时 buffer 可在返回后释放；IR 的有效期由本次调用覆盖。作者能在一次变换内跨函数使用活 IR。验收用两个分别构建的程序互传 bytecode，验证第二个程序能读到第一个做的修改；再给出损坏输入或让变换失败，检查退出码、诊断和下游不会消费半份输出。进程内执行两次也应各自清理。[bytecode 读入用例](../circtlib/tests/src/OmSmoke.scala)
4. **Scala 实现 MLIR external pass。** 这是 `mlirlib` 支持的另一种入口。MLIR 可主动回调 construct、initialize、clone、run 和 destruct，执行线程由 MLIR 决定。Native 端要把 C 函数指针与能被 GC 跟踪的 Scala 状态配对，为失败回调保存错误，按 C API 允许的方式报告失败；pass manager 的析构完成后才能释放最后的回调状态。单独覆盖工作线程、clone、异常、析构和后一轮运行。普通命令行插件能先用同步 transform 验收。[ExternalPassLifetimeSpec](../mlirlib/tests/src/ExternalPassLifetimeSpec.scala) · [遍历用例](../zaozi/lit/tests/ExternalPassWalk.scala)

#### IDE、回归入口与其余模块

- **IDE 有两份交付证据。** 宿主 JVM 的批编译插件继续为动态字段写 SemanticDB，Native Mill target 要通过 BSP 给出正确的源码、依赖、`-Xplugin` 和索引目录。用保存后的跨文件引用和 rename 验收，包括增量重编。交互编译器另行加载导航 phase，在未保存的缓冲区验证嵌套字段的定义跳转和 hover。现有该 phase 只处理普通字段选择。若升 Scala，还要同步核验 Dotty 私有 API、phase 顺序和编辑器锁定的 PC 版本。[批编译插件](../zaozi-compiler-plugin/src/me/jiuyang/zaozi/plugin/ZaoziSemanticDBPlugin.scala) · [交互插件](../zaozi-compiler-plugin/src/me/jiuyang/zaozi/plugin/ZaoziPcNavPhase.scala)
- **已有回归素材，运行入口要迁。** 核心有 17 个 uTest 源文件、12 个 lit 设计/脚本，stdlib 有 14 个 lit 设计/脚本，编译器插件的 spec 定义 9 项测试。`testlib` 自己也直接创建和销毁 Arena/context，须经同一套 Native 运行入口。`ExportVerilog` 现在用 JVM scala-cli 启动并匹配带固定哈希的模块名；Native 用例检查 32 位端口和 `assign o = i`。负例分别在宏展开、elaboration 和 firtool/pass 阶段触发，检查对应操作确实失败以及原因，诊断文字和自动名字采用 Native 行为。[测试辅助库](../testlib/src/ZaoziTest.scala) · [Passthrough](../zaozi/lit/tests/ExportVerilog.scala) · [插件测试](../zaozi-compiler-plugin/tests/src/PluginSpec.scala)
- **跑通核心后，还有独立功能。** `stdlib` 的时序、队列、寄存器映射和 IO 组合覆盖实际硬件；`omlib` 要读 OM 值；`smtlib` 要迁直接使用 jextract 的 registry、指针类型、operation 空值等调用，验证 SMT-LIB 导出与 JIT 返回值；`decoder` 要能启动 espresso，`rvdecoderdb` 要能从 Native 产物取得指令表归档、调用解包工具。各模块分别构建和运行，最后从 Nix 产物做独立下游验收。[stdlib 用例](../stdlib/lit/tests) · [OM 用例](../circtlib/tests/src/OmSmoke.scala) · [SMT 执行路径](../smtlib/src/SMTExecution.scala) · [指令表读取](../rvdecoderdb/src/Api.scala)

**建议最先验证的依赖链。** 固定工具链生成绑定 → 一个 Native 程序创建 context、构造 operation、往返 bytecode → 独立下游的 Passthrough 完成 `config/design` 并得到预期 RTL。接着用一个包含嵌套字段和重复实例的设计同时检验宏、层次链接和 IDE；MLIR 工作线程回调与 SMT/JIT 各用独立用例。scalac 会对整个目标做类型检查，最小电路只限制执行路径，同一个 target 中的公共签名仍须一起迁。本轮早期调研的实测止于选定头文件生成的 C 包装经临时修正后编成目标文件，完整 Native 链接、下游宏展开、编辑器和跨插件往返均待验证。

### 按功能复核的交付清单

比较主线 `f1a5359d` 与 `scala-native` 工作树。下面的文件数只计各模块 `src/` 里的生产 Scala 源。FFM 指源码中出现 `java.lang.foreign`，含 import。单独统计 `Arena` 时去掉了注释和 import，并按完整单词匹配。

| 模块 | 主线文件数 | 主线直接引用 FFM 的文件 | 当前已改动的原有生产文件 |
| --- | ---: | ---: | ---: |
| zaozi eDSL | 59 | 53 | 53 |
| stdlib | 28 | 17 | 17 |
| Scala 编译器插件 | 3 | 0 | 0 |
| mlirlib | 54 | 51 | 51 |
| circtlib | 81 | 73 | 76 |
| smtlib / omlib | 7 / 1 | 5 / 1 | 5 / 1 |
| decoder / rvdecoderdb | 4 / 15 | 0 / 0 | 0 / 1 |
| testlib | 1 | 1 | 1 |

#### eDSL 需要分别交付的行为

| 用户操作 | 实现改动与可复用的部分 | 怎样判断完成 |
| --- | --- | --- |
| 在 `architecture(parameter)` 内写电路 | 53 个 eDSL 文件里实际用到 `Arena` 共 484 处，181 处集中于 `Api.scala`。默认实现传入 Native 分配作用域和 MLIR context。当前插入 block、clock、reset、layer 和 instance 作用域仍分别有含义。显式声明上下文的第三方 DSL 扩展需要更新类型。 | 普通 generator 无须打开或关闭 Zone。自定义扩展在嵌套 block 内建出来的 operation 位于正确的 region，离开局部时钟或复位作用域后能恢复外层设置。[公共签名](../zaozi/src/Api.scala) |
| 写 `io.payload.valid`、可选字段、下标、切片 | 最外层端口由 `Interface` 按索引取已有 Value。嵌套 `Referable` 要根据字段类型在当前 block 创建访问操作。两者共享部分宏逻辑。Vec/Bits 路径还会拼接 contextual arguments，宏与其调用目标必须同步。 | 独立工程编译普通、嵌套、可选字段和命名参数切片，检查推导的引用类型、IR 的字段和位范围。错误字段或无效参数留在原来的失败环节。[Dynamic](../zaozi/src/magic/macros/Dynamic.scala) |
| 定义 generator、外部 Verilog 模块或可选实例 | 当前 diff 中，三个宏实现文件里只有 `Dynamic.scala`、`InstanceChoice.scala` 有改动。`Generator.scala` 内的注解展开、默认参数和 layer 推导可沿用。`InstanceChoice` 同时依赖 architecture 的函数类型、调用树形状和生成的实例化调用。 | 用发布物展开宏并链接 Native generator。覆盖默认参数、可覆盖的 main、非法声明、不同参数类型的候选、候选参数只求值一次，以及目标模块和 label 的对应。[Generator](../zaozi/src/magic/macros/Generator.scala) · [InstanceChoice](../zaozi/src/magic/macros/InstanceChoice.scala) |
| 连接端口、实例化寄存器、写验证逻辑 | 保留位宽、符号、常量和读写能力规则。Native 适配要正确构造 operand 数组、选择 result、取得端口方向，并把 when、layer、contract 的 operation 插入正确的 region。contract 还需要运行内隔离其 clause 栈。 | 核心测试验证类型与 IR，firtool 再检验真实电路。分别覆盖 signed/unsigned、翻转的嵌套 Bundle、Probe、SVA，以及带多个结果的嵌套 Contract。自动名和异常文案采用 Native 行为。[连接](../zaozi/src/default/Connect.scala) · [Contract](../zaozi/src/default/ContractApi.scala) · [核心测试](../zaozi/tests/src) |
| 运行 `config/design` 或内存内 elaboration | 文件模式要输出各模块的 bytecode，内存模式要把子电路从原 module 移入目标 module 并运行 link pass。去重表须按本次设计管理，异常路径也要释放拥有的模块。 | 同一参数实例化两次，只产生一份模块定义和两个正确引用。同一进程向不同目录运行两次，各目录结果完整。Passthrough 的 32 位端口和 `assign o = i` 通过 firtool 验证。[Elaborate](../zaozi/src/default/Elaborate.scala) · [多个实例](../zaozi/lit/tests/MultipleInstances.scala) · [导出 Verilog](../zaozi/lit/tests/ExportVerilog.scala) |
| 在编辑器里用动态字段 | 3 个宿主插件生产文件保持原样，变化集中在目标 classpath、插件加载和 fixtures。批编译写 SemanticDB，交互编译器单独处理打开的文档。现有交互 phase 只识别普通字段选择。 | 在实际 Native BSP target 上保存并增量重编，查跨文件引用与 rename。再在未保存的嵌套字段访问处请求定义和 hover。插件单测当前 9/9 通过，真实语言服务器往返仍待验证。[插件测试](../zaozi-compiler-plugin/tests/src/PluginSpec.scala) · [交互导航](../zaozi-compiler-plugin/src/me/jiuyang/zaozi/plugin/ZaoziPcNavPhase.scala) |

#### 第三方库与程序交付

- **绑定生成和链接。** 工作树固定上游 sn-bindgen `main@7f00cb27`。现有项目适配器处理了函数指针、定长数组声明以及 CIRCT 和 MLIR 共享的结构体映射。对于普通 C 函数可以批量转接，按值结构体、反向回调、JIT 的 `void **` 各有不同的内存布局。当前 firtool verification flavor 另需一个 C++ 适配器。上游生成器源码可照用，项目仍须维护这些生成、编译和链接规则。[生成脚本](../native/bindings.py) · [firtool 适配器](../nix/pkgs/firtool.cpp) · [Scala Native C 互操作](https://www.scala-native.org/en/stable/user/interop.html)
- **回调所得的 IR。** 现有 `walk` C 桥把按值 `MlirOperation` 参数的地址传给 Scala，Scala 直接用这个地址创建 `Operation`。如果用户在遍历中收集 operation，再在同一插件调用中处理，C 栈上的 handle 存储已经失效，即使底层 IR 依然存在。这里应把 handle 的值复制到本次调用有效的存储，保持 MLIR 本身的所有权，且不能延长已被删除的 IR 的寿命。HW instance graph 的回调有同样的地址传递方式。验收要在遍历结束后读取先前收集的几个不同 operation。[C 回调](../mlirlib/resources/scala-native/callbacks.c) · [Scala 接收方](../mlirlib/src/capi/support/NativeCallbacks.scala) · [HW 遍历](../circtlib/resources/scala-native/graph.c)
- **一次变换的生命周期。** `ModuleTransform` 已负责注册 dialect、读入、变换、verify、写出和清理 module/context。现有 `zaozi-opt` 自己创建和销毁 pass manager；要让编写普通 pipeline 的第三方也省掉这段管理，需提供库级入口。external pass 的闭包、GC、clone/析构和异常回传另有 Native 测试。插件协议的验收要用两个分别构建的进程，第二个读到第一个实际修改的 IR；损坏数据和失败 pass 应有失败退出码、stderr 诊断以及空的结果输出。[ModuleTransform](../mlirlib/src/Mlir.scala) · [zaozi-opt](../zaozi/opt/src/ZaoziOpt.scala) · [外部 pass 测试](../mlirlib/tests/src/ExternalPassLifetimeSpec.scala) · [MLIR 的所有权](https://mlir.llvm.org/docs/CAPI/#naming-convention-and-ownership-model)
- **独立下游和仓库设施。** 下游 scalac 需要宏实现和 TASTy，Native 链接需要 NIR、C 包装和可解析的 MLIR/CIRCT 库。离线 Nix 构建、发布物、外部工程、文档和 CI 都要对准同一个入口。现有 benchmark workflow 和锁文件更新脚本仍调用原来的 JMH target。stdlib 14 个 lit 用例、OM 读取、SMT 导出与执行、decoder 的 espresso、rvdecoderdb 的归档资源各有独立的运行路径。[构建接线](../build.mill) · [Nix 安装](../nix/zaozi/zaozi.nix) · [benchmark CI](../.github/workflows/benchmark.yml) · [锁更新脚本](../bump-mill-lock.sh)

#### 第一次分支复核的运行结果

| 已有记录 | 结果与证据边界 |
| --- | --- |
| `mill zaozi.compile` | Native 配置下编译通过 59 个 eDSL 源文件。 |
| Native 单测 | `mlirlib` 57/57（含 MLIR 工作线程运行 external pass、JIT packed 调用），`decoder` 63/63，`rvdecoderdb` 1/1。宿主 JVM 的编译器插件 9/9。 |
| `mill __.testForked + zaozi.opt.nativeLink` | 5 个目标都在编译同一个 `firtool.cpp` 时失败，LLVM 头使用的 C++ 标准库特性在当前编译模式下不可用。影响 `circtlib`、`smtlib`、`zaozi`、`stdlib` 的测试可执行文件和 `zaozi-opt`。 |
| 核心 lit | 最近一轮 12 个用例均在启动测试用 scala-cli 时失败，启动器未取得 `JAVA_HOME`，尝试执行 `/bin/java`。这一轮未验证任何设计的 RTL 行为。 |

从现在的状态判断，硬件类型规则和宿主宏已有相当部分可复用。还需要手写判断、且对第三方最有影响的是回调返回后 handle 是否有效、谁拥有每个 module/pass，以及从发布物独立展开宏并链接的全过程。以上运行结果均来自当前工作树的本地记录。


### 迁移验证记录

基线为 `f1a5359d`，分支为 `scala-native`。表中的数量只用于界定需要审查的代码。对工程量更有区分度的，是同一个设计必须经过哪些不同的执行路径。

| 交付对象 | 实际改动 | 验证 |
| --- | --- | --- |
| eDSL 的类型与电路 | 59 个生产源文件里，53 个显式依赖 FFM。上下文参数贯穿 API、默认实现、Ref/Data、block、clock、reset 和 layer。类型规则沿用原实现。Native 还需要正确打包 operand/result 数组、传递 region 和插入点，以及在模块级保存实例引用和去重状态。 | zaozi 的 223 项单测验证硬件语义。12 个核心 lit 覆盖生成 CLI、宏负例、重复实例、probe、InstanceChoice、external pass 和实际导出 Verilog。stdlib 的 14 个 lit 另行运行，包括 Queue、IOMux、PLA 和 RegMap。 |
| 三个宏实现 | `Dynamic` 和 `InstanceChoice` 展开的代码含分配上下文或 architecture 的函数类型，必须与运行时签名一起改。`Generator` 的注解展开逻辑可沿用。独立本地模块名来自 Native 的类名，需要正确取得声明名后才能做层次去重。 | 仓库外的 Passthrough 从 Maven 产物展开注解，单独链接为 Native 可执行文件，执行 `config/design`，经 firtool 和 FileCheck 验证 32 位输入输出及 `assign o = i`。 |
| IDE 的两条路径 | 三个宿主插件生产源文件均可沿用。批编译加载 Native 后端和 zaozi 插件并生成 SemanticDB。锁定版本的交互编译器若同时加载 Native 后端，连普通局部变量都无法查询。Nix 打包的语言服务器在创建交互编译器前过滤后端、保留 zaozi 导航插件。 | 9 项宿主插件测试通过。仓库外的 Mill Native target 通过实际 BSP/LSP 验证保存后的跨文件 references 和 rename。在未保存的 `io.inner.flag` 上，definition 指向另一源文件的 `val flag`，hover 同时显示 `Ref[Bool]` 和 `val flag: BundleField[Bool]`。客户端使用的是 LSP 协议测试程序。 |
| MLIR 与 CIRCT 库 | 从锁定的上游 sn-bindgen `main@7f00cb27` 生成 MLIR/CIRCT 绑定。项目侧处理共享结构、定长数组与函数指针声明，按值回调使用 C 桥。CIRCT 的 firtool 选项有一处需要 C++ 桥。锁定版本的头声明了 `circtMSFTGetInstance`，安装库缺少该符号，旧 Scala 包装层也没有调用它。 | mlirlib 的 59 项测试和 circtlib 的 93 项测试覆盖 operation 遍历、回调、JIT 调用、OM 等。工作线程测试驱动 MLIR 克隆 pass、从 C++ 进入 Scala 并触发 GC；在 manager 已经销毁、module 仍然存活时还能读回 32 个不同的子模块。独立下游程序能够链接生成的 Native 绑定和 C 桥。 |
| 第三方插件和资源 | `ModuleTransform` 由框架建立 context，加载 dialect，解析、执行、验证并写出 bytecode，成功与抛错都清理本次资源。`Mlir.runPipeline` 在内部管理 pass manager。普通插件只实现对 module 的修改。传输使用显式字节长度。 | 两个独立编译的插件通过 stdin/stdout 交换 bytecode，后一个验证前一个实际写入的属性。另用两个 `zaozi-opt` 进程串起 symbol-dce 和 canonicalize。损坏输入或变换失败写 stderr 并返回失败状态。库级回归还检验含零字节的属性、截断输入，以及遍历结束后读取多个不同 operation。 |
| 下游与构建 | Mill 分开发布 Native 库和 JVM 编译器插件。硬件库的下游同时需要 TASTy、NIR、C 桥、头文件与链接库。Nix 还需记录 Native snapshot 和 Maven Central 的依赖。SMT/JIT、espresso、RISC-V 归档与 benchmark 各有独立入口。 | 602 项单测中 593 项在 Native 上运行，9 项宿主插件测试在 JVM 上运行。另有 26 个 lit 设计/脚本。Native GCD benchmark 通过一次执行验收。验证平台为 x86_64 Linux。 |

已采用 Scala 3.8.4 与 Scala Native `0.5.13-20261114-143517b-SNAPSHOT`。工作线程进入 Scala 是选用该 revision 的具体原因。sn-bindgen 采用上游源码，项目内维护 C 声明修正和生成规则；语言服务器的 Native 后端过滤补丁也由项目维护。[绑定生成](../native/bindings.py) · [生命周期入口](../mlirlib/src/Mlir.scala) · [语言服务器补丁](../nix/patches/scala-native-presentation-compiler.patch) · [开发和独立插件用法](development.md)

**安装包验收。** `nix build .#zaozi` 使用重新生成的依赖锁在离线 Mill 构建中产出 `bin/zaozi-opt` 和 `share/maven`，其中包含 10 个 Native 库及 1 个宿主编译器插件。用该 Maven 目录在仓库外从源码构建 Write、Read 和 Passthrough 三个可执行文件，使用 `nix develop` 提供的 Scala CLI 和 clang。Write/Read 串联后的 bytecode 可由 mlir-opt 读出修改，Read 单独收到缺少所需属性的合法模块时以失败状态退出、stdout 为空。Passthrough 的 `config/design` 产物通过 firtool 和 FileCheck。另用安装包里的两个 `zaozi-opt` 进程串联，检查私有未使用函数被删除、公开函数仍在。

### T1 下游验证与模块写盘

T1 的完整 testbench 暴露了逐模块导出时的内存开销。文件模式现在由 MLIR 的 bytecode 回调直接写入临时文件，写完后原子替换目标。每个模块的 FFI 临时分配随该模块释放。内存模式下，供后续链接使用的模块仍归调用者的作用域管理。

T1 从 zaozi 的 `zaozi-runtime` flake 输出取得 Native 库、源码 JAR 和编译器插件。在 x86-64 Linux 上，`vme64t` 的完整 testbench 用 4 GiB 托管堆导出了 287 份模块 bytecode，并生成 507 份 SystemVerilog。
