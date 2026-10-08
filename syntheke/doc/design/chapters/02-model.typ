#import "../lib.typ": *

= 概念模型 <ch-model>

@ch-motivation 把参数协商定义为硬件生成流程中的独立阶段（@sec-explicit-phase）。为使该阶段可单独执行和测试，构建结果必须显式表示设计并在进入协商前固化。本章依次定义模块、层次树、连接结构与域结构、节点与 bind、稳定标识、构建阶段、设计与冻结边界、三阶段流水线和序列化边界。

== 模块的两种形态 <sec-module-kinds>

Syntheke 把设计中的层次化电路单元称为#term[模块][module]。

Syntheke 把以参数为输入并返回电路模块的 zaozi 工厂称为#term[生成器][generator]。生成器在框架侧由一个#term[生成器定义][`GeneratorDefinition`]代表，它给出生成器名字与完整参数的序列化（@sec-generator-contract）。

每个模块在构造时收到一份#term[用户参数][user parameter]：它在模块生命周期的最开始就已确定，模块随后声明的一切都可以依赖它。模块分为两种：

- #term[结构模块][wrapper module]　由 `wrapper(moduleName) { ... }` 声明，不带生成器，只用来组织层次：它按用户参数例化子模块，声明子模块之间的 bind，也可以声明域（@sec-domain-model）。它的电路只有子模块实例、端口和连线；穿过它的连接需要哪些端口和连线，由框架算出并生成（@ch-hierarchy）。
- #term[生成器模块][generator module]　由 `generator[FP] { ... }` 声明，没有子模块，绑定恰好一个生成器定义，硬件逻辑全部由该生成器实现。它声明节点、节点所属的域、端口参数函数、探针与完整参数函数。它的用户参数是构建期给定、不依赖连接关系的参数，例如容量、关联度、基地址和功能开关，并进入完整参数（@sec-two-layer-params）。它的端口与生成器端口的对应契约见 @sec-generator-module。

两种模块体拿到不同类型的构建上下文：结构模块体是 `WrapperScope`，生成器模块体是 `GeneratorScope[FP]`。在生成器模块体内例化子模块或写 bind 是类型错误。

== 层次树、连接结构与域结构 <sec-two-graphs>

一个 Syntheke 设计由三套结构共同描述：

- #term[层次树][hierarchy tree]　顶点是模块。它表达*所有权*：模块例化关系、命名空间嵌套和物理模块边界。这棵树只包含设计显式例化的模块，最终一一对应生成电路的模块层次。Xbar、NoC、直连和时钟树等有 RTL 实现的互连也是树上的生成器模块。
- #term[连接结构][connection structure]　生成器模块声明具名的 inward 节点和 outward 节点，每个节点就是该模块的一个协议端口；bind 把一个 outward 节点接到一个 inward 节点。节点的端口参数函数读取哪些本模块节点，就构成模块内部参数依赖。节点、bind 与内部依赖的定义见 @sec-node-conn-proto。
- #term[域结构][domain structure]　模块声明时钟、复位或电源的域；每个节点声明它属于哪些域；模块、端口参数函数与协议对域提出要求和检查。域结构表达语义归属与约束，不表达 RTL 连线；定义见 @sec-domain-model。

连接可以跨越任意层级。一个位于层次树深处的模块节点，可以 bind 到另一棵子树中的模块节点；连接结构给出“两端节点”的关系，层次树给出两端生成器模块之间的模块路径。协商阶段沿该路径统一规划跨层端口与连线（@ch-hierarchy）。

域结构引用层次树中的模块与连接结构中的节点。物理时钟与复位仍由设计节点和 bind 连接；承载它们的协议声明自己传送哪些域类，inward 端的域沿 bind 取自 outward 端（@sec-domain-physical-carrier）。电源域通常没有 RTL 连线，由构建作用域提供默认值（@dec-domain-context）。

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

== 域结构 <sec-domain-model>

#term[域类][domain kind]规定一类域的定值类型 `Value`、要求类型 `Requirement`、冲突见证类型 `Witness`、验证函数 `validate`、附着策略以及三种类型的序列化。代码里一个域类是实现 `Domain` 的对象，带一个由命名空间与名字组成的 `DomainKey`；一个设计中同一 `DomainKey` 只能对应同一个对象。时钟、复位和电源是不同的域类，框架不认识它们的名字。

#term[域声明][domain declaration]在模块体中产生一个域，返回类型化的#term[域 handle][`DomainHandle`]。声明有两种写法：`D.declare(value, requirement)` 直接给出定值；`sources.derive(D) { view => ... }` 从显式列出的#term[前驱][predecessor]计算定值。前驱可以是其它域 handle，也可以是本模块某个节点所属的域，例如 PLL 从参考时钟输入的域导出输出域。两种写法都可以顺带给出声明者自己的一项要求。每个域恰有一个声明，定值只由它产生；需要从多个输入中选择时，由生成器模块读取多个前驱、声明一个新域。

#term[节点域][node domain]是一个节点对某个域类的归属。节点在声明时列出它的全部节点域，每个域类至多一项。每项是一个#term[选择器][selector]，决定该节点属于哪个域；选择器的写法决定#term[附着方法][attachment method]：

#table(
  columns: (auto, auto, 1fr),
  table.header([选择器], [附着方法], [含义]),
  [域 handle], [`Direct`], [直接属于该域。],
  [本模块另一节点的节点域], [`Follow`], [与那个节点属于同一域。],
  [域类对象], [`Contextual`], [属于构建作用域中最近一次 `provide` 给出的该类域（@dec-domain-context）。],
  [承载 outward 节点上的 handle 或节点域], [`CarrierOut`], [该节点经物理连线传出这个域（@sec-domain-physical-carrier）。],
  [承载 inward 节点上的域类对象], [`CarrierIn`], [沿本节点唯一的 bind 取 outward 端的域。],
)

节点域本身也是一个可读的域引用：端口参数函数、完整参数函数和约束都可以按它读取定值。

协商时框架把每个节点域#term[解析][resolve]为唯一的域声明，并记录解析经过的路径，称为#term[附着来源][attachment provenance]。`Follow` 与 `CarrierIn` 可以串联，成环时报错。每个域类以#term[附着策略][attachment policy]规定允许哪些附着方法，并可以逐条检查附着来源；演示设计中时钟与复位不允许 `Contextual`，电源允许。

#term[约束][constraint]是对域定值的两类陈述：

- #term[要求][requirement]：`r.requirement(value)` 对引用 `r` 所解析到的域贡献一份要求。同一个域的全部要求连同定值交给域类的 `validate`。
- #term[检查][check]：`Seq(r1, r2, ...).check { view => ... }` 读取若干域的定值并接受或拒绝。检查表达跨域的局部关系，例如电源边界核对 AON 电源与 CPU 电源是两个独立的域。

约束有四个来源：模块体的返回值、端口参数函数的返回值、协议 `negotiate` 的返回值，以及域声明自带的要求。约束只接受或拒绝，不改变任何域定值（@sec-domain-settlement）。

#决策([域身份与节点域基数])[
  域的稳定身份由声明模块的 `ModuleId` 与声明名组成；用户代码传递类型化 handle，不构造域名字符串。每个节点对每个域类至多一个节点域，在声明节点时给出，之后不可增删。一个模块有多个时钟时，各节点分别跟随对应的时钟输入。
] <dec-domain-identity-attachment>

#决策([域结构不产生硬件连接])[
  域声明、节点域与约束都不产生端口或连线。bind 仍是唯一的设计连接原语；物理时钟与复位节点照常满足一次绑定不变量。电源域不虚构 RTL 端口。
] <dec-domain-no-wire>

#决策([作用域提供默认域])[
  `handle.provide { ... }` 在其构建作用域内为该域类设定默认值；以域类对象作选择器的节点取最近一次 `provide`。默认值随作用域进入子模块，内层覆盖外层。是否允许这种附着由域类的附着策略决定。时钟与复位通常不允许，因为它们必须由本模块的物理输入决定。
] <dec-domain-context>

== 模块节点、bind 与协议 <sec-node-conn-proto>

#term[模块节点][module node]由生成器模块声明，分为 #term[inward 节点][inward node]和 #term[outward 节点][outward node]。`inward(p)(domains*)` 与 `outward(p)(domains*)` 记录名称、方向、协议、节点域和源码位置；每个节点同时对应生成器的一个顶层端口。#term[协议][protocol]规定一条连接上传播的参数类型和求解规则（@ch-protocol）；每个节点属于一个协议。

*bind* 是连接声明：把一个模块的 outward 节点接到另一个模块（或同一模块）的 inward 节点，写作 `目标 inward 节点 <-- 源 outward 节点`。验证观测不经 bind（@ch-verification）。

bind 写在结构模块的构建体里，声明它的结构模块必须是两端节点所在模块的祖先，可以不是最近的祖先；两端节点可以在它之下的任意深度。也就是说，模块只连接自己子树内部的节点；要连到子树外面，由外层模块来写。bind 记录声明它的结构模块，结构校验核对这一祖先关系（@sec-structural-check）。

方向按 `Down` 的传播定义：outward 节点是 bind 的源，inward 节点是 bind 的目标。每条 bind 在协商期得到三项参数：源节点算出的下行参数 `Down`、目标节点算出的上行参数 `Up`，以及由协议把二者合成的#term[边参数][edge parameter] `Edge`（@sec-three-param-kinds）。一条 bind 连同它求出的参数称为一条#term[边][edge]。每个模块节点恰好参与一次设计 bind：outward 节点恰好作为一次 bind 的源，inward 节点恰好作为一次 bind 的目标。

节点声明返回一个#term[节点草稿][node draft]。草稿必须恰好封口一次，封口后得到可以 bind 的#term[端口句柄][port handle]：

- `node.derive(sources) { values => ... }`：`sources` 是本模块反方向的节点（或它们的序列、元组）以及本节点的节点域。outward 节点读取 inward 节点的 `Down`，inward 节点读取 outward 节点的 `Up`；方向不对是编译错误。函数返回本节点的参数和一组约束，或一项 `Violation`。
- `node.fixed(value)`：本节点的参数是常量。

outward 节点的函数称为 `dFn`，inward 节点的函数称为 `uFn`，二者统称#term[端口参数函数][port parameter functions]。`sources` 中的每个反方向节点就是一条#term[模块内部参数依赖][module-internal parameter dependency]：inward 节点 `i` 出现在 outward 节点 `o` 的 `sources` 中，或 `o` 出现在 `i` 的 `sources` 中，都记为从 `i` 到 `o` 的依赖。依赖由读取集合推出，不单独声明，因此函数能读的就是依赖的全部。不读取任何反方向节点的节点称为#term[边界节点][boundary node]，它的值只来自用户参数与本节点的域定值。同一个函数可以读不同协议的节点。Xbar、NoC 以多个具名节点表示多个端口，以内部参数依赖表示端口之间的参数影响关系；每个节点仍只参与一条 bind。

#不变量[全部模块节点均由生成器模块声明。]

#不变量[一条设计 bind 的源 outward 节点与目标 inward 节点必须使用同一协议。跨协议参数变换由具有不同 inward、outward 协议的显式生成器模块承担（@sec-protocol-object）。]

#不变量[bind 与模块内部参数依赖组成的有向图必须无环；这张图称为#term[参数依赖 DAG][parameter dependency DAG]（@sec-propagation）。发现环时，报告环上的模块节点、bind、内部依赖及其源码位置。]

#不变量[域声明与其前驱组成的有向图必须无环。前驱只来自 `derive` 显式列出的 `sources`，不从节点域、bind 或参数依赖推断。]

== 稳定标识 <sec-identity>

实体标识由已命名结构派生：`ModuleId` 是从设计根开始的实例名路径；`ModuleNodeId` 由 `module` 与节点名组成；`BindId` 由声明顺序和源、目标 `ModuleNodeId` 组成；`DomainDeclId` 由 `module` 与域声明名组成；`NodeDomainKey` 由 `ModuleNodeId` 与 `DomainKey` 组成。同一模块内节点名与探针名共用一个命名空间；域声明名在本模块的域声明中唯一。每个节点唯一关联一条 bind，因此 `ModuleNodeId` 可以确定该节点所在的 `BindId` 和已求解边。探针的标识也是 `ModuleNodeId`（@sec-dv-declarations）。

每个模块、节点、bind、域声明、节点域、约束和探针都记录声明处的源码位置，直接采用 sourcecode 库的 `File` 与 `Line` 捕获，不自设位置类型。源码位置只用于诊断，实体身份由稳定标识确定。

== 构建阶段 <sec-build>

设计由 `Design(moduleName) { ... }` 定义，它的体就是根结构模块的体。构建期声明节点、域和连接需要框架注入的#term[构建上下文][`BuildContext`]。条件拓扑与循环生成的子系统由宿主语言控制流表达。bind 算子 `<--` 只能在结构模块体中记录连接。声明的名称默认取自绑定它的 val（sourcecode 的 `Name`，与 zaozi 的实例命名一致）；循环等 val 名不可用的场合以局部 `given sourcecode.Name` 覆盖。名称形状限定为 `[A-Za-z_][A-Za-z0-9_]*`，在声明处检查。

每个模块体返回一对值：向外交出的#term[设计引用][design reference]与一组约束。设计引用的类型必须有 `Dangles` 证据：端口句柄、域 handle、探针、设计边界，以及它们的 `Option`、`Vector` 与字段全为设计引用的积类型。参数读取值和构建上下文无法离开模块体。框架检查每个交进来的句柄属于本次构建中仍然有效的声明；来自另一次构建或已丢弃模块的句柄在使用处报错。

可复用的模块定义由节点类和定义函数组成：节点类以字段持有端口句柄，定义函数调用 `generator[FP]` 或 `wrapper` 并转发名称上下文，实例名来自调用点的 val。生成器定义以 `given GeneratorDefinition[FP]` 提供，模块体按 `FP` 取得。

节点与域声明的生命周期在用户参数之后开始：模块构造时按用户参数声明它们。`DesignSpec` 固化后节点、域和节点域不再增删；协商时每个节点恰好得到一条边，每个域恰好得到一个已验证定值；例化时每个节点对应生成器的一个端口。节点与域的有无、数量和名字只依赖用户参数与所例化设计的冻结结果（@sec-design-boundary），不依赖本设计的协商结果，也不依赖是否有 bind 指向节点。

协议的身份就是协议对象本身：bind 两端使用同一个对象由构造保证，无需注册表。设计的生成器注册表由模块树推导——先序首次出现的生成器定义序列，不单独存储；同一名字只能对应一个定义。

`DesignSpec` 包含：

- 固化后的模块树：结构模块的模块名与子实例序列；生成器模块的生成器定义、节点规格（方向、协议、节点域、端口参数函数）、内部参数依赖、探针与完整参数函数；
- 按声明顺序的 bind 序列；
- 域声明序列与约束序列；
- 设计边界，以及代表外侧与所例化设计的边界模块（@sec-design-boundary）。

== 设计与冻结边界 <sec-design-boundary>

一个 `Design` 是一个独立协商的单位。根结构模块可以把内部的端口句柄声明为#term[设计边界][design boundary]：`port.boundary(externalParams)(externalDomains*)`。`externalParams` 是对外侧的假设：inward 端口的边界给出外侧送来的 `Down`，outward 端口的边界给出外侧返回的 `Up`。`externalDomains` 给出外侧端点的节点域。框架在设计根下放一个#term[边界模块][boundary module]，以常量节点代表外侧，与端口 bind 起来，于是设计可以单独协商。

设计第一次被使用时协商一次，结果随后#term[冻结][frozen]：每个边界的 `Down`、`Up`、`Edge` 与接口，边界上各节点域的定值，域之间的身份关系和成员要求，都成为设计对外的契约。

外层设计以 `design.instantiate` 例化一个冻结的设计。被例化的设计在外层看来是一个边界模块：它的每个边界成为一个常量节点，参数取自冻结结果；它的边界域以冻结定值重新声明在外层，冻结时收集到的要求成为外层的约束。外层照常 bind 这些节点并协商。协商结束后框架核对：每条边界边的 `Down`、`Up`、`Edge` 与接口与冻结时相同，边界节点域的定值相同，域之间的身份关系不变。任何改变都是错误。

例化结果 `DesignInstance` 向外层构建期公开冻结结果：`edgeOf(boundary)` 读取边界边，`value(boundary, D)` 读取边界域定值，`probes` 是公开的探针目录（@ch-verification）。外层可以用这些值决定自己的用户参数，例如测试平台按 SoC 参考时钟的定值配置振荡器。外层还可以用 `boundary.boundary` 把内层的边界转发为自己的边界，用 `probe.boundary` 把内层的探针转发为自己的公开探针。

#决策([设计是冻结的复用单位])[
  一个设计只协商一次，它的边界契约在外层不可改变；外层协商只确认契约成立。冻结的设计在例化期单独生成一份电路，被多个外层共享（@sec-elaboration-flow）。外层读取内层冻结结果，等于把上一轮协商的产物作为本轮的用户参数，不形成回读环（@apx-readback）。
] <dec-design-frozen>

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
    node((5, 0), [电路 \ mlirbc 与 Verilog], name: <fir>, shape: fletcher.shapes.pill, fill: c-fill),
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
  [使用宿主语言代码例化模块树、声明节点、域和连接（@sec-build）。产物是设计规格 `DesignSpec`。],
  [协商],
  [读入设计规格，解析节点域并按域依赖 DAG 结算各域定值；随后对参数依赖 DAG 做拓扑排序，正向传播 `Down`、反向传播 `Up`，逐边调用协议求解；然后收齐全部约束，按域验证要求并执行检查；最后计算各生成器模块的完整参数（@sec-two-layer-params），规划跨模块的端口与连线（@ch-negotiation）。产出协商结果 `ResolvedDesign`；发现首个错误立即终止并报告（@sec-error-semantics）。],
  [例化],
  [读入协商结果：以各生成器模块的完整参数调用 zaozi 生成器，生成结构模块的电路，执行连线计划，链接为一个电路（@ch-hardware）。产出 mlirbc 与一组 Verilog 文件。],
)

`ResolvedDesign` 保留对应的 `DesignSpec`，并加上协商的全部结果：每个域的定值与要求、每个节点域的解析结果、已执行的检查、每条边求出的参数和接口、每个生成器模块的完整参数、公开探针目录与观测绑定、跨层端口与连线的计划、FIRRTL 层声明，以及所例化的冻结设计；字段见 @sec-resolved-records 与 @sec-generator-records。

协商阶段检查 `DesignSpec` 的结构、协议与参数约束（@req-iteration）；例化阶段检查生成器实际端口与协议接口（@dec-binding-check）。拓扑、声明或 IP 的变化触发新一轮三阶段。

== 序列化边界 <sec-serialization-boundary>

协商结果与硬件生成器之间的数据接口是每个生成器模块的#term[完整参数][full parameter]，即用户参数与已求解参数的合成（@sec-two-layer-params）。完整参数可序列化，使每个 IP 能以固定参数文件独立例化和测试，并支持归档与复现（@req-ip）。

规格中的端口参数函数、域定值函数、约束与完整参数函数属于当前协商进程。跨进程数据包括完整参数，以及按需导出的拓扑、域结算结果、连接求解结果和端口计划（@ch-tooling）。全局 `DomainDeclId` 属于集成元数据，不自动进入生成器完整参数。

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
