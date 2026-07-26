#import "../lib.typ": *

= 概念模型 <ch-model>

本章建立全文档共用的概念骨架：一个设计由两个相互正交的结构描述；它的生成过程被切成三个单向依赖的阶段；参与其中的模块只有两种形态；穿越协商与硬件之间边界的数据只有一种。

== 两张图 <sec-two-graphs>

一个 Syntheke 设计同时是两个正交的结构：

- #term[层次树][hierarchy tree]　顶点是#term[模块][module]。它表达*所有权*：谁例化了谁、命名空间如何嵌套、物理上的模块边界在哪里。这棵树最终一一对应生成电路的模块层次。
- #term[连接结构][connection structure]　由#term[总线][bus]与附着到其上的#term[成员][member]构成（详见@ch-interconnect）；连接两条总线的桥也只是一种成员设备，不另立一类。它表达*可达与协商*：谁与谁经哪条总线相通、地址如何译码、参数在何处协商。成员各自唯一从属于一个模块。

关键性质是：*连接不受层次约束*。一个位于层次树深处的成员，可以附着到定义在别处的总线上；成员与它所属模块相隔多少层边界，由层次树决定，与连接结构无关。跨越边界所需的端口，由协商阶段收尾的打洞遍统一规划（@sec-passes，规则详见@ch-hierarchy），不需要设计者逐层转发。

#图([两个结构的正交。方框嵌套是层次树；圆点与绿色连线是连接结构。成员 A 与 C 的连接依次穿过 P、Q、R 三层模块边界，各穿越处的端口由框架规划（@ch-hierarchy）。])[
  #syn-diagram(
    spacing: (11mm, 7mm),
    // 协商图节点
    node((0, 0.2), text(fill: c-edge)[A], name: <na>, shape: fletcher.shapes.circle),
    node((1, 1.2), text(fill: c-edge)[B], name: <nb>, shape: fletcher.shapes.circle),
    node((3, 0.2), text(fill: c-edge)[C], name: <nc>, shape: fletcher.shapes.circle),
    // 层次 enclose
    node(enclose: (<na>,), stroke: c-hier, inset: 12pt, snap: false, name: <m1>),
    node(enclose: (<na>, <nb>, <m1>), stroke: c-hier, inset: 24pt, snap: false, name: <m2>),
    node(enclose: (<nc>,), stroke: c-hier, inset: 12pt, snap: false, name: <m3>),
    node(enclose: (<m2>, <m3>), stroke: c-hier, inset: 36pt, snap: false, name: <top>),
    // 标签
    node((0, -0.62), text(size: 8pt, fill: c-hier)[模块 P], stroke: none),
    node((0.5, 1.95), text(size: 8pt, fill: c-hier)[模块 Q], stroke: none),
    node((3, -0.62), text(size: 8pt, fill: c-hier)[模块 R], stroke: none),
    node((1.6, 2.6), text(size: 8pt, fill: c-hier)[顶层], stroke: none),
    // 连接
    edge(<na>, <nb>, "-|>", stroke: c-edge),
    edge(<na>, <nc>, "-|>", stroke: c-edge, label: text(fill: c-edge)[跨层次连接]),
  )
]

两个结构分开。层次按物理与团队边界组织，连接按协议逻辑组织，两者互不约束（@req-hierarchy）：连接若追随层次（只许父子相连），跨子树的每条连接就要手工逐层转发端口，crossbar 与它的每个使用者被迫互为父子，层次与连接彼此绑死。

== 成员、附着与协议 <sec-node-conn-proto>

一个成员通过一个节点接入总线（@ch-interconnect）。节点声明两件事：

+ 它服从的#term[协议][protocol]——参数的类型与结算规则（@ch-protocol）；
+ 它的方向——发起者还是响应者，即它在协商里供给什么、要求什么（@sec-attach）。

#term[附着][attach]把一个成员的节点接到某条总线的一个具名节点上（@sec-attach）。连接结构就是全部附着的集合；数量没有独立声明——一条总线有几个成员，就是数一数它的附着。协商完成后，每次附着结算出一条#term[边][edge]：点对点的最终链路，持有结算出的边参数。

#不变量[一次附着的两端必须服从同一协议。跨协议的转换由一个显式的跨协议 adaptor 承担（@sec-protocol-object）。]

== 三阶段流水线 <sec-triptych>

设计的生成被切为三个阶段，前一阶段的输出是后一阶段的唯一输入。我们借三联画之名称之为 #term[Triptych 流水线][the Triptych pipeline]：

#图([Triptych 流水线。矩形是阶段，胶囊是阶段间的不可变产物。左右两侧分别是宿主语言闭包与硬件对象的世界，中间的协商是纯数据计算。])[
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
  columns: (auto, 1fr, 1fr, auto),
  table.header([阶段], [做什么], [禁止什么], [纯度]),
  [构建],
  [宿主语言普通代码：例化模块树、声明节点、书写连接。可以有任意控制流——条件拓扑、循环生成子系统都只是普通程序。],
  [读取任何协商结果：结果对象只在协商返回后才存在，构建期程序无从引用它——这由类型保证。],
  [有副作用（累积声明），但被限制在 `design` 块内],
  [协商],
  [纯函数：输入设计规格，输出协商结果或一个错误（返回类型见@sec-error-semantics）。传播参数、结算每条边、计算协议参数、装配清单、规划端口与连线。],
  [触碰任何硬件对象；读取规格之外的任何环境。],
  [纯函数：同一规格必得同一结果],
  [例化],
  [消费协商结果：以最终参数调用 zaozi 生成器，发射结构模块，执行连线计划。],
  [做任何决策。所有需要判断的问题都已在协商结果中有答案；本阶段只是忠实执行。],
  [将协商结果转写为电路（经 zaozi），全程不含决策],
)

这个切分的目的是把错误前置（@req-iteration）：凡静态可查的问题都在协商一步暴露，硬件生成只消费已经确定的结果。特别地，"在协商完成前读取协商结果"在本模型中*无法表达*：构建期的世界里不存在边参数这种东西；生成器模块想依赖协商结果的部分，被显式建模为协商期计算的协议参数（@sec-two-layer-params），在例化期整体交付。三个阶段之间不存在任何回调回流。

构建期的副作用（声明节点、记录连接）被一个只能由框架入口注入的#term[构建器令牌][builder token]封闭。在宿主语言中，令牌是一个上下文值：

```scala
def design(body: DesignBuilder ?=> Unit): DesignSpec   // 唯一提供令牌的入口
```

附着算子 `<-`（@sec-attach）要求 `(using DesignBuilder)` 才能编译，于是在 `design { ... }` 块之外书写附着直接编译失败；块返回时构建器固化为不可变的规格，令牌不再被持有——"在协商完成后追加附着"同样是无法表达的程序。

== 模块的两种形态 <sec-module-kinds>

层次树上的模块恰好两种，以密封类型区分，不存在混合形态：

- #term[结构模块][`WrapperModule`]　只包含：子模块、用于转发的节点、连接声明。*没有任何硬件逻辑。*它对应的电路模块由框架整体生成——内容仅有子实例、端口与连线（@ch-hierarchy）。
- #term[生成器模块][`GeneratorModule`]　持有*恰好一个* zaozi 生成器。它声明的每个节点就是生成器的一个协议端口——节点结算出的参数即端口的线形状,再没有"把节点另行映射到某个接口字段"的第二层手续（@sec-generator-module）。*全部硬件逻辑都在生成器里。*

用词辨析：*生成器*指硬件域的模块工厂本身——zaozi 的对象，以参数为唯一输入、以模块为输出（@sec-generator-contract）；*生成器模块*是协商域里持有它的那个拓扑模块。二者始终不混用。

#不变量[结构模块拥有零个生成器，生成器模块拥有恰好一个生成器；二者密封，无第三种形态。]

禁止"既有子模块又含少量逻辑"的中间形态，是因为此类逻辑几乎必然要引用协商结果（例如按最终成员数量生成仲裁器），而它所处的位置又在协商所需的连接声明之中：两个阶段在同一段代码里相互依赖，阶段边界随之失效。含逻辑的部分一律归入生成器（它以协议参数的形式获得协商结果），纯组合留给结构模块，三阶段边界才成立。经验上，所有在结构模块中直接书写逻辑的需求，都可以改写为例化一个小型生成器模块。

== 序列化边界 <sec-serialization-boundary>

协商这一侧与硬件生成那一侧之间，只交换一种数据：每个生成器模块最终交给生成器的#term[完整参数][full parameter]——用户参数与协议参数的合成（@sec-two-layer-params）。zaozi 要求它可序列化；这正对应#ref(<req-ip>)：每个 IP 拿到一份固定的参数文件，能够脱离整个设计独立例化、独立测试；协商产物可归档、可复现。

边界的协商一侧，规格中的参数变换函数、协议参数推导函数等都是宿主语言闭包，*从不*要求可序列化——它们活在单次进程内。可选地，拓扑、边参数、层树可以导出为 JSON 供工具消费（@ch-tooling），但那是便利，格式不作兼容承诺。

#图([序列化边界。左侧协商域内是含闭包的规格与纯数据的协商结果；右侧硬件域是生成器与 MLIR。跨越边界的只有可序列化的完整参数。])[
  #syn-canvas({
    import cetz.draw: *
    // 左域
    rect((0, 0), (5.1, 3.2), stroke: 0.7pt, radius: 0.1)
    content((2.55, 2.8), [*协商域*])
    rect((0.35, 1.55), (4.75, 2.35), stroke: 0.6pt + gray, radius: 0.08)
    content((2.55, 1.95), [规格 / 协商结果（含闭包，JVM 局部）])
    // 右域
    rect((7.1, 0), (11.6, 3.2), stroke: 0.7pt, radius: 0.1)
    content((9.35, 2.8), [*硬件域*])
    rect((7.45, 1.55), (11.25, 2.35), stroke: 0.6pt + gray, radius: 0.08)
    content((9.35, 1.95), [zaozi 生成器 / MLIR])
    // 边界线
    line((6.1, -0.25), (6.1, 3.45), stroke: 2.2pt)
    content((6.1, 3.72), [序列化边界])
    // 跨界箭头
    line((4.9, 0.75), (7.3, 0.75), mark: (end: ">"), stroke: 1.1pt + c-edge)
    content((6.1, 1.12), text(fill: c-edge)[完整参数（可序列化）])
  })
]

以上各节给出了骨架：两张图、三个阶段、两种模块、一条序列化边界；它们如何逐条回应#ref(<sec-requirements>)的七条需求，见@sec-req-map。余下各章逐一充实：协议与参数（@ch-protocol）、互连模型（@ch-interconnect）、协商算法（@ch-negotiation）、层次穿越与模块生成（@ch-hierarchy）、硬件边界（@ch-hardware）、验证（@ch-verification）以及工具与静态评估（@ch-tooling）；需求与章节的完整对应见@sec-req-map。
