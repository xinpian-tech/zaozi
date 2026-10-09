#import "../lib.typ": *

= 概念模型 <ch-model>

@ch-motivation 把参数协商定义为硬件生成流程中的独立阶段（@sec-explicit-phase）。为使该阶段可单独执行和测试，构建结果必须显式表示设计并在进入协商前固化。本章依次定义模块、层次树、连接结构与域结构、节点与 bind、稳定标识、构建阶段、设计与冻结边界、三个阶段和序列化边界。

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
- #term[域结构][domain structure]　模块声明时钟、复位或电源的域及其派生关系；每个节点声明它属于哪些域；协议声明一条 bind 可以跨越什么。域结构表达语义归属，不表达 RTL 连线；定义见 @sec-domain-model。

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

#term[域类][domain kind]定义一类域：根的属性 `Root`、派生关系 `Link`、跨越关系 `Relation`、求两个域之间关系的 `relate`、冻结边界据以比较的属性 `describe`，以及该类的检查插件（@sec-domain-checks）。代码里一个域类是实现 `DomainKind` 的对象；一个设计中同一个名字只能对应同一个对象。时钟、复位、电源由设计库定义，框架不认识它们的名字；安全域等其它域类用同样的方式定义。域类可以提供把结算后的域换算成下游生成器参数的函数，例如 PRCM 域类给出 PRCM 生成器的参数；生成器在完整参数函数里调用它。

#term[域][domain]在模块体中声明，有两种：

- #term[根][root]：`K.root(value)`，不从其它域派生，例如时钟输入的频率、复位源的极性、一路电源能否关断及其上下电时序。
- #term[派生][derivation]：`K.derive(sources*)(link)`，从同类的源域经一个类型化的关系得到，例如门控或分频后的时钟、在某个时钟上释放的复位。

源可以是一个域，也可以是本模块某个节点所在的域（`node.domain(K)`）。派生关系是域类定义的数据，框架不调用任何用户函数来计算域。跨类的引用也写在关系里，例如复位的 `Async(clock, stages)` 点名它在哪个时钟上释放。

#term[归属][membership]说明一个节点在某类域中属于哪个域。节点在声明时给出归属，每个域类至多一项：

- 一个域：节点属于这个域。
- 另一个节点的域：`clk.domain(ClockDomain)`，与那个节点同域。
- 不写：协议承载该类时，inward 节点沿 bind 接收 outward 端的域（@sec-domain-physical-carrier）；域类允许作用域时，取外围 `domain.scope { ... }` 给出的域。

`physical` 的域类（时钟、复位）要求硬件节点的归属来自本模块：本模块的节点，或本模块声明的域。数据端口的时钟因此只能来自本模块的物理时钟输入，或本模块自己派生的时钟。

#决策([域身份与归属])[
  域的稳定身份由声明模块的 `ModuleId` 与声明名组成；用户代码传递类型化的域，不构造字符串。每个节点对每个域类至多一项归属，声明节点时给出。归属只有“属于”一种关系，不同写法只是指名方式不同。
] <dec-domain-identity-attachment>

#决策([域结构不产生硬件连接])[
  域声明与归属都不产生端口或连线。bind 仍是唯一的设计连接原语；物理时钟与复位节点照常满足一次绑定不变量。电源域不虚构 RTL 端口。
] <dec-domain-no-wire>

#决策([作用域提供默认域])[
  `domain.scope { ... }` 把其中创建的模块放进该域，对应 UPF 按层次划分电源域的方式。只有声明 `scoped` 的域类允许这样做：电源允许，时钟与复位不允许，它们必须来自物理输入。
] <dec-domain-context>

#决策([域是数据])[
  根的属性与派生关系都是数据，框架不调用用户函数产生域。生成器需要的属性，例如时钟频率，由域类从根沿派生关系算出。模块对域的要求写在完整参数函数里，不满足即协商失败（@sec-generator-parameters）。
] <dec-domain-data>

== 模块节点、bind 与协议 <sec-node-conn-proto>

#term[模块节点][module node]由生成器模块声明，分为 #term[inward 节点][inward node]和 #term[outward 节点][outward node]。`inward(p)(domains*)` 与 `outward(p)(domains*)` 记录名称、方向、协议、归属和源码位置；每个节点同时对应生成器的一个顶层端口。#term[协议][protocol]规定一条连接上传播的参数类型和求解规则（@ch-protocol）；每个节点属于一个协议。

*bind* 是连接声明：把一个模块的 outward 节点接到另一个模块（或同一模块）的 inward 节点，写作 `目标 inward 节点 <-- 源 outward 节点`。验证观测不经 bind（@ch-verification）。

bind 写在结构模块的构建体里，声明它的结构模块必须是两端节点所在模块的祖先，可以不是最近的祖先；两端节点可以在它之下的任意深度。也就是说，模块只连接自己子树内部的节点；要连到子树外面，由外层模块来写。bind 记录声明它的结构模块，结构校验核对这一祖先关系（@sec-structural-check）。

方向按 `Down` 的传播定义：outward 节点是 bind 的源，inward 节点是 bind 的目标。每条 bind 在协商期得到三项参数：源节点算出的下行参数 `Down`、目标节点算出的上行参数 `Up`，以及由协议把二者合成的#term[边参数][edge parameter] `Edge`（@sec-three-param-kinds）。一条 bind 连同它求出的参数称为一条#term[边][edge]。每个模块节点恰好参与一次设计 bind：outward 节点恰好作为一次 bind 的源，inward 节点恰好作为一次 bind 的目标。

节点声明返回一个#term[节点草稿][node draft]。草稿必须恰好封口一次，封口后得到可以 bind 的#term[端口句柄][port handle]：

- `node.derive(sources) { values => ... }`：`sources` 是本模块反方向的节点，或它们的序列。outward 节点读取 inward 节点的 `Down`，inward 节点读取 outward 节点的 `Up`；方向不对是编译错误。函数返回本节点的参数，或一项 `Violation`。
- `node.fixed(value)`：本节点的参数是常量。

outward 节点的函数称为 `dFn`，inward 节点的函数称为 `uFn`，二者统称#term[端口参数函数][port parameter functions]。`sources` 中的每个反方向节点就是一条#term[模块内部参数依赖][module-internal parameter dependency]：inward 节点 `i` 出现在 outward 节点 `o` 的 `sources` 中，或 `o` 出现在 `i` 的 `sources` 中，都记为从 `i` 到 `o` 的依赖。依赖由读取集合推出，不单独声明，因此函数能读的就是依赖的全部。不读取任何反方向节点的节点称为#term[边界节点][boundary node]，它的值只来自用户参数。同一个函数可以读不同协议的节点。Xbar、NoC 以多个具名节点表示多个端口，以内部参数依赖表示端口之间的参数影响关系；每个节点仍只参与一条 bind。

#不变量[全部模块节点均由生成器模块声明。]

#不变量[一条设计 bind 的源 outward 节点与目标 inward 节点必须使用同一协议。跨协议参数变换由具有不同 inward、outward 协议的显式生成器模块承担（@sec-protocol-object）。]

#不变量[bind 与模块内部参数依赖组成的有向图必须无环；这张图称为#term[参数依赖 DAG][parameter dependency DAG]（@sec-propagation）。发现环时，报告环上的模块节点、bind、内部依赖及其源码位置。]

#不变量[域与其派生源组成的有向图必须无环。派生源只来自 `derive` 列出的源，不从归属、bind 或参数依赖推断。]

== 稳定标识 <sec-identity>

实体标识由已命名结构派生：`ModuleId` 是从设计根开始的实例名路径；`ModuleNodeId` 由 `module` 与节点名组成；`BindId` 由源、目标 `ModuleNodeId` 组成；`DomainId` 由 `module` 与域的声明名组成；一项归属由 `ModuleNodeId` 与域类标识。同一模块内节点名与探针名共用一个命名空间；域声明名在本模块的域声明中唯一。每个节点唯一关联一条 bind，因此 `ModuleNodeId` 可以确定该节点所在的 `BindId` 和已求解边。探针的标识也是 `ModuleNodeId`（@sec-dv-declarations）。

每个模块、节点、bind、域、归属和探针都记录声明处的源码位置 `SourceLoc`，即 sourcecode 库捕获的 `File` 与 `Line`。源码位置只用于诊断，实体身份由稳定标识确定。

== 构建阶段 <sec-build>

设计由 `Design(moduleName) { ... }` 定义，它的体就是根结构模块的体。构建期声明节点、域和连接需要框架注入的#term[构建上下文][`BuildContext`]。条件拓扑与循环生成的子系统由宿主语言控制流表达。bind 算子 `<--` 只能在结构模块体中记录连接。声明的名称默认取自绑定它的 val（sourcecode 的 `Name`，与 zaozi 的实例命名一致）；循环等 val 名不可用的场合以局部 `given sourcecode.Name` 覆盖。名称形状限定为 `[A-Za-z_][A-Za-z0-9_]*`，在声明处检查。

设计的体返回它向外交出的#term[设计引用][design reference]，例化它的设计会把这些引用移到实例上。设计引用的类型必须有 `Dangles` 证据：端口句柄、探针、设计边界，以及它们的 `Option`、`Vector` 与字段全为设计引用的积类型。域不是设计引用，它只经边界离开设计。构建上下文只在它的模块体执行期间有效；模块体返回后再用它声明或连接即报错。

可复用的模块定义由节点类和定义函数组成：节点类以字段持有端口句柄，定义函数调用 `generator[FP]` 或 `wrapper` 并转发名称上下文，实例名来自调用点的 val。生成器定义以 `given GeneratorDefinition[FP]` 提供，模块体按 `FP` 取得。

节点与域声明的生命周期在用户参数之后开始：模块构造时按用户参数声明它们。`DesignSpec` 固化后节点、域和归属不再增删；协商时每个节点恰好得到一条边，每个域恰好结算一次；例化时每个节点对应生成器的一个端口。节点与域的有无、数量和名字只依赖用户参数与所例化设计的冻结结果（@sec-design-boundary），不依赖本设计的协商结果，也不依赖是否有 bind 指向节点。

协议的身份就是协议对象本身。一个设计里，同一个生成器名字只能对应一个生成器定义。

`DesignSpec` 包含：

- 固化后的模块树：结构模块的模块名与子实例序列；生成器模块的生成器定义、节点规格（方向、协议、归属、端口参数函数及其读取的节点）、探针与完整参数函数；
- 按声明顺序的 bind 序列；
- 域的声明序列；
- 设计边界，以及代表外侧与所例化设计的边界模块（@sec-design-boundary）。

== 设计与冻结边界 <sec-design-boundary>

一个 `Design` 是一个独立协商的单位。根结构模块可以把内部的端口句柄声明为#term[设计边界][design boundary]：`port.boundary(externalParams)(externalDomains*)`。`externalParams` 是对外侧的假设：inward 端口的边界给出外侧送来的 `Down`，outward 端口的边界给出外侧返回的 `Up`。`externalDomains` 给出外侧端点的归属。框架在设计根下放一个#term[边界模块][boundary module]，以常量节点代表外侧，与端口 bind 起来，于是设计可以单独协商。

设计第一次被使用时协商一次，结果随后#term[冻结][frozen]：每个边界的 `Down`、`Up`、`Edge` 与接口，边界所属各域的属性（域类的 `describe`），以及哪些边界同域，都成为设计对外的契约。

外层设计以 `design.instantiate` 例化一个冻结的设计。被例化的设计在外层看来是一个边界模块：它的每个边界成为一个常量节点，参数取自冻结结果；它的边界所属的域成为外层的#term[导入域][imported domain]；某个 inward 承载边界接收的域，就是外层 bind 给它的域。外层照常 bind 这些节点并协商。协商结束后框架核对：每条边界边的 `Down`、`Up`、`Edge` 与接口与冻结时相同，边界所属各域的属性相同，哪些边界同域不变。任何改变都是错误。

例化结果 `DesignInstance` 向外层构建期公开冻结结果：`edgeOf(boundary)` 读取边界边，`domainOf(boundary, K)` 读取边界所属的域，`probes` 是公开的探针目录（@ch-verification）。外层可以用这些值决定自己的用户参数，例如测试平台按 SoC 参考时钟的频率配置振荡器。外层还可以用 `boundary.boundary` 把内层的边界转发为自己的边界，用 `probe.boundary` 把内层的探针转发为自己的公开探针。

#决策([设计是冻结的复用单位])[
  一个设计只协商一次，它的边界契约在外层不可改变；外层协商只确认契约成立。冻结的设计在例化期单独生成一份电路，被多个外层共享（@sec-elaboration-flow）。外层读取内层冻结结果，等于把上一轮协商的产物作为本轮的用户参数，不形成回读环。
] <dec-design-frozen>

== 三阶段流水线 <sec-triptych>

设计生成分为三个阶段，前一阶段的输出作为后一阶段的输入。执行协商阶段的框架部分称为#term[协商器][negotiator]。

#图([三个阶段。矩形表示阶段，胶囊表示阶段间的不可变产物。])[
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
  [读入设计规格，结算各域与归属，核对每条 bind 跨越的域，并分阶段运行域检查；随后对参数依赖 DAG 做拓扑排序，正向传播 `Down`、反向传播 `Up`，逐边调用协议求解；最后计算各生成器模块的完整参数（@sec-two-layer-params），规划跨模块的端口与连线（@ch-negotiation）。产出协商结果 `ResolvedDesign`。检查整批报告失败，计算在首个失败处终止（@sec-error-semantics）。],
  [例化],
  [读入协商结果：以各生成器模块的完整参数调用 zaozi 生成器，生成结构模块的电路，执行连线计划，链接为一个电路（@ch-hardware）。产出 mlirbc 与一组 Verilog 文件。],
)

`ResolvedDesign` 保留对应的 `DesignSpec`，并加上协商的全部结果：结算后的域与每个节点的归属、每条边求出的参数和接口、每个生成器模块的完整参数、公开探针目录与观测绑定、跨层端口与连线的计划、FIRRTL 层声明，以及所例化的冻结设计；字段见 @sec-resolved-records 与 @sec-generator-records。

协商阶段检查 `DesignSpec` 的结构、协议与参数约束（@req-iteration）；例化阶段检查生成器实际端口与协议接口（@dec-binding-check）。拓扑、声明或 IP 的变化触发新一轮三阶段。

== 序列化边界 <sec-serialization-boundary>

协商结果与硬件生成器之间的数据接口是每个生成器模块的#term[完整参数][full parameter]，即用户参数与已求解参数的合成（@sec-two-layer-params）。完整参数可序列化，使每个 IP 能以固定参数文件独立例化和测试，并支持归档与复现（@req-ip）。

规格中的端口参数函数、完整参数函数与域检查属于当前协商进程。跨进程数据包括完整参数，以及按需导出的拓扑、域结算结果、连接求解结果和端口计划（@ch-tooling）。

#决策([完整参数只含属性与稳定名字])[
  进入完整参数的是域的属性（如时钟频率、电源的上下电时序）和稳定名字（如域的声明名），不是实例路径。同一个 IP 在不同位置例化时，完整参数相同则模块相同（@sec-dedup）。
] <dec-full-param-content>

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
