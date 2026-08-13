#import "../lib.typ": *

= 验证协议 <ch-verification>

验证环境需要观察设计内部的信号，例如供协同仿真比对的架构状态、供记分板检查的互连事务与各级断言。Syntheke 将这些观察关系表示为探针源、探针汇和显式连接；跨模块边界的信号由框架统一规划端口和连线。每个探针还声明一条 FIRRTL 层路径，例如 `verification.cosim`，用于控制对应验证逻辑的生成与移除（@req-verification、@sec-layers）。

== 探针源、探针汇与参数流 <sec-dv-model>

第 5 章定义探针源、探针汇、验证 bind 及其声明类型（@sec-dv-declarations）。探针源和探针汇分别对应生成器 IO 中以其声明名命名的顶层 Bundle，内部字段采用 `Probe` 观测信号；验证生成器以求解后的汇端 Bundle 为输入，并实现协同仿真、记分板或断言逻辑（@sec-generator-contract）。

实现类和构造器由框架密封；`DesignBuilder` 根据当前生成器模块的 `ModuleId` 与名称派生端点标识。探针源的私有实现保存构造方法接收的 `protocol.Down`。`sink <- source` 产生 `DVBindId` 与 bind 的 `SourceLocation`；每个源恰好 bind 一次，每个汇至少连接一个源，同一汇的全部端点使用注册表中的同一个 `DVProtocol` 对象。

探针连接与设计连接共享 `<-` 声明语法和跨层路由。验证协议以探针汇为求解单位：框架按 bind 声明顺序收集该汇的 `NonEmptyVector[Down]`，再调用一次该汇协议的 `resolve`（@sec-dv-protocol）。

`resolve` 成功后，框架以 bind 声明顺序收集各源的层路径，并调用 `interfacesOf(edge, layers)` 返回 `DVInterfaces`。其中 `sources: NonEmptyVector[ProtocolBundle]` 与输入的 `Down` 按位置一一对应，供各探针源及其跨层端口使用；`sink: ProtocolBundle` 是探针汇的聚合接口；`sinkPaths(i)` 指定第 $i$ 个源在 `sink` 中的目标 Bundle。三者共同定义每个探针源与 `sink` 中选定 Bundle 的连接。

每个源的 `VerificationView` 条目包含 `DVSourceId`、`DVBindId`、带 `ProtocolId` 的聚合 `Edge`、`sources(i)` 与层路径；汇端条目包含 `DVSinkId`、按声明顺序排列的 `DVBindId` 列表、同一个聚合 `Edge` 与完整 `DVInterfaces`。源生成器和汇生成器的 `computeProtocolParam` 分别读取这些条目并生成协议参数（@sec-settle-pp、@sec-generator-module）。

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

== 连接与路由规则 <sec-dv-routing>

#决策([探针连接必须逐条显式枚举])[
  每个观察点由一条独立的 `<-` 声明连接到探针汇。批量连接由宿主语言循环展开；`DesignSpec` 记录展开后的各条 bind 及其声明顺序。
] <dec-dv-once>

#决策([探针汇生成器的父模块必须是源模块的严格祖先])[
  设探针汇生成器的父结构模块为 $W$。$W$ 必须是每个探针源模块的严格祖先；汇生成器是 $W$ 的直接子模块。每条硬件路径由源到 $W$ 的唯一上行路径及 $W$ 到汇生成器的连接组成。兄弟模块之间的观察应把汇生成器放在二者公共祖先之下。
] <dec-dv-ancestor>

探针路由复用设计侧的跨层端口规划（@sec-punch-planning）。第 $i$ 条 bind 的沿途端口使用已求解 `DVInterfaces.sources(i)`，源端及其沿途转发端口以 Output 为根方向。该 Output 路径最终连接到汇生成器中由 `sinkPaths(i)` 指定的 Input Bundle。验证接口中的 `flip` 固定为 `false`；该源接口及对应汇端 Bundle 的所有信号叶，都是带 `layers(i)` 的 `Probe`。

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

跨层端口与连线写入对应的 FIRRTL 层；关闭层路径时，FIRRTL 编译流程移除其中的验证逻辑。验证求解期核对 `DVInterfaces` 中的 `Probe` 层标注与探针源声明；例化期再把生成器的实际 Probe 端口与已求解 `ProtocolBundle` 比对。`DVInterfaces` 违反接口映射契约时报告 N6；生成器实际 Probe 端口与 `ProtocolBundle` 失配时报告 `ElaborationError`（@sec-error-semantics、@sec-generator-contract）。

#决策([层路径按前缀合并])[
  相同层路径合并为同一声明。连接到同一探针汇的全部 bind 采用该汇的同一个 `DVProtocol`；检查发现其他协议时，报告相关 bind 的 `SourceLocation`。不同探针汇可以在同一层路径下使用不同协议。
] <dec-layer-merge>

== 探针汇接口结构 <sec-sink-shape>

验证生成器的顶层端口采用 `DVInterfaces.sink`；第 $i$ 条 `DVBindId` 把 `sources(i)` 连接到 `sinkPaths(i)` 选定的 Bundle。

验证协商的 `Edge`、`DVInterfaces`、端口计划与层声明均进入 `ResolvedDesign`，供例化与工具导出使用。
