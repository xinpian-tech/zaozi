#import "../lib.typ": *

= 概念模型 <ch-model>

@ch-motivation 把参数协商定义为硬件生成流程中的独立阶段（@sec-explicit-phase）。为使该阶段可单独执行和测试，构建结果必须显式表示设计并在进入协商前固化。本章依次定义模块、层次树与连接结构、节点与 bind、稳定标识、构建阶段、三阶段流水线和序列化边界。

== 模块的两种形态 <sec-module-kinds>

Syntheke 把设计中的层次化电路单元称为#term[模块][module]。

Syntheke 把以参数为输入并返回电路模块的 zaozi 工厂称为#term[生成器][generator]。

每个模块在构造时收到一份#term[用户参数][user parameter]：它在模块生命周期的最开始就已确定，模块随后声明的一切都可以依赖它。模块类型是包含以下两个分支的密封类型：

- #term[结构模块][`WrapperModule`]　不带生成器，只用来组织层次：它按用户参数例化子模块，并在其中声明模块之间的连接（@sec-node-conn-proto）和验证探针的连接（@ch-verification）。它的电路只有子模块实例、端口和连线；穿过它的连接需要哪些端口和连线，由框架算出并生成（@ch-hierarchy）。
- #term[生成器模块][`GeneratorModule`]　没有子模块，绑定恰好一个生成器，硬件逻辑全部由该生成器实现。它的用户参数是构建期给定、不依赖连接关系的参数，例如容量、关联度、基地址和功能开关，并进入完整参数（@sec-two-layer-params）。它的端口与生成器端口的对应契约见 @sec-generator-module。

== 层次树与连接结构 <sec-two-graphs>

一个 Syntheke 设计由两套结构共同描述：

- #term[层次树][hierarchy tree]　顶点是模块。它表达*所有权*：模块例化关系、命名空间嵌套和物理模块边界。这棵树只包含设计显式例化的模块，最终一一对应生成电路的模块层次。Xbar、NoC、直连、时钟树和电源网格等互连实现也是树上的生成器模块。
- #term[连接结构][connection structure]　生成器模块声明具名的 inward 节点和 outward 节点，每个节点就是该模块的一个协议端口；bind 把一个 outward 节点接到一个 inward 节点。模块还声明本模块内部哪些 inward 节点的参数会影响哪些 outward 节点，称为模块内部参数依赖。节点、bind 与内部依赖的定义见 @sec-node-conn-proto。

连接可以跨越任意层级。一个位于层次树深处的模块节点，可以 bind 到另一棵子树中的模块节点；连接结构给出“两端节点”的关系，层次树给出两端生成器模块之间的模块路径。协商阶段沿该路径统一规划跨层端口与连线（@ch-hierarchy）。

#图([层次树与连接结构。方框嵌套是层次树；圆点与绿色实线是模块节点及 bind，灰色点线是模块内部参数依赖。A、B 分别连接 Xbar 模块 R 的两个 inward 节点，R 的 outward 节点连接 C；每个圆点只对应一个端口和一条 bind。])[
  #syn-diagram(
    spacing: (11mm, 7mm),
    // 模块节点
    node((0, 0.2), text(fill: c-edge)[A], name: <na>, shape: fletcher.shapes.circle),
    node((1, 1.2), text(fill: c-edge)[B], name: <nb>, shape: fletcher.shapes.circle),
    node((3, 0.2), text(fill: c-edge)[`in0`], name: <ri0>, shape: fletcher.shapes.circle),
    node((3, 1.2), text(fill: c-edge)[`in1`], name: <ri1>, shape: fletcher.shapes.circle),
    node((4.1, 0.7), text(fill: c-edge)[`out0`], name: <ro0>, shape: fletcher.shapes.circle),
    node((5.5, 0.7), text(fill: c-edge)[C], name: <nc>, shape: fletcher.shapes.circle),
    // 层次 enclose
    node(enclose: (<na>,), stroke: c-hier, inset: 12pt, snap: false, name: <m1>),
    node(enclose: (<na>, <nb>, <m1>), stroke: c-hier, inset: 24pt, snap: false, name: <m2>),
    node(enclose: (<ri0>, <ri1>, <ro0>), stroke: c-hier, inset: 12pt, snap: false, name: <m3>),
    node(enclose: (<nc>,), stroke: c-hier, inset: 12pt, snap: false, name: <m4>),
    node(enclose: (<m2>, <m3>, <m4>), stroke: c-hier, inset: 32pt, snap: false, name: <top>),
    // 标签
    node((0, -0.62), text(size: 8pt, fill: c-hier)[模块 P], stroke: none),
    node((0.5, 1.95), text(size: 8pt, fill: c-hier)[模块 Q], stroke: none),
    node((3.55, -0.62), text(size: 8pt, fill: c-hier)[Xbar 模块 R], stroke: none),
    node((5.5, -0.62), text(size: 8pt, fill: c-hier)[模块 S], stroke: none),
    node((2.7, 2.6), text(size: 8pt, fill: c-hier)[顶层], stroke: none),
    // 连接
    edge(<nb>, <ri1>, "-|>", stroke: c-edge),
    edge(<na>, <ri0>, "-|>", stroke: c-edge, label: text(fill: c-edge)[跨层 bind]),
    edge(<ri0>, <ro0>, "..>", stroke: c-dim),
    edge(<ri1>, <ro0>, "..>", stroke: c-dim),
    edge(<ro0>, <nc>, "-|>", stroke: c-edge),
  )
]

== 模块节点、bind 与协议 <sec-node-conn-proto>

#term[模块节点][module node]由生成器模块声明，分为 #term[inward 节点][`InwardNode`]和 #term[outward 节点][`OutwardNode`]，记录名称、方向、协议和源码位置；每个节点同时对应生成器的一个顶层端口。#term[协议][protocol]规定一条连接上传播的参数类型和求解规则（@ch-protocol）；每个节点属于一个协议。

*bind* 是连接声明：把一个模块的 outward 节点接到另一个模块（或同一模块）的 inward 节点，写作 `目标 inward 节点 <- 源 outward 节点`。bind 有两种：#term[设计 bind][design bind]连接两个模块节点，本章及 @ch-protocol、@ch-interconnect 讨论的都是它；#term[验证 bind][verification bind]把一个探针源接到一个探针汇，见 @ch-verification。不加限定的 bind 指设计 bind。

bind 写在结构模块的构建体里，声明它的结构模块必须是两端节点所在模块的祖先，可以不是最近的祖先；两端节点可以在它之下的任意深度，包括孙子模块及更深。也就是说，模块只连接自己子树内部的节点；要连到子树外面，由外层模块来写。bind 记录声明它的结构模块，结构校验核对这一祖先关系（@sec-structural-check）。设计 bind 与验证 bind 都遵守这条规则。

方向按 `Down` 的传播定义：outward 节点是 bind 的源，inward 节点是 bind 的目标。每条 bind 在协商期得到三项参数：源节点算出的下行参数 `Down`、目标节点算出的上行参数 `Up`，以及由协议把二者合成的#term[边参数][edge parameter] `Edge`（@sec-three-param-kinds）。一条 bind 连同它求出的参数称为一条#term[边][edge]；设计 bind 对应设计边，验证 bind 对应验证边；bind 与边在不强调求解结果时统称连接。每个模块节点恰好参与一次设计 bind：outward 节点恰好作为一次 bind 的源，inward 节点恰好作为一次 bind 的目标。

模块显式声明 inward 节点到 outward 节点的#term[模块内部参数依赖][module-internal parameter dependency]：一条依赖表示该 inward 节点的 `Down` 参与计算该 outward 节点的 `Down`，反过来该 outward 节点的 `Up` 参与计算该 inward 节点的 `Up`。每个 outward 节点带一个函数 `dFn`：读取本节点所依赖的各 inward 节点的 `Down` 和本模块的用户参数，返回该 outward 节点唯一的 `Down`。每个 inward 节点带一个函数 `uFn`：读取依赖本节点的各 outward 节点的 `Up` 和用户参数，返回该 inward 节点唯一的 `Up`。`dFn` 与 `uFn` 统称#term[端口参数函数][port parameter functions]。不依赖任何 inward 节点的 outward 节点和不被任何 outward 节点依赖的 inward 节点称为#term[边界节点][boundary node]，它们的函数只从用户参数产生初值。函数能读哪些节点由依赖声明决定，声明方式见 @sec-generator-module；求值顺序见 @sec-propagation。同一个函数可以读不同协议的节点。Xbar、NoC 以多个具名节点表示多个端口，以内部参数依赖表示端口之间的参数影响关系；每个节点仍只参与一条 bind。

#不变量[全部模块节点均由生成器模块声明。]

#不变量[一条设计 bind 的源 outward 节点与目标 inward 节点必须使用同一协议。跨协议参数变换由具有不同 inward、outward 协议的显式生成器模块承担（@sec-protocol-object）。]

#不变量[bind 与模块内部参数依赖组成的有向图必须无环；这张图称为#term[参数依赖 DAG][parameter dependency DAG]（@sec-propagation）。协商开始时的结构校验（@sec-structural-check）发现环时，报告环上的模块节点、bind、内部依赖及其源码位置。]

== 稳定标识 <sec-identity>

实体标识由已命名结构派生：`ModuleId` 是从设计根开始的实例名路径；`ModuleNodeId` 由 `module: ModuleId` 与 `name: NonEmptyString` 组成；`BindId` 由声明顺序和源、目标 `ModuleNodeId` 组成。同一模块内节点名唯一。每个节点唯一关联一条 bind，因此 `ModuleNodeId` 可以确定该节点所在的 `BindId` 和已求解边；一条 bind 同时关联源、目标两个节点。探针源与探针汇（合称验证端点）的标识见 @sec-dv-declarations。

每个模块、节点、bind、内部参数依赖和验证端点都记录声明处的 `SourceLocation`（源码文件、行与列）。源码位置只用于诊断，实体身份由稳定标识确定。

== 构建阶段 <sec-build>

构建期声明节点和记录连接需要框架提供的构建上下文 `DesignBuilder`。设计入口注入该上下文。每个模块以它的用户参数构造：生成器模块在其中声明所用的生成器、inward 与 outward 节点、模块内部参数依赖和验证端点（@sec-generator-module）；结构模块在其中例化子模块并记录 bind。条件拓扑与循环生成的子系统由宿主语言控制流表达。bind 算子 `<-` 只能在该上下文中记录连接。设计体返回时，构建器固化为不可变的 `DesignSpec`，构建上下文的生命周期随之结束。

节点的生命周期在用户参数之后开始：生成器模块构造时按用户参数声明节点（可选节点按开关声明，成组节点按列表循环声明，名字互不相同）和节点之间的内部参数依赖；`DesignSpec` 固化后节点集合不再增删；协商时每个节点恰好得到一条边；例化时每个节点对应生成器的一个端口。节点的有无、数量和名字只依赖用户参数，不依赖协商结果，也不依赖是否有 bind 指向它。

设计维护两张注册表：协议注册表登记设计用到的每个协议对象，键 `ProtocolId` 由协议种类、名称与版本组成；生成器注册表登记每个生成器，键 `GeneratorId` 由生成器限定名与版本组成。同一个键在一个设计里只对应一个对象。

`DesignSpec` 包含三组内容：

- 固化后的模块树，以及树中各模块的用户参数、不可变 inward、outward 节点规格、模块内部参数依赖、验证端点和有向 bind；节点规格保存方向、协议及相应的 `dFn` 或 `uFn`，每条内部依赖保存声明顺序和源码位置；
- 两张注册表的不可变副本；
- 按稳定标识索引的 `SourceLocation`，以及模块、节点、内部参数依赖、验证端点与 bind 的声明顺序。

== 三阶段流水线 <sec-triptych>

设计生成分为三个阶段，前一阶段的输出作为后一阶段的输入。这套流程称为#term[Triptych 流水线][the Triptych pipeline]；执行协商阶段的框架部分称为#term[协商器][negotiator]。

#图([Triptych 流水线。矩形表示阶段，胶囊表示阶段间的不可变产物。])[
  #syn-diagram(
    spacing: (8mm, 9mm),
    node((0, 0), [*构建* \ Build], name: <b>),
    node((1, 0), [设计规格 \ `DesignSpec`], name: <spec>, shape: fletcher.shapes.pill, fill: c-fill),
    node((2, 0), [*协商* \ Negotiate], name: <n>),
    node((3, 0), [协商结果 \ `ResolvedDesign`], name: <res>, shape: fletcher.shapes.pill, fill: c-fill),
    node((4, 0), [*例化* \ Elaborate], name: <e>),
    node((5, 0), [电路 \ FIRRTL], name: <fir>, shape: fletcher.shapes.pill, fill: c-fill),
    edge(<b>, <spec>, "-|>"),
    edge(<spec>, <n>, "-|>"),
    edge(<n>, <res>, "-|>"),
    edge(<res>, <e>, "-|>"),
    edge(<e>, <fir>, "-|>"),
  )
]

#table(
  columns: (auto, 1fr),
  table.header([阶段], [处理]),
  [构建],
  [使用宿主语言代码例化模块树、声明节点和连接（@sec-build）。产物是设计规格 `DesignSpec`。],
  [协商],
  [读入设计规格，对参数依赖 DAG 做拓扑排序，正向传播 `Down`、反向传播 `Up`，逐边调用协议求解，计算各生成器模块的协议参数与完整参数（@sec-two-layer-params），并规划跨模块的端口与连线（@ch-negotiation）；产出协商结果 `ResolvedDesign`，或一组协商错误（@sec-error-semantics）。],
  [例化],
  [读入协商结果：以各生成器模块的完整参数调用 zaozi 生成器，生成结构模块的电路（本文称发射），执行连线计划（@ch-hardware）；产出 FIRRTL 电路。],
)

`ResolvedDesign` 保留对应的 `DesignSpec`，并加上协商的全部结果：每条边求出的参数和接口、每个生成器模块的完整参数、跨层端口与连线的计划、FIRRTL 层声明；字段见 @sec-resolved-records 与 @sec-generator-records。例化阶段据此调用生成器；工具导出通过协议注册表调用 @ch-protocol 定义的编解码与渲染函数。

协商阶段检查 `DesignSpec` 的结构、协议与参数约束（@req-iteration）；例化阶段检查生成器实际端口与协议接口（@dec-binding-check）。拓扑、声明或 IP 的变化触发新一轮三阶段。

== 序列化边界 <sec-serialization-boundary>

协商结果与硬件生成器之间的数据接口是每个生成器模块的#term[完整参数][full parameter]，即用户参数与协议参数的合成（@sec-two-layer-params）。完整参数可序列化，使每个 IP 能以固定参数文件独立例化和测试，并支持归档与复现（@req-ip）。

规格中的 `dFn`、`uFn` 与协议参数推导函数属于当前协商进程。跨进程数据包括完整参数，以及按需导出的拓扑、连接求解结果和 FIRRTL 层声明树（@ch-tooling）。

#图([序列化边界。`DesignSpec` 保存当前进程内的闭包，`ResolvedDesign` 保存已求解数据与完整参数；可序列化的完整参数进入生成器。])[
  #syn-canvas({
    import cetz.draw: *
    // 协商侧
    rect((0, 0), (5.1, 3.2), stroke: 0.7pt, radius: 0.1)
    content((2.55, 2.8), [*构建与协商*])
    rect((0.35, 1.55), (4.75, 2.35), stroke: 0.6pt + gray, radius: 0.08)
    content((2.55, 1.95), [`DesignSpec`（可含闭包）与 `ResolvedDesign`（含完整参数）])
    // 生成器侧
    rect((7.1, 0), (11.6, 3.2), stroke: 0.7pt, radius: 0.1)
    content((9.35, 2.8), [*例化*])
    rect((7.45, 1.55), (11.25, 2.35), stroke: 0.6pt + gray, radius: 0.08)
    content((9.35, 1.95), [zaozi 生成器与 MLIR])
    // 边界线
    line((6.1, -0.25), (6.1, 3.45), stroke: 2.2pt)
    content((6.1, 3.72), [序列化边界])
    // 跨界箭头
    line((4.9, 0.75), (7.3, 0.75), mark: (end: ">"), stroke: 1.1pt + c-edge)
    content((6.1, 1.12), text(fill: c-edge)[完整参数（可序列化）])
  })
]
