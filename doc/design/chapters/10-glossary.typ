#import "../lib.typ": *

= 术语、决策与索引

== 术语表

#table(
  columns: (auto, auto, 1fr),
  table.header([中文], [English], [一句话定义（定义处）]),
  [层次树], [hierarchy tree], [模块的所有权与命名空间树，一一对应电路层次（@sec-two-graphs）。],
  [模块], [module], [层次树的顶点；只有结构模块与生成器模块两种形态（@sec-two-graphs、@sec-module-kinds）。],
  [连接结构], [connection structure], [总线、成员与附着构成的结构，独立于层次（@sec-two-graphs、@ch-interconnect）。],
  [总线], [bus], [命名一组成员的逻辑分组；承载可达、地址、域，并在其上协商，不产硬件（@sec-bus-fabric）。],
  [物理互连], [fabric], [兑现一条总线的硬件生成器（交叉开关／片上网络／直连）；一条总线恰好对应一个（@sec-bus-fabric）。],
  [成员], [member], [附着到总线的东西：叶子设备，或连接两条总线的桥，二者同质（@sec-attach）。],
  [桥], [bridge], [连接两条平总线的设备：一侧为响应者、一侧为发起者，内部做地址转换/跨时钟域/位宽转换；有意义的边界（独立交付、域改变、换 fabric）即一个桥（@sec-flat-nest）。],
  [节点], [node], [模块在某个协议上的接入点；一次附着把它接到总线的一个具名节点上（@sec-attach）。],
  [边], [edge], [协商后一条点对点链路；每次附着结算出一条边（@sec-settle-pp）。],
  [构建器令牌], [builder token], [框架入口注入的上下文值；附着在其作用域外无法编译（@sec-triptych）。],
  [协议], [protocol], [定义 `Down`/`Up`/`Edge` 三类型与结算函数的对象（@sec-protocol-object）。],
  [跨协议适配], [cross-protocol adapter], [两侧服从不同协议、携带跨协议变换的适配器，置于桥内（@sec-protocol-object）。],
  [下行 / 上行参数], [downward / upward parameter], [上游端 / 下游端的声明，分别沿边、逆边传播（@sec-three-param-kinds）。],
  [两端指认], [end-role identification], [协议对边两端的规定：各是什么角色、各自声明什么（供给与需求）、哪端为上游（@sec-three-param-kinds）。],
  [边参数], [edge parameter], [一条边结算后的最终参数（@sec-three-param-kinds）。],
  [协议接口], [protocol interface], [边的硬件形状的纯数据描述（@sec-protocol-interface)。],
  [用户 / 协议 / 完整参数], [user / protocol / full parameter], [构建期人写 / 协商期算出 / 二者合并后交给生成器（@sec-two-layer-params）。],
  [结构模块], [`WrapperModule`], [只含子模块、节点与附着的模块；电路体由框架发射（@sec-module-kinds、@sec-wrapper-emission）。],
  [生成器], [generator], [硬件域的模块工厂：消费一个可序列化参数产出电路模块。与"生成器模块"是不同概念（@sec-generator-contract）。],
  [生成器模块], [`GeneratorModule`], [持有恰一个生成器的模块；全部硬件在生成器内（@sec-module-kinds、@sec-generator-module）。],
  [硬件绑定], [hardware binding], [节点到生成器接口字段的映射函数，例化期做结构校验（@sec-generator-module）。],
  [角色], [role], [成员的方向：发起者或响应者；聚合由总线承担，跨协议或跨域适配落在桥内（@sec-attach、@sec-propagation）。],
  [附着], [attach], [`bus.node("名") <~ 成员`：唯一的连接原语；数量即附着的个数（@sec-attach）。],
  [具名节点], [named node], [物理互连暴露的物理落点，一个端口或路由器，以名字标识（@sec-placement）。],
  [落点], [placement], [成员落在哪个具名节点；构建期显式给出，协商不读，例化期消费（@sec-placement）。],
  [清单], [manifest], [总线交给物理互连的数据：成员表与可达表；唯一的一次交接（@sec-manifest）。],
  [边视图], [`EdgeView`], [生成器模块可见的本模块边只读视图（@sec-settle-pp）。],
  [打洞], [port punching], [沿层次路径为跨界边规划端口与连线（@sec-punch-planning）。],
  [结构键], [structural key], [模块去重用的规范化身份（@sec-dedup）。],
  [探针源 / 探针汇], [DV source / DV sink], [验证协议的上游端与下游端；只有源侧的下行声明，汇端聚合（@sec-dv-model）。],
  [层路径], [layer path], [探针所属的 FIRRTL 层命名链；层的关闭与移除由 FIRRTL 提供（@sec-layers）。],
  [zaozi], [—], [Syntheke 委托硬件构造的独立生成器库（Scala 3，经 MLIR/CIRCT 产出 FIRRTL）；Syntheke 对它只假设"参数进、模块出"（@sec-generator-contract）。],
  [Triptych 流水线], [the Triptych pipeline], [构建—协商—例化三阶段（@sec-triptych）。],
)

== 需求映射 <sec-req-map>

#table(
  columns: (auto, 1fr, auto),
  table.header([需求], [回应它的设计], [主要章节]),
  [@req-iteration], [eDSL 规格是唯一事实来源；图从规格导出；三阶段把静态可查错误全部前置到协商期；数量即附着的个数，增删设备只增删附着、不改任何数量声明], [@sec-triptych、@sec-attach、@sec-visualization、@sec-error-semantics],
  [@req-negotiation], [两端指认与双向参数流；逐边结算；错误在协商期报出], [@sec-three-param-kinds–@sec-protocol-object、@sec-propagation],
  [@req-hierarchy], [打洞规划与确定性命名], [@sec-punch-planning–@sec-port-naming],
  [@req-verification], [验证协议、层次路由与层声明], [@ch-verification],
  [@req-ip], [唯一跨越序列化边界的数据是完整参数], [@sec-serialization-boundary、@sec-serialization-list],
  [@req-perf], [协商结果即性能模型，评估先于电路], [@sec-static-eval],
  [@req-interconnect], [逻辑结构在总线上描述一次、始终保留；物理互连（交叉开关／片上网络／直连）按总线承载、可替换；落点显式给出], [@ch-interconnect],
)

== 设计决策索引

#table(
  columns: (auto, 1fr, auto),
  table.header([编号], [决策], [章节]),
  [@dec-pi-required], [协议接口必选。], [@sec-protocol-interface],
  [@dec-pp-local], [协议参数的依赖界限是本模块。], [@sec-settle-pp],
  [@dec-port-naming], [端口名不含哈希，接受线性长名。], [@sec-port-naming],
  [@dec-binding-check], [绑定校验在例化期做结构比对。], [@sec-generator-module],
  [@dec-dv-once], [探针连接必须逐条显式枚举。], [@sec-dv-routing],
  [@dec-dv-ancestor], [探针汇必须是探针源的严格祖先。], [@sec-dv-routing],
  [@dec-layer-merge], [同路径合并，同路径异协议报错。], [@sec-layers],
)

== 开放问题索引

#table(
  columns: (auto, 1fr, auto),
  table.header([编号], [问题], [章节]),
  [@open-multi-round], [显式多轮协商。], [@sec-settle-pp],
  [@open-incr-cache], [跨设计的增量缓存。], [@sec-dedup],
  [@open-typed-binding], [类型级绑定强化。], [@sec-generator-module],
)

== 结语

这份文档从"接口参数是拓扑的全局函数"这一个观察出发，推出了两张图、三个阶段、一条序列化边界；协议给出参数的语义，总线与附着给出连接结构的形状，协商把二者算成一份可序列化、可复现、可审计的结果，例化只是照着这份结果生成电路。每一处形式化——地址图、清单、结构键、层的前缀树并——都服务于同一个目标：*使每一个字节的来源都可以被追溯*。实现应当以本文档为契约；文档未及之处，以#ref(<sec-requirements>)的七条需求为裁决依据。
