#import "../lib.typ": *

= 协商算法 <ch-negotiation>

协商将 `DesignSpec` 转换为 `ResolvedDesign`。设计协议的核心算法是在两张方向相反的参数依赖 DAG 上传播：`Down` 按拓扑序前进，`Up` 按反向拓扑序返回；两遍完成后，每条 bind 独立求解。本章按执行顺序规定结构校验、传播、求解与生成器参数计算，然后给出输出记录和错误语义；跨层规划见 @ch-hierarchy。

== 协商流程 <sec-passes>

#图([协商流程。稳定拓扑排序同时服务于 `Down` 正向传播和 `Up` 反向传播；两遍互不以对方的结果为输入，结束后才逐边求解。])[
  #syn-diagram(
    spacing: (7mm, 10mm),
    node((0, 0.5), [结构校验], name: <v>),
    node((1, 0.5), [参数依赖拓扑排序], name: <topo>),
    node((2, 0), [`Down` 正向传播], name: <d>, fill: rgb("#edf5ff")),
    node((2, 1), [`Up` 反向传播], name: <u>, fill: rgb("#fff1ef")),
    node((3, 0.5), [设计边求解], name: <e>),
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
+ 逐条设计边调用 `negotiate`；
+ 解析跨协议引用（@sec-settle-pp），按模块装配 `EdgeView`，计算协议参数与完整参数，并执行生成器能力校验；
+ 规划跨层端口、连线与 FIRRTL 层，装配 `ResolvedDesign`。

函数输入按相关节点的声明顺序排列；稳定拓扑序决定求值次序，因而也决定首个错误是哪一个；导出顺序采用 @ch-tooling 的规范。

== 结构校验与稳定拓扑序 <sec-structural-check>

结构校验先固化生成器注册表。同一模块内的子实例名唯一，节点名与探针源名唯一；每个生成器名字对应一个注册表条目。

每条设计 bind 的源、目标节点必须存在，声明该 bind 的结构模块必须是两端节点所在模块的祖先（@sec-node-conn-proto）。源节点方向为 outward，目标节点方向为 inward，两端协议匹配；每个 outward 节点恰好作为一次 bind 的源，每个 inward 节点恰好作为一次 bind 的目标。节点的数量在构建期已经固定，结构校验分别核对每个 outward 节点在 bind 源中出现一次、每个 inward 节点在 bind 目标中出现一次。

模块内部只保存一份从本模块 inward 节点指向 outward 节点的参数依赖边集；每条依赖带声明顺序和源码位置。outward 节点的前驱与 inward 节点的后继都从该边集派生，按节点声明顺序排列。重复依赖边非法。每个 outward 节点必须携带 `dFn`，每个 inward 节点必须携带 `uFn`，函数字段不可选；函数可读的节点集合与依赖边集来自同一次声明（@sec-generator-module）。

`Down` 参数依赖 DAG 由以下两类边组成：每条 bind 从源 outward 节点指向目标 inward 节点；每条模块内部参数依赖从 inward 节点指向 outward 节点。`Up` 参数依赖 DAG 反转上述全部方向。结构校验检查 `Down` 图无环；`Up` 是它的反向图，自动无环。稳定拓扑排序在多个节点均可选择时，采用模块的层次树先序和节点声明顺序打破平局；`Up` 直接使用同一拓扑序的逆序。检测到环时，错误包含环上的全部 `ModuleNodeId`、`BindId`、模块内部参数依赖和源码位置。

== `Down` 与 `Up` 传播 <sec-propagation>

对每个 outward 节点 $o$，令 $"pred"(o)$ 为本模块中显式声明会影响它的 inward 节点序列；对每个 inward 节点 $i$，令 $"succ"(i)$ 为依赖关系中由它影响的 outward 节点序列。

- 当 $"pred"(o)$ 中各 inward 节点的 `Down` 都已到达时，调用 `dFn_o`；函数按节点声明顺序读取这些值，得到 outward 节点 $o$ 的唯一 `Down`，随后沿 $o$ 所在的 bind 传给目标 inward 节点。
- 当 $"succ"(i)$ 中各 outward 节点的 `Up` 都已到达时，调用 `uFn_i`；函数按节点声明顺序读取这些值，得到 inward 节点 $i$ 的唯一 `Up`，随后沿 $i$ 所在的 bind 传回源 outward 节点。

$"pred"(o)$ 为空时，`dFn_o` 从构建期用户参数产生边界初值；$"succ"(i)$ 为空时，`uFn_i` 对称地产生初始 `Up`。每个函数返回一个参数值或一项传播错误。函数输入仅包括显式依赖的参数值和构建期已经确定的用户参数；同一输入产生同一结果。两遍传播互不以对方的结果为输入：`dFn` 只读 `Down`，`uFn` 只读 `Up`。

以内存互连为例，Xbar 或 NoC 为每个下游 outward 节点声明它可由哪些上游 inward 节点到达。该 outward 节点的 `dFn` 可以聚合这些 inward 节点的事务身份需求，计算 ID 扩展、节点编号或内部表项容量；每个上游 inward 节点的 `uFn` 则聚合其可达 outward 节点的地址区域、操作能力和位宽约束。地址重叠、不可达或身份空间无法分配等冲突由正在执行的端口参数函数以参数冲突值报出（@sec-error-semantics）。

#图([同一组 bind 与模块内部参数依赖上的两遍传播。Xbar 模块显式声明两个 inward 节点和两个 outward 节点；每个节点仍只对应一条 bind。蓝色 `Down` 沿实线 bind 与点线内部参数依赖前进，红色 `Up` 反向返回。])[
  #syn-diagram(
    spacing: (14mm, 8mm),
    node((0, 0), [A outward], name: <a>),
    node((0, 1.2), [B outward], name: <b>),
    node((1.4, 0), [X.in0], name: <i0>, fill: c-fill),
    node((1.4, 1.2), [X.in1], name: <i1>, fill: c-fill),
    node((2.8, 0), [X.out0], name: <o0>, fill: c-fill),
    node((2.8, 1.2), [X.out1], name: <o1>, fill: c-fill),
    node((4.2, 0), [C inward], name: <c>),
    node((4.2, 1.2), [D inward], name: <d>),
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

== 边求解与生成器参数 <sec-settle-pp>

*边求解*在两遍传播全部完成后，为每个 `BindId` 调用一次 `negotiate(down, up)`（@sec-protocol-object）。失败结果包含两个节点、两份参数快照及 bind 的源码位置；成功结果通过 `interfaceOf` 得到非空 `ProtocolBundle`。各设计边之间可以并行求解。

*跨协议引用*是一个节点对本模块另一个节点的引用，用来声明本节点属于哪个时钟节点、电源节点（@sec-generator-module）；它在目标边求解后解析为目标边的 `Edge`。目标必须是本模块的节点，声明处即检查；引用名取自绑定它的 val，声明返回的句柄是读回该引用的唯一途径。目标节点恰好一条 bind 由结构校验保证，装配 `EdgeView` 时直接取该边的 `Edge`。

*生成器参数*在 `EdgeView` 装配后计算。每个生成器模块以 `computeFullParam(EdgeView)` 由已求解边与闭包中的用户参数得到 `FullParam`，并在其中执行能力校验。注册表条目、`EdgeView` 和完整参数一并存入 `ResolvedGeneratorModule`（@sec-generator-records、@sec-two-layer-params、@sec-generator-module）。

#决策([协议参数只读取本模块的已求解数据])[
  `computeFullParam` 接收本模块的 `EdgeView`：每个节点唯一的已求解边、显式跨协议引用，以及框架接进本模块的探针清单（仅测试平台非空，@sec-dv-testbench）。它不读取其他模块的数据，也不把计算结果反馈给 `dFn` 或 `uFn`。
] <dec-pp-local>

单次协商没有整机回读：`dFn` 只读 `Down`，`uFn` 只读 `Up`，生成器拿不到整张连接图的汇总，例如整机地址映射。这类产物由工具从导出数据生成（@sec-export）；生成器需要它时（例如 boot ROM 镜像），作为用户参数进入下一轮构建。

== 已求解记录 <sec-resolved-records>

每条设计 bind 产生一个 `ResolvedEdge`。记录包含 `BindId`、源与目标 `ModuleNodeId`、对应协议对象、传播得到的 `Down` 和 `Up`、逐边求得的 `Edge` 以及 `interfaceOf(edge)` 返回的 `ProtocolBundle`。全部记录按 bind 声明顺序保存。

`ResolvedProtocolReference` 保存引用名、引用方、目标 `ModuleNodeId`、目标协议对象与该节点唯一一条边的 `Edge`。这些协议值按 @sec-protocol-object 的序列化规则编码、解码。

== 生成器参数记录 <sec-generator-records>

生成器注册表记录生成器标识、生成器实现和完整参数 codec。已求解生成器模块记录模块标识、注册表条目、`EdgeView`、协议参数和完整参数。

`EdgeView` 按本模块的节点声明顺序保存条目。每个条目记录节点方向、该节点唯一的 `ResolvedEdge`，以及该节点显式声明且已经解析的跨协议引用；读取以声明得到的节点与引用句柄为键，结果按句柄的协议类型化，不提供按名字符串的查询。

`GeneratorEntry` 保存生成器及其 `FullParam` codec。`ResolvedGeneratorModule.entry` 选定完整参数类型，`fullParam` 采用该条目的 `FullParam`。`EdgeView` 在双向传播和逐边求解后装配，供本模块的 `computeFullParam` 使用。

== 错误语义 <sec-error-semantics>

协商成功返回 `ResolvedDesign`；发现首个错误时立即以异常终止，不收集后续错误。异常消息直接陈述问题本身，并内联主体的稳定标识、全部相关源码位置及参数快照；没有错误类别或编号。协议对象与端口参数函数仍以值（`Left`）表达参数冲突——那是协议作者的表达通道；协商器收到即抛。用户代码抛出的异常不由协商器包装：它是该代码自身的缺陷，原样穿透并保留其调用栈。

凡是声明处即可判定的契约在构建阶段当场检查、当场抛出，不进入协商：同一作用域内实例名或端点名重复；名称形状非法（@sec-port-naming）；在生成器 body 内声明子模块或 bind（生成器模块是叶子，@sec-module-kinds）；模块内部参数依赖的端点不在本模块、方向非法或依赖重复；跨协议引用的目标不在本模块；一个节点声明多个参数函数。协商阶段只报告需要全局视角才能判定的问题：

#table(
  columns: (auto, 1fr, 1fr),
  table.header([产生阶段], [触发条件], [报告必含]),

  [校验], [节点引用不存在；outward 节点未恰好作为一次 bind 的源；inward 节点未恰好作为一次 bind 的目标；或声明 bind 的结构模块不是两端节点所在模块的祖先。], [相关 `ModuleNodeId`、`BindId`、声明 bind 的模块、实际 bind 次数和源码位置。],
  [校验], [两个不同的注册表条目使用同一生成器名字。], [冲突的名字；相关模块及源码位置。],
  [拓扑排序], [参数依赖图存在环。], [环上的完整路径；环内每条 bind 与模块内部参数依赖的源码位置。],
  [`Down` 或 `Up` 传播], [`dFn` 或 `uFn` 返回约束冲突，例如地址区域重叠、请求地址不可达或事务身份空间无法分配。], [模块、outward 或 inward 节点、传播方向、有序输入快照、冲突描述和源码位置。],
  [边求解], [设计边的 `negotiate` 返回参数冲突。], [`BindId`、`Down`、`Up` 与源码位置；协议给出的冲突描述。],
  [边求解], [设计边接口含 `Probe`——探针属于验证协议。], [`BindId`、越界的接口路径与源码位置。],
  [生成器参数], [本模块已求解边要求的端口数、接口参数、拓扑条件或资源容量超出生成器用户参数给出的实现上限。], [生成器模块、相关节点与 `BindId`、所需值、实现上限和用户参数。],
)
