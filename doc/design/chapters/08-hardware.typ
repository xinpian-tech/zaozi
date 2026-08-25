#import "../lib.typ": *

= 硬件边界 <ch-hardware>

协商结果通过一份可序列化的完整参数交给 zaozi 生成器。单个 IP 可以使用同一参数独立例化、测试和复现（@req-ip）。本章规定生成器契约、模块节点与生成器端口的对应关系，以及例化次序。

== 生成器的契约 <sec-generator-contract>

生成器以完整参数为输入并产出电路模块，必须满足以下契约：

+ 硬件接口、探针接口、FIRRTL 层结构与电路体都由完整参数确定。跨层端口规划使用 `ResolvedEdge` 中的 `ProtocolBundle`；生成器从 `FullParam` 重建实际端口后，以同一 `ProtocolBundle` 作为期望结构进行校验。
+ 同一 `GeneratorId` 与相同完整参数产生结构相同的模块，模块去重据此使用二者作为结构键（@sec-dedup）。
+ 生成器 API 以完整参数为输入；将完整参数写成 JSON 后，可以直接调用生成器完成独立例化与测试。

生成器没有隐式时钟与复位：时钟和复位一律声明为时钟协议、复位协议的 inward 节点，与其它节点一样对应端口和一次 bind。

每个生成器发布一个 `GeneratorId` 和 `FullParam` codec；codec 提供 schema、规范化编码与解码。完整的 `GeneratorId` 确定生成器实现和 codec schema（@sec-dedup）。

`GeneratorId`、`GeneratorEntry` 与 `ResolvedGeneratorModule` 的类型见 @sec-generator-records。`DesignBuilder` 把每个生成器登记到生成器注册表，注册表将 `GeneratorId` 映射到 `GeneratorEntry`。同一 `GeneratorId` 的所有模块引用同一个条目；两个条目使用相同 `GeneratorId` 而生成器实现或 codec 不同时报告 N10。

`ResolvedGeneratorModule.entry` 确定完整参数的类型，`fullParam` 采用该条目的 `FullParam`。记录按模块的层次树先序存入 `ResolvedDesign`。例化从条目取得生成器，参数导出从同一条目取得 codec。

单个 IP 从参数文件例化时，由生成器库提供包含该 `GeneratorId` 条目的注册表。

`computeProtocolParam` 将框架提供的 `EdgeView` 转换为生成器自有的 `ProtocolParam`，`combine` 再生成 `FullParam`。独立例化根据 `GeneratorId` 找到注册项，并用该项的 codec 解码完整参数。`FullParam` 必须足以确定生成器全部设计端口与验证端点的名称、方向和接口结构。对于构建期动态声明的端点，`computeProtocolParam` 把端点名称、方向和 `ProtocolBundle` 约束转换为生成器自有字段，`combine` 在 `FullParam` 中保留复现这些接口所需的值；若端点名称由 `GeneratorId` 对应的固定接口 schema 决定，`FullParam` 只需保存决定接口结构的参数。

#图([序列化边界。框架侧的生成器模块保存用户参数、参数合并函数、协议参数推导函数与模块节点声明；zaozi 侧的生成器从完整参数确定接口、FIRRTL 层结构与电路体。])[
  #syn-canvas({
    import cetz.draw: *
    rect((0, 0), (5.0, 3.4), stroke: 0.7pt, radius: 0.1)
    content((2.5, 3.05), [*生成器模块*（框架侧）])
    for (y, t) in ((2.35, [用户参数（构建期声明）]), (1.65, [`combine : (UP, PP) => FP`]), (0.95, [`computeProtocolParam`]), (0.25, [模块节点声明])) {
      content((2.5, y), text(size: 8.5pt, t))
    }
    rect((7.6, 0), (12.4, 3.4), stroke: 0.7pt, radius: 0.1)
    content((10.0, 3.05), [*生成器*（zaozi 侧）])
    for (y, t) in ((2.35, [硬件接口 $=f("完整参数")$]), (1.65, [探针接口 $=f("完整参数")$]), (0.95, [层结构 $=f("完整参数")$]), (0.25, [电路体 $=f("完整参数")$])) {
      content((10.0, y), text(size: 8.5pt, t))
    }
    line((6.3, -0.3), (6.3, 3.7), stroke: 2.2pt)
    line((5.15, 1.7), (7.45, 1.7), mark: (end: ">"), stroke: 1.1pt + c-edge)
    content((6.3, 2.12), text(fill: c-edge)[完整参数])
    content((6.3, 3.95), [序列化边界])
  })
]

== 生成器模块的声明 <sec-generator-module>

生成器模块（@sec-module-kinds）在构建期声明契约数据与函数，包括用户参数、生成器注册表条目、inward 与 outward 节点列表、模块内部参数依赖、验证端点列表、协议参数推导函数，以及用户参数和协议参数到完整参数的合成函数。节点声明记录节点标识、名称、方向、协议、相应的 `dFn` 或 `uFn`、跨协议引用和源码位置。跨协议引用声明记录引用名、目标 `ModuleNodeId`、期望协议和源码位置。

协商器为每个生成器模块装配 `EdgeView`，再调用该模块的 `computeProtocolParam`。

`nodes` 按声明顺序返回本模块的全部设计模块节点；`parameterDependencies` 按声明顺序返回本模块从 inward 节点到 outward 节点的依赖边，每条记录包含两端 `ModuleNodeId` 与 `SourceLocation`。`OutwardNodeSpec` 必须携带 `dFn`，`InwardNodeSpec` 必须携带 `uFn`，函数字段不可选。构建 API 每声明一条依赖边，就同时返回两个带协议类型的读取句柄（只能读取指定节点参数的句柄），分别供 outward 节点函数读取 inward `Down`、供 inward 节点函数读取 outward `Up`；原始节点句柄没有读取操作。因此函数可读集合与 `parameterDependencies` 由同一次调用产生，不能分开声明。函数返回类型由本节点协议确定。边界节点的函数从用户参数产生初值。处理器、存储、桥、Xbar、NoC、直连和时钟树均通过这套公开构造方法声明节点和模块内部参数依赖。

`DesignBuilder` 根据当前模块的 `ModuleId` 与节点名派生 `ModuleNodeId`。同一模块内节点名唯一；每个节点恰好参与一次设计 bind。节点在生成器的端口中对应一个以节点声明名命名、由节点方向确定根方向的顶层 Bundle（@sec-port-naming）。

跨协议引用只能指向本模块的节点，用来声明本节点属于哪个时钟节点和电源节点：模块声明统一的时钟、电源 inward 节点，数据节点引用它们，求解后得到对应边的 `Edge`，每个端口的时钟域与电源域由此可知。引用只提供信息，框架不检查 bind 两端是否同域；跨域必须经过桥（@sec-bridge-boundary）。

`EdgeView` 是双向传播和逐边求解完成后按模块投影的数据。它按节点给出方向及唯一的已求解边；该边包含 `Down`、`Up`、`Edge` 与 `ProtocolBundle`。引用方节点条目另行包含显式跨协议引用的解析结果。

`dvSources` 与 `dvSinks` 声明验证端点（@sec-dv-declarations）。解析后的条目由 `VerificationView` 按声明顺序提供，并经 `computeProtocolParam` 进入完整参数；字段契约见 @sec-generator-records。

每条设计边在源、目标生成器的端口中各对应一个顶层 Bundle；探针源和探针汇各对应一个具名顶层 Bundle。节点、探针源和探针汇的声明名称在模块内共用同一唯一性约束，重复时在结构校验中报告 N9。参与框架连线的每个生成器顶层 Bundle 必须能由相应 `ModuleNodeId` 或验证端点声明唯一还原。设计边端口的期望结构来自 `interfaceOf(edge)`；探针源与探针汇的期望结构分别来自 `DVInterfaces.sources(i)` 与 `DVInterfaces.sink`。

#决策([端口结构校验在例化期进行])[
  生成器的设计端口和验证端口必须与相应 `ProtocolBundle` 完全一致：设计 bind 的源端根方向为 Output，目标端为 Input，探针源为 Output，探针汇为 Input；字段名称、顺序和 `flip`，`Bundle`、`Vec`、`UInt`、`SInt`、`Bool`、`Clock`、`Reset`、`Probe` 类型构造器，Vec 长度、整数宽度与符号，以及 Probe 的 `LayerPath` 均逐层相同。声明端口缺失、参与连线的顶层 Bundle 没有对应声明或结构失配时，错误包含端点稳定标识、bind 的源码位置（`SourceLocation`）以及期望结构与实际结构的差异路径。
] <dec-binding-check>

端口失配属于 `ElaborationError`，与 @sec-error-semantics 定义的 `NegotiationError` 分开报告。

== 例化流程 <sec-elaboration-flow>

例化期对层次树自底向上执行，每个模块一步：

+ *生成器模块*：读取 `ResolvedDesign` 中的完整参数并调用生成器；按 @dec-binding-check 校验设计节点、探针源和探针汇端口，并登记设计边与验证 bind 的硬件端点。
+ *结构模块*：按#ref(<ch-hierarchy>)的端口与连线计划发射端口、子实例与连线。连线按 Bundle 整体连接，由 zaozi 根据字段方向展开。

自底向上的次序保证子模块先于父模块发射，父模块生成连线时可以直接引用子实例端口。

== 序列化范围 <sec-serialization-list>

#table(
  columns: (auto, 1fr, 1fr),
  table.header([类别], [内容], [约束]),
  [*必须可序列化*], [完整参数（用户参数 $+$ 协议参数合并后）。], [生成器以完整参数为输入；单个 IP 独立例化使用同一参数作为命令行输入（@req-ip）。],
  [*可序列化、按需导出*], [稳定标识、设计边的 `Down`、`Up` 与 `Edge`、验证协议的 `Down` 与 `Edge`、`DVInterfaces`、`InterfacePath`、`ProtocolBundle`、`EdgeView`、端口与连线计划、FIRRTL 层声明树和诊断信息表。], [`ResolvedDesign` 使用这些数据类型；工具文件按需生成（@ch-tooling）。],
  [*进程内函数*], [参数变换、合并与推导函数。], [这些闭包的生命周期限于当前设计进程；生成器输入采用完整参数值。],
)
