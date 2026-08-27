#import "../lib.typ": *

= 附录 B：与 Diplomacy 的例化生命周期对比 <apx-lifecycle>

@sec-diplomacy 从机制取舍的角度概述了与 Diplomacy 的关系。本附录换一个剖面：沿"设计源码到 Verilog"的生命周期逐段对齐两者，并记录由此产生的设计裁定与待决问题。

== 逐段对比 <apx-lifecycle-stages>

#table(
  columns: (auto, 1fr, 1fr),
  table.header([阶段], [Diplomacy], [Syntheke]),
  [图构建],
  [`LazyModule` 构造急性执行；四种 bind 算子只记录标签，边数未定；CDE `Parameters` 作为环境贯穿构造。],
  [`Design` 体急性执行，产物是纯数据 `DesignSpec`；每节点恰好一条 bind，节点数量此刻定死（@sec-build）；无环境对象。构建结束时图已终形，只有参数未解。],
  [求解触发],
  [无独立求解阶段：Chisel 例化按需强制 lazy val 链，求值顺序由需求驱动涌现，与用户模块体代码交织；环表现为栈溢出。],
  [协商是显式独立阶段：结构校验、稳定拓扑排序、两遍传播、逐边求解，逐步全量完成（@sec-passes）；参数依赖环在拓扑排序时报出。],
  [基数求解],
  [`resolveStar` 在端口映射时求解边数。],
  [子问题不存在：边数是构建期事实（@sec-attach）。],
  [参数传播],
  [函数挂在节点上、按 `Seq` 处理；NexusNode 各输出获得同一份聚合下行参数。],
  [`dFn` 挂在单个 outward 节点上，读取集合由显式模块内部参数依赖给定，各 outward 节点可有不同可达集（@sec-propagation）。],
  [逐边求解],
  [`edgeI` 与 `edgeO` 求出两份边参数（类型可不同），入参含 `Parameters` 与 `SourceInfo`。],
  [一次 `negotiate` 求出两端共享的单一 `Edge`，纯函数（@sec-protocol-object）。],
  [硬件接口出生点],
  [`bundleI`/`bundleO` 在例化中直接产出 Chisel 类型；用户代码同一时刻读边参数写硬件，协商与硬件构造在同一次 elaboration 中交织。],
  [`interfaceOf` 在协商期产出接口描述数据；例化期才译成 FIRRTL 类型；生成器只见完整参数并从中重建端口，框架按 @dec-binding-check 对账。],
  [跨层端口],
  [Dangle 在例化中逐层上浮聚成 `auto` 端口，名字由例化顺序涌现。],
  [协商期产出端口与连线计划，名字可逆编码、与例化顺序无关（@sec-punch-planning、@dec-port-naming）；例化期机械执行。],
  [验证],
  [`NodeImp.monitor` 在例化中自动插入监视器；内部信号观测靠打洞惯用法。],
  [探针是一等协议：协商期规划路由与层，例化期由父结构模块在层块内 `ref.resolve` 喂给验证生成器（@ch-verification、@sec-sink-shape）。],
  [模块身份与去重],
  [例化后由编译器按结构去重，辅以 `CloneLazyModule` 等手工手段。],
  [例化前按结构键承诺去重（@sec-dedup）；生成器缓存键为（生成器名字，规范化完整参数）。],
  [产物边界],
  [一次 elaboration 一个电路，中途无可序列化断面。],
  [`DesignSpec`、`ResolvedDesign`、完整参数三个可序列化断面（@sec-serialization-boundary）；IP 可凭一份参数文件独立例化（@req-ip）。],
)

生命周期上最深的分岔在"硬件接口出生点"：Diplomacy 将协商结果与硬件构造合并为一次求值，换来例化中任意读图的自由（其损失面见 @apx-readback）；Syntheke 以序列化边界切开两者，换来确定性、整批错误报告与可独立测试的协商器。

== 监视器的承接 <apx-lifecycle-monitor>

#决策([不提供自动监视器插入])[
  Syntheke 不提供 `NodeImp.monitor` 的对应机制。监视器拆解为三件已有之物：观测由探针源承担（协议库可为端口提供镜像探针的标准声明）；检查硬件是普通的验证生成器模块，具有完整参数、可独立例化测试并参与去重；可移除性由 FIRRTL 层承担。自动插入本身属于 @sec-diplomacy 所删除的隐式行为。
] <dec-no-auto-monitor>

残留差异需言明：Diplomacy 的监视器挂在连线上，不需要端点配合；Syntheke 的探针必须由生成器自行声明，不配合的生成器的边无法被观测。本设计接受该差异——观测是模块声明的契约的一部分；协议库以标准件降低声明成本。曾考虑由结构模块对计划连线引出探针（"边 tap"），因重新引入隐式硬件而否决。

== 待决问题 <apx-lifecycle-open>

以下差异的处置尚未裁定，记录于此：

- *去重的先验承诺。*例化前按结构键去重把"同键同构"从编译器后验事实变为生成器契约（@sec-dedup、@sec-generator-contract）。可考虑在例化期对同键多实例做一次廉价的结构比对，把违约报告为例化错误。
- *接口的双写。*协议的 `interfaceOf` 与生成器的端口声明各写一次同一形状，@dec-binding-check 只能事后对账。消除路径是从 `ProtocolBundle` 生成（或运行时构造）生成器侧接口类型。
- *单电路直通。*当前每模块一个电路再链接，是生成器库独立例化形状的副产物；若生成器库提供向现有电路追加模块的入口，可省去链接步骤。
