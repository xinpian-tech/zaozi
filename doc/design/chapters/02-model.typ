#import "../lib.typ": *

= 概念模型 <ch-model>

第 1 章将参数协商定义为硬件生成流程中的独立编译阶段（@sec-explicit-phase）。为使该阶段可单独执行和测试，构建结果必须显式表示设计并在进入协商前固化。

== 模块的两种形态 <sec-module-kinds>

Syntheke 把设计中的层次化电路单元称为#term[模块][module]。

Syntheke 把以参数为输入并返回电路模块的 zaozi 工厂称为#term[生成器][generator]。

模块类型是包含以下两个分支的密封类型：

- #term[结构模块][`WrapperModule`]　生成器数量为零，可以包含子模块、设计 bind 与验证 bind。其电路内容由子实例、端口与连线组成；跨层转发端口和连线由框架规划并发射（@ch-hierarchy）。
- #term[生成器模块][`GeneratorModule`]　子模块数量为零，绑定恰好一个生成器。硬件逻辑与辅助逻辑由该生成器实现；端口声明和生成器 IO 的对应契约见 @sec-generator-module。

== 层次树与连接结构 <sec-two-graphs>

#term[协议][protocol]定义一条边上 `Down`、`Up` 与 `Edge` 的数据类型、逐边求解函数和硬件接口。参数如何穿过生成器模块，由模块输入、输出节点之间的 `dFn` 与 `uFn` 定义；两者的分工见第 3 章和第 5 章。

一个 Syntheke 设计由两套结构共同描述：

- #term[层次树][hierarchy tree]　顶点是模块。它表达*所有权*：模块例化关系、命名空间嵌套和物理模块边界。这棵树只包含设计显式例化的模块，最终一一对应生成电路的模块层次。Xbar、NoC、直连、时钟树和电源网格等互连实现由生成器模块产生。
- #term[连接结构][connection structure]　生成器模块声明具名输入节点和输出节点，显式 bind 从一个输出节点指向一个输入节点。每个节点唯一关联一条 bind 和已求解边，并对应所属生成器的一个端口；每条 bind 则具有源、目标两个节点和两个端口。模块还声明从输入节点到输出节点的模块内部参数依赖；这些依赖与 bind 共同决定参数的传播顺序（@ch-interconnect）。

连接可以跨越任意层级。一个位于层次树深处的模块节点，可以 bind 到另一棵子树中的模块节点；连接结构给出“两端模块节点”的关系，层次树给出两端生成器模块之间的模块路径。协商阶段沿该路径统一规划跨层端口与连线（@sec-passes，细则见@ch-hierarchy）。

#图([层次树与连接结构。方框嵌套是层次树；圆点与绿色实线是模块节点及 bind，灰色点线是模块内部参数依赖。A、B 分别连接 Xbar 模块 R 的两个输入节点，R 的输出节点连接 C；每个圆点只对应一个端口和一条 bind。])[
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

模块节点由生成器模块声明，分为输入节点和输出节点。方向按 `Down` 的传播定义：输出节点是 bind 的源，输入节点是 bind 的目标。节点记录名称、方向、协议和源码位置；它同时标识生成器 IO 中的一个顶层端口。

模块显式声明输入节点到输出节点的参数依赖。每个输出节点必须定义一个 `dFn`：读取它所依赖的输入节点已经收到的 `Down`，返回该输出节点唯一的 `Down`。每个输入节点必须定义一个 `uFn`：读取依赖关系另一端各输出节点已经收到的 `Up`，返回该输入节点唯一的 `Up`。声明一条输入到输出的依赖时，构建 API 同时记录依赖边，并分别为输出节点的 `dFn` 生成读取该输入 `Down` 的句柄、为输入节点的 `uFn` 生成读取该输出 `Up` 的句柄；原始节点句柄只标识节点，不提供参数读取。读取句柄保存对应协议对象作为关联类型见证，所以协议 `p` 的输入读取结果为 `p.Down`，输出读取结果为 `p.Up`，而不是无类型值。函数只能持有依赖声明返回的读取句柄，依赖值按节点声明顺序提供，函数本身按参数 DAG 的拓扑次序调用。同一函数可以读取不同协议的节点，由宿主语言循环生成的同协议节点集合也可以逐项读取。函数还可以读取本模块构建期已经确定的用户参数，并返回参数值或一项传播错误。

没有前驱输入节点的输出节点仍具有 `dFn`，它不读取其它节点并从构建期参数产生初始 `Down`；没有后继输出节点的输入节点同样由不读取其它节点的 `uFn` 产生初始 `Up`。Xbar、NoC 以多个具名节点表示多个端口，并以模块内部参数依赖表示这些端口之间的参数影响关系；每个节点仍只参与一条 bind。

#不变量[全部模块节点均由生成器模块声明。]

bind 从一个输出节点连接到一个输入节点（@sec-attach）。每条 bind 在连接结构中产生一条有向边，并在协商期得到一项 `Down`、一项 `Up` 和一个#term[边参数][edge parameter] `Edge`。每个模块节点恰好参与一次设计 bind：输出节点恰好作为一次 bind 的源，输入节点恰好作为一次 bind 的目标。

#不变量[一条设计 bind 的源输出节点与目标输入节点必须使用同一协议。跨协议参数变换由具有不同输入、输出协议的显式生成器模块承担（@sec-protocol-object）。]

#不变量[bind 与模块内部参数依赖组成的有向图必须无环。结构校验发现环时，报告环上的模块节点、bind、内部依赖及其源码位置（@sec-propagation）。]

== 三阶段流水线 <sec-triptych>

设计生成分为三个阶段，前一阶段的输出作为后一阶段的输入。这套流程称为#term[Triptych 流水线][the Triptych pipeline]：

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
  [使用宿主语言代码例化模块树、声明节点和连接；条件拓扑与循环生成子系统由宿主语言控制流表达。产物是设计规格 `DesignSpec`。],
  [协商],
  [读入设计规格，对参数依赖 DAG 做拓扑排序，正向传播 `Down`、反向传播 `Up`，逐边调用协议求解，计算各生成器模块的协议参数与完整参数，并规划跨模块的端口与连线；产出协商结果 `ResolvedDesign`，或一组协商错误（@sec-error-semantics）。],
  [例化],
  [读入协商结果：以各生成器模块的完整参数调用 zaozi 生成器，发射结构模块，执行连线计划；产出 FIRRTL 电路。],
)

`DesignSpec` 包含三组内容：

- 固化后的模块树，以及树中各模块的不可变输入、输出节点规格、模块内部参数依赖、验证端点和有向 bind；节点规格保存方向、协议及相应的 `dFn` 或 `uFn`，每条内部依赖保存声明顺序和源码位置；
- 协议注册表，其键 `ProtocolId` 由协议种类、名称与版本组成；生成器注册表，其键 `GeneratorId` 由生成器限定名与版本组成；
- 按稳定标识索引的 `SourceLocation`（源码文件、行与列），以及模块、节点、内部参数依赖、验证端点与 bind 的声明顺序。

`ResolvedDesign` 保留对应的 `DesignSpec`，并增加按 bind 声明顺序排列的设计边、按探针汇分组的验证求解数据、各生成器模块的 `EdgeView` 和已求解参数记录、跨层端口计划、连线计划和 FIRRTL 层声明树。每条设计边保存源节点、目标节点、协议、`Down`、`Up`、`Edge` 与接口结构；每个节点在所属模块的 `EdgeView` 中映射到这条唯一的边。具体契约见 @sec-resolved-records。每条生成器记录绑定注册表条目、协议参数和完整参数，例化阶段据此调用生成器。工具导出通过协议注册表调用第 3 章定义的编解码与渲染函数。

协商阶段检查 `DesignSpec` 的结构、协议与参数约束（@req-iteration）；例化阶段检查生成器实际端口与协议接口（@dec-binding-check）。拓扑、声明或 IP 的变化触发新一轮三阶段。

构建期声明节点和记录连接需要框架提供的构建上下文 `DesignBuilder`。设计入口负责注入该上下文；设计体返回时，构建器固化为不可变的 `DesignSpec`，构建上下文的生命周期随之结束。bind 算子 `<-`（@sec-attach）只能在该构建上下文中记录连接。

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
