#import "../lib.typ": *

= 术语、决策与索引

== 术语表

#table(
  columns: (auto, auto, 1fr),
  table.header([中文], [English], [一句话定义（定义处）]),
  [层次树], [hierarchy tree], [模块的所有权与命名空间树，只包含设计源码显式例化的模块（@sec-two-graphs）。],
  [模块], [module], [层次树的顶点，分为结构模块与生成器模块（@sec-two-graphs、@sec-module-kinds）。],
  [连接结构], [connection structure], [生成器模块的具名 inward、outward 节点、从一个 outward 节点到一个 inward 节点的 bind，以及模块内部从 inward 到 outward 的参数依赖；它与层次树分别建模（@sec-two-graphs、@ch-interconnect）。],
  [模块内部参数依赖], [module-internal parameter dependency], [生成器模块显式声明的一条 inward 节点到 outward 节点的依赖；正向供 outward 节点的 `dFn` 读取 `Down`，反向供 inward 节点的 `uFn` 读取 `Up`（@sec-node-conn-proto、@sec-propagation）。],
  [桥], [bridge], [声明 inward、outward 节点及二者间参数依赖，并实现协议、位宽、时钟或电源转换硬件的生成器模块（@sec-bridge-boundary）。],
  [模块节点], [module node], [生成器模块声明的一个具名 inward 或 outward 节点；恰好参与一次设计 bind，并对应一条边和一个生成器端口（@sec-node-conn-proto、@sec-attach、@sec-generator-module）。],
  [inward 节点], [`InwardNode`], [模块节点的一种：接收所在 bind 的 `Down`，用 `uFn` 产生 `Up`，恰好作为一次 bind 的目标（@sec-node-conn-proto）。],
  [outward 节点], [`OutwardNode`], [模块节点的一种：用 `dFn` 产生所在 bind 的 `Down`，接收 `Up`，恰好作为一次 bind 的源（@sec-node-conn-proto）。],
  [稳定标识], [stable identifier], [`ModuleId`、`ModuleNodeId`、`BindId` 及探针源标识；由实例名路径与声明名派生，用于记录、导出与诊断（@sec-identity、@sec-dv-declarations）。],
  [边], [edge], [一次设计 bind 对应的已求解连接，以 `BindId` 为稳定标识，包含传播得到的 `Down`、`Up` 及逐边求得的 `Edge`（@sec-node-conn-proto、@sec-settle-pp、@sec-punch-planning）。],
  [构建上下文], [`DesignBuilder`], [`design` 入口注入的构建期上下文；节点声明与 bind 通过它写入 `DesignSpec`（@sec-build）。],
  [协议], [protocol], [定义一条边的 `Down`、`Up`、`Edge`、逐边 `negotiate` 与接口描述的连接契约（@sec-two-graphs、@sec-node-conn-proto、@sec-protocol-object）。],
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
  [用户参数、协议参数与完整参数], [user, protocol, and full parameter], [用户参数是每个模块构造时给定、先于节点存在的参数；协议参数由协商结果算出；完整参数是二者合并后交给生成器的参数（@sec-module-kinds、@sec-explicit-phase、@sec-serialization-boundary、@sec-two-layer-params）。],
  [结构模块], [`WrapperModule`], [包含子模块与设计 bind；模块节点归属生成器模块，端口、连线和层声明由框架发射（@sec-module-kinds、@sec-wrapper-emission）。],
  [生成器], [generator], [以可序列化完整参数为输入并返回电路模块的 zaozi 工厂函数（@sec-module-kinds、@sec-generator-contract）。],
  [生成器模块], [`GeneratorModule`], [通过注册表条目绑定恰好一个生成器的叶模块；每个设计节点对应其 IO 中的顶层 Bundle，每个探针源按信号叶对应纯 `Probe` 端口（@sec-module-kinds、@sec-generator-module）。],
  [bind], [—], [连接声明，写作 `目标 <- 源`，连接两个模块节点（@sec-node-conn-proto、@sec-attach）。],
  [跨协议引用], [cross-protocol reference], [节点对本模块时钟或电源 inward 节点的引用；求解后得到该节点边的 `Edge`，只提供域信息（@sec-resolved-records、@sec-settle-pp、@sec-generator-module）。],
  [边视图], [`EdgeView`], [求解完成后按模块和节点整理的“节点到唯一设计边”映射（@sec-generator-records、@sec-settle-pp）。],
  [跨层端口规划], [cross-hierarchy port planning], [根据连接两端的层次路径生成所需的 Dangle 端口计划与逐层连线计划（@sec-punch-planning）。],
  [Dangle 端口], [dangle], [框架在被连接穿过的结构模块上生成的端口，方向由所在分支决定，名称可逆编码层次路径（@sec-punch-planning、@sec-port-naming）。],
  [链接键], [linking key], [模块名对（生成器名字，规范化完整参数）的忠实编码，定义共享与链接据此进行（@sec-dedup）。],
  [探针源], [DV source], [生成器模块声明的观察点，提供 `Down` 与层路径；框架把每个探针叶自动上提到设计根（@sec-dv-declarations）。],
  [探针清单], [probe manifest], [`DesignSpec` 的纯函数：全部探针源按模块先序与声明顺序、每叶一条的可序列化记录（@sec-dv-testbench）。],
  [测试平台后端], [testbench backend], [例化期消费探针清单的后端：按层在根内例化 harness，逐叶 `ref.resolve` 后按清单端口名接入（@sec-dv-testbench）。],
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
  [@req-negotiation], [bind 与模块内部参数依赖组成双向参数 DAG；`dFn`、`uFn` 按正反拓扑序传播，随后逐边求解], [@sec-three-param-kinds–@sec-protocol-object、@sec-propagation],
  [@req-interconnect], [互连生成器模块显式声明每个端口节点和模块内部参数依赖，并与其它生成器模块使用同一双向传播和逐边求解], [@ch-interconnect],
  [@req-hierarchy], [跨层端口规划与端口命名], [@sec-punch-planning–@sec-port-naming],
  [@req-verification], [探针自动上提到顶层的跨层路由与 FIRRTL 层声明], [@ch-verification],
  [@req-ip], [完整参数作为序列化边界上的生成器输入], [@sec-serialization-boundary、@sec-serialization-list],
)

== 设计决策索引

#table(
  columns: (auto, 1fr, auto),
  table.header([编号], [决策], [章节]),
  [@dec-pi-required], [每个设计协议必须实现 `interfaceOf`；返回值的根及嵌套 Bundle 均含至少一个字段。], [@sec-protocol-interface],
  [@dec-dv-top], [探针自动上提到根，图内不设收集端。], [@sec-dv-routing],
  [@dec-layer-merge], [层路径按前缀合并。], [@sec-layers],
  [@dec-pp-local], [`computeFullParam` 只读取本模块的已求解数据。], [@sec-settle-pp],
  [@dec-port-naming], [框架生成的 Dangle 端口名采用名称段的可逆编码，长度随层次线性增长。], [@sec-port-naming],
  [@dec-binding-check], [端口结构校验在例化期进行。], [@sec-generator-module],
)

== 与 Diplomacy 的关系 <sec-diplomacy>

Diplomacy 是 rocket-chip 生态中的参数协商框架：模块在图上声明节点，参数沿边双向传播，逐边求出接口参数。Syntheke 保留了这一核心思想，并做了有针对性的约束和简化：

- 保留参数依赖图上的 `Down` / `Up` 双向传播与逐边求解；
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
  [`negotiate`], [`edgeI`、`edgeO`], [一条边只求出一个 `Edge`，两端共用，不区分 `EdgeIn` 与 `EdgeOut`（@sec-three-param-kinds）。],
  [`interfaceOf` 与 `ProtocolBundle`], [`bundleI`、`bundleO`], [Syntheke 返回可序列化的接口描述数据，例化期才翻译为 FIRRTL 类型；Diplomacy 直接返回 Chisel Bundle（@sec-protocol-interface）。],
  [模块内部参数依赖], [—], [Diplomacy 的 `dFn`、`uFn` 属于节点，`NexusNode` 隐含全部 inward 影响全部 outward；Syntheke 把两个函数挂在模块上，并显式声明哪个 inward 节点影响哪个 outward 节点（@sec-node-conn-proto）。],
  [`WrapperModule`、`GeneratorModule`], [`LazyModule`], [Diplomacy 不区分二者；Syntheke 在类型层面分开，硬件只在生成器模块里（@sec-module-kinds）。],
  [`EdgeView`], [`node.in`、`node.out`], [Syntheke 按模块投影出全部节点的已求解边，供 `computeFullParam` 读取（@sec-generator-records）。],
  [Dangle 端口], [`Dangle`、`AutoBundle`], [同一机制：连接穿过的模块边界上由框架生成端口；Syntheke 在协商期把它规划成计划数据（@sec-punch-planning）。],
)
