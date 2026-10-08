#import "../lib.typ": *

= 协商算法 <ch-negotiation>

协商将 `DesignSpec` 转换为 `ResolvedDesign`。框架先解析域结构，在域依赖 DAG 上得到源定值并对全域成员要求作一次验证；随后在两张方向相反的参数依赖 DAG 上传播：`Down` 按拓扑序前进，`Up` 按反向拓扑序返回；两遍完成后，每条 bind 独立求解。本章按执行顺序规定结构校验、域结算、传播、求解与生成器参数计算，然后给出输出记录和错误语义；跨层规划见 @ch-hierarchy。

== 协商流程 <sec-passes>

#图([协商流程。域结构先按自己的依赖 DAG 结算；其定值成为两遍数据传播的只读输入。`Down` 与 `Up` 两遍互不以对方的结果为输入，结束后才逐边求解。])[
  #syn-diagram(
    spacing: (9mm, 8mm),
    node((0, 0), [结构校验], name: <v>),
    node((1, 0), [域依赖排序], name: <dtopo>),
    node((2, 0), [域结算], name: <ds>, fill: rgb("#fdf3d7")),
    node((3.3, 0), [参数依赖排序], name: <topo>),
    node((2.7, 1.2), [`Down` 正向传播], name: <d>, fill: rgb("#edf5ff")),
    node((3.9, 1.2), [`Up` 反向传播], name: <u>, fill: rgb("#fff1ef")),
    node((3.3, 2.3), [设计边求解], name: <e>),
    node((2.1, 2.3), [本模块视图与完整参数], name: <p>),
    node((0.8, 2.3), [端口与连线计划], name: <w>),
    node((0.8, 3.4), [`ResolvedDesign`], name: <r>, shape: fletcher.shapes.pill, fill: c-fill),
    edge(<v>, <dtopo>, "-|>"),
    edge(<dtopo>, <ds>, "-|>"),
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

+ 核对生成器名字唯一性，校验名称、节点方向、bind、协议匹配、模块内部参数依赖和域结构；解析时钟与复位物理路径以及电源作用域继承，以并查集检查 `DomainContract` 与同域组；
+ 由显式域依赖构造域依赖 DAG，执行稳定拓扑排序，依次计算源定值与成员要求并逐域验证；
+ 由 bind 与模块内部参数依赖构造 `Down` 参数依赖 DAG，并执行稳定拓扑排序；
+ 按该顺序执行 `Down` 正向传播，按逆序执行 `Up` 反向传播；
+ 逐条设计边调用 `negotiate`；
+ 按模块装配 `EdgeView` 与 `DomainView`，计算已求解参数与完整参数，并执行生成器能力及模块局部联合校验；
+ 规划跨层端口、连线与 FIRRTL 层，装配 `ResolvedDesign`。

端口参数函数输入按相关节点的声明顺序排列。两张 DAG 均使用稳定拓扑序；它们决定求值次序，因而也决定首个错误是哪一个。域成员的稳定顺序只用于诊断与首错选择，不得影响域验证结论；导出顺序采用 @ch-tooling 的规范。

== 结构校验与稳定拓扑序 <sec-structural-check>

结构校验先核对生成器注册表——由模块树推导的先序条目序列。同一模块内的子实例名唯一，节点名与探针源名唯一；每个生成器名字对应一个注册表条目。全设计的结构模块名互不相同，根的 `Top` 与其余结构模块一同参与（@dec-wrapper-module-name）。

每条设计 bind 的源、目标节点必须存在，声明该 bind 的结构模块必须是两端节点所在模块的祖先（@sec-node-conn-proto）。源节点方向为 outward，目标节点方向为 inward，两端协议匹配；每个 outward 节点恰好作为一次 bind 的源，每个 inward 节点恰好作为一次 bind 的目标。节点的数量在构建期已经固定，结构校验分别核对每个 outward 节点在 bind 源中出现一次、每个 inward 节点在 bind 目标中出现一次。

模块内部只保存一份从本模块 inward 节点指向 outward 节点的参数依赖边集；每条依赖带声明顺序和源码位置。outward 节点的前驱与 inward 节点的后继都从该边集派生，按节点声明顺序排列。重复依赖边非法。每个 outward 节点必须携带 `dFn`，每个 inward 节点必须携带 `uFn`，函数字段不可选；函数可读的节点集合与依赖边集来自同一次声明（@sec-generator-module）。

域结构校验核对每个 `DomainDeclId` 唯一且只有一个源，域 handle 与域依赖目标存在且域类匹配，附着和要求的 subject 属于声明模块允许的作用域，每个 `(subject, 域类)` 至多一个有效附着和一项要求。时钟和复位附着沿本模块 inward 节点唯一的 bind 找到源侧 `realizes` 声明；电源附着按 @dec-power-domain-inheritance 解析。承载声明只能位于相应物理协议的 outward 节点，且引用同域类的域。

每个生成器模块按域类声明的同域组必须覆盖该类的全部相关节点，节点在本模块同一域类中恰好出现一次。框架将同域组、设计边两端协议 `DomainContract` 的 `Same` 谓词和已解析附着作为等式交给并查集；同一等价类含两个不同域身份，或协议要求 `Same` 而相应类没有有效附着，均在数据传播前报告。协议对设计中出现的某个域类没有给出 `Same` 或带理由的 `Unconstrained` 时，报告协议定义错误。域关系不从参数依赖推断。

`Down` 参数依赖 DAG 由以下两类边组成：每条 bind 从源 outward 节点指向目标 inward 节点；每条模块内部参数依赖从 inward 节点指向 outward 节点。`Up` 参数依赖 DAG 反转上述全部方向。结构校验检查 `Down` 图无环；`Up` 是它的反向图，自动无环。稳定拓扑排序在多个节点均可选择时，采用模块的层次树先序和节点声明顺序打破平局；`Up` 直接使用同一拓扑序的逆序。检测到环时，错误包含环上的全部 `ModuleNodeId`、`BindId`、模块内部参数依赖和源码位置。

== 域依赖与结算 <sec-domain-settlement>

域依赖 DAG 的顶点是 `DomainDeclId`。域源定值函数或某项成员要求读取前驱域时，声明一条从前驱域到本域的依赖边；附着、物理 bind、承载和同域组均不产生依赖边。结构校验检查依赖两端存在、读取句柄与依赖声明来自同一次 API 调用，并对全图执行稳定拓扑排序。多个域可选时，按声明模块的层次树先序和域声明顺序打破平局；检测到环时报告环上全部域、依赖声明与源码位置。

按拓扑序处理一个域时，框架先调用源函数得到唯一的定值，再按成员所属模块先序及要求声明顺序计算全部要求。成员序列是多重集：两个值相同的要求仍是两个参与者。域类随后调用 `validate(settledValue, members)`；成功保留源定值，失败返回 `DomainViolation`。域结算只验证源给定的值，不在候选值中选择，也不把成员要求反馈给源。

`DomainViolation` 包含源与全部相关成员的稳定标识、冲突见证和描述。相关参与者由域类验证器主动返回；框架不搜索最小不可满足核，也不把折叠中最后访问的成员视为肇事者。验证函数必须对成员序列的任意置换返回同一成功定值或等价的冲突参与者多重集与见证；稳定成员顺序只用于输入快照、参与者展示和确定首个错误。域验证器返回冲突时协商器立即抛出，不继续结算后续域。

#决策([域结算采用置换不变的首错语义])[
  域按稳定拓扑序结算，成员按模块树先序和要求声明顺序形成多重集。域类验证必须对成员排列置换不变；`DomainViolation` 由验证器给出源、相关成员和见证，框架不求最小不可满足核。框架在第一个失败域立即终止，但完整报告该失败的全部相关参与者。
] <dec-domain-order-errors>

#决策([域只验证静态定值且不接受数据反馈])[
  域源定值与成员要求只读用户参数和显式前驱域定值；禁止读取 `Down`、`Up`、`Edge`、`EdgeView`、`computeFullParam` 结果或整机导出。域结算不综合定值、不执行通用迭代。本设计不做跨模块联合优化，因为它要求把数据结果反馈给域，或引入跨域全局求解，都会破坏显式无环分层。需要数据宽度或吞吐结果决定频率、SerDes lane 数与 symbol rate 联合选择、NoC 资源与频率联合选择等场景，须由用户参数共同决定，或把上一轮导出作为用户参数进入显式第二轮构建。
] <dec-domain-no-feedback>

同一生成器模块可以在 `computeFullParam` 中读取它声明或附着的全部域定值并作模块局部联合校验。例如多输出 PLL 可以核对各输出定值能否由共同 VCO 和分频器实现；该检查只接受或拒绝既有定值，不重新选择任何域值。本设计不做跨模块联合求解；需要统一选择时，按 @dec-domain-no-feedback 使用共同用户参数或显式第二轮构建。

== `Down` 与 `Up` 传播 <sec-propagation>

对每个 outward 节点 $o$，令 $"pred"(o)$ 为本模块中显式声明会影响它的 inward 节点序列；对每个 inward 节点 $i$，令 $"succ"(i)$ 为依赖关系中由它影响的 outward 节点序列。

- 当 $"pred"(o)$ 中各 inward 节点的 `Down` 都已到达时，调用 `dFn_o`；函数按节点声明顺序读取这些值，并可读取节点 $o$ 已附着域的定值，得到唯一的 `Down`，随后沿 $o$ 所在的 bind 传给目标 inward 节点。
- 当 $"succ"(i)$ 中各 outward 节点的 `Up` 都已到达时，调用 `uFn_i`；函数按节点声明顺序读取这些值，并可读取节点 $i$ 已附着域的定值，得到唯一的 `Up`，随后沿 $i$ 所在的 bind 传回源 outward 节点。

$"pred"(o)$ 为空时，`dFn_o` 从构建期用户参数和本节点已附着域的定值产生边界初值；$"succ"(i)$ 为空时，`uFn_i` 对称地产生初始 `Up`。每个函数返回一个参数值或一项传播错误。除用户参数外，`dFn` 只读显式前驱的 `Down` 与本节点域定值，`uFn` 只读显式后继的 `Up` 与本节点域定值；二者不能读取对方传播结果或全局域身份。同一输入产生同一结果，两遍传播仍互不以对方的结果为输入。

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

*域视图*在域结构解析和结算后按生成器模块装配。`DomainView` 保存本模块所声明域的定值、各 subject 的附着域定值，以及按本模块节点声明顺序规范化的实际同域等价类；读取以声明得到的域、subject 与域类句柄为键，不提供全局域身份的字符串查询。全局 `DomainDeclId` 保留在 `ResolvedDesign` 的集成记录中，不进入 `DomainView` 的生成器参数投影。

*生成器参数*在 `DomainView` 与 `EdgeView` 装配后计算。每个生成器模块以 `computeFullParam(EdgeView, DomainView)` 由已求解数据与闭包中的用户参数得到 `FullParam`，并在其中执行能力校验与模块局部联合校验。注册表条目、两份视图和完整参数一并存入 `ResolvedGeneratorModule`（@sec-generator-records、@sec-two-layer-params、@sec-generator-module）。

#决策([已求解参数只读取本模块的已求解数据])[
  `computeFullParam` 接收本模块的 `EdgeView` 与 `DomainView`：每个节点唯一的已求解边、本模块 subject 的域定值与本地同域关系，以及框架接进本模块的探针清单（仅测试平台非空，@sec-dv-testbench）。它不读取其他模块的数据或全局域身份，也不把计算结果反馈给域结算、`dFn` 或 `uFn`。
] <dec-pp-local>

单次协商没有任意整机回读：`dFn`、`uFn` 只能额外读取本节点已附着域的定值，生成器只能读取本模块两份视图，拿不到整张连接图的汇总，例如整机地址映射。这类产物由工具从导出数据生成（@sec-export）；生成器需要它时（例如 boot ROM 镜像），作为用户参数进入下一轮构建。

== 已求解记录 <sec-resolved-records>

每个域声明产生一个 `ResolvedDomain`。记录包含 `DomainDeclId`、域类对象、源定值、显式前驱域、按稳定顺序保存的成员要求及其 subject、验证结果和源码位置。域类对象提供定值与要求的规范化序列化。

每条设计 bind 产生一个 `ResolvedEdge`。记录包含 `BindId`、源与目标 `ModuleNodeId`、对应协议对象、传播得到的 `Down` 和 `Up`、逐边求得的 `Edge` 以及 `interfaceOf(edge)` 返回的 `ProtocolBundle`。全部记录按 bind 声明顺序保存。

域结构的已解析记录保存每个 subject 的有效附着、要求、物理承载路径和同域等价类；集成记录保留全局 `DomainDeclId`，生成器投影只保留定值与本地关系。

== 生成器参数记录 <sec-generator-records>

生成器注册表记录生成器标识、生成器实现和完整参数 codec。已求解生成器模块记录模块标识、注册表条目、`EdgeView`、`DomainView`、已求解参数和完整参数。

`EdgeView` 按本模块的节点声明顺序保存条目。每个条目记录节点方向和该节点唯一的 `ResolvedEdge`；读取以声明得到的节点句柄为键，结果按节点协议类型化，不提供按名字符串的查询。

`DomainView` 先按本模块域声明顺序保存所声明域的定值，再按 subject 声明顺序保存各附着域定值，并以本地编号保存实际同域等价类。两个模块即使附着不同的全局 `DomainDeclId`，只要域定值与本地关系相同，就得到相同的生成器输入；全局实例路径不会仅因域附着进入链接键。

`GeneratorEntry` 保存生成器及其 `FullParam` codec。`ResolvedGeneratorModule.entry` 选定完整参数类型，`fullParam` 采用该条目的 `FullParam`。`DomainView` 在域结算后装配，`EdgeView` 在双向传播和逐边求解后装配，二者共同供本模块的 `computeFullParam` 使用。

== 错误语义 <sec-error-semantics>

协商成功返回 `ResolvedDesign`；发现首个错误时立即以异常终止，不收集后续错误。异常消息直接陈述问题本身，并内联主体的稳定标识、全部相关源码位置及参数快照；没有错误类别或编号。域类验证器、协议对象与端口参数函数仍以值（`Left`）表达约束冲突——那是领域作者的表达通道；协商器收到即抛。用户代码抛出的异常不由协商器包装：它是该代码自身的缺陷，原样穿透并保留其调用栈。

凡是声明处即可判定的契约在构建阶段当场检查、当场抛出，不进入协商：同一作用域内声明名重复；名称形状非法（@sec-port-naming）；在生成器 body 内声明子模块或 bind（生成器模块是叶子，@sec-module-kinds）；模块内部参数依赖的节点不在本模块、方向非法或依赖重复；附着、要求、承载或同域组引用的本模块 subject 不存在、方向非法、同一节点重复入组，或一个节点声明多个参数函数。协商阶段只报告需要全局视角才能判定的问题：

#table(
  columns: (auto, 1fr, 1fr),
  table.header([产生阶段], [触发条件], [报告必含]),

  [校验], [节点引用不存在；outward 节点未恰好作为一次 bind 的源；inward 节点未恰好作为一次 bind 的目标；或声明 bind 的结构模块不是两端节点所在模块的祖先。], [相关 `ModuleNodeId`、`BindId`、声明 bind 的模块、实际 bind 次数和源码位置。],
  [校验], [两个不同的注册表条目使用同一生成器名字。], [冲突的名字；相关模块及源码位置。],
  [域结构校验], [域 handle 或依赖目标不存在、域类不符、域多源；物理载体缺少或错接 `realizes`；有效附着缺失、重复或继承歧义；同域组与协议域契约冲突；协议域契约不完整。], [域、subject、物理节点或协议对象的稳定标识；冲突等价类、相关 bind、声明和源码位置。],
  [域拓扑排序], [域依赖图存在环。], [环上的完整 `DomainDeclId` 路径；环内每条显式域依赖及源码位置。],
  [域结算], [源定值函数或要求函数返回冲突，或域类验证返回 `DomainViolation`。], [`DomainDeclId`、域类、源定值、按稳定顺序排列的要求快照、验证器给出的相关参与者、见证及全部源码位置。],
  [参数拓扑排序], [参数依赖图存在环。], [环上的完整路径；环内每条 bind 与模块内部参数依赖的源码位置。],
  [`Down` 或 `Up` 传播], [`dFn` 或 `uFn` 返回约束冲突，例如地址区域重叠、请求地址不可达或事务身份空间无法分配。], [模块、outward 或 inward 节点、传播方向、有序输入快照、冲突描述和源码位置。],
  [边求解], [设计边的 `negotiate` 返回参数冲突。], [`BindId`、`Down`、`Up` 与源码位置；协议给出的冲突描述。],
  [边求解], [设计边接口含 `Probe`——探针属于验证协议。], [`BindId`、越界的接口路径与源码位置。],
  [生成器参数], [本模块已求解边要求的端口数、接口参数、拓扑条件或资源容量超出生成器用户参数给出的实现上限，或本模块多个域定值不能由同一生成器局部联合实现。], [生成器模块、相关节点、`BindId` 与本地域定值、所需值、实现上限和用户参数。],
)
