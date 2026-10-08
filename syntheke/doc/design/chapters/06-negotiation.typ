#import "../lib.typ": *

= 协商算法 <ch-negotiation>

协商将 `DesignSpec` 转换为 `ResolvedDesign`。框架先解析节点域，按域依赖 DAG 结算各域定值；随后在两张方向相反的参数依赖 DAG 上传播：`Down` 按拓扑序前进，`Up` 按反向拓扑序返回；两遍完成后逐边求解；最后收齐各处贡献的约束，统一验证域要求与检查。本章按执行顺序规定这些步骤，然后给出输出记录和错误语义；跨层规划见 @ch-hierarchy。

== 协商流程 <sec-passes>

#图([协商流程。域定值先按自己的依赖 DAG 结算，成为数据传播的只读输入；传播与求解贡献的约束最后统一验证，只接受或拒绝。])[
  #syn-diagram(
    spacing: (9mm, 8mm),
    node((0, 0), [结构校验], name: <v>),
    node((1, 0), [节点域解析], name: <att>),
    node((2, 0), [域定值结算], name: <ds>, fill: rgb("#fdf3d7")),
    node((3.3, 0), [参数依赖排序], name: <topo>),
    node((2.7, 1.2), [`Down` 正向传播], name: <d>, fill: rgb("#edf5ff")),
    node((3.9, 1.2), [`Up` 反向传播], name: <u>, fill: rgb("#fff1ef")),
    node((3.3, 2.3), [设计边求解], name: <e>),
    node((2.1, 2.3), [约束验证], name: <c>, fill: rgb("#fdf3d7")),
    node((0.8, 2.3), [视图、完整参数与探针目录], name: <p>),
    node((0.8, 3.4), [端口与连线计划], name: <w>),
    node((2.3, 3.4), [`ResolvedDesign`], name: <r>, shape: fletcher.shapes.pill, fill: c-fill),
    edge(<v>, <att>, "-|>"),
    edge(<att>, <ds>, "-|>"),
    edge(<ds>, <topo>, "-|>"),
    edge(<topo>, <d>, "-|>"),
    edge(<topo>, <u>, "-|>"),
    edge(<d>, <e>, "-|>"),
    edge(<u>, <e>, "-|>"),
    edge(<e>, <c>, "-|>"),
    edge(<c>, <p>, "-|>"),
    edge(<p>, <w>, "-|>"),
    edge(<w>, <r>, "-|>"),
  )
]

具体阶段依次为：

+ 结构校验：名称唯一性、bind、协议匹配、一次绑定、模块内部参数依赖；域类对象唯一性、域声明、节点域与读取集合（@sec-structural-check）；
+ 解析每条 bind 两端的节点域，按附着策略检查附着来源（@sec-attachment-resolution）；
+ 由域声明的前驱构造域依赖 DAG，按稳定拓扑序计算各域定值（@sec-domain-settlement）；
+ 由 bind 与模块内部参数依赖构造 `Down` 参数依赖 DAG，并执行稳定拓扑排序；
+ 按该顺序执行 `Down` 正向传播，按逆序执行 `Up` 反向传播（@sec-propagation）；
+ 逐条设计边调用 `negotiate`（@sec-settle-pp）；
+ 收齐全部约束，逐域验证要求，核对跨边域关系的覆盖，执行检查（@sec-constraint-validation）；
+ 求出每条边的接口；按模块装配 `EdgeView` 与 `DomainView`，计算完整参数；执行探针选择，得到探针目录与观测绑定（@sec-generator-parameters、@ch-verification）；
+ 核对所例化设计的冻结契约，规划跨层端口、连线与 FIRRTL 层，装配 `ResolvedDesign`（@sec-design-boundary、@ch-hierarchy）。

前三步失败时，任何端口参数函数、`negotiate`、`interface`、完整参数函数和探针选择都还没有执行，错误消息会注明这一点。

端口参数函数输入按读取集合的声明形状排列。两张 DAG 均使用稳定拓扑序；它们决定求值次序，因而也决定首个错误是哪一个。导出顺序采用 @ch-tooling 的规范。

== 结构校验与稳定拓扑序 <sec-structural-check>

结构校验核对：同一生成器模块内节点名与探针名唯一；不同生成器定义不得同名；全设计的结构模块名互不相同（@dec-wrapper-module-name）。

每条设计 bind 的源、目标节点必须存在，源节点方向为 outward，目标节点方向为 inward，两端使用同一协议对象，声明该 bind 的结构模块是两端节点所在模块的祖先（@sec-node-conn-proto）。每个 outward 节点恰好作为一次 bind 的源，每个 inward 节点恰好作为一次 bind 的目标。

模块内部参数依赖的两端必须是本模块节点，方向为 inward 到 outward，且不重复。每个端口参数函数的读取集合必须来自本次构建：outward 节点只读本模块 inward 节点的 `Down`，inward 节点只读本模块 outward 节点的 `Up`，域引用只读本节点的节点域；每一次读取都有对应的依赖。

域结构校验核对：同一 `DomainKey` 在设计中只对应一个域类对象；每个 `DomainDeclId` 只声明一次；域 handle 与节点域属于本次构建且不是已丢弃的旧实例；每个节点对每个域类至多一个节点域；承载协议的节点为每个承载的域类给出节点域，非承载的节点域不使用承载选择器；`CarrierOut` 只在 outward 节点，`CarrierIn` 只在 inward 节点；`Contextual` 的提供者是该节点所在模块的祖先；`Follow` 的目标是同一域类、且不是本节点自己；冻结选择器只出现在边界模块上（@sec-design-boundary）。

`Down` 参数依赖 DAG 由以下两类边组成：每条 bind 从源 outward 节点指向目标 inward 节点；每条模块内部参数依赖从 inward 节点指向 outward 节点。`Up` 参数依赖 DAG 反转上述全部方向。结构校验检查 `Down` 图无环；`Up` 是它的反向图，自动无环。稳定拓扑排序在多个节点均可选择时，采用模块的层次树先序和节点声明顺序打破平局；`Up` 直接使用同一拓扑序的逆序。检测到环时，错误包含环上的全部 `ModuleNodeId` 以及相关节点、bind 与内部依赖的源码位置。

== 节点域解析 <sec-attachment-resolution>

对每条 bind，两端节点域中出现的域类与协议承载的域类构成这条 bind 的活跃域类（@sec-domain-crossing）。框架为每条 bind 两端的每个活跃域类解析节点域，按模块先序、节点声明顺序与 `DomainKey` 排序：

- `Direct` 与 `Contextual` 直接得到所选的域声明；
- `Follow` 先解析目标节点域，再取它的结果；
- `CarrierOut` 取所选 handle，或先解析所跟随的节点域；
- `CarrierIn` 沿本节点的 bind 解析 outward 端同一域类的节点域。

解析结果记录域声明与完整的附着来源。解析路径成环时报错，并列出环上的节点域。每次解析都交给该域类的附着策略：方法不被允许，或策略拒绝附着来源，均报错。

== 域依赖与结算 <sec-domain-settlement>

域依赖 DAG 的顶点是域声明。域声明的每个前驱贡献一条边：前驱是域 handle 时直接连到它；前驱是节点域时，连到该节点域解析所得的域声明。节点域、bind 和参数依赖本身不产生域依赖边。多个域可选时，按声明模块的层次树先序、域声明顺序与声明名打破平局；检测到环时报告环上全部域与源码位置。

按拓扑序处理一个域时，框架以前驱的定值调用声明的定值函数，得到唯一的定值，以及声明者可能附带的一项要求。定值函数返回冲突时立即报错。此时只产生定值；要求留到约束验证统一处理。

#决策([域定值先于数据结算，数据只能确认或拒绝])[
  域定值只由声明的定值函数从前驱定值算出，在任何端口参数函数执行之前确定。端口参数函数、`negotiate` 与模块体可以对域贡献要求和检查，但它们只能接受或拒绝既有定值，不能改变它，也不能把数据结果反馈给定值函数。域结算不综合定值、不执行迭代。需要由数据结果决定频率一类的场景，由外层设计读取内层的冻结结果作为用户参数（@dec-design-frozen）。
] <dec-domain-no-feedback>

== `Down` 与 `Up` 传播 <sec-propagation>

对每个 outward 节点 $o$，令 $"pred"(o)$ 为它的 `dFn` 读取的 inward 节点；对每个 inward 节点 $i$，令 $"succ"(i)$ 为它的 `uFn` 读取的 outward 节点。

- 按拓扑序到达 outward 节点 $o$ 时，$"pred"(o)$ 的 `Down` 都已到达；调用 `dFn_o`，得到唯一的 `Down` 与一组约束，随后沿 $o$ 所在的 bind 传给目标 inward 节点。
- 按逆序到达 inward 节点 $i$ 时，$"succ"(i)$ 的 `Up` 都已到达；调用 `uFn_i`，得到唯一的 `Up` 与一组约束，随后沿 $i$ 所在的 bind 传回源 outward 节点。

函数还可以读取本节点的节点域定值。`fixed` 节点直接给出常量。函数返回 `Violation` 时立即报错，消息包含节点、方向、按读取顺序编码的输入快照与源码位置。两遍传播互不以对方的结果为输入；同一输入产生同一结果。

以内存互连为例，Xbar 或 NoC 为每个下游 outward 节点读取能到达它的上游 inward 节点。该 outward 节点的 `dFn` 聚合这些 inward 节点的事务身份需求，计算 ID 扩展、节点编号或内部表项容量；每个上游 inward 节点的 `uFn` 则聚合其可达 outward 节点的地址区域、操作能力和位宽约束。地址重叠、不可达或身份空间无法分配等冲突由正在执行的端口参数函数以 `Violation` 报出（@sec-error-semantics）。

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

*边求解*在两遍传播全部完成后，按 bind 声明顺序为每条 bind 调用一次 `negotiate(down, up, domains)`（@sec-protocol-object）。`domains` 给出两端节点的节点域及其定值。失败结果包含 `BindId`、协议给出的冲突描述与 bind 的源码位置；成功结果给出 `Edge` 与一组约束。

== 约束验证 <sec-constraint-validation>

求解结束后，框架收齐四个来源的约束：域声明自带的要求、模块体返回的约束、端口参数函数返回的约束、`negotiate` 返回的约束。每项约束记录它的#term[贡献者][contributor]：域声明、模块、节点或 bind。贡献者能读的域受限：节点只读本节点的节点域，bind 只读两端的节点域，域声明只能对自己提要求。

验证分三步：

+ *要求。*按域结算的拓扑序，把每个域收到的全部要求连同定值交给域类的 `validate`。要求带有序号，验证器失败时返回违反的要求序号与见证；框架据此列出相关贡献者与源码位置。验证器返回本域没有的序号是验证器的错误。
+ *跨边覆盖。*对每条 bind 的每个活跃域类，协议承载该域类，或 `negotiate` 返回了一项同时读取两端该域类节点域的检查；否则报错（@dec-domain-crossing-coverage）。
+ *检查。*按收集顺序执行每项检查，以它读取的域定值构成的视图调用；拒绝即报错。

#决策([约束验证置换不变，首错完整报告])[
  域类的 `validate` 必须对要求序列的任意置换给出同一结论；稳定顺序只用于展示和确定首个错误。框架不搜索最小不可满足核：验证器返回哪些要求，就报告哪些贡献者。协商在第一个失败的域或检查处终止，但完整报告该失败的全部相关贡献者与见证。
] <dec-domain-order-errors>

== 生成器参数 <sec-generator-parameters>

约束验证通过后，框架以每条边的 `Edge` 调用协议的 `interface`，得到 `ProtocolBundle`；接口含 `Probe` 时报错。随后按模块先序为每个生成器模块装配两份视图：

- `EdgeView`：本模块每个节点及其唯一的已求解边；
- `DomainView`：本设计全部域 handle 与本模块全部节点域的定值。

以两份视图调用模块的完整参数函数，得到 `FullParam`；函数返回 `Violation` 表示本模块的生成器实现承载不了协商出的参数，例如端口数、接口能力或资源容量超限，或多个域定值不能由同一生成器联合实现。随后框架从完整参数读出公开探针端口清单，执行探针选择，并读出观测绑定（@ch-verification）。

#决策([完整参数只读本模块视图与域定值])[
  完整参数函数读取本模块的 `EdgeView` 与 `DomainView`：每个节点唯一的已求解边，以及域定值。它读不到其它模块的边，也不把结果反馈给域结算或端口参数函数。全局域身份只能以 `sameIdentity` 比较，不进入完整参数。整机地址映射一类的全局汇总由工具从导出数据生成（@sec-export）；生成器需要它时，作为用户参数进入下一轮构建。
] <dec-pp-local>

== 已求解记录 <sec-resolved-records>

每个域声明产生一个 `ResolvedDomain`：`DomainDeclId`、域类对象、定值及其编码、前驱域、按收集顺序保存的要求（贡献者、值与编码）和源码位置。

每个被解析的节点域产生一个 `ResolvedDomainAttachment`：`NodeDomainKey`、解析得到的 `DomainDeclId` 与附着来源。每项通过的承载或检查产生一个 `ResolvedDomainCheck`，记录它的来源与涉及的域类。

每条设计 bind 产生一个 `ResolvedEdge`：`BindId`、协议对象、传播得到的 `Down` 和 `Up`、逐边求得的 `Edge` 以及 `interface(edge)` 返回的 `ProtocolBundle`。全部记录按 bind 声明顺序保存。

== 生成器参数记录 <sec-generator-records>

每个生成器模块产生一个 `ResolvedGeneratorModule`：模块标识、生成器定义、`EdgeView`、`DomainView`、完整参数及其编码，以及公开探针端口清单。记录按模块的层次树先序保存。

`EdgeView` 按本模块的节点声明顺序保存条目，每个条目记录节点方向和该节点唯一的 `ResolvedEdge`。读取以本模块的端口句柄为键，结果按节点协议类型化，不提供按名字符串的查询。`DomainView` 以域 handle 或节点域为键返回类型化的定值，并提供 `sameIdentity` 比较两个引用是否解析到同一域。

== 错误语义 <sec-error-semantics>

协商成功返回 `ResolvedDesign`；发现首个错误时立即以 `NegotiationException` 终止，不收集后续错误。异常消息直接陈述问题本身，并内联相关稳定标识、源码位置及参数快照；没有错误类别或编号。域类验证器、协议对象、端口参数函数、检查与完整参数函数以值（`Left`）表达约束冲突；协商器收到即抛。用户代码抛出的异常不由协商器包装，原样穿透并保留其调用栈。

凡是声明处即可判定的契约在构建阶段当场检查、当场抛出，不进入协商：声明名形状非法或在同一作用域重复；节点重复给出同一域类的节点域；承载节点缺少承载域，或 inward 承载节点指定了域源；节点草稿重复封口或从未封口；生成器模块没有声明完整参数函数；端口参数函数读取了别的模块的节点，或方向不对；`Contextual` 附着没有可用的 `provide`，或域类不允许；句柄来自另一次构建或已丢弃的模块；边界声明在设计根以外。观测绑定的归属与冻结契约的核对发生在集成步骤，以 `IllegalArgumentException` 报出。协商阶段只报告需要全局视角才能判定的问题：

#table(
  columns: (auto, 1fr, 1fr),
  table.header([产生阶段], [触发条件], [报告必含]),
  [结构校验], [节点或探针名重复；不同生成器定义同名；结构模块名重复；bind 端点不存在、方向不对、协议对象不同、声明处不是祖先；节点未恰好参与一次 bind；内部依赖重复或方向不对。], [相关 `ModuleNodeId`、`BindId`、声明 bind 的模块、实际 bind 次数和源码位置。],
  [域结构校验], [同一 `DomainKey` 对应不同对象；域重复声明；节点域与协议承载不符；选择器非法；读取集合越界。], [域、节点域或节点的稳定标识与源码位置。],
  [节点域解析], [解析成环；附着策略不允许该方法或拒绝附着来源。], [环上的节点域；节点域、方法与策略给出的描述。],
  [域结算], [域依赖成环；定值函数返回冲突。], [环上的域与源码位置；`DomainDeclId` 与冲突描述。],
  [参数拓扑排序], [参数依赖图存在环。], [环上的节点；环内每条 bind 与内部依赖的源码位置。],
  [`Down` 或 `Up` 传播], [`dFn` 或 `uFn` 返回 `Violation`，例如地址区域重叠、请求地址不可达或事务身份空间无法分配。], [节点、传播方向、有序输入快照、冲突描述和源码位置。],
  [边求解], [`negotiate` 返回参数冲突。], [`BindId`、冲突描述与源码位置。],
  [约束验证], [域类验证器拒绝要求；某条 bind 的活跃域类没有被承载或协议检查覆盖；检查拒绝；约束读取了贡献者不能读的域。], [`DomainDeclId`、相关贡献者、见证与全部源码位置；或 `BindId` 与域类。],
  [生成器参数], [设计边接口含 `Probe`；完整参数函数返回冲突；公开探针端口名重复或与节点同名；探针选择失败或选中不存在的端口；观测绑定的探针不在目录中或端口名冲突。], [生成器模块、相关节点或探针、冲突描述与源码位置。],
  [冻结契约], [外层协商改变了所例化设计的边界边、边界域定值或域身份关系。], [边界标识与改变的内容。],
)
