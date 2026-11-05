#import "../lib.typ": *

= 概念模型 <ch-model>

@ch-motivation 把参数协商定义为硬件生成流程中的独立阶段（@sec-explicit-phase）。为使该阶段可单独执行和测试，构建结果必须显式表示设计并在进入协商前固化。本章依次定义模块、层次树、连接结构与域结构、节点与 bind、稳定标识、构建阶段、设计与冻结边界、三个阶段和序列化边界。

== 模块的两种形态 <sec-module-kinds>

Syntheke 把设计中的层次化电路单元称为#term[模块][module]。

Syntheke 把以参数为输入并返回电路模块的工厂称为#term[生成器][generator]，例如 zaozi 生成器。生成器在框架侧由一个#term[生成器定义][`GeneratorDefinition`]代表，它给出生成器名字与完整参数的序列化（@sec-generator-contract）。

每个模块在构造时收到一份#term[用户参数][user parameter]：它在模块生命周期的最开始就已确定，模块随后声明的一切都可以依赖它。模块分为两种：

- #term[结构模块][wrapper module]　由 `wrapper(moduleName) { ... }` 声明，不带生成器，只用来组织层次：它按用户参数例化子模块，声明子模块之间的 bind，也可以声明域（@sec-domain-model）。它的电路只有子模块实例、端口和连线；穿过它的连接需要哪些端口和连线，由框架算出并生成（@ch-hierarchy）。
- #term[生成器模块][generator module]　由 `generator[FP] { ... }` 声明，没有子模块，绑定恰好一个生成器定义，硬件逻辑全部由该生成器实现。它声明节点、节点所属的域、端口参数函数、探针与完整参数函数。它的用户参数是构建期给定、不依赖连接关系的参数，例如容量、关联度、基地址和功能开关，并进入完整参数（@sec-two-layer-params）。它的端口与生成器端口的对应契约见 @sec-generator-module。

两种模块体拿到不同类型的构建上下文：结构模块体是 `WrapperScope`，生成器模块体是 `GeneratorScope[FP]`。在生成器模块体内例化子模块或写 bind 是类型错误。

== 层次树、连接结构与域结构 <sec-two-graphs>

一个 Syntheke 设计由三套结构共同描述：

- #term[层次树][hierarchy tree]　顶点是模块。它表达*所有权*：模块例化关系、命名空间嵌套和物理模块边界。这棵树只包含设计显式例化的模块，最终一一对应生成电路的模块层次。Xbar、NoC、直连和时钟树等有 RTL 实现的互连也是树上的生成器模块。
- #term[连接结构][connection structure]　生成器模块声明具名的 inward 节点和 outward 节点，每个节点就是该模块的一个协议端口；bind 把一个 outward 节点接到一个 inward 节点。节点的端口参数函数读取哪些本模块节点，就构成模块内部参数依赖。节点、bind 与内部依赖的定义见 @sec-node-conn-proto。
- #term[域结构][domain structure]　模块声明时钟、复位或电源的域及其定义；每个节点声明它属于哪些域；协议声明一条 bind 可以跨越什么。域结构表达语义归属，不表达 RTL 连线；定义见 @sec-domain-model。

连接可以跨越任意层级。一个位于层次树深处的模块节点，可以 bind 到另一棵子树中的模块节点；连接结构给出“两端节点”的关系，层次树给出两端生成器模块之间的模块路径。协商阶段沿该路径统一规划跨层端口与连线（@ch-hierarchy）。

域结构引用层次树中的模块与连接结构中的节点。物理时钟与复位仍由设计节点和 bind 连接；承载它们的协议声明自己传送哪些域类，一条这样的 bind 两端必须同域（@sec-domain-physical-carrier）。电源域通常没有 RTL 连线，节点同样写出它所在的电源域（@dec-domain-context）。

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

#term[域类][domain kind]定义一类域：域的定义 `Definition`、改写定义所指的域的 `map`和例化时比较送来的域所用的属性 `describe`；域类自身的规则在构造定义时检查。代码里一个域类是实现 `DomainKind` 的对象，按对象同一性区分。时钟、复位、电源由设计库定义，框架不认识它们的名字；安全域等其它域类用同样的方式定义。域类可以提供把域换算成下游生成器参数的函数，例如 PRCM 域类给出 PRCM 生成器的参数；生成器在完整参数函数里调用它。

#term[域][domain]在设计根上以 `K.declare(definition)` 声明，模块只引用这些域。定义是域类给出的数据，例如时钟输入的频率，分频后的时钟来自哪个时钟、经过什么分频器，复位源的极性，一路电源能否关断及其上下电时序。域类可以在它之上给出自己的写法，例如分频后的时钟写成 `clock.divided(by = 4)`。框架不调用任何用户函数来计算域。

定义可以#term[引用][reference]别的域，同类或跨类：时钟来自哪些时钟，复位在哪个时钟上释放，门控时钟由哪个 PRCM 域或电源域打开，电源依赖哪个电源，PRCM 管理域属于哪个控制器、开关哪个电源。域类以 `map` 给出这些引用：它把定义所指的每个域换成给定函数的结果。框架用它求出引用、导出引用边，例化时用它把冻结的定义改写为指向副本。域只能引用在它之前构造的域，引用因此不成环；读引用就是读定义。定义不引用同类域的域是该类的#term[根][root]，例如时钟输入；其余从它引用的同类域#term[派生][derivation]。

#term[归属][membership]说明一个节点在某类域中属于哪个域。节点在声明时写出它所在的域，每个域类至多一项，不写的域类节点就不在其中；节点必须在它的协议承载的每个域类中有归属。归属在声明时就是确定的域，不随 bind 变化；`node.domain(K)` 读出它。生成器所在的时钟、复位与电源因此作为参数传给生成器，例如 `uart(clock = periphClock, reset = periphReset, power = alwaysOn)`。


#决策([域身份与归属])[
  域的稳定身份由所在设计与声明名组成，实例中的副本另加实例路径；用户代码传递类型化的域，不构造字符串。每个节点对每个域类至多一项归属，声明节点时给出。归属只有“属于”一种关系，不同写法只是指名方式不同。
] <dec-domain-identity-attachment>

#决策([域结构不产生硬件连接])[
  域声明与归属都不产生端口或连线。bind 仍是唯一的设计连接原语；物理时钟与复位节点照常满足一次绑定不变量。电源域不虚构 RTL 端口。
] <dec-domain-no-wire>

#决策([域在设计根上声明])[
  一个设计的全部域在设计根上声明，模块不声明域，只通过参数得到并引用它们；归属一律写出，没有放置或继承的默认。读设计根就能看到全部时钟、复位、电源及其关系，读一个模块的调用处就能看到它在哪些域上。域只描述"有哪些域、彼此如何派生"，不描述由哪个模块产生；产生域的电路照常是模块，与域的一致性属于生成器自身的契约。电源岛只是一个结构模块，其中模块的节点写出岛的电源域。
] <dec-domain-context>

#决策([域是数据])[
  域的定义是数据，框架不调用用户函数产生域。生成器需要的属性，例如时钟频率，由域类沿定义的引用算出。模块对域的要求写在完整参数函数里，不满足即协商失败（@sec-generator-parameters）。时钟、复位、电源与 PRCM 的参数都由域的定义描述。
] <dec-domain-data>

== 模块节点、bind 与协议 <sec-node-conn-proto>

#term[模块节点][module node]由生成器模块声明，分为 #term[inward 节点][inward node]和 #term[outward 节点][outward node]。`inward(p)(domains*)` 与 `outward(p)(domains*)` 记录名称、方向、协议、归属和源码位置；每个节点同时对应生成器的一个顶层端口。#term[协议][protocol]规定一条连接上传播的参数类型和求解规则（@ch-protocol）；每个节点属于一个协议。

*bind* 是连接声明：把一个模块的 outward 节点接到另一个模块（或同一模块）的 inward 节点，写作 `目标 inward 节点 <-- 源 outward 节点`。验证观测不经 bind（@ch-verification）。

bind 写在结构模块的构建体里，声明它的结构模块必须是两端节点所在模块的祖先，可以不是最近的祖先；两端节点可以在它之下的任意深度。也就是说，模块只连接自己子树内部的节点；要连到子树外面，由外层模块来写。bind 记录声明它的结构模块，结构校验核对这一祖先关系（@sec-structural-check）。

方向按 `Down` 的传播定义：outward 节点是 bind 的源，inward 节点是 bind 的目标。每条 bind 在协商期得到三项参数：源节点算出的下行参数 `Down`、目标节点算出的上行参数 `Up`，以及由协议把二者合成的#term[边参数][edge parameter] `Edge`（@sec-three-param-kinds）。一条 bind 连同它求出的参数称为一条#term[边][edge]。每个模块节点恰好参与一次设计 bind：outward 节点恰好作为一次 bind 的源，inward 节点恰好作为一次 bind 的目标。

节点声明返回一个#term[节点草稿][node draft]。草稿必须恰好封口一次，封口后得到可以 bind 的#term[端口句柄][port handle]：

- `node.derive(sources) { values => ... }`：`sources` 是本模块反方向的节点，或它们的序列、元组。outward 节点读取 inward 节点的 `Down`，inward 节点读取 outward 节点的 `Up`；方向不对是编译错误。函数返回本节点的参数，或一项 `Violation`。域与用户参数一样是构造时就确定的值，参数需要域的属性时直接读，不经过 `sources`。
- `node.fixed(value)`：本节点的参数是常量。

outward 节点的函数称为 `dFn`，inward 节点的函数称为 `uFn`，二者统称#term[端口参数函数][port parameter functions]。`sources` 中的每个反方向节点就是一条#term[模块内部参数依赖][module-internal parameter dependency]：inward 节点 `i` 出现在 outward 节点 `o` 的 `sources` 中，或 `o` 出现在 `i` 的 `sources` 中，都记为从 `i` 到 `o` 的依赖。依赖由读取集合推出，不单独声明，因此函数能读的就是依赖的全部。不读取任何反方向节点的节点称为#term[边界节点][boundary node]，它的值只来自用户参数。同一个函数可以读不同协议的节点。Xbar、NoC 以多个具名节点表示多个端口，以内部参数依赖表示端口之间的参数影响关系；每个节点仍只参与一条 bind。

端口参数函数与完整参数函数只读用户参数、域、所例化设计的冻结结果和框架交给它的值，不能有副作用：求值顺序由依赖决定，副作用会让结果依赖这个顺序。

#不变量[全部模块节点均由生成器模块声明。]

#不变量[一条设计 bind 的源 outward 节点与目标 inward 节点必须使用同一协议。跨协议参数变换由具有不同 inward、outward 协议的显式生成器模块承担（@sec-protocol-object）。]

#不变量[bind 与模块内部参数依赖组成的有向图必须无环；这张图称为#term[参数依赖 DAG][parameter dependency DAG]（@sec-propagation）。发现环时，报告环上的模块节点、bind、内部依赖及其源码位置。]

== 稳定标识 <sec-identity>

实体标识由已命名结构派生：`ModuleId` 是从设计根开始的实例名路径；`ModuleNodeId` 由 `module` 与节点名组成；`BindId` 由源、目标 `ModuleNodeId` 组成；`DomainId` 由 `module` 与域的声明名组成；一项归属由 `ModuleNodeId` 与域类标识。同一模块内节点名与探针名共用一个命名空间；域声明名在本模块的域声明中唯一。每个节点唯一关联一条 bind，因此 `ModuleNodeId` 可以确定该节点所在的 `BindId` 和已求解边。探针的标识也是 `ModuleNodeId`（@sec-dv-declarations）。

每个模块、节点、bind、域、归属和探针都记录声明处的源码位置 `SourceLoc`，即 sourcecode 库捕获的 `File` 与 `Line`。源码位置只用于诊断，实体身份由稳定标识确定。

== 构建阶段 <sec-build>

设计由 `Design(moduleName) { ... }` 定义，它的体就是根结构模块的体。构建期声明节点、域和连接需要框架注入的#term[构建上下文][`BuildContext`]。条件拓扑与循环生成的子系统由宿主语言控制流表达。bind 算子 `<--` 只能在结构模块体中记录连接。声明的名称默认取自绑定它的 val（sourcecode 的 `Name`，与 zaozi 的实例命名一致）；循环等 val 名不可用的场合以 `named(name) { ... }` 给出。名称形状限定为 `[A-Za-z_][A-Za-z0-9_]*`，在声明处检查。

设计的体返回它向外交出的#term[设计引用][design reference]，例化它的设计会把这些引用移到实例上。设计引用的类型必须有 `Dangles` 证据：设计边界，以及它们的 `Vector` 与字段全为设计引用的积类型。端口、探针与域只经边界离开设计。构建上下文只在它的模块体执行期间有效；模块体返回后再用它声明或连接即报错。

可复用的模块定义由节点类和定义函数组成：节点类以字段持有端口句柄，定义函数调用 `generator[FP]` 或 `wrapper` 并转发名称上下文，实例名来自调用点的 val。生成器定义以 `given GeneratorDefinition[FP]` 提供，模块体按 `FP` 取得。

节点与域声明的生命周期在用户参数之后开始：模块构造时按用户参数声明它们。`DesignSpec` 固化后节点、域和归属不再增删；协商时每个节点恰好得到一条边；例化时每个节点对应生成器的一个端口。节点与域的有无、数量和名字只依赖用户参数与所例化设计的冻结结果（@sec-design-boundary），不依赖本设计的协商结果，也不依赖是否有 bind 指向节点。

协议的身份就是协议对象本身。一个设计里，同一个生成器名字只能对应一个生成器定义。

`DesignSpec` 包含：

- 固化后的模块树：结构模块的模块名与子实例序列；生成器模块的生成器定义、节点规格（方向、协议、归属、端口参数函数及其读取的节点）、探针与完整参数函数；
- 按声明顺序的 bind 序列；
- 域的声明序列；
- 设计边界，以及代表外侧与所例化设计的边界模块（@sec-design-boundary）。

== 设计与冻结边界 <sec-design-boundary>

一个 `Design` 是一个独立协商的单位。根结构模块可以把内部的端口句柄声明为#term[设计边界][design boundary]：`port.boundary(externalParams)(externalDomains*)`。`externalParams` 是对外侧的假设：inward 端口的边界给出外侧送来的 `Down`，outward 端口的边界给出外侧返回的 `Up`。`externalDomains` 给出外侧端点的归属。框架在设计根下放一个#term[边界模块][boundary module]，以常量节点代表外侧，与端口 bind 起来，于是设计可以单独协商。

设计第一次被使用时协商一次，结果随后#term[冻结][frozen]：每个边界的 `Down`、`Up`、`Edge` 与接口，边界所属各域的属性（域类的 `describe`），以及哪些边界同域，都成为设计对外的契约。

外层设计以 `design.instantiate` 例化一个冻结的设计。被例化的设计在外层看来是一个边界模块：它的每个边界成为一个常量节点，参数取自冻结结果；边界所属的冻结域，连同它们沿引用能到达的冻结域，都被复制到实例之下：副本的身份是实例路径加原来的路径，定义由域类的 `map` 改写为指向同一实例的副本，因此它是一个普通的域。同一个设计例化两次，得到两组副本。外层可以在例化时把自己的域送给边界，代替相应的冻结域：`design.instantiate(ports => Seq(ports.ref -> Seq(clock, reset, power)))`，常用于冻结设计的时钟与复位输入；副本的引用随之指向送来的域。`boundary.domain(K)` 读出边界在外层所在的域。外层照常 bind 这些节点并协商。送来的域在例化时当场核对：属性与它代替的冻结域相同，不同的冻结域不能送同一个域。副本由冻结的域复制而来，属性与分组自然不变。协商结束后框架核对每条边界边的 `Down`、`Up`、`Edge` 与接口与冻结时相同。任何改变都是错误。

例化结果 `DesignInstance` 给出实例的边界 `ports` 与实例中的公开探针 `probes`（@ch-verification）。冻结结果从设计本身读，不必例化：`design.ports` 是它交出的边界，`design.edgeOf(boundary)` 与 `design.probes` 读出冻结的边界边与公开探针；外层可以用这些值决定自己的用户参数。实例只通过边界和公开探针被外层看到：冻结设计保持独立，外层读不到它内部的模块、边与参数。外部工具需要的信息因此都要经边界或公开探针交出，成为设计的 ABI；例如调试器配置由 SoC 的内存边界与调试探针生成。外层还可以用 `boundary.boundary` 把内层的边界转发为自己的边界，用 `probe.boundary` 把内层的探针转发为自己的公开探针。

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
  [读入设计规格，检查各域与归属，核对每条 bind 跨越的域，并分阶段运行域检查；随后对参数依赖 DAG 做拓扑排序，正向传播 `Down`、反向传播 `Up`，逐边调用协议求解；最后计算各生成器模块的完整参数（@sec-two-layer-params），规划跨模块的端口与连线（@ch-negotiation）。产出协商结果 `ResolvedDesign`。检查整批报告失败，计算在首个失败处终止（@sec-error-semantics）。],
  [例化],
  [读入协商结果：以各生成器模块的完整参数调用 zaozi 生成器，生成结构模块的电路，执行连线计划，链接为一个电路（@ch-hardware）。产出 mlirbc 与一组 Verilog 文件。],
)

`ResolvedDesign` 保留对应的 `DesignSpec`，并加上协商的全部结果：本设计的域与每个节点的归属、每条边求出的参数和接口、每个生成器模块的完整参数、公开探针目录与观测绑定、跨层端口与连线的计划、FIRRTL 层声明，以及对所例化的冻结设计的引用；它不含实例内部的内容，每个冻结设计各有自己的 `ResolvedDesign`；字段见 @sec-resolved-records 与 @sec-generator-records。

协商阶段检查 `DesignSpec` 的结构、协议与参数约束（@req-iteration）；例化阶段检查生成器实际端口与协议接口（@dec-binding-check）。拓扑、声明或 IP 的变化触发新一轮三阶段。

== 序列化边界 <sec-serialization-boundary>

协商结果与硬件生成器之间的数据接口是每个生成器模块的#term[完整参数][full parameter]，即用户参数与已求解参数的合成（@sec-two-layer-params）。完整参数可序列化，使每个 IP 能以固定参数文件独立例化和测试，并支持归档与复现（@req-ip）。

规格中的端口参数函数、完整参数函数与域检查属于当前协商进程。跨进程数据包括完整参数，以及按需导出的拓扑、域、连接求解结果和端口计划（@ch-tooling）。

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
