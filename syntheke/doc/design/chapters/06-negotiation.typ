#import "../lib.typ": *

= 协商算法 <ch-negotiation>

协商将 `DesignSpec` 转换为 `ResolvedDesign`。框架先结算域与归属，并分阶段检查；随后在两张方向相反的参数依赖 DAG 上传播：`Down` 按拓扑序前进，`Up` 按反向拓扑序返回；两遍完成后逐边求解，再计算各生成器模块的完整参数。本章按执行顺序规定这些步骤，然后给出输出记录和错误语义；跨层规划见 @ch-hierarchy。

== 协商流程 <sec-passes>

#图([协商流程。域在任何参数计算之前结算并检查；之后的数据传播只读结算后的域。])[
  #syn-diagram(
    spacing: (9mm, 8mm),
    node((0, 0), [结构校验], name: <v>),
    node((1.3, 0), [域结算与检查], name: <ds>, fill: rgb("#fdf3d7")),
    node((2.7, 0), [参数依赖排序], name: <topo>),
    node((2.1, 1.2), [`Down` 正向传播], name: <d>, fill: rgb("#edf5ff")),
    node((3.3, 1.2), [`Up` 反向传播], name: <u>, fill: rgb("#fff1ef")),
    node((2.7, 2.3), [设计边求解], name: <e>),
    node((1.1, 2.3), [视图、完整参数与探针目录], name: <p>),
    node((1.1, 3.4), [端口与连线计划], name: <w>),
    node((2.6, 3.4), [`ResolvedDesign`], name: <r>, shape: fletcher.shapes.pill, fill: c-fill),
    edge(<v>, <ds>, "-|>"),
    edge(<ds>, <topo>, "-|>"),
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

+ 结构校验：生成器与结构模块名唯一、bind 的端点与协议、一次绑定（@sec-structural-check）；
+ 结算每个域与每项归属，核对每条 bind 跨越的域，分阶段运行各域类的检查（@sec-domain-settlement、@sec-domain-checks）；
+ 由 bind 与模块内部参数依赖构造 `Down` 参数依赖 DAG，并执行稳定拓扑排序；
+ 按该顺序执行 `Down` 正向传播，按逆序执行 `Up` 反向传播（@sec-propagation）；
+ 逐条设计边调用 `negotiate`（@sec-settle-pp）；
+ 求出每条边的接口；按模块装配 `EdgeView`，与 `DomainGraph` 一起计算完整参数；执行探针选择，得到探针目录与观测绑定（@sec-generator-parameters、@ch-verification）；
+ 核对所例化设计的冻结契约，规划跨层端口、连线与 FIRRTL 层，装配 `ResolvedDesign`（@sec-design-boundary、@ch-hierarchy）。

端口参数函数输入按读取集合的声明形状排列。两张 DAG 均使用稳定拓扑序；它们决定求值次序，因而也决定首个错误是哪一个。导出顺序采用 @ch-tooling 的规范。

== 结构校验与稳定拓扑序 <sec-structural-check>

构建器已保证的性质不在协商期重查：名称唯一、节点方向与 bind 方向、归属与节点方向和协议承载一致、模块内部参数依赖由读取集合推出（@sec-build）。结构校验只核对构建器看不到全局的部分，并报告全部失败：不同生成器定义不得同名；全设计的结构模块名互不相同（@dec-wrapper-module-name）；每条 bind 的两端节点属于本设计、使用同一协议对象，声明该 bind 的结构模块是两端节点所在模块的祖先（@sec-node-conn-proto）；每个 outward 节点恰好作为一次 bind 的源，每个 inward 节点恰好作为一次 bind 的目标。

`Down` 参数依赖 DAG 由以下两类边组成：每条 bind 从源 outward 节点指向目标 inward 节点；每条模块内部参数依赖从 inward 节点指向 outward 节点。`Up` 参数依赖 DAG 反转上述全部方向。结构校验检查 `Down` 图无环；`Up` 是它的反向图，自动无环。稳定拓扑排序在多个节点均可选择时，采用模块的层次树先序和节点声明顺序打破平局；`Up` 直接使用同一拓扑序的逆序。检测到环时，错误包含环上的全部 `ModuleNodeId` 及其源码位置。

== 域的结算 <sec-domain-settlement>

结算把构建期的域与归属变成#term[结算后的域][`Settled`]：每个源都已确定，派生关系是纯数据。

- 根直接结算。
- 派生域先结算它的每个源：源是域时结算该域；源是节点的域时，结算该节点的归属。
- 归属给出域时结算该域；给出另一个节点时取那个节点的归属；不写的承载 inward 节点取其 bind 的 outward 端的归属。

结算路径成环、引用了不属于本设计的域，或节点在某类域中没有归属，都在域结算阶段报告。结算不调用用户函数，也不依赖任何参数，所以它在数据传播之前完成。

#决策([域先于数据结算])[
  域只由根和派生关系决定，在任何端口参数函数执行之前结算完毕；端口参数函数和 `negotiate` 不读也不写域。需要由数据结果决定频率一类的场景，由外层设计读取内层的冻结结果作为用户参数（@dec-design-frozen）。
] <dec-domain-no-feedback>

== 域检查 <sec-domain-checks>

域的检查分阶段运行：

+ *结算。*同一个域类名只对应一个对象；每个域与每项归属都能结算。
+ *跨越。*每条 bind 的每个活跃域类两端都有归属，并由协议承载或由协议的 `accepts` 接受（@sec-domain-crossing）。
+ *良构。*各域类插件中 `WellFormed` 阶段的检查，例如时钟派生的源与链路数一致、节点的复位在它的时钟上释放、PRCM 参数满足 #159 的规则。
+ *行为。*各域类插件中 `Behavior` 阶段的检查，用于由参数构造状态模型并证明其性质，例如 PRCM 的上下电时序。

插件实现 `DomainCheck`：给出名字和阶段，读取 `DomainGraph`，返回失败描述。行为检查用到的模型与搜索算法属于域类，框架不提供。

#决策([检查整批报告，计算首错终止])[
  同一阶段的检查互不依赖，全部运行，失败一起报告；有失败就不进入下一阶段，因为下一阶段假定前面的性质成立。这对结构校验、域的各阶段、公开探针、观测绑定与冻结契约都一样。计算（端口参数函数、`negotiate`、完整参数函数、探针选择）依赖前面的值，在第一个失败处停止。
] <dec-domain-order-errors>

== `Down` 与 `Up` 传播 <sec-propagation>

对每个 outward 节点 $o$，令 $"pred"(o)$ 为它的 `dFn` 读取的 inward 节点；对每个 inward 节点 $i$，令 $"succ"(i)$ 为它的 `uFn` 读取的 outward 节点。

- 按拓扑序到达 outward 节点 $o$ 时，$"pred"(o)$ 的 `Down` 都已到达；调用 `dFn_o`，得到唯一的 `Down`，随后沿 $o$ 所在的 bind 传给目标 inward 节点。
- 按逆序到达 inward 节点 $i$ 时，$"succ"(i)$ 的 `Up` 都已到达；调用 `uFn_i`，得到唯一的 `Up`，随后沿 $i$ 所在的 bind 传回源 outward 节点。

`fixed` 节点直接给出常量。函数返回 `Violation` 时立即报错，消息包含节点、方向、按读取顺序编码的输入快照与源码位置。两遍传播互不以对方的结果为输入；同一输入产生同一结果。

互连上的传播见 @sec-interconnect-flow；地址重叠、不可达或身份空间无法分配等冲突由正在执行的端口参数函数以 `Violation` 报出（@sec-error-semantics）。

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

== 边求解 <sec-settle-pp>

*边求解*在两遍传播全部完成后，按 bind 声明顺序为每条 bind 调用一次 `negotiate(down, up)`（@sec-protocol-object）。失败结果包含 `BindId`、协议给出的冲突描述与 bind 的源码位置；成功结果给出 `Edge`。

== 生成器参数 <sec-generator-parameters>

逐边求解时，框架以每条边的 `Edge` 调用协议的 `interface`，得到 `ProtocolBundle`；接口含 `Probe` 时报错。随后按模块先序为每个生成器模块装配 `EdgeView`：本模块每个节点及其唯一的已求解边。

以 `EdgeView` 和 `DomainGraph` 调用模块的完整参数函数，得到 `FullParam`。函数返回 `Violation` 表示本模块承载不了协商出的参数或所在的域：端口数、接口能力或资源容量超限，时钟频率不够，电源不是常开，复位不在本模块的时钟上释放，等等。随后框架从完整参数读出公开探针端口清单，执行探针选择，并读出观测绑定（@ch-verification）。

#决策([边只读本模块，域可读整张图])[
  完整参数函数读取本模块的 `EdgeView` 与整张 `DomainGraph`：本模块每个节点唯一的已求解边，以及全设计结算后的域。它读不到其它模块的边，也不把结果反馈给域结算或端口参数函数。读整张域图让汇总型生成器（PRCM、时钟树、电源树）取得它管理的域；写进完整参数的仍只是属性与稳定名字（@dec-full-param-content）。整机地址映射一类的边上汇总由工具从导出数据生成（@sec-export）；生成器需要它时，作为用户参数进入下一轮构建。
] <dec-pp-local>

== 已求解记录 <sec-resolved-records>

域的结果是一个 `DomainGraph`：按声明顺序的结算后的域，以及每个节点在每个域类中的归属。

每条设计 bind 产生一个 `ResolvedEdge`：`BindId`、协议对象、传播得到的 `Down` 和 `Up`、逐边求得的 `Edge` 以及 `interface(edge)` 返回的 `ProtocolBundle`。全部记录按 bind 声明顺序保存。

== 生成器参数记录 <sec-generator-records>

每个生成器模块产生一个 `ResolvedGeneratorModule`：模块标识、生成器定义、`EdgeView`、完整参数，以及公开探针端口清单。记录按模块的层次树先序保存。

`EdgeView` 按本模块的节点声明顺序保存条目，每个条目记录节点方向和该节点唯一的 `ResolvedEdge`。读取以本模块的端口句柄为键，结果按节点协议类型化，不提供按名字符串的查询。`DomainGraph` 以域或 `node.domain(K)` 为键返回类型化的结算后的域；域类据此计算属性与关系。

== 错误语义 <sec-error-semantics>

协商成功返回 `ResolvedDesign`。用户设计错误一律以 `NegotiationException` 报出：消息先陈述问题，再内联相关稳定标识、参数快照与源码位置；没有错误类别或编号。检查整批报告，计算首错终止（@dec-domain-order-errors）。协议对象、端口参数函数与完整参数函数以值（`Left`）表达冲突。用户代码抛出的异常不由协商器包装，原样穿透并保留其调用栈。

凡是声明处即可判定的契约在构建阶段当场以 `NegotiationException` 报出，带声明处的源码位置，不进入协商：声明名形状非法或在同一作用域重复；节点重复给出同一域类的归属；outward 承载节点没有给出它驱动的域，或 inward 承载节点给出了；物理域类的归属不来自本模块；节点草稿重复封口或从未封口；生成器模块没有声明完整参数函数；端口参数函数读取了别的模块的节点（方向不对是编译错误）；不允许作用域的域类被用于 `scope`；边界声明在设计根以外。协商阶段报告需要全局视角才能判定的问题：

#table(
  columns: (auto, 1fr, 1fr),
  table.header([产生阶段], [触发条件], [报告必含]),
  [结构校验], [不同生成器定义同名；结构模块名重复；bind 端点不属于本设计、协议对象不同、声明处不是祖先；节点未恰好参与一次 bind。], [相关 `ModuleNodeId`、`BindId`、声明 bind 的模块、实际 bind 次数和源码位置。],
  [域结算], [同一域类名对应不同对象；域或归属结算成环、引用了本设计以外的域、节点没有归属。], [全部失败：域或节点与源码位置。],
  [域跨越], [bind 只有一端有某类归属，协议没有声明该类，或不接受两端的关系。], [全部失败：`BindId`、域类、两端的域与关系、源码位置。],
  [域检查：良构、行为], [域类插件返回失败。], [全部失败：域类、检查名与插件给出的描述。],
  [参数拓扑排序], [参数依赖图存在环。], [环上的节点；环内每条 bind 与内部依赖的源码位置。],
  [`Down` 或 `Up` 传播], [`dFn` 或 `uFn` 返回 `Violation`，例如地址区域重叠、请求地址不可达或事务身份空间无法分配。], [节点、传播方向、有序输入快照、冲突描述和源码位置。],
  [边求解], [`negotiate` 返回参数冲突。], [`BindId`、冲突描述与源码位置。],
  [生成器参数], [设计边接口含 `Probe`；完整参数函数返回冲突；探针选择失败或选中不存在的端口。], [生成器模块、相关节点或探针、冲突描述与源码位置。],
  [公开探针、观测绑定], [公开探针端口名重复或与节点同名；观测的探针不在目录中或端口名冲突。], [全部失败：生成器模块、端口名与源码位置。],
  [冻结契约], [外层协商改变了所例化设计的边界边、边界所属域的属性，或哪些边界同域；不同生成器或结构模块共用模块名。], [全部失败：边界标识、改变的内容与源码位置。],
)
