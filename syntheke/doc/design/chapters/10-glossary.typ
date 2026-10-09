#import "../lib.typ": *

= 术语、决策与索引

== 术语表

#table(
  columns: (auto, auto, 1fr),
  table.header([中文], [English], [一句话定义（定义处）]),
  [层次树], [hierarchy tree], [模块的所有权与命名空间树，只包含设计源码显式例化的模块（@sec-two-graphs）。],
  [模块], [module], [层次树的顶点，分为结构模块与生成器模块（@sec-two-graphs、@sec-module-kinds）。],
  [连接结构], [connection structure], [生成器模块的具名 inward、outward 节点、从一个 outward 节点到一个 inward 节点的 bind，以及模块内部从 inward 到 outward 的参数依赖（@sec-two-graphs、@ch-interconnect）。],
  [域结构], [domain structure], [与层次树、连接结构并列的第三套结构，包含域、派生关系与节点的归属；不表达 RTL 连线（@sec-domain-model）。],
  [域类], [domain kind], [实现 `DomainKind` 的对象，定义一类域的根、派生关系、跨越关系、属性描述与检查插件（@sec-domain-model）。],
  [域], [domain], [模块体中声明的根或派生域；身份是 `DomainId`（@sec-domain-model）。],
  [根与派生], [root, derivation], [根不从其它域派生；派生域从同类源域经类型化的关系得到（@sec-domain-model）。],
  [归属], [membership], [一个节点在某类域中属于哪个域：一个域、另一个节点的域、沿承载 bind 接收，或作用域的默认（@sec-domain-model）。],
  [结算后的域], [`Settled`], [源都已确定的域，派生关系是纯数据（@sec-domain-settlement）。],
  [域图], [`DomainGraph`], [结算后的域与全部归属；检查插件与完整参数函数读取它（@sec-domain-settlement）。],
  [跨越声明], [`accepts`], [协议对每个非承载的活跃域类声明接受哪些关系（@sec-domain-crossing）。],
  [域检查], [`DomainCheck`], [域类插件提供的检查，按良构、行为两个阶段运行（@sec-domain-checks）。],
  [承载], [carries], [协议声明自己经物理连线传送的域类；inward 端从 outward 端取域（@sec-domain-physical-carrier）。],
  [活跃域类], [active domain kind], [一条 bind 两端归属中出现的域类，必须由承载或协议的 `accepts` 覆盖（@sec-domain-crossing）。],
  [模块内部参数依赖], [module-internal parameter dependency], [端口参数函数读取本模块反方向节点所形成的 inward 到 outward 的依赖（@sec-node-conn-proto、@sec-propagation）。],
  [桥], [bridge], [声明 inward、outward 节点及二者间参数依赖，并实现协议、位宽、时钟或电源转换硬件的生成器模块（@sec-bridge-boundary）。],
  [模块节点], [module node], [生成器模块声明的一个具名 inward 或 outward 节点；恰好参与一次设计 bind，并对应一条边和一个生成器端口（@sec-node-conn-proto、@sec-attach、@sec-generator-module）。],
  [inward 节点], [inward node], [模块节点的一种：接收所在 bind 的 `Down`，用 `uFn` 产生 `Up`，恰好作为一次 bind 的目标（@sec-node-conn-proto）。],
  [outward 节点], [outward node], [模块节点的一种：用 `dFn` 产生所在 bind 的 `Down`，接收 `Up`，恰好作为一次 bind 的源（@sec-node-conn-proto）。],
  [节点草稿与端口句柄], [node draft, port handle], [节点声明返回草稿，以 `derive` 或 `fixed` 恰好封口一次后得到可以 bind 的端口句柄（@sec-node-conn-proto）。],
  [稳定标识], [stable identifier], [`ModuleId`、`ModuleNodeId`、`BindId`、`DomainId`；由实例名路径与声明名派生（@sec-identity）。],
  [边], [edge], [一次设计 bind 对应的已求解连接，以 `BindId` 为稳定标识，包含 `Down`、`Up` 与 `Edge`（@sec-node-conn-proto、@sec-settle-pp）。],
  [构建上下文], [`BuildContext`], [框架注入模块体的构建期上下文；结构模块体为 `WrapperScope`，生成器模块体为 `GeneratorScope[FP]`（@sec-build）。],
  [设计引用], [design reference], [设计体向外交出的值：端口句柄、探针、设计边界及其组合，须有 `Dangles` 证据（@sec-build）。],
  [设计], [`Design`], [一个独立协商的单位，体即根结构模块的体（@sec-build、@sec-design-boundary）。],
  [设计边界], [design boundary], [根上声明的对外端口，附带对外侧参数与域的假设（@sec-design-boundary）。],
  [边界模块], [boundary module], [代表设计外侧或所例化设计的伪模块，以常量节点参与协商，不产生硬件（@sec-design-boundary、@sec-boundary-ports）。],
  [冻结], [frozen], [设计协商一次后边界契约固定；外层只能确认，不能改变（@dec-design-frozen）。],
  [协议], [protocol], [定义一条边的 `Down`、`Up`、`Edge`、承载的域类、逐边 `negotiate` 与接口描述的连接契约（@sec-node-conn-proto、@sec-protocol-object）。],
  [协议转换模块], [protocol converter], [声明不同协议的 inward、outward 节点及二者间参数依赖，并实现相应参数与硬件转换的生成器模块（@sec-protocol-object）。],
  [端口参数函数（`dFn` 与 `uFn`）], [port parameter functions], [outward 节点从所读 inward 节点的 `Down` 计算本节点 `Down`，inward 节点从所读 outward 节点的 `Up` 计算本节点 `Up` 的确定性函数，可附带约束（@sec-node-conn-proto、@sec-propagation）。],
  [边界节点], [boundary node], [不读取任何反方向节点的节点；值只来自用户参数（@sec-node-conn-proto）。],
  [`Down` 参数依赖 DAG], [downward dependency DAG], [由正向 bind 与模块内部参数依赖组成，按稳定拓扑序求值（@sec-propagation）。],
  [`Up` 参数依赖 DAG], [upward dependency DAG], [`Down` 参数依赖 DAG 的反向图，按同一拓扑序的逆序求值（@sec-propagation）。],
  [下行参数与上行参数], [downward and upward parameter], [`negotiate` 的两项输入，分别沿 bind 方向和反方向传播（@sec-three-param-kinds）。],
  [边参数], [edge parameter], [一条边求解后的最终参数（@sec-three-param-kinds）。],
  [事务身份需求], [transaction identity requirement], [协议用于区分节点、区分同一节点的并发事务、返回应答路由及索引内部资源的身份约束集合（@sec-three-params、@sec-three-param-kinds）。],
  [`ProtocolBundle`], [`ProtocolInterface.Bundle`], [协议端口的顶层 Bundle 描述（@sec-protocol-interface）。],
  [用户参数、已求解参数与完整参数], [user, resolved, and full parameter], [用户参数是模块构造时给定的参数；已求解参数是设计边的协议参数与生成器所需的域属性；完整参数是二者合并后交给生成器的参数（@sec-module-kinds、@sec-serialization-boundary、@sec-two-layer-params）。],
  [结构模块], [wrapper module], [包含子模块与设计 bind 的模块；端口、连线和层声明由框架发射（@sec-module-kinds、@sec-wrapper-emission）。],
  [生成器], [generator], [以可序列化完整参数为输入并返回电路模块的 zaozi 工厂（@sec-module-kinds、@sec-generator-contract）。],
  [生成器定义], [`GeneratorDefinition`], [生成器在框架侧的代表：名字、完整参数序列化、探针与观测报告，以及例化后端（@sec-generator-contract）。],
  [生成器模块], [generator module], [绑定恰好一个生成器定义的叶模块；声明节点、归属、端口参数函数、探针与完整参数函数（@sec-module-kinds、@sec-generator-module）。],
  [bind], [—], [连接声明，写作 `目标 <-- 源`，连接两个模块节点（@sec-node-conn-proto、@sec-attach）。],
  [边视图], [`EdgeView`], [求解完成后按模块整理的“节点到唯一设计边”映射（@sec-generator-records）。],
  [跨层端口规划], [cross-hierarchy port planning], [根据连接两端的层次路径生成所需的 Dangle 端口计划与逐层连线计划（@sec-punch-planning）。],
  [Dangle 端口], [dangle], [框架在被连接穿过的结构模块上生成的端口，名称可逆编码层次路径（@sec-punch-planning、@sec-port-naming）。],
  [链接键], [linking key], [（生成器名字，规范化完整参数）的摘要，即模块名；定义共享与链接据此进行（@sec-dedup）。],
  [公开探针端口], [public probe port], [生成器由完整参数决定的 Output `Probe` 端口（@sec-dv-declarations）。],
  [探针契约], [probe contract], [代表一类探针的 `ProbeContract[P]` 对象，按对象同一性比较（@sec-dv-declarations）。],
  [探针], [probe], [生成器模块对一个公开探针端口的描述，`ProbeNode[P]`（@sec-dv-declarations）。],
  [探针目录], [probe catalog], [协商后执行全部探针选择得到的记录；外层按契约查询（@sec-dv-catalog）。],
  [观测者与观测绑定], [observer, observation binding], [读取探针的生成器，及其由完整参数报告的读取清单（@sec-dv-observation）。],
  [层路径], [layer path], [探针所属的 FIRRTL 层名称序列；层的关闭与移除由 FIRRTL 提供（@sec-layers）。],
  [例化单元], [elaboration unit], [一个设计例化成的独立电路（@sec-elaboration-flow）。],
  [zaozi], [—], [Syntheke 使用的独立硬件生成器库，通过基于 MLIR 的 CIRCT 产出 FIRRTL（@sec-generator-contract）。],
  [协商器], [negotiator], [执行协商阶段的框架部分（@sec-triptych、@ch-negotiation）。],
)

== 需求映射 <sec-req-map>

#table(
  columns: (auto, 1fr, auto),
  table.header([需求], [对应设计], [主要章节]),
  [@req-iteration], [eDSL 源码定义连接和参数；静态连接错误在协商期报告], [@sec-build、@sec-attach、@sec-error-semantics],
  [@req-negotiation], [域由根和派生关系定义，先于数据结算并分阶段检查；bind 与模块内部参数依赖组成双向参数 DAG，`dFn`、`uFn` 按正反拓扑序传播，随后逐边求解], [@sec-domain-settlement、@sec-three-param-kinds–@sec-protocol-object、@sec-propagation、@sec-domain-checks],
  [@req-interconnect], [互连生成器模块显式声明每个端口节点与归属；物理时钟和复位仍经显式 bind，归属不生成连线], [@ch-interconnect],
  [@req-hierarchy], [跨层端口规划与端口命名], [@sec-punch-planning–@sec-port-naming],
  [@req-verification], [公开探针端口、探针契约、观测者与跨层路由], [@ch-verification],
  [@req-ip], [完整参数作为序列化边界上的生成器输入], [@sec-serialization-boundary],
  [@req-subsystem], [设计边界、冻结契约与按设计生成电路], [@sec-design-boundary、@sec-boundary-ports、@sec-elaboration-flow],
)

== 设计决策索引

#table(
  columns: (auto, 1fr, auto),
  table.header([编号], [决策], [章节]),
  [@dec-pi-required], [每个设计协议必须实现 `interface`。], [@sec-protocol-interface],
  [@dec-domain-identity-attachment], [域身份由声明模块与声明名派生；每个节点每个域类至多一项归属。], [@sec-domain-model],
  [@dec-domain-no-wire], [域结构不产生端口和连线；bind 仍是唯一设计连接原语。], [@sec-domain-model],
  [@dec-domain-context], [`scope` 在构建作用域内提供默认域，只用于声明 `scoped` 的域类。], [@sec-domain-model],
  [@dec-design-frozen], [设计是冻结的复用单位，外层只能确认它的边界契约。], [@sec-design-boundary],
  [@dec-domain-crossing-coverage], [每条 bind 的每个活跃域类必须由承载或协议的 `accepts` 覆盖。], [@sec-domain-crossing],
  [@dec-domain-no-feedback], [域先于数据结算；端口参数函数与协议不读也不写域。], [@sec-domain-settlement],
  [@dec-domain-data], [域的根与派生关系是数据；模块对域的要求写在完整参数函数里。], [@sec-domain-model],
  [@dec-domain-order-errors], [检查整批报告，有失败不进入下一阶段；计算在首个失败处停止。], [@sec-domain-checks],
  [@dec-pp-local], [完整参数函数读本模块的边与整张域图。], [@sec-generator-parameters],
  [@dec-full-param-content], [进入完整参数的是域的属性与稳定名字，不是实例路径。], [@sec-serialization-boundary],
  [@dec-dv-typed-query], [按契约查询探针，不按名字或路径。], [@sec-dv-catalog],
  [@dec-dv-top], [探针公开到设计根，读取由观测者声明；测试平台不是特殊模块。], [@sec-dv-routing],
  [@dec-port-naming], [框架生成的 Dangle 端口名采用名称段的可逆编码，长度随层次线性增长。], [@sec-port-naming],
  [@dec-wrapper-module-name], [结构模块的模块名显式声明，并与其它模块名处于同一符号空间。], [@sec-dedup],
  [@dec-binding-check], [端口结构校验在例化期进行。], [@sec-generator-module],
  [@dec-unit-per-design], [每个设计单独生成一个电路。], [@sec-elaboration-flow],
  [@dec-no-auto-monitor], [不提供自动监视器插入；观测面、检查逻辑与可移除性分别承担。], [@sec-no-auto-monitor],
)

== 与 Diplomacy 的关系 <sec-diplomacy>

Diplomacy 是 rocket-chip 生态中的参数协商框架：模块在图上声明节点，参数沿边双向传播，逐边求出接口参数。Syntheke 保留了这一核心思想，并做了有针对性的约束和简化：

- 保留参数依赖图上的 `Down` / `Up` 双向传播与逐边求解；
- 在连接图之外增加域结构：域由根和类型化的派生关系定义，先于数据传播结算，跨越由协议声明，检查以插件分阶段运行；
- 强制要求 IP 模块的完整参数可序列化，并使用 zaozi 作为生成器语言（@ch-hardware）；
- 去掉 AOP（Aspect-Oriented Programming）带来的过高自由度，硬件只在生成器里（@sec-module-kinds）；
- 去掉 `resolveStar` 与四种基数算子：端口数量由具名节点的显式声明给出，每个节点恰好参与一次 bind（@sec-attach）。

这样做的目的是减少隐式行为，使模块边界、连接关系和生成过程更加明确，并回应 Diplomacy 在多层次耦合下的几个问题：验证成本高（@req-verification、@req-ip），后端设计无法切分边界（@req-ip、@sec-bridge-boundary），NoC 无法作为生成器模块纳入同一套集成流程（@req-interconnect）。既有代码库中基数算子的实际使用情况另见分析文档《边数机制实证调查》。Diplomacy 惯用的“从图读回参数”在本方案中拆到四处：传播折叠进的边参数、先于数据结算的域、按模块投影的完整参数，以及外层读取的冻结结果（@sec-propagation、@dec-design-frozen）。

术语上，凡与 Diplomacy 指同一件事的，本文沿用 Diplomacy 的名字：`InwardNode` 与 `OutwardNode`、`Down` 与 `Up`、边、`dFn` 与 `uFn`、Dangle。下表列出名字不同或概念不完全重合之处。

#table(
  columns: (auto, auto, 1fr),
  table.header([Syntheke], [Diplomacy], [差异]),
  [inward 节点、outward 节点], [`InwardNode`、`OutwardNode`], [Diplomacy 的一个节点可同时具有两侧（`MixedNode`）；Syntheke 的一个节点只有一侧，对应一个端口和一条 bind。],
  [边界节点], [`SourceNode`、`SinkNode`], [同一件事：只有 outward 侧或只有 inward 侧的节点。],
  [bind，写作 `<--`], [`:=`], [方向相同（目标在左、源在右）；Syntheke 只有相当于 `BIND_ONCE` 的一种，没有 `:*=`、`:=*`、`:*=*` 与 `resolveStar`（@sec-attach）。],
  [`Protocol`], [`NodeImp`], [Syntheke 的协议是独立于节点的对象，身份即对象本身，可被多个模块的节点共享（@sec-protocol-object）。],
  [域结构], [—], [域不是设计边，不使用 `Down` / `Up` 或 bind；域由根和派生关系定义，先于数据结算（@sec-domain-model、@sec-domain-settlement）。],
  [`negotiate`], [`edgeI`、`edgeO`], [一条边只求出一个 `Edge`，两端共用，不区分 `EdgeIn` 与 `EdgeOut`（@sec-three-param-kinds）。],
  [`interface` 与 `ProtocolBundle`], [`bundleI`、`bundleO`], [Syntheke 返回可序列化的接口描述数据，例化期才翻译为 FIRRTL 类型；Diplomacy 直接返回 Chisel Bundle（@sec-protocol-interface）。],
  [模块内部参数依赖], [—], [Diplomacy 的 `dFn`、`uFn` 属于节点，`NexusNode` 隐含全部 inward 影响全部 outward；Syntheke 的函数也挂在节点上，但每个函数只读它列出的反方向节点，读取集合就是哪个 inward 节点影响哪个 outward 节点（@sec-node-conn-proto）。],
  [`wrapper`、`generator`], [`LazyModule`], [Diplomacy 不区分二者；Syntheke 以不同的构建上下文类型分开，硬件只在生成器模块里（@sec-module-kinds）。],
  [`EdgeView`、`DomainGraph`], [`node.in`、`node.out`], [Syntheke 按模块投影出全部节点的已求解边，并给出结算后的域，供完整参数函数读取（@sec-generator-records）。],
  [Dangle 端口], [`Dangle`、`AutoBundle`], [同一机制：连接穿过的模块边界上由框架生成端口；Syntheke 在协商期把它规划成计划数据（@sec-punch-planning）。],
)
