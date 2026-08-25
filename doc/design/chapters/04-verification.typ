#import "../lib.typ": *

= 验证协议 <ch-verification>

验证环境需要观察设计内部的信号，例如供协同仿真比对的架构状态、供记分板检查的互连事务与各级断言。Syntheke 用三样东西表示这类观察关系：被观察模块上声明的观察点，称为探针源；实现验证逻辑的生成器模块（称为验证生成器模块）上声明的收集入口，称为探针汇；以及把探针源接到探针汇的验证 bind。信号本身以 FIRRTL 的探针（`Probe`，对内部信号的只读引用）形式引出，跨模块边界的部分由框架统一规划端口和连线（@ch-hierarchy）。每个探针源还声明一条 FIRRTL 层路径，例如 `verification.cosim`，用于控制对应验证逻辑的生成与移除（@req-verification、@sec-layers）。

验证协议是设计协议之外的第二种协议：设计协议在一条 bind 的两端之间双向传播、逐边求解；验证协议由探针汇一次聚合它的全部探针源，代码里对应的协议对象是 `DVProtocol`。两种协议共用注册表，`ProtocolKind` 为二者建立各自的标识空间。

== 探针源、探针汇与验证 bind <sec-dv-declarations>

#term[探针源][DV source]由生成器模块声明，提供验证协议的 `Down` 与 FIRRTL 层路径。#term[探针汇][DV sink]由验证生成器模块声明，收集显式连接到该汇的全部探针源。探针源声明记录标识、协议、层路径和源码位置；探针汇声明记录标识、协议和源码位置。构建期创建探针端点时，框架把这些声明写入 `DesignSpec`。

验证端点的稳定标识由所属模块和端点名组成（`DVSourceId`、`DVSinkId`）；验证 bind 的稳定标识 `DVBindId` 由探针汇和探针源共同组成。实现类和构造器由框架密封；`DesignBuilder` 根据当前生成器模块的 `ModuleId` 与名称派生端点标识。探针源的私有实现保存构造方法接收的 `protocol.Down`。

验证 bind 与设计 bind 共用 `<-` 声明语法和声明位置规则（@sec-node-conn-proto）：`sink <- source` 产生 `DVBindId`，并记录声明它的结构模块与 `SourceLocation`。每个源恰好 bind 一次，每个汇至少连接一个源，同一汇的全部端点使用注册表中的同一个 `DVProtocol` 对象。

探针源和探针汇分别对应生成器的一个顶层端口，端口名就是声明名，端口内部的字段都是 `Probe`；验证生成器以求解后的汇端端口为输入，并实现协同仿真、记分板或断言逻辑（@sec-generator-contract）。

#图([探针的层次路由。两个探针源（紫）沿层次树向上连接到顶层验证模块的探针汇；跨越的模块边界均产生带层路径标注的端口。])[
  #syn-diagram(
    spacing: (15mm, 7.5mm),
    node((0, 0.4), [源 α], name: <s1>, shape: fletcher.shapes.circle, stroke: c-dv),
    node((1.4, 0.4), [源 β], name: <s2>, shape: fletcher.shapes.circle, stroke: c-dv),
    node(enclose: (<s1>,), stroke: c-hier, inset: 10pt, snap: false, name: <m1>),
    node(enclose: (<s2>,), stroke: c-hier, inset: 10pt, snap: false, name: <m2>),
    node(enclose: (<m1>, <m2>), stroke: c-hier, inset: 22pt, snap: false, name: <mid>),
    node((2.9, -0.75), [探针汇], name: <k>, stroke: c-dv, fill: rgb("#f6f1fd")),
    node(enclose: (<mid>, <k>), stroke: c-hier, inset: 34pt, snap: false),
    node((0, -0.35), text(size: 8pt, fill: c-hier)[核], stroke: none),
    node((1.4, -0.35), text(size: 8pt, fill: c-hier)[核], stroke: none),
    node((0.7, -0.95), text(size: 8pt, fill: c-hier)[簇], stroke: none),
    node((0.7, -1.62), text(size: 8pt, fill: c-hier)[顶层], stroke: none),
    edge(<s1>, <k>, "--|>", stroke: c-dv),
    edge(<s2>, <k>, "--|>", stroke: c-dv),
  )
]

== 验证协议对象与求解 <sec-dv-protocol>

验证协议规定探针源为上游、探针汇为下游。它的参数只有两种：探针源声明的 `Down`，以及探针汇把全部源的 `Down` 聚合后得到的 `Edge`；聚合由验证协议对象的函数 `resolve` 完成。求解结果同时给出各探针源沿途使用的接口、汇端聚合接口，以及每个源接口在汇端接口中的路径（`InterfacePath`）。路径由字段选择和 Vec 索引组成。

`NonNegativeInt` 表示大于或等于零的整数。

框架以探针汇为求解单位：按 bind 声明顺序收集该汇的 `NonEmptyVector[Down]`，调用一次该汇协议的 `resolve`；成功后以相同顺序收集各源的层路径，调用 `interfacesOf(edge, layers)` 得到 `DVInterfaces`。返回值中的 `sources` 与输入一一对应，供各探针源及其跨层端口使用；`sink` 是探针汇的聚合接口；`sinkPaths(i)` 在 `sink` 中选择与 `sources(i)` 结构完全相同的 Bundle。空路径选择 `sink` 根 Bundle；非空路径用 `Field` 进入具名字段、用 `Index` 进入 Vec 元素，并且最后一段必须落在 Bundle。所有路径必须有效、互异且互不重叠，其选中 Bundle 的信号叶必须精确覆盖 `sink` 的全部信号叶。

验证连接是从源到汇的单向观测。`sources` 与 `sink` 中的每个信号叶都必须是 `Probe`，所有 `flip` 必须为 `false`；`sources(i)` 及 `sinkPaths(i)` 选中的汇端子树中，每个 `Probe` 的 `LayerPath` 必须等于 `layers(i)`。源接口用于跨层端口规划，路径用于汇端连接，汇端接口用于生成器端口校验。`DVInterfaces` 违反以上契约时，报告接口映射违约错误（N6，@sec-error-semantics）。

求解结果按模块整理进 `EdgeView` 的验证部分 `VerificationView`：每个源的条目包含 `DVSourceId`、`DVBindId`、带 `ProtocolId` 的聚合 `Edge`、`sources(i)` 与层路径；汇端条目包含 `DVSinkId`、按声明顺序排列的 `DVBindId` 列表、同一个聚合 `Edge` 与完整 `DVInterfaces`。源生成器和汇生成器的 `computeProtocolParam` 分别读取这些条目并生成协议参数（@sec-settle-pp、@sec-generator-module）。

`Down`、`Edge`、`DVInterfaces`、`InterfacePath` 与 `LayerPath` 为不可变、可序列化的数据。`downCodec` 与 `edgeCodec` 提供两个关联类型的 schema 与规范化编码；其余三种类型采用框架定义的 schema。`DVProtocol.id.kind` 固定为 `Verification`；任何会改变求解函数、接口、渲染结果或 codec schema 的变更都必须更新版本。

== 连接与路由规则 <sec-dv-routing>

#决策([探针连接必须逐条显式枚举])[
  每个观察点由一条独立的 `<-` 声明连接到探针汇。批量连接由宿主语言循环展开；`DesignSpec` 记录展开后的各条 bind 及其声明顺序。
] <dec-dv-once>

#决策([探针汇生成器的父模块必须是源模块的严格祖先])[
  设探针汇生成器的父结构模块为 $W$。$W$ 必须是每个探针源模块的严格祖先；汇生成器是 $W$ 的直接子模块。每条硬件路径由源到 $W$ 的唯一上行路径及 $W$ 到汇生成器的连接组成。兄弟模块之间的观察应把汇生成器放在二者公共祖先之下。
] <dec-dv-ancestor>

探针路由复用设计侧的跨层端口规划（@sec-punch-planning）。第 $i$ 条 bind 沿途各层生成的 Dangle 端口（@sec-punch-planning）使用已求解 `DVInterfaces.sources(i)` 的结构，源端及这些 Dangle 端口以 Output 为根方向。该 Output 路径最终连接到汇生成器中由 `sinkPaths(i)` 指定的 Input Bundle。验证接口中的 `flip` 固定为 `false`；该源接口及对应汇端 Bundle 的所有信号叶，都是带 `layers(i)` 的 `Probe`。

结构校验核对探针源与探针汇的协议、源的唯一 bind 及祖先关系，违反时报告验证拓扑非法错误（N8，@sec-error-semantics）。

== FIRRTL 层与探针移除 <sec-layers>

每个探针源声明一条#term[层路径][layer path]，例如 `verification.cosim` 或 `verification.assert`。框架将穿过同一模块的探针层路径合并为前缀树：

$ "layers"(w) = "前缀树并" {"layer"(s) : s in "子树"(w) "的全部探针端口"} $

#图([层的前缀树合并。子树包含 `verification.cosim` 与 `verification.assert.fatal` 两条层路径，模块的层声明是二者的前缀树并。])[
  #syn-canvas({
    import cetz.draw: *
    // 左树
    content((0.9, 2.6), text(size: 8.5pt)[`verification`])
    content((0.9, 1.6), text(size: 8.5pt)[`cosim`])
    line((0.9, 2.35), (0.9, 1.85), stroke: 0.55pt + gray)
    // 加号
    content((2.6, 2.0), [$+$])
    // 中树
    content((4.4, 2.6), text(size: 8.5pt)[`verification`])
    content((4.4, 1.6), text(size: 8.5pt)[`assert`])
    content((4.4, 0.6), text(size: 8.5pt)[`fatal`])
    line((4.4, 2.35), (4.4, 1.85), stroke: 0.55pt + gray)
    line((4.4, 1.35), (4.4, 0.85), stroke: 0.55pt + gray)
    // 等号
    content((6.1, 2.0), [$=$])
    // 并
    content((8.6, 2.6), text(size: 8.5pt)[`verification`])
    content((7.7, 1.6), text(size: 8.5pt)[`cosim`])
    content((9.5, 1.6), text(size: 8.5pt)[`assert`])
    content((9.5, 0.6), text(size: 8.5pt)[`fatal`])
    line((8.45, 2.35), (7.75, 1.85), stroke: 0.55pt + gray)
    line((8.75, 2.35), (9.45, 1.85), stroke: 0.55pt + gray)
    line((9.5, 1.35), (9.5, 0.85), stroke: 0.55pt + gray)
  })
]

跨层端口与连线写入对应的 FIRRTL 层；关闭层路径时，FIRRTL 编译流程移除其中的验证逻辑。验证求解期核对 `DVInterfaces` 中的 `Probe` 层标注与探针源声明；例化期再把生成器的实际 Probe 端口与已求解 `ProtocolBundle` 比对，失配时报告例化期错误 `ElaborationError`（@sec-generator-contract）。

#决策([层路径按前缀合并])[
  相同层路径合并为同一声明。连接到同一探针汇的全部 bind 采用该汇的同一个 `DVProtocol`；检查发现其他协议时，报告相关 bind 的 `SourceLocation`。不同探针汇可以在同一层路径下使用不同协议。
] <dec-layer-merge>

== 探针汇接口结构 <sec-sink-shape>

验证生成器的顶层端口采用 `DVInterfaces.sink`；第 $i$ 条 `DVBindId` 把 `sources(i)` 连接到 `sinkPaths(i)` 选定的 Bundle。

FIRRTL 不允许输入方向的 `Probe` 端口。例化时，汇生成器端口采用 `DVInterfaces.sink` 逐叶去掉 `Probe` 后的数据结构；其父结构模块在对应层的 layerblock 内对每个探针执行 `ref.resolve`，汇生成器实例与这些连线一并置于该层块中。协议层面的接口契约仍以带 `Probe` 的 `DVInterfaces` 为准。

验证协商的 `Edge`、`DVInterfaces`、端口计划与层声明均进入 `ResolvedDesign`，供例化与工具导出使用。
