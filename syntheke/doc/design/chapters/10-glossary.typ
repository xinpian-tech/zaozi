#import "../lib.typ": *

= 术语、决策与索引

== 术语表

#table(
  columns: (auto, auto, 1fr),
  table.header([中文], [English], [一句话定义（定义处）]),
  [层次树], [hierarchy tree], [模块的所有权与命名空间树，只包含设计源码显式例化的模块（@sec-two-graphs）。],
  [模块], [module], [层次树的顶点，分为结构模块与生成器模块（@sec-two-graphs、@sec-module-kinds）。],
  [连接结构], [connection structure], [生成器模块的具名 inward、outward 节点、从一个 outward 节点到一个 inward 节点的 bind，以及模块内部从 inward 到 outward 的参数依赖；它与层次树分别建模（@sec-two-graphs、@ch-interconnect）。],
  [域结构], [domain structure], [与层次树、连接结构并列的第三套结构，包含域声明、显式域依赖、附着、要求、承载和同域组；不表达 RTL 连线（@sec-domain-model）。],
  [域], [domain], [时钟、复位或电源的一组语义归属，由声明处的稳定身份标识（@sec-domain-model）。],
  [域类], [domain kind], [规定一类域的定值、要求、验证与序列化的对象；身份是域类对象本身（@sec-domain-model）。],
  [域声明], [domain declaration], [模块声明的单源域，给出名称、域类、定值函数、显式前驱域和源码位置（@sec-domain-model）。],
  [域主体], [domain subject], [可以附着于域并独立声明要求的模块或模块节点（@sec-domain-model）。],
  [附着], [attachment], [把节点或生成器模块 subject 归入某域的声明；时钟和复位经本地物理 inward 节点解析，电源可由层次作用域继承（@sec-domain-model）。],
  [要求], [requirement], [subject 对已附着域独立贡献的一份约束；不由附着数量或端口数量推断（@sec-domain-model、@sec-domain-settlement）。],
  [定值], [settled value], [域源由用户参数与显式前驱域产生、并经全域成员要求验证后的唯一值（@sec-domain-settlement）。],
  [同域组], [same-domain group], [生成器模块按域类声明的端口等价组，表示组内端口必须属于同一域（@sec-same-domain-groups）。],
  [承载], [realizes], [物理时钟或复位 outward 节点对其所传送域 handle 的声明（@sec-domain-physical-carrier）。],
  [域契约], [`DomainContract`], [设计协议对每个域类显式给出的 `Same` 或带理由 `Unconstrained` 端点关系（@sec-domain-contract）。],
  [模块内部参数依赖], [module-internal parameter dependency], [生成器模块显式声明的一条 inward 节点到 outward 节点的依赖；正向供 outward 节点的 `dFn` 读取 `Down`，反向供 inward 节点的 `uFn` 读取 `Up`（@sec-node-conn-proto、@sec-propagation）。],
  [桥], [bridge], [声明 inward、outward 节点及二者间参数依赖，并实现协议、位宽、时钟或电源转换硬件的生成器模块（@sec-bridge-boundary）。],
  [模块节点], [module node], [生成器模块声明的一个具名 inward 或 outward 节点；恰好参与一次设计 bind，并对应一条边和一个生成器端口（@sec-node-conn-proto、@sec-attach、@sec-generator-module）。],
  [inward 节点], [`InwardNode`], [模块节点的一种：接收所在 bind 的 `Down`，用 `uFn` 产生 `Up`，恰好作为一次 bind 的目标（@sec-node-conn-proto）。],
  [outward 节点], [`OutwardNode`], [模块节点的一种：用 `dFn` 产生所在 bind 的 `Down`，接收 `Up`，恰好作为一次 bind 的源（@sec-node-conn-proto）。],
  [稳定标识], [stable identifier], [`ModuleId`、`ModuleNodeId`、`BindId`、`DomainDeclId` 及探针源标识；由实例名路径、声明名与声明顺序派生，用于记录、导出与诊断（@sec-identity、@sec-dv-declarations）。],
  [边], [edge], [一次设计 bind 对应的已求解连接，以 `BindId` 为稳定标识，包含传播得到的 `Down`、`Up` 及逐边求得的 `Edge`（@sec-node-conn-proto、@sec-settle-pp、@sec-punch-planning）。],
  [构建上下文], [`DesignBuilder`], [`design` 入口注入的构建期上下文；节点声明与 bind 通过它写入 `DesignSpec`（@sec-build）。],
  [协议], [protocol], [定义一条边的 `Down`、`Up`、`Edge`、逐边 `negotiate`、接口描述与域契约的连接契约；没有连线的域结构不是协议（@sec-two-graphs、@sec-node-conn-proto、@sec-protocol-object）。],
  [验证协议], [`DVProtocol`], [由探针源的 `Down` 与层路径导出全叶 `Probe` 的接口；无聚合、无协商，契约在声明处检查（@sec-dv-protocol）。],
  [协议转换模块], [protocol converter], [声明不同协议的 inward、outward 节点及二者间参数依赖，并实现相应参数与硬件转换的生成器模块（@sec-protocol-object）。],
  [端口参数函数（`dFn` 与 `uFn`）], [port parameter functions], [outward 节点从所依赖 inward 节点的 `Down` 计算本节点 `Down`，inward 节点从依赖它的 outward 节点的 `Up` 计算本节点 `Up` 的确定性函数（@sec-node-conn-proto、@sec-propagation）。],
  [边界节点], [boundary node], [不依赖任何 inward 节点的 outward 节点，或不被任何 outward 节点依赖的 inward 节点；其函数只从用户参数产生初值（@sec-node-conn-proto）。],
  [`Down` 参数依赖 DAG], [downward dependency DAG], [由正向 bind 与模块内部 inward 到 outward 的参数依赖组成，按稳定拓扑序求值（@sec-propagation）。],
  [`Up` 参数依赖 DAG], [upward dependency DAG], [`Down` 参数依赖 DAG 的反向图，按同一拓扑序的逆序求值（@sec-propagation）。],
  [下行参数与上行参数], [downward and upward parameter], [`negotiate` 的两项输入，分别沿 bind 方向和反方向传播（@sec-three-param-kinds）。],
  [边参数], [edge parameter], [一条边求解后的最终参数（@sec-three-param-kinds）。],
  [事务身份需求], [transaction identity requirement], [协议用于区分节点、区分同一节点的并发事务、返回应答路由及索引内部资源的身份约束集合（@sec-three-params、@sec-three-param-kinds）。],
  [`ProtocolBundle`], [`ProtocolBundle`], [协议端口的顶层 Bundle 描述，其根及嵌套 Bundle 均含至少一个字段（@sec-protocol-interface）。],
  [用户参数、已求解参数与完整参数], [user, resolved, and full parameter], [用户参数是每个模块构造时给定、先于节点存在的参数；已求解参数包括设计边的协议参数、生成器所需域定值与本地关系；完整参数是用户参数与已求解参数合并后交给生成器的参数（@sec-module-kinds、@sec-explicit-phase、@sec-serialization-boundary、@sec-two-layer-params）。],
  [结构模块], [`WrapperModule`], [包含子模块与设计 bind，并可声明层次作用域上的电源域信息；模块节点归属生成器模块，端口、连线和层声明由框架发射（@sec-module-kinds、@sec-wrapper-emission）。],
  [生成器], [generator], [以可序列化完整参数为输入并返回电路模块的 zaozi 工厂函数（@sec-module-kinds、@sec-generator-contract）。],
  [生成器模块], [`GeneratorModule`], [通过注册表条目绑定恰好一个生成器的叶模块；声明节点、域结构和参数函数，每个设计节点对应其 IO 中的顶层 Bundle，每个探针源按信号叶对应纯 `Probe` 端口（@sec-module-kinds、@sec-generator-module）。],
  [bind], [—], [连接声明，写作 `目标 <- 源`，连接两个模块节点（@sec-node-conn-proto、@sec-attach）。],
  [边视图], [`EdgeView`], [求解完成后按模块和节点整理的“节点到唯一设计边”映射（@sec-generator-records、@sec-settle-pp）。],
  [域视图], [`DomainView`], [域结算后按模块整理的本模块所声明域定值、subject 附着域定值与本地实际同域等价类；不含全局域身份（@sec-generator-records、@sec-settle-pp）。],
  [跨层端口规划], [cross-hierarchy port planning], [根据连接两端的层次路径生成所需的 Dangle 端口计划与逐层连线计划（@sec-punch-planning）。],
  [Dangle 端口], [dangle], [框架在被连接穿过的结构模块上生成的端口，方向由所在分支决定，名称可逆编码层次路径（@sec-punch-planning、@sec-port-naming）。],
  [链接键], [linking key], [模块名对（生成器名字，规范化完整参数）的忠实编码，定义共享与链接据此进行（@sec-dedup）。],
  [探针源], [DV source], [生成器模块声明的观察点，提供 `Down` 与层路径；框架把每个探针叶自动上提到设计根（@sec-dv-declarations）。],
  [探针清单], [probe manifest], [`DesignSpec` 的纯函数：全部探针源按模块先序与声明顺序、每叶一条的可序列化记录（@sec-dv-testbench）。],
  [测试平台], [testbench], [顶层唯一的特殊生成器模块：节点经普通 bind 照常协商，框架把全部探针叶接进它的同名输入（@sec-dv-testbench）。],
  [探针源标识], [`DVSourceId`], [由源模块与名称组成（@sec-dv-declarations）。],
  [层路径], [layer path], [探针所属的 FIRRTL 层名称序列；层的关闭与移除由 FIRRTL 提供（@sec-layers）。],
  [zaozi], [—], [Syntheke 使用的独立硬件生成器库，通过基于 MLIR 的 CIRCT 产出 FIRRTL（@sec-generator-contract）。],
  [Triptych 流水线], [the Triptych pipeline], [构建、协商、例化三阶段（@sec-triptych）。],
  [协商器], [negotiator], [执行协商阶段的框架部分（@sec-triptych、@ch-negotiation）。],
)

== 需求映射 <sec-req-map>

#table(
  columns: (auto, 1fr, auto),
  table.header([需求], [对应设计], [主要章节]),
  [@req-iteration], [eDSL 源码定义连接和参数；架构图由源码导出；静态连接错误在协商期报告], [@sec-build、@sec-attach、@sec-visualization、@sec-error-semantics],
  [@req-negotiation], [域源给出静态定值并按显式域依赖 DAG 对成员要求作整域验证；bind 与模块内部参数依赖组成双向参数 DAG，`dFn`、`uFn` 按正反拓扑序传播，随后逐边求解], [@sec-domain-settlement、@sec-three-param-kinds–@sec-protocol-object、@sec-propagation],
  [@req-interconnect], [互连生成器模块显式声明每个端口节点、模块内部参数依赖与同域组；物理时钟和复位仍经显式 bind，域附着不自动生成线], [@ch-interconnect],
  [@req-hierarchy], [跨层端口规划与端口命名], [@sec-punch-planning–@sec-port-naming],
  [@req-verification], [探针自动上提到顶层的跨层路由与 FIRRTL 层声明], [@ch-verification],
  [@req-ip], [完整参数作为序列化边界上的生成器输入], [@sec-serialization-boundary、@sec-serialization-list],
)

== 设计决策索引

#table(
  columns: (auto, 1fr, auto),
  table.header([编号], [决策], [章节]),
  [@dec-pi-required], [每个设计协议必须实现 `interfaceOf`；返回值的根及嵌套 Bundle 均含至少一个字段。], [@sec-protocol-interface],
  [@dec-bits-default], [端口默认采用 `Bits`，只有算术到达的端口采用 `UInt`。], [@sec-protocol-interface],
  [@dec-domain-identity-attachment], [域身份由声明模块与声明名派生；每个 subject、每个域类至多一个有效附着。], [@sec-domain-model],
  [@dec-domain-no-wire], [域结构不产生端口和连线；bind 仍是唯一设计连接原语。], [@sec-domain-model],
  [@dec-power-domain-inheritance], [电源域采用最近祖先默认和显式覆盖；跨域状态组合交由整机工具检查。], [@sec-domain-model],
  [@dec-domain-contract-required], [每个设计协议必须给出无默认值的 `DomainContract`。], [@sec-domain-contract],
  [@dec-domain-same-groups], [生成器以同域组声明模块内部 `Same` 关系。], [@sec-same-domain-groups],
  [@dec-domain-physical-check], [物理时钟与复位 bind 和域结构并存，并按 `realizes(domain)` 核对。], [@sec-domain-physical-carrier],
  [@dec-domain-order-errors], [域验证对成员排列置换不变，首个失败携带相关参与者集合与见证。], [@sec-domain-settlement],
  [@dec-domain-no-feedback], [域只验证静态定值，不接受数据结果反馈，也不执行通用迭代。], [@sec-domain-settlement],
  [@dec-dv-top], [探针自动上提到根，图内不设收集端。], [@sec-dv-routing],
  [@dec-layer-merge], [层路径按前缀合并。], [@sec-layers],
  [@dec-pp-local], [已求解参数与 `computeFullParam` 只读取本模块的已求解数据。], [@sec-settle-pp],
  [@dec-port-naming], [框架生成的 Dangle 端口名采用名称段的可逆编码，长度随层次线性增长。], [@sec-port-naming],
  [@dec-wrapper-module-name], [结构模块的模块名显式声明，并与其它模块名处于同一符号空间。], [@sec-dedup],
  [@dec-binding-check], [端口结构校验在例化期进行。], [@sec-generator-module],
  [@dec-no-auto-monitor], [不提供自动监视器插入；观测、检查逻辑与可移除性分别承担。], [@apx-lifecycle-monitor],
)

== 与 Diplomacy 的关系 <sec-diplomacy>

Diplomacy 是 rocket-chip 生态中的参数协商框架：模块在图上声明节点，参数沿边双向传播，逐边求出接口参数。Syntheke 保留了这一核心思想，并做了有针对性的约束和简化：

- 保留参数依赖图上的 `Down` / `Up` 双向传播与逐边求解；
- 在连接图之外增加域结构，以单源静态定值对全域成员要求作验证；域结算先于数据传播，且没有数据结果反馈；
- 强制要求 IP 模块的完整参数可序列化，并使用 zaozi 作为生成器语言（@ch-hardware）；
- 去掉 AOP（Aspect-Oriented Programming）带来的过高自由度，硬件只在生成器里（@sec-module-kinds）；
- 去掉 `resolveStar` 与四种基数算子：端口数量由具名节点的显式声明给出，每个节点恰好参与一次 bind（@sec-attach）。

这样做的目的是减少隐式行为，使模块边界、连接关系和生成过程更加明确，并回应 Diplomacy 在多层次耦合下的几个问题：验证成本高（@req-verification、@req-ip），后端设计无法切分边界（@req-ip、@sec-bridge-boundary），NoC 无法作为生成器模块纳入同一套集成流程（@req-interconnect）。既有代码库中基数算子的实际使用情况另见分析文档《边数机制实证调查》；Diplomacy 惯用的"从图读回参数"在本方案中的承接见 @apx-readback。

术语上，凡与 Diplomacy 指同一件事的，本文沿用 Diplomacy 的名字：`InwardNode` 与 `OutwardNode`、`Down` 与 `Up`、边、`dFn` 与 `uFn`、Dangle。下表列出名字不同或概念不完全重合之处。

#table(
  columns: (auto, auto, 1fr),
  table.header([Syntheke], [Diplomacy], [差异]),
  [inward 节点、outward 节点], [`InwardNode`、`OutwardNode`], [Diplomacy 的一个节点可同时具有两侧（`MixedNode`）；Syntheke 的一个节点只有一侧，对应一个端口和一条 bind。],
  [边界节点], [`SourceNode`、`SinkNode`], [同一件事：只有 outward 侧或只有 inward 侧的节点。],
  [bind，写作 `<-`], [`:=`], [方向相同（目标在左、源在右）；Syntheke 只有相当于 `BIND_ONCE` 的一种，没有 `:*=`、`:=*`、`:*=*` 与 `resolveStar`（@sec-attach）。],
  [`Protocol`], [`NodeImp`], [Syntheke 的协议是独立于节点的对象，身份即对象本身，可被多个模块的节点共享（@sec-protocol-object）。],
  [域结构], [—], [域不是设计边，不使用 `Down` / `Up` 或 bind；域源静态定值按显式域依赖 DAG 计算，并对成员要求作整域验证（@sec-domain-model、@sec-domain-settlement）。],
  [`negotiate`], [`edgeI`、`edgeO`], [一条边只求出一个 `Edge`，两端共用，不区分 `EdgeIn` 与 `EdgeOut`（@sec-three-param-kinds）。],
  [`interfaceOf` 与 `ProtocolBundle`], [`bundleI`、`bundleO`], [Syntheke 返回可序列化的接口描述数据，例化期才翻译为 FIRRTL 类型；Diplomacy 直接返回 Chisel Bundle（@sec-protocol-interface）。],
  [模块内部参数依赖], [—], [Diplomacy 的 `dFn`、`uFn` 属于节点，`NexusNode` 隐含全部 inward 影响全部 outward；Syntheke 把两个函数挂在模块上，并显式声明哪个 inward 节点影响哪个 outward 节点（@sec-node-conn-proto）。],
  [`WrapperModule`、`GeneratorModule`], [`LazyModule`], [Diplomacy 不区分二者；Syntheke 在类型层面分开，硬件只在生成器模块里（@sec-module-kinds）。],
  [`EdgeView`、`DomainView`], [`node.in`、`node.out`], [Syntheke 按模块投影出全部节点的已求解边、域定值与本地同域关系，供 `computeFullParam` 读取（@sec-generator-records）。],
  [Dangle 端口], [`Dangle`、`AutoBundle`], [同一机制：连接穿过的模块边界上由框架生成端口；Syntheke 在协商期把它规划成计划数据（@sec-punch-planning）。],
)
