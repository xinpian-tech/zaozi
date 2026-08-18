#import "../lib.typ": *

= 协商算法 <ch-negotiation>

协商将 `DesignSpec` 转换为 `ResolvedDesign`。设计协议的核心算法是在两张方向相反的参数依赖 DAG 上传播：`Down` 按拓扑序前进，`Up` 按反向拓扑序返回；两遍完成后，每条 bind 独立求解。本章同时规定验证求解、生成器参数计算、跨层规划与错误语义。

== 协商流程 <sec-passes>

#图([协商流程。稳定拓扑排序同时服务于 `Down` 正向传播和 `Up` 反向传播；两遍互不以对方的结果为输入，结束后才逐边求解。])[
  #syn-diagram(
    spacing: (7mm, 10mm),
    node((0, 0.5), [结构校验], name: <v>),
    node((1, 0.5), [参数依赖拓扑排序], name: <topo>),
    node((2, 0), [`Down` 正向传播], name: <d>, fill: rgb("#edf5ff")),
    node((2, 1), [`Up` 反向传播], name: <u>, fill: rgb("#fff1ef")),
    node((3, 0.5), [设计边与验证求解], name: <e>),
    node((4, 0.5), [`EdgeView` 与完整参数], name: <p>),
    node((5, 0.5), [端口、连线与层计划], name: <w>),
    node((6, 0.5), [`ResolvedDesign`], name: <r>, shape: fletcher.shapes.pill, fill: c-fill),
    edge(<v>, <topo>, "-|>"),
    edge(<topo>, <d>, "-|>"),
    edge(<topo>, <u>, "-|>"),
    edge(<d>, <e>, "-|>"),
    edge(<u>, <e>, "-|>"),
    edge(<e>, <p>, "-|>"),
    edge(<p>, <w>, "-|>"),
    edge(<w>, <r>, "-|>"),
  )
]

具体阶段依次为：

+ 固化协议与生成器注册表，校验名称、节点方向、bind、协议匹配和模块内部参数依赖；
+ 由 bind 与模块内部参数依赖构造 `Down` 参数依赖 DAG，并执行稳定拓扑排序；
+ 按该顺序执行 `Down` 正向传播，按逆序执行 `Up` 反向传播；
+ 逐条设计边调用 `negotiate`，并按探针汇执行验证协议的 `resolve`；
+ 解析显式跨协议引用，按模块装配 `EdgeView`，计算协议参数与完整参数，并执行生成器能力校验；
+ 规划跨层端口、连线与 FIRRTL 层，装配 `ResolvedDesign`。

同一阶段中相互独立的端口参数函数、边求解或验证求解可以并行执行。函数输入按相关节点的声明顺序排列；稳定拓扑序决定求值和诊断次序，导出与错误排序采用第 9 章的规范。

== 验证端点声明 <sec-dv-declarations>

#term[探针源][DV source]由生成器模块声明，提供验证协议的 `Down` 与 FIRRTL 层路径。#term[探针汇][DV sink]由验证生成器模块声明，收集显式连接到该汇的全部探针源。验证端点的稳定标识由所属模块和端点名组成；验证 bind 的稳定标识由探针汇和探针源共同组成。探针源声明记录标识、协议、层路径和源码位置；探针汇声明记录标识、协议和源码位置。构建期创建探针端点时，框架把这些声明写入 `DesignSpec`。

== 已求解记录 <sec-resolved-records>

每条设计 bind 产生一个 `ResolvedEdge`。记录包含 `BindId`、源与目标 `ModuleNodeId`、对应协议对象与 `ProtocolId`、传播得到的 `Down` 和 `Up`、逐边求得的 `Edge` 以及 `interfaceOf(edge)` 返回的 `ProtocolBundle`。全部记录按 bind 声明顺序保存。

`ResolvedDVGroup` 保存一个探针汇、验证协议对象、按声明顺序排列的验证 bind、源端 `Down` 与层路径、`resolve` 得到的 `Edge` 以及完整 `DVInterfaces`。`ResolvedProtocolReference` 保存引用名、引用方、目标 `ModuleNodeId`、目标协议对象与该节点唯一一条边的 `Edge`。这些协议值按 @sec-protocol-object 和 @sec-dv-protocol 的 codec 规则编码、解码。

每个模块节点恰好对应一条设计边，因此跨协议引用以 `ModuleNodeId` 唯一确定目标边。引用声明同时给出期望的 `ProtocolId`。

== 生成器参数记录 <sec-generator-records>

生成器注册表记录生成器标识、生成器实现和完整参数 codec。已求解生成器模块记录模块标识、注册表条目、`EdgeView`、协议参数和完整参数。

`EdgeView` 按本模块的节点声明顺序保存条目。每个条目记录节点方向、该节点唯一的 `ResolvedEdge`，以及该节点显式声明且已经解析的跨协议引用；`EdgeView` 还包含按验证端点声明顺序装配的 `VerificationView`。

`GeneratorEntry` 保存生成器及其 `FullParam` codec。`ResolvedGeneratorModule.entry` 选定完整参数类型，`fullParam` 采用该条目的 `FullParam`。`EdgeView` 在双向传播和逐边求解后装配，供本模块的 `computeProtocolParam` 使用。

== 结构校验与稳定拓扑序

结构校验先固化协议注册表与生成器注册表。同一作用域内的实例名和端点名分别唯一；每个 `ProtocolId` 对应一个协议对象，每个 `GeneratorId` 对应一个生成器实现与 `FullParam` codec。

每条设计 bind 的源、目标节点必须存在。源节点方向为输出，目标节点方向为输入，两端协议匹配；每个输出节点恰好作为一次 bind 的源，每个输入节点恰好作为一次 bind 的目标。节点的数量在构建期已经固定，结构校验分别核对每个输出节点在 bind 源中出现一次、每个输入节点在 bind 目标中出现一次。

模块内部只保存一份从本模块输入节点指向输出节点的参数依赖边集；每条依赖带声明顺序和源码位置。输出节点的前驱与输入节点的后继都从该边集派生，按节点声明顺序排列。重复依赖边非法。每个输出节点必须携带 `dFn`，每个输入节点必须携带 `uFn`，函数字段不可选。构建 API 在记录一条依赖边时，原子地生成两个带协议类型的读取句柄：输出节点的函数用其中一个读取输入节点的 `Down`，输入节点的函数用另一个读取输出节点的 `Up`；原始节点句柄不能读取参数。读取句柄中的协议对象为关联类型提供见证，函数结果采用本节点协议的 `Down` 或 `Up` 类型。由此，函数可读集合与唯一保存的依赖边集来自同一次声明。空依赖时函数仍然存在，只是不持有其它节点的读取句柄。

`Down` 参数依赖 DAG 包含沿 bind 方向的依赖和模块内部参数依赖。稳定拓扑排序在多个节点均可选择时，采用模块的层次树先序和节点声明顺序打破平局。检测到环时，错误包含环上的全部 `ModuleNodeId`、`BindId`、模块内部参数依赖和源码位置。`Up` DAG 是它的反向图，直接使用同一拓扑序的逆序。

验证连接另行核对探针源与探针汇的协议、源的唯一 bind 及祖先关系（@sec-dv-routing）。

== `Down` 与 `Up` 传播 <sec-propagation>

对每个输出节点 $o$，令 $"pred"(o)$ 为本模块中显式声明会影响它的输入节点序列；对每个输入节点 $i$，令 $"succ"(i)$ 为依赖关系中由它影响的输出节点序列。

- 当 $"pred"(o)$ 中各输入节点的 `Down` 都已到达时，调用 `dFn_o`；函数按节点声明顺序读取这些值，得到输出节点 $o$ 的唯一 `Down`，随后沿 $o$ 所在的 bind 传给目标输入节点。
- 当 $"succ"(i)$ 中各输出节点的 `Up` 都已到达时，调用 `uFn_i`；函数按节点声明顺序读取这些值，得到输入节点 $i$ 的唯一 `Up`，随后沿 $i$ 所在的 bind 传回源输出节点。

$"pred"(o)$ 为空时，`dFn_o` 从构建期用户参数产生边界初值；$"succ"(i)$ 为空时，`uFn_i` 对称地产生初始 `Up`。每个函数返回一个参数值或一项传播错误。函数输入仅包括显式依赖的参数值和构建期已经确定的用户参数；同一输入产生同一结果。

以内存互连为例，Xbar 或 NoC 为每个下游输出节点声明它可由哪些上游输入节点到达。该输出节点的 `dFn` 可以聚合这些输入节点的事务身份需求，计算 ID 扩展、节点编号或内部表项容量；每个上游输入节点的 `uFn` 则聚合其可达输出节点的地址区域、操作能力和位宽约束。地址重叠、不可达或身份空间无法分配等冲突由正在执行的端口参数函数报告 N2。

#图([同一组 bind 与模块内部参数依赖上的两遍传播。Xbar 模块显式声明两个输入节点和两个输出节点；每个节点仍只对应一条 bind。蓝色 `Down` 沿实线 bind 与点线内部参数依赖前进，红色 `Up` 反向返回。])[
  #syn-diagram(
    spacing: (14mm, 8mm),
    node((0, 0), [A 输出], name: <a>),
    node((0, 1.2), [B 输出], name: <b>),
    node((1.4, 0), [X.in0], name: <i0>, fill: c-fill),
    node((1.4, 1.2), [X.in1], name: <i1>, fill: c-fill),
    node((2.8, 0), [X.out0], name: <o0>, fill: c-fill),
    node((2.8, 1.2), [X.out1], name: <o1>, fill: c-fill),
    node((4.2, 0), [C 输入], name: <c>),
    node((4.2, 1.2), [D 输入], name: <d>),
    edge(<a>, <i0>, "-|>", stroke: c-down, label: text(fill: c-down, size: 8pt)[bind]),
    edge(<b>, <i1>, "-|>", stroke: c-down),
    edge(<i0>, <o0>, "..>", stroke: c-down),
    edge(<i0>, <o1>, "..>", stroke: c-down),
    edge(<i1>, <o0>, "..>", stroke: c-down),
    edge(<i1>, <o1>, "..>", stroke: c-down),
    edge(<o0>, <c>, "-|>", stroke: c-down),
    edge(<o1>, <d>, "-|>", stroke: c-down),
    edge(<c>, <o0>, "--|>", stroke: c-up, bend: 20deg, label: text(fill: c-up, size: 8pt)[`Up`]),
    edge(<d>, <o1>, "--|>", stroke: c-up, bend: -20deg),
    edge(<i0>, <a>, "--|>", stroke: c-up, bend: 20deg),
    edge(<i1>, <b>, "--|>", stroke: c-up, bend: -20deg),
  )
]

== 边求解、验证求解与生成器参数 <sec-settle-pp>

*边求解*在两遍传播全部完成后，为每个 `BindId` 调用一次 `negotiate(down, up)`（@sec-protocol-object）。失败结果包含两个节点、两份参数快照及 bind 的源码位置；成功结果通过 `interfaceOf` 得到非空 `ProtocolBundle`。各设计边之间可以并行求解。

*验证求解*按 `DVSinkId` 分组。每组按 `DVBindId` 的声明顺序收集非空 `Down` 序列及相同顺序的 `LayerPath`。每个探针汇调用一次 `DVProtocol.resolve(downs)`，再调用 `interfacesOf(edge, layers)`。框架核对 `sources`、`sinkPaths` 的数量、结构、精确覆盖、单向 `Probe` 约束及 FIRRTL 层路径（@sec-dv-protocol、@sec-dv-routing）。

*跨协议引用*在目标边求解后解析。目标必须是本模块的节点，通常是时钟或电源输入节点（@sec-generator-module）；目标不存在、不在本模块、未 bind、协议不符或目标边未求解时报告 N7。

*生成器参数*在 `EdgeView` 装配后计算。每个生成器模块以 `computeProtocolParam(EdgeView)` 计算自有类型的协议参数，并可依据 `EdgeView` 与用户参数执行能力校验；框架随后调用 `combine(userParam, protocolParam)` 得到 `FullParam`。注册表条目、`EdgeView`、协议参数和完整参数一并存入 `ResolvedGeneratorModule`（@sec-two-layer-params、@sec-generator-module）。

#决策([协议参数只读取本模块的已求解数据])[
  `computeProtocolParam` 接收本模块的 `EdgeView`：每个节点唯一的已求解边、显式跨协议引用和验证端点结果。它不读取其他模块的数据，也不把计算结果反馈给 `dFn` 或 `uFn`。
] <dec-pp-local>

单次协商没有整机回读：`dFn` 只读 `Down`，`uFn` 只读 `Up`，生成器拿不到整张连接图的汇总，例如整机地址映射。这类产物由工具从导出数据生成（@sec-export）；生成器需要它时（例如 boot ROM 镜像），作为用户参数进入下一轮构建。

== 错误语义 <sec-error-semantics>

`NegotiationError` 包含类别、主体的稳定标识、全部相关源码位置（`SourceLocation`）及参数快照。文本格式见@ch-tooling。

协商返回 `Either[NonEmptyVector[NegotiationError], ResolvedDesign]`。传播只执行全部依赖值都已就绪的节点；同一就绪前沿及其它互不依赖分支中的失败可以一并收集，受失败值阻断的后继不执行，也不产生衍生错误。`Down` 与 `Up` 两遍互不依赖，各自继续执行仍可求值的分支。当前阶段可确定的全部失败按第 9 章的规范化顺序排列后返回，后续阶段停止执行。

#table(
  columns: (auto, auto, 1fr, 1fr),
  table.header([类别], [产生阶段], [触发条件], [报告必含]),
  [*N1* 协议标识或类型不一致], [校验], [不同协议对象声明同一 `ProtocolId`；`ProtocolKind` 与协议种类不符；bind 两端协议不匹配；或端口参数函数的输入、结果类型与相关节点协议不一致。], [冲突的协议对象与 `ProtocolId`；相关模块节点和参数函数；源码位置。],
  [*N2* 参数传播失败], [`Down` 或 `Up` 传播], [`dFn` 或 `uFn` 返回约束冲突，例如地址区域重叠、请求地址不可达或事务身份空间无法分配。], [模块、输出或输入节点、传播方向、相关依赖与 bind、有序输入快照、冲突描述和源码位置。],
  [*N3* 边或验证求解失败], [边与验证求解], [设计边的 `negotiate` 或探针汇的 `resolve` 返回参数冲突。], [设计边：`BindId`、`Down`、`Up` 与源码位置；探针汇：`DVSinkId`、有序 `Down` 与相关源码位置；协议给出的冲突描述。],
  [*N4* 节点或 bind 非法], [校验], [节点引用不存在；输出节点未恰好作为一次 bind 的源；输入节点未恰好作为一次 bind 的目标；或 bind 两端方向不符。], [相关 `ModuleNodeId`、`BindId`、实际 bind 次数和源码位置。],
  [*N5* 生成器能力校验失败], [生成器参数], [本模块已求解边要求的端口数、接口参数、拓扑条件或资源容量超出生成器用户参数给出的实现上限。], [生成器模块、相关节点与 `BindId`、所需值、实现上限和用户参数。],
  [*N6* 接口映射违约], [边或验证求解], [设计 `ProtocolBundle` 非法；或 `DVInterfaces` 的数量、路径、结构、精确覆盖、单向 `Probe` 或层路径契约不成立。], [节点或 `DVSinkId`；期望与实际结构；无效路径；相关 bind 的源码位置。],
  [*N7* 跨协议引用失败], [`EdgeView` 装配], [目标 `ModuleNodeId` 不存在或不属于引用方模块、未 bind、协议标识与声明不符，或目标边没有成功求解。], [引用方、目标 `ModuleNodeId`、期望与实际协议标识、引用声明的源码位置。],
  [*N8* 验证拓扑非法], [校验], [探针汇的源集合为空；探针源的 bind 数量异于一；源与汇协议不匹配；汇生成器父结构模块与源模块的严格祖先关系缺失。], [`DVSourceId`、`DVSinkId` 与 `DVBindId`；协议标识与模块路径；全部相关源码位置。],
  [*N9* 结构名称或参数依赖非法], [校验与拓扑排序], [同一作用域内的实例名或端点名重复；模块内部参数依赖重复、端点或方向非法；或参数依赖图存在环。], [冲突的稳定标识；相关模块、节点、bind 与模块内部参数依赖；环上的完整路径；全部相关源码位置。],
  [*N10* 生成器标识冲突], [校验], [两个注册表条目使用同一 `GeneratorId`，但生成器实现或 `FullParam` codec 不同。], [`GeneratorId`；冲突的生成器实现与 codec schema；相关模块及源码位置。],
)
