#import "../lib.typ": *

= 互连模型 <ch-interconnect>

Xbar、NoC、直连、时钟树和复位树都由生成器模块实现。它们与处理器核、DMA、内存控制器和外设使用相同的模块、节点与 bind：设计源码显式例化模块，模块声明具名的端口节点和端口之间的参数依赖，协商结果形成完整参数，zaozi 生成对应硬件。两段互连之间的桥也是这样的模块（@sec-bridge-boundary）。电源域不虚构 RTL 网格；电源开关、隔离控制等实际逻辑仍由显式生成器模块实现，电源归属与边界由域结构记录。

== 互连生成器的节点 <sec-interconnect-nodes>

一个生成器模块可以声明任意数量的具名 inward 节点和 outward 节点；节点的数量和名字由该模块的用户参数决定，在构造模块时确定（@sec-build）。每个节点表示生成器的一个顶层端口，并恰好参与一次设计 bind。

例如，具有两个上游端口和两个下游端口的 `sysNoc` 声明 `cpuIn`、`dmaIn` 两个 inward 节点以及 `dramOut`、`periphOut` 两个 outward 节点。`periphXbar` 声明一个 inward 节点和三个外设 outward 节点。端口数量由声明直接给出，框架不从连接关系推算。

#图([互连生成器与其它生成器模块使用相同的 bind。图中的每条箭头分别连接源模块的一个 outward 节点与目标模块的一个 inward 节点；`sysNoc`、桥和 `periphXbar` 各自拥有图中所需的多个具名节点。])[
  #syn-diagram(
    spacing: (13mm, 8mm),
    node((0, 0), [CPU], name: <cpu>),
    node((0, 1.2), [DMA], name: <dma>),
    node((1.3, 0.6), [`sysNoc`], name: <noc>, fill: c-fill),
    node((2.6, 0), [DRAM], name: <dram>),
    node((2.6, 1.2), [桥], name: <bridge>),
    node((3.9, 1.2), [`periphXbar`], name: <xbar>, fill: c-fill),
    node((5.2, 0.7), [UART], name: <uart>),
    node((5.2, 1.7), [SPI], name: <spi>),
    edge(<cpu>, <noc>, "-|>", stroke: c-edge),
    edge(<dma>, <noc>, "-|>", stroke: c-edge),
    edge(<noc>, <dram>, "-|>", stroke: c-edge),
    edge(<noc>, <bridge>, "-|>", stroke: c-edge),
    edge(<bridge>, <xbar>, "-|>", stroke: c-edge),
    edge(<xbar>, <uart>, "-|>", stroke: c-edge),
    edge(<xbar>, <spi>, "-|>", stroke: c-edge),
  )
]

NoC 的路由器拓扑、虚通道、物理信道和内部表项资源属于生成器用户参数。外部模块节点描述 NoC 的端口契约；端口到路由器位置的映射和 NoC 内部可能带环的物理拓扑由生成器实现。Xbar 的仲裁结构、时钟树与复位树的分支结构采用同一分工。

== bind：唯一的设计连接原语 <sec-attach>

设计 bind 记录源 `ModuleNodeId`、目标 `ModuleNodeId`、声明它的结构模块、声明顺序和源码位置。源必须是 outward 节点，目标必须是 inward 节点，两端使用同一个协议。bind 的方向也是 `Down` 的传播方向，`Up` 沿同一条边反向传播。

每条 bind 直接产生一条边。outward 节点恰好作为一次 bind 的源，inward 节点恰好作为一次 bind 的目标；未使用的可选端口通过构建期条件代码不声明相应节点。多个同协议端口由多个不同名称的节点表示。中断控制器这类模块有几个 inward 节点由用户参数决定；构造模块和写 bind 用同一个设备列表，数量就不会对不上。

bind 及其两端节点的稳定标识见 @sec-identity。

节点域、约束和域声明不是连接，不使用 bind，也不改变节点的一次绑定计数（@dec-domain-no-wire）。

每条已求解边在源、目标生成器模块上各对应一个硬件端口。生成器端口使用节点声明名；跨层 Dangle 端口的名称和路径由 @sec-port-naming 规定。

=== 拓扑位置与实现约束 <sec-placement>

生成器用户参数可以把具名节点映射到具体实现位置。例如 NoC 参数把 `cpuIn`、`dmaIn` 等节点映射到路由器接入位置，Xbar 参数指定各 inward 节点的仲裁策略，时钟树参数指定 outward 节点所在的分支。时钟树的各 outward 节点以节点域给出自己承载哪个域；同域的多个 tap 引用同一个域 handle，不各自产生定值。完整参数函数从本模块每个节点的已求解边和节点域的定值得到接口形状和协议资源需求，与闭包中的用户参数合成 `FullParam`。

物理结构中未对外暴露的位置只存在于生成器用户参数和内部实现中。协商结束时的生成器能力校验（@sec-generator-parameters）根据已声明节点及其已求解参数检查端口数、接口能力、拓扑位置和资源容量是否超出生成器实现的能力。

#不变量[一个模块节点恰好参与一次设计 bind，并对应一条已求解边和一个生成器端口。]

== 模块内部的同域关系 <sec-same-domain-follow>

生成器模块内部哪些端口同域，由各节点的选择器表达：两个节点选同一个域 handle，或一个节点以 `Follow` 跟随另一个节点，就属于同一域。典型写法是让数据节点跟随本模块的时钟输入：`inward(Axi4)(clk.domain(ClockDomain), clk.domain(ResetDomain), PowerDomain)`。单时钟 Xbar 的全部端口跟随同一个时钟输入；跨时钟域桥的两侧分别跟随两个时钟输入。

节点域描述生成器内部的硬件关系，与组合或时序实现无关。两个节点跟随不同的输入，不表示它们必然属于不同的域，只表示框架不替模块证明二者相同。模块需要拒绝某种域关系时，写一项检查，例如电源边界要求 AON 电源与 CPU 电源不是同一个域：`view.sameIdentity(aon, cpu)` 为真即拒绝。

#决策([模块内部的域关系由选择器与检查表达])[
  模块内部只有“同域”可以由声明直接给出：同一 handle 或 `Follow`。需要“不同域”或数值关系时，由模块写检查；域的衍生关系以 `derive` 的前驱表达。框架只核对声明，不能从声明证明生成器 RTL 内部不存在未申报的 CDC；后者属于 RTL 审计范围。
] <dec-domain-same-groups>

== 物理载体与域 <sec-domain-physical-carrier>

物理时钟和复位保持普通设计协议：源、树与接收端声明端口节点，每个端口照常参与一次 bind，跨层连线仍由 @ch-hierarchy 规划。区别在于协议的 `carries` 列出它承载的域类，例如时钟复位协议承载时钟与复位。

承载协议的节点必须为每个承载的域类给出节点域。outward 节点用 `CarrierOut` 指明传出哪个域：一个域 handle，或本模块另一节点的节点域。inward 节点只能写域类对象，得到 `CarrierIn`：它的域沿本节点唯一的 bind 取自 outward 端。接收模块的数据节点再跟随这个 inward 节点。这样一个数据端口的时钟域，经本模块时钟输入、bind、时钟源的承载声明，解析到唯一的域声明。

域定值只在域结算中产生。承载协议的 `negotiate` 只求解接口形状和物理属性，不选择频率或复位类型。时钟树的多个 outward tap 可以承载同一个域；PLL 一类模块以 `derive` 从输入的节点域导出新域，再由输出 tap 承载。电源也可以有承载协议，例如保持信号同时传送电源与复位。

#决策([物理连线与域结构并存])[
  框架不从节点域推导物理连线。时钟与复位端口仍经显式 bind 连接；承载协议的 inward 端从 outward 端取域，两端由构造同域。域定值只由域结算产生，物理边不重新选择。
] <dec-domain-physical-check>

== 桥与硬件边界 <sec-bridge-boundary>

桥是一个普通生成器模块。典型的单向桥声明一个 inward 节点和一个 outward 节点，并在二者之间声明模块内部参数依赖。outward 节点的 `dFn` 把 inward 侧 `Down` 变换为 outward 侧 `Down`，inward 节点的 `uFn` 把 outward 侧 `Up` 变换为 inward 侧 `Up`；位宽转换、时钟域转换、电源隔离和协议转换电路由桥生成器产生。

桥用节点域表达两侧的域关系。保持某类域的桥让两侧节点跟随同一个输入；跨时钟域桥让两侧分别跟随两个时钟输入，复位仍可跟随同一个输入。两侧跟随不同输入不表示必然不同域；桥如需拒绝两侧同域，写一项检查。电源边界一类桥还从输入的节点域派生新域，供另一侧节点使用。

同协议桥的两个节点使用同一个协议。跨协议桥的 inward 节点使用协议 A，outward 节点使用协议 B；相应参数函数执行 `A.Down => B.Down` 与 `B.Up => A.Up`，两侧 bind 分别调用各自协议的 `negotiate`。

桥的生成器以完整参数独立例化，两侧端口由各自节点的已求解边确定，跨模块连线由层次规划生成，因此桥也可以作为子系统的交付边界。

== 双向传播与生成器参数 <sec-interconnect-flow>

参数传播同时使用两类显式关系：从一个 outward 节点到一个 inward 节点的 bind，以及模块内部从 inward 节点到 outward 节点的参数依赖。传播顺序和规则见 @sec-propagation。原始 bind 关系可以因模块具有彼此独立的端口而形成物理上的回路，只要模块内部没有把这些端口连成参数依赖环。

以内存互连为例，`sysNoc` 的每个下游 outward 节点可以在 `dFn` 中读取所有能够到达该端口的上游 inward 节点 `Down`，计算事务身份扩展、节点编号或内部表项容量；每个上游 inward 节点的 `uFn` 可以读取它能够到达的下游 outward 节点 `Up`，汇聚地址区域、操作能力和位宽约束。不同 outward 节点的可达集合可以不同，依赖关系与函数均由 `sysNoc` 模块显式声明。协议库随协议提供标准的合并函数，例如地址集合求并、事务身份合并；互连模块的 `dFn`、`uFn` 调用这些函数，自己只声明哪些 inward 到达哪些 outward。

所有域和边求解后，框架按生成器模块投影出 `EdgeView` 与 `DomainView`。每个节点条目包含该节点唯一一条边的 `Down`、`Up`、`Edge` 与接口结构；域视图按域 handle 与本模块节点域给出定值。生成器模块的完整参数函数由这两份视图与闭包中的用户参数直接得到 `FullParam`。

#图([参数流。bind 与模块内部参数依赖组成 `Down` DAG（蓝），反向组成 `Up` DAG（红）；两者在每条 bind 上成为 `negotiate` 的输入，已求解边随后进入生成器参数计算。])[
  #syn-diagram(
    spacing: (11mm, 8mm),
    node((0, 0), [边界 outward 节点], name: <s>),
    node((1.25, 0), [模块 inward 节点], name: <i>),
    node((2.5, 0), [模块 outward 节点], name: <o>, fill: c-fill),
    node((3.75, 0), [边界 inward 节点], name: <t>),
    edge(<s>, <i>, "-|>", stroke: c-down, label: text(fill: c-down, size: 8pt)[bind]),
    edge(<i>, <o>, "..>", stroke: c-down, label: text(fill: c-down, size: 8pt)[`dFn`]),
    edge(<o>, <t>, "-|>", stroke: c-down),
    edge(<t>, <o>, "--|>", stroke: c-up, bend: 24deg, label: text(fill: c-up, size: 8pt)[`Up`]),
    edge(<o>, <i>, "--|>", stroke: c-up, bend: -24deg, label: text(fill: c-up, size: 8pt)[`uFn`]),
    edge(<i>, <s>, "--|>", stroke: c-up, bend: 24deg),
    node((5.05, 0), [逐边 `Edge`], name: <e>, fill: rgb("#f2f9f4")),
    node((6.3, 0), [`EdgeView` + `DomainView` \ `FullParam`], name: <p>),
    edge(<t>, <e>, "..>", label: text(size: 8pt)[`negotiate`], label-side: left),
    edge(<e>, <p>, "-|>"),
  )
]

== 双互连示例 <sec-ic-phases>

示例中，CPU 和 DMA 各声明一个 outward 节点；`sysNoc` 声明两个 inward 节点以及分别通向 DRAM、桥的两个 outward 节点；DRAM 声明一个 inward 节点。桥声明 `sysIn` 与 `periphOut`，`periphXbar` 声明一个 inward 节点和 UART、SPI、GPIO 三个 outward 节点，各外设分别声明一个 inward 节点。每对端口由一条普通设计 bind 连接。

*构建。*设计源码显式例化 `sysNoc`、`periphBridge` 与 `periphXbar` 三个生成器模块。NoC 网格、外设 Xbar 仲裁方式和桥的位宽及时钟转换策略是各自的用户参数；节点数量、名称、方向、协议和模块内部参数依赖在 `DesignSpec` 中固定。

*协商。*框架先解析各节点域并结算域定值；随后 `Down` 从 CPU、DMA 经 `sysNoc`、桥和 `periphXbar` 正向传播，`Up` 从 DRAM 与三个外设反向传播。`sysNoc` 与 `periphXbar` 的端口函数按显式依赖聚合地址窗口、可达关系和事务身份。两遍结束后，每条 bind 独立产生一个 `Edge`，各协议的域检查随之收齐，最后统一验证。

*例化。*三个互连相关生成器都从本模块 `EdgeView`、`DomainView` 与用户参数得到 `FullParam`。`sysNoc` 生成与四个具名节点对应的端口及 NoC 内部硬件，`periphXbar` 生成外设端口与仲裁逻辑，桥生成位宽和时钟域转换。

修改互连时，设计者修改相应模块节点、bind、模块内部参数依赖或生成器用户参数；框架重新执行结构校验、域结算、双向传播、逐边求解、域验证和能力校验。
