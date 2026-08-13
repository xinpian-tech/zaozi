#import "@preview/fletcher:0.5.8" as fletcher: diagram, node, edge

#set page(margin: (x: 2.2cm, y: 2.4cm), numbering: "1")
#set text(font: ("Noto Serif CJK SC",), lang: "zh", region: "cn", size: 10.5pt)
#set par(justify: true, leading: 0.82em, spacing: 1.15em)
#set heading(numbering: "1.1")
#show heading.where(level: 1): it => { v(2mm); text(size: 15pt, it); v(1mm) }
#show heading.where(level: 2): it => { v(1mm); text(size: 12pt, it); v(0.5mm) }
#set table(stroke: 0.5pt + luma(170), inset: 5.5pt)
#show table: set text(size: 9pt)

#let c-edge = rgb("#1a7f37")
#let c-fill = luma(247)
#let syn-diagram(..args) = text(size: 9pt, diagram(
  node-stroke: 0.7pt, node-corner-radius: 2.5pt, node-inset: 6.5pt,
  edge-stroke: 0.7pt, spacing: (13mm, 9mm), ..args,
))
#let 图(caption, body) = figure(align(center, body), caption: caption)
#show figure.caption: set text(size: 9pt, fill: luma(80))

#align(center)[
  #text(size: 19pt, weight: "bold")[互连模块模型的理由记录]
  #v(2mm)
  #text(size: 12pt)[互连实现作为生成器模块的设计理由]
  #v(2mm)
  #text(size: 9.5pt, fill: luma(90))[Syntheke 设计讨论记录 · 2026-08-06 · 理由文档，非规范描述]
]
#v(4mm)

*本文性质。*本文记录互连模型的设计理由，不替代 `doc/design/` 下的规范描述。最终规范以设计文档为准。

= 问题 <sec-problem>

SoC 集成中的接口参数由连接图共同决定。处理器、DMA、缓存、内存控制器、外设、时钟源、电源域和验证观察点都只掌握本地事实；位宽、地址集合、事务身份、时钟关系和电源约束必须结合多个端点的声明才能确定。

旧式基数求解的问题不在于“能不能数出几条边”，而在于它把三个本应分开的事实混在一起：

#table(
  columns: (auto, 1fr),
  table.header([事实], [应归属的位置]),
  [谁与谁连接], [设计源码中的显式 bind],
  [端点参数如何变换或聚合], [生成器模块声明输入到输出的参数依赖；输出、输入节点分别定义 `dFn`、`uFn`],
  [硬件用什么结构实现], [显式例化的生成器模块及其用户参数],
)

隐式节点和自动基数机制把这三项职责放在同一个对象上，会使验证边界、后端切分边界和 NoC 这类结构化互连的表达都变得不清楚。

= 模型边界 <sec-boundary>

Syntheke 用生成器模块实现互连。

Xbar、NoC、直连、时钟树、复位树和电源网格都由设计源码显式例化。对应的生成器模块在层次树中声明具名输入、输出节点及模块内部参数依赖；这些节点参与 bind，模块接收完整参数并生成硬件。

#图([Xbar、NoC 与其它硬件一样由生成器模块实现。实线 bind 分别连接具名端口节点；模块框内的点线是输入到输出的参数依赖。])[
  #syn-diagram(
    spacing: (11mm, 7mm),
    node((0, 0), [`cpuOut`], name: <cpu>, shape: fletcher.shapes.circle),
    node((0, 1.2), [`dmaOut`], name: <dma>, shape: fletcher.shapes.circle),
    node((1.5, 0), [`in0`], name: <in0>, shape: fletcher.shapes.circle),
    node((1.5, 1.2), [`in1`], name: <in1>, shape: fletcher.shapes.circle),
    node((2.8, 0), [`out0`], name: <out0>, shape: fletcher.shapes.circle),
    node((2.8, 1.2), [`out1`], name: <out1>, shape: fletcher.shapes.circle),
    node((4.3, 0), [`dramIn`], name: <dram>, shape: fletcher.shapes.circle),
    node((4.3, 1.2), [`periphIn`], name: <per>, shape: fletcher.shapes.circle),
    node(enclose: (<in0>, <in1>, <out0>, <out1>), name: <ic>, fill: c-fill, inset: 11pt, snap: false),
    node((2.15, 2.05), [NoC / Xbar 生成器模块], stroke: none),
    edge(<cpu>, <in0>, "-|>", stroke: c-edge),
    edge(<dma>, <in1>, "-|>", stroke: c-edge),
    edge(<out0>, <dram>, "-|>", stroke: c-edge),
    edge(<out1>, <per>, "-|>", stroke: c-edge),
    edge(<in0>, <out0>, "..>", stroke: luma(120)),
    edge(<in0>, <out1>, "..>", stroke: luma(120)),
    edge(<in1>, <out0>, "..>", stroke: luma(120)),
    edge(<in1>, <out1>, "..>", stroke: luma(120)),
  )
]

实现互连的生成器模块按外部端口数量声明一组具名模块节点；每个节点恰好对应一次 bind、一条边和一个生成器端口。

= 为什么这能承载 NoC <sec-noc>

NoC 由生成器模块实现。它的用户参数可以描述路由器拓扑、端口到接入位置的映射、虚通道和内部表项资源；它的模块节点描述外部事务端口；它的内部实现可以继续下沉到更低协议层，也可以直接生成 RTL。

NoC 内部的路由器数量、物理信道数量和路径选择由 NoC 生成器的用户参数和内部实现确定。连接结构上的双向传播与逐边求解确定外部语义：哪些发起者能到达哪些响应者，地址如何译码，事务身份如何追踪，边接口形状是什么，以及 NoC 需要满足哪些资源约束。

Xbar 也按同一规则处理；外部端口数量表现为相应数量的具名输入、输出节点。

= 桥与边界 <sec-bridge>

两段互连之间通过桥连接。桥由生成器模块实现，在两侧分别声明输入节点与输出节点，并声明二者之间的参数依赖。地址重映射、位宽转换、时钟域转换、电源隔离和协议转换都属于桥的内部逻辑。

生成器模块（包括桥）构成后端切分边界。每个可交付 IP 或子系统拿到自己的完整参数和端口契约；跨边界连线由协商后的端口计划生成。

= 结论 <sec-conclusion>

连接结构由显式例化的模块、模块声明的具名输入与输出节点、从一个输出节点到一个输入节点的 bind，以及模块内部从输入到输出的参数依赖组成。

bind 与模块内部参数依赖组成 `Down` DAG，反向组成 `Up` DAG；输出节点的 `dFn` 与输入节点的 `uFn` 在两个方向上变换或聚合参数，两遍完成后每条 bind 独立调用协议的 `negotiate`。Xbar、NoC、直连、时钟树和电源网格都使用同一套模块、节点与参数传播规则。
