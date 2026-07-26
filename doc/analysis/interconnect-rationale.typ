#import "@preview/fletcher:0.5.8" as fletcher: diagram, node, edge
#import "@preview/cetz:0.3.4"

#set page(margin: (x: 2.2cm, y: 2.4cm), numbering: "1")
#set text(font: ("Noto Serif CJK SC",), lang: "zh", region: "cn", size: 10.5pt)
#set par(justify: true, leading: 0.82em, spacing: 1.15em)
#set heading(numbering: "1.1")
#show heading.where(level: 1): it => { v(2mm); text(size: 15pt, it); v(1mm) }
#show heading.where(level: 2): it => { v(1mm); text(size: 12pt, it); v(0.5mm) }
#show raw: set text(font: ("JetBrains Mono", "Noto Sans Mono CJK SC"), size: 8.5pt)
#show raw.where(block: true): it => block(width: 100%, fill: luma(247), inset: 8pt, radius: 3pt, it)
#set table(stroke: 0.5pt + luma(170), inset: 5.5pt)
#show table: set text(size: 9pt)
#show ref: it => {
  let el = it.element
  if el != none and el.func() == heading {
    link(el.location(), [§#counter(heading).at(el.location()).map(str).join(".")])
  } else { it }
}

#let c-down = rgb("#1f6feb")
#let c-up = rgb("#c0392b")
#let c-edge = rgb("#1a7f37")
#let c-hier = rgb("#9a6700")
#let c-phys = rgb("#8250df")
#let c-fill = luma(247)
#let syn-diagram(..args) = text(size: 9pt, diagram(
  node-stroke: 0.7pt, node-corner-radius: 2.5pt, node-inset: 6.5pt,
  edge-stroke: 0.7pt, spacing: (13mm, 9mm), ..args,
))
#let syn-canvas(body) = text(size: 9pt, cetz.canvas(body))
#let 图(caption, body) = figure(align(center, body), caption: caption)
#show figure.caption: set text(size: 9pt, fill: luma(80))

#align(center)[
  #text(size: 19pt, weight: "bold")[连接模型的重构]
  #v(2mm)
  #text(size: 12pt)[为什么以 resolveStar 求解基数的绑定不成立，显式化 NexusNode 的分层实现为什么成立]
  #v(2mm)
  #text(size: 9.5pt, fill: luma(90))[Syntheke 设计讨论记录 · 2026-07-25 · 理由文档，非规范描述]
]
#v(4mm)

*本文性质。*本文是一次设计讨论的理由记录:从"互连应当可替换"这一动机出发,论证原有的、以 resolveStar 求解连接基数为核心的绑定方式,为何在真实互连(交叉开关与片上网络)面前不成立;以及讨论收敛出的模型——把 NexusNode 显式化为命名的总线,逻辑连通与物理实现两层分离——为何成立。本文属于分析文档,直接点名 diplomacy、resolveStar、NexusNode、交叉开关(xbar)、片上网络(NoC)等既有构造以作对照——这些命名纪律上的约束只适用于设计文档系列,不适用于本文。本文记录*论证*,不替代设计文档对最终模型的规范描述。引用既有代码处标 file:line。

= 动机:互连应当可替换，而这暴露了一个隐含假设 <sec-motiv>

一颗 SoC 的迭代之慢,根子在"接口参数是整张连接图的全局函数"——改一处,全图重对。设计文档第一章已论证这一点。本文的起点是它更尖锐的一个侧面:*承载这些连接的物理互连,本身应当可替换*。同一套"谁与谁通信"的逻辑,可以用一个交叉开关(xbar)实现,也可以用一张片上网络(NoC)实现;在两者之间切换,不应当要求重写连接结构。

#图([同一逻辑连通(三个发起者到达两个目的地)的两种物理实现。逻辑意图相同,物理形态迥异——旧模型无法让二者共享同一份逻辑描述。])[
  #syn-diagram(
    spacing: (10mm, 6mm),
    node((0, 0.5), [逻辑意图\ 三主 → 二从], name: <l>, fill: c-fill),
    node((1.4, -0.1), [xbar], name: <x>, stroke: c-edge),
    node((1.4, 1.1), [NoC\ (router 网)], name: <n>, stroke: c-phys),
    edge(<l>, <x>, "-|>", label: text(size: 8pt)[实现之一]),
    edge(<l>, <n>, "-|>", label: text(size: 8pt)[实现之二], label-side: right),
  )
]

把"可替换"当作要求,立刻逼出一个问题:逻辑连通性与物理实现,在旧模型里是不是分得开?答案是否定的——而使它们分不开的,正是 resolveStar。真正把这个隐含假设顶穿的是 NoC:它把"谁跟谁通信"和"物理上铺了几条线"彻底拆成两张不同的图。下文先复述旧模型,再逐层论证它为何不成立。

= 原有模型:resolveStar 把逻辑拓扑一步算成物理连接数 <sec-old>

旧模型(diplomacy)用四个连接算子书写拓扑:`:=`(恰一条边)、`:*=`(边数取汇侧解)、`:=*`(边数取源侧解)、`:*=*`(弹性,方向由连通分量判定)。每条连接携带一个基数意图;`resolveStar` 沿图结构递归求解出每个节点两侧的边数。

关键在于:*这个解出来的边数,直接就是物理端口数*。交叉开关接了 $N$ 个发起者,resolveStar 解出 $N$,这 $N$ 就是它例化时的端口数。于是"逻辑拓扑 → 物理连接数"是*一步算出来的*:

#图([旧模型的一步融合。连接算子 + resolveStar 把"接了几个"直接解成"物理几个口",逻辑与物理在同一步、同一张图上完成。])[
  #syn-diagram(
    spacing: (22mm, 7mm),
    node((0, 0), [三个发起者\ (`:*=`)], name: <m>),
    node((1, 0), [xbar\ 节点], name: <x>, fill: rgb("#fdf3d7")),
    node((2, 0), [解出 3 条边\ = 3 个物理端口], name: <p>, stroke: c-edge),
    edge(<m>, <x>, "-|>"),
    edge(<x>, <p>, "..>", label: text(size: 8pt)[resolveStar]),
  )
]

对交叉开关,这看起来天经地义、也确实好用。问题在于它把三件事悄悄焊成了一件:*逻辑上谁连谁*、*物理上是什么互连*、*互连有几个口*。下一章论证:这个焊接在三个层面上不成立。

= 为什么这套绑定不成立 <sec-why-fail>

论证分三层,层层加深:算子服务的其实是实现而非逻辑;这套融合在 NoC 上直接破裂;而且物理实现会泄漏进逻辑参数。

== 算子服务的是"实现"，不是"逻辑" <sec-fail-role>

对 rocket-chip、federation、chipyard 三个真实仓库的实证调查(另见基数机制调查报告)给出三条一致事实:

- *数字从不在算子里*。"到底几条边"这个数字的来源只有四种:某个源/汇节点的参数表长度、宿主语言循环的次数、一个配置键、或从图中读回(如 PLIC 的 `nDevices = intnode.edges.in.map(_.source.num).sum`)。算子本身至多编码平凡的"1",从不含 $N$。
- *多重性就是集合大小*。装配层的连接数,永远是"接了几个"= 参数表长度或名单长度。同一个"N 条内存通道"问题,rocket-chip 用显式 `for` 循环 + 纯 `:=` 完成(`subsystem/Ports.scala:79`,全文件零星号),chipyard 却用一条 `:*=` 链(`System.scala:86`)——两种写法跨两个仓库并存,可见星号只是"省得枚举"的便利,不是逻辑上的必需。
- *弹性算子只活在框架层*。`:*=*` 无一例外落在框架的可复用胶水里(跨时钟域 helper、总线挂接占位):federation 76/76、rocket-chip 103/103、chipyard 0,产品与集成代码零裸用。

三条里第三条最硬:弹性算子只在框架胶水层出现,说明它服务的是"写胶水时不知道自己会被接在几条边上"这件事——一段跨时钟域适配器用星号"接多少算多少"。合起来看,四算子 + resolveStar 的主要作用,是让一个数*穿过对它无知的可复用中间件*,这是*实现层*的搬运机制。这里要划清一条界,免得把方向也一并否定:连接的*方向*(谁是生产者、边指向谁)确实是逻辑信息,它作为一条朴素的有向附着保留下来;被否定的不是方向,而是*基数的方向*(哪一侧决定边数)——那是每条连接上人工做的算子选择,服务的是实现层的穿线,不是逻辑连通性的描述。逻辑层真正需要的,只是"这组发起者到达这组目的地",数量是集合大小,一个有向附着足矣。

== 这套融合在 NoC 上直接破裂 <sec-fail-noc>

resolveStar 隐含一个假设:*物理连接数 = 逻辑连接数*。对交叉开关这成立——$N$ 个发起者恰好对应 $N$ 个物理端口,逻辑扇入与物理端口同构。NoC 打破这个同构。

考察 constellation(一个基于 diplomacy 的 NoC 生成器)的实际结构:它把本该由 resolveStar 统一处理的东西,拆成三层各自数数,而且*绕开 resolveStar 去表达物理网络*。

#图([NoC 把一个"边数"拆成三个互相独立的边数。resolveStar 只能碰最上一个;物理信道数由拓扑函数闭式给出,与逻辑流数无关。])[
  #syn-canvas({
    import cetz.draw: *
    let box(x, y, w, t, col) = { rect((x, y), (x + w, y + 0.7), stroke: 0.7pt + col, radius: 0.06); content((x + w/2, y + 0.35), text(size: 8.5pt, t)) }
    box(0, 2, 3.4, [逻辑端点数], c-down);   content((5.4, 2.35), text(size: 8pt)[resolveStar 管这个])
    box(0, 1, 3.4, [逻辑流数 = 主×从], c-edge); content((5.9, 1.35), text(size: 8pt)[宿主代码笛卡尔积])
    box(0, 0, 3.4, [物理信道数], c-phys);   content((6.0, 0.35), text(size: 8pt)[拓扑函数 `topo(i,j)`])
    line((3.6, 2.35), (3.6, 0.35), stroke: (dash: "dotted"))
    content((1.7, -0.55), text(size: 8pt, fill: luma(90))[三者互相独立,一个 resolveStar 数字覆盖不了])
  })
]

具体证据:

- *物理信道数是拓扑的闭式函数,与逻辑无关*。`PhysicalTopology.topo(src, dst): Boolean` 是一个纯函数(mesh/torus 各是一个数学式);物理信道由 `Seq.tabulate(nNodes, nNodes){ (i,j) => if (topo(i,j)) Some(...) else None }` 生成(constellation `noc/Parameters.scala:82`)。一张 $4×4$ mesh 有多少条链路,与有多少主从通信毫无关系。
- *逻辑流是另一张图,在宿主代码里枚举*。`flows = edgesIn.zipWithIndex.map { edgeIn => edgesOut.zipWithIndex.map { edgeOut => ... }}`(`protocol/Tilelink.scala:255`)——按地址可达性,每一对能互达的主从一条流(上界 $O("主"×"从")$),与物理信道数无关。
- *两层之间靠路由关联,不靠计数*。ingress/egress 把逻辑端点对到物理节点(`UserIngressParams(destId)`);routing relation 把流铺到物理信道上,多条流共享一条信道。

结论:resolveStar 解出的边数,只是三个数里最上那一个(逻辑端点数);物理网络的规模由拓扑函数独立给出,resolveStar 对它无能为力。所以 resolveStar *根本不是"实现拓扑"的通用模型*——它只在交叉开关这种"逻辑 = 物理"的退化情形下,看起来像在实现拓扑。一旦实现换成 NoC,它就只剩边界处数一数逻辑端点,内部结构全由另一套机制承担。

== 物理实现会泄漏进逻辑参数 <sec-fail-leak>

前两层说的是"数量",这一层更致命:旧模型里,*物理互连的选择会改变逻辑参数,进而改变 IP 的硬件*。

用一个具体例子。一个 CPU,一个 RAM(`0x8000_0000` 起),一个 UART(`0x1000_0000` 起),同挂一条总线。CPU 要生成地址译码器,必须拿到全部可达从设备的*聚合地址表* `{RAM, UART}`——这是 RAM 与 UART 地址段的并集。RAM 不知道 UART 存在,反之亦然;于是必须有个中间人收集、合并、发回给 CPU。旧模型里这个中间人就是交叉开关:它的 `uFn` 吃进 `[RAM 地址段, UART 地址段]`、求并、广播回 CPU。

问题在于,这个中间函数*一身二任*,把两件性质完全不同的东西混在一起算:

#table(
  columns: (auto, 1fr),
  table.header([混在 `uFn` 里的成分], [性质]),
  [地址并集、echo 字段并集、`minLatency` 取 min], [*fabric 无关*的纯聚合——换任何互连都一样],
  [`fifoId`(事务顺序)], [*fabric 特有*的事实——交叉开关与 NoC 在此分歧],
)

铁证就在 `fifoId` 上:交叉开关*保留并重编号* `fifoId`(rocket-chip `tilelink/Xbar.scala:62-64`),而 NoC *直接抹掉*它——`fifoId = None`,注释原话"TileLink NoC does not preserve FIFO-ness, masters to this NoC should instantiate FIFOFixers"(constellation `protocol/Tilelink.scala:502-503`)。

#图([同一个 CPU、同一条逻辑总线,底下换互连,CPU 拿到的参数与硬件都变。这就是"绑定不成立"最硬的表现。])[
  #syn-diagram(
    spacing: (16mm, 5mm),
    node((0, 0.5), [CPU], name: <c>),
    node((1, 0), [底下是 xbar], name: <x>, stroke: c-edge),
    node((1, 1), [底下是 NoC], name: <n>, stroke: c-phys),
    node((2, 0), [`fifoId` 保留\ → 无需重排器], name: <px>, fill: c-fill),
    node((2, 1), [`fifoId = None`\ → CPU 须加 FIFOFixer], name: <pn>, fill: c-fill),
    edge(<c>, <x>, "-|>"), edge(<c>, <n>, "-|>"),
    edge(<x>, <px>, "..>"), edge(<n>, <pn>, "..>"),
  )
]

后果直接:`fifoId` 一旦变成 `None`,依赖顺序的发起者就必须自己插入一个 FIFOFixer 硬件(NoC 的注释原话已明说);它的上行参数、以及据此合成的完整参数都随之改变。所以旧模型里"换互连"*从来不是免费的*,它悄悄改了 IP 的硬件——因为物理实现被焊进了逻辑参数计算。

== 小结:一个焊接，三处崩裂 <sec-fail-sum>

resolveStar 的绑定把三件本可分开的事焊成一件:逻辑连通、物理实现、参数计算。三处崩裂对应三层:算子把实现机制冒充成逻辑语言(#ref(<sec-fail-role>));"逻辑 = 物理"的假设在 NoC 上破裂(#ref(<sec-fail-noc>));物理实现泄漏进逻辑参数,使"换互连"改动 IP 硬件(#ref(<sec-fail-leak>))。想"换实现"或"用 NoC",崩的正是这个焊接。

= 新模型:显式的总线，两层分离 <sec-new>

讨论收敛出的模型,核心是把上面焊死的三件事拆开;而拆开靠的只是一个动作——*把旧模型里隐式的 NexusNode 显式提前,成为命名的总线*(#ref(<sec-new-model>) 展开)。逻辑连通在总线上协商,物理实现退到总线的下游。

== 两层:逻辑连通 vs 物理实现 <sec-new-two>

- *逻辑连通层*:*总线*。总线是那个显式的 NexusNode(#ref(<sec-new-model>)),承载"谁与谁通信"以及地址、顺序、域等参数的协商。管*正确性*,与物理互连无关。
- *物理实现层*:*fabric*(交叉开关 / NoC / 直连),承载某一条总线。管 *PPA*(功耗、性能、面积)。

两层单向:fabric 是逻辑层的下游——消费总线协商出的结果,只做 yes/no 的能力校验,不回改任何逻辑参数。

== 总线:被显式提前的 NexusNode <sec-new-model>

*resolveStar 与四个基数算子唯一的活儿,是在例化期解出一个隐式 NexusNode 到底有几个口。Syntheke 把 NexusNode 显式提前,成为一个命名的总线——nexus 一旦显式,它的口数就平凡地等于"附着了几个成员",resolveStar 与四算子无事可做,一并消失,只剩一个 attach 原语;diplomacy 协商的其余一切,原样保留。*

先支撑第一句。旧模型里,交叉开关是一个 NexusNode:没有人直接告诉它端口数,端口数散落在各处的连接语句里(#ref(<sec-old>))。于是每条连接都要用算子标注"边数由哪侧决定",resolveStar 再沿图递归,把数字从声明处搬运到未知处。而图上真正未知的只有一类位置——隐式 nexus 的口数:源与汇的数量本来就写在各自的参数表里(#ref(<sec-fail-role>) 的第一条事实:数字从不在算子里,来源是参数表、循环、配置键);胶水层的弹性算子,也只是让这个数字穿过无知的中间件、最终抵达 nexus(第三条事实)。整套星号机制,就是隐式 nexus 的口数递送系统。

再看第二句为什么是解法。总线先于一切连接被声明、被命名;成员随后逐个附着上来。"这条总线有几个口"于是不再是方程的解,而是"写了几条附着"这一可数事实。#ref(<sec-fail-role>) 末尾说,逻辑层真正需要的只是"这组发起者到达这组目的地"——总线正是那个"组"的实名化。至此,#ref(<sec-fail-role>) 的批判与这里的解法接成一条线:算子与 resolveStar 服务的是"隐式 nexus"这个实现机制;把 nexus 显式化,它们就失去了服务对象,退场不是删减功能,而是前提消失。

所以这不是发明一套新连接模型,而是对旧模型的一次定位移动:*把 diplomacy 的 NexusNode 从"隐式的、例化期才解出口数的",提升为"显式的、命名的、口数由附着直接给定的"*。nexus 上的参数聚合、下行/上行的双向传播、图必须无环——这些照旧(#ref(<sec-new-param>))。

逻辑层由此收缩到极小:一种容器、一种节点、一个原语。

- *总线*:显式的 NexusNode。命名、先声明,承载协商(#ref(<sec-new-two>) 的逻辑层);本身不产硬件。
- *设备(agent)*:吃参数、产硬件的 generator,以成员身份附着到总线上。
- *attach*:唯一的连接原语 `<-`,把一个设备的节点附着到一条总线的落点上。方向不写进算子——设备的节点自己声明是发起者还是响应者(协议的两端指认),`<-` 只说"谁承载谁"。数量只是附着的条数:*没有基数算子、没有 resolveStar、没有待解的端口数*(#ref(<sec-fail-role>) 保留下来的方向信息由节点角色承载,不再需要每条边上人工挑算子)。更进一步:连接与硬件是*同一个节点*的两面——一个节点附上来,就接上了它的 IO,不必再有一层"节点 → 接口字段"的硬件绑定。旧模型里那层映射之所以非平凡、要单独存在,恰恰也是因为基数(一个节点可展开成多个端口);基数一去,它与四算子一同退场。

#图([设备经唯一的 attach 原语 `<-` 附着到总线,总线即显式的 nexus。方向由节点角色给出(发起者、响应者),算子不带方向;多重性 = 附着条数(此处发起侧 2、响应侧 2)。总线由下游的 fabric 兑现——fabric 消费清单,不在成员之列。])[
  #syn-diagram(
    spacing: (16mm, 6mm),
    node((0, 0), [CPU], name: <a>),
    node((0, 1), [DMA], name: <b>),
    node((1, 0.5), [总线\ (显式 nexus)], name: <bus>, stroke: c-down, fill: c-fill),
    node((2, 0), [RAM], name: <r>),
    node((2, 1), [UART], name: <u>),
    node((1, 1.9), [fabric\ (xbar / NoC / 直连)], name: <f>, stroke: c-phys),
    edge(<a>, <bus>, "-|>"),
    edge(<b>, <bus>, "-|>"),
    edge(<bus>, <r>, "-|>"),
    edge(<bus>, <u>, "-|>"),
    edge(<bus>, <f>, "..>", label: text(size: 8pt)[清单], label-side: left),
  )
]

总线永远是*平的*:附着上来的都是叶子设备,*总线不是别的总线的成员*——没有子总线、没有总线树、没有任何嵌套。要在系统里造一道边界——独立交付的子系统、时钟/电源域的改变、想换一种 fabric——就放一个*桥*:一个同时附着到两条总线的设备,在一条总线上是响应者、在另一条上是发起者;地址重映射、位宽变换、跨时钟域、协议转换,都在桥内完成。系统级拓扑因此是"若干条平总线 + 桥":每条总线内部无结构(结构在兑现它的 fabric 里),总线之间无嵌套(衔接在桥里)。层次来自模块,不来自总线。

#图([边界靠桥。桥是同时附着到两条总线的设备:在总线 A 上是响应者,在总线 B 上是发起者;地址重映射、位宽变换、跨时钟域、协议转换都在桥内完成。两条总线平级,各自被各自的 fabric 兑现——总线之间不嵌套,层次来自模块。])[
  #syn-diagram(
    spacing: (13mm, 6mm),
    node((0, 0), [CPU], name: <c>),
    node((1, 0), [总线 A], name: <ba>, stroke: c-down, fill: c-fill),
    node((2, 0), [桥], name: <br>, stroke: c-hier),
    node((3, 0), [总线 B], name: <bb>, stroke: c-down, fill: c-fill),
    node((4, 0), [RAM], name: <r>),
    node((1, 1), [xbar], name: <fa>, stroke: c-phys),
    node((3, 1), [NoC], name: <fb>, stroke: c-phys),
    edge(<c>, <ba>, "-|>"), edge(<ba>, <br>, "-|>"), edge(<br>, <bb>, "-|>"), edge(<bb>, <r>, "-|>"),
    edge(<ba>, <fa>, "..>"), edge(<bb>, <fb>, "..>"),
  )
]

最后,调查报告里那些做真正基数算术的节点去哪了?各归其位。按 `nBanks` 分 bank 的 BankBinder:分 bank 是内存 agent 自身的配置——一个四 bank 内存就是一个占四个附着点的 agent(附着四次,与它有四条边一样自然),`nBanks` 是它的用户参数,与"总线上附着了几个成员"无关;按地址位把请求分到哪个 bank,是兑现这条总线的 fabric 内部的路由。固定 1 入 2 出、按地址段改写的 AddressAdjuster,就是上一段的桥——同时附着在本地与远程两侧、在内部做地址改写的设备。按比例分流的 Jbar 仲裁,是 fabric 内部的事。"没有算子"指的是逻辑层没有;这些算术没有消失,只是回到了它们本该在的地方——agent 的配置、桥、或 fabric 的内部。

== 参数计算在总线，与 fabric 无关 <sec-new-param>

总线*就是*做双向参数计算的地方——这是 diplomacy 协商的核心设计,也正是 #ref(<sec-new-model>) 所说"原样保留"的部分:各成员声明各自的下行/上行参数,总线按协议把地址并起来、上下传播、逐边结算,并据此定出成员间的可达关系(谁到谁);这些结果——成员、各自的参数、以及可达关系——一并构成交给 fabric 的那份*清单*。*算完参数,才例化硬件*。这套计算由*协议*驱动(地址怎么并是总线协议的事),完全与 xbar/NoC 无关。

总线是 nexus,参数确实流经它——那会不会绕出环来?不会,而且是构造上的不会:每个成员的声明来自它*自身的用户参数*,自给自足、不依赖图;总线做的是单向的纯聚合——收成员的声明、按协议合并、把对侧的聚合回灌给每个成员(发起者收到响应侧的并集,反之亦然,正如 #ref(<sec-fail-leak>) 里 CPU 收到 `{RAM, UART}`)。声明 → 聚合 → 回灌,每步只朝一个方向,没有任何参数是自己下游结果的函数,依赖图恒为 DAG。无环是构造保证,不是运行期检查。

那件会泄漏的 fabric 特有事实(顺序),不再去问 fabric,而是由设计者在总线上*明写一条契约声明*("本总线保证顺序 / 不保证顺序")。它在协商期即已知,同样与 fabric 无关。fabric 彻底留在参数计算之外:参数算完后,它只做一个 yes/no 能力校验("我这个 NoC 扛得动这条总线的契约吗"),扛不动就报错,绝不回改参数。

阶段顺序本身就保证了这一点:fabric 是例化期(Elaborate)才实例化的,而参数计算在协商期(Negotiate)。fabric 在参数算完之前根本不存在,自然挤不进去。

== 级别无关:协议级的递归自用 <sec-new-recur>

整台引擎对协议 $P$ 全称量化($P$ 定义下行/上行/边参数、结算、投影;总线事务、NoC flit、供电都是 $P$ 的实例)。于是同一套"generator + attach + 总线 + 协商"在每个协议层级各跑一遍。fabric 是层级之间的换能器:它把一条总线,用*下一层协议*的图实现出来。

先划清一条界,免得与 #ref(<sec-new-model>) 刚删掉的东西混淆:这里的递归是*协议级*的,不是总线级的。总线之间不嵌套——一条总线永远不是另一条总线的成员,总线间的边界靠桥(#ref(<sec-new-model>));能嵌套的是*层级*:一个 fabric 的内部可以下沉到更低的协议层,在那里又是同一套引擎的一次完整应用——又一批 agent、又一些总线、又一轮协商。外层看 fabric 是一个 generator;内层看它是一整张下层协议的图。

#图([三阶段流水线(构建 → 协商 → 例化)的自我嵌套。外层在事务级算好参数,例化一个 fabric 时,触发一个下沉一层协议的、嵌套的同一套三阶段;两层之间的接口就是那份清单。下沉的是协议层级,不是总线——总线之间不嵌套。])[
  #syn-diagram(
    spacing: (7mm, 8mm),
    node((0, 0), [构建], name: <b1>), node((1, 0), [协商\ (算总线参数)], name: <n1>), node((2, 0), [例化], name: <e1>),
    node((1.4, 1), [清单], name: <m>, shape: fletcher.shapes.pill, fill: c-fill),
    node((0.6, 2), [构建], name: <b2>), node((1.6, 2), [协商\ (flit 级)], name: <n2>), node((2.6, 2), [例化], name: <e2>),
    edge(<b1>, <n1>, "-|>"), edge(<n1>, <e1>, "-|>"),
    edge(<e1>, <m>, "-|>", label: text(size: 8pt)[产出]),
    edge(<m>, <b2>, "-|>", label: text(size: 8pt)[即内层输入]),
    edge(<b2>, <n2>, "-|>"), edge(<n2>, <e2>, "-|>"),
  )
]

NoC 因为要把事务打包成 flit,内部是一张 *flit 级*的图(router = agent,channel = 连接);交叉开关不打包、不换协议,内部要么在 *同一 TL 级* 分解成译码器/仲裁器,要么干脆是个叶子生成器直接吐 RTL。递归到哪个层级、乃至递不递归,是每个 fabric 库自己的事——框架一视同仁,fabric 就是个 generator。

全称量化顺带覆盖了两个常被特判的域:时钟与电源。它们本身就是协议——有两端的指认(谁供给、谁消费),有可协商的参数;一个时钟域或电源域,就是一条以该协议承载的总线。域归属因此是协商的结果,不是贴在模块上的标签。

= 为什么新模型可以 <sec-new-works>

先给出这次分离带来的总收益,再逐条回应上一章的三处崩裂。

*总收益:互连可替换。*成员对总线的附着只写两件事:在哪条总线上、什么方向;fabric 是总线下游的一次指派,不在附着之列。换互连 = 给同一条总线指派另一个 fabric(把兑现它的交叉开关换成 NoC),所有成员的附着一字不动。要在同一系统里混用两种 fabric,就各成一条总线、以桥相接(#ref(<sec-new-model>)),改动局部于那座桥。契约不变的替换,参数不变;越契约的替换(如把声明保序的总线改成不保序、以便换上 NoC),是一次显式的逻辑改动(改总线契约)+ 重协商——这恰恰是对的:保序总线与不保序总线本就是两个设计,旧系统假装能免费互换,是它在骗人。

*回应 #ref(<sec-fail-role>)——算子服务实现:显式化 nexus,算子失去服务对象。*四算子与 resolveStar 的唯一职责,是解出隐式 NexusNode 的口数(#ref(<sec-new-model>));总线把 nexus 显式化,口数 = 附着数,求解与标注一并退场。逻辑层只剩总线 / agent / attach,多重性是集合大小,没有把实现机制冒充成逻辑语言的算子;方向作为节点角色保留、不写进算子;基数的方向不复存在。richness 全在库:协议库、agent 库、fabric 库。

*回应 #ref(<sec-fail-noc>)——NoC 破裂:三个边数各归其位。*NoC 与 xbar 都只是吃清单的 fabric。三个"边数"各有其家:逻辑端点数 = 附着集合的大小(总线上);逻辑*可达关系*(谁到谁)在总线上由协议算好、进清单——这是逻辑事实,与 fabric 无关(旧模型里 constellation 只好在自己宿主代码里算它,因为当时没有总线级的可达机制);NoC 内部的 flow 表,是它从清单里的可达关系*自己派生*的;物理信道数 = fabric 自己的配置(NoC 的拓扑函数)。

*回应 #ref(<sec-fail-leak>)——参数泄漏:泄漏通道从结构上堵死。*参数计算留在总线、由协议驱动、与 fabric 无关;fabric 在例化期才实例化,天然在参数计算下游,挤不进来。把上面那个 `fifoId` 的例子在新模型里走一遍——先说破关键:*一个发起者要不要 FIFOFixer,由"总线契约(本总线保不保证顺序)"加"发起者自身要不要顺序"共同决定,两者都在逻辑层、与具体 fabric 无关*;旧模型的病根,恰恰是这件事被 fabric 私自决定(xbar 给顺序、NoC 不给),于是换 fabric 就改硬件。新模型里:总线声明"保证顺序",意即要求 fabric 兜底顺序,此时指派一个保证不了的 NoC,能力校验*直接报错*——你只能用交叉开关这类保序 fabric,谁都无需 FIFOFixer。总线声明"不保证顺序",意即设计者主动放弃让 fabric 兜底、以换取选型自由;此时真正需要顺序的发起者自带一个 FIFOFixer——一个显式的双端设备,按 #ref(<sec-new-model>) 桥的方式垫在该发起者与总线之间——在逻辑层就接了进来。不论底下最终指派的是交叉开关(fixer 冗余但无害)还是 NoC(fixer 必需),这个发起者拿到的参数与硬件完全一样。`fifoId` 不再随 fabric 变,因为"谁需要 FIFOFixer"已由总线契约在协商期定死。

一句话概括这次重构:*从"一张图 + resolveStar 把逻辑拓扑一步算成物理连接数",转为"把 NexusNode 显式化为总线——逻辑连通性在总线上协商,再由可替换的 fabric 在下一层协议里实现"。*前者把逻辑、物理、参数焊成一件,在换实现与用 NoC 时崩裂;后者把三件事拆开,让"换互连不动逻辑""NoC 与 xbar 平等""参数不被物理污染"同时成立。

= 结论 <sec-concl>

resolveStar 的绑定不成立,不是因为它算错了,而是因为它*把三件本应分层的事焊在了一起*:逻辑上谁连谁、物理上是什么互连、互连有几个口与什么参数。这个焊接对交叉开关这一退化情形恰好无害,于是长期看起来成立;真实互连(NoC)与真实需求(可替换)一到,焊缝就崩。

新模型成立,靠的不是发明一套新机制,而是一次定位移动:*把 diplomacy 的 NexusNode 从"隐式的、例化期才求解的",显式提前为"命名的总线"*。nexus 显式了,口数平凡地等于附着数——多重性回落为集合大小,四算子与 resolveStar 失去唯一的职责而退场;协商的其余部分(聚合、双向传播、构造性无环)原样保留。焊缝随之拆成一条单向的两层边界:逻辑连通性在总线上、由协议驱动、与物理无关地协商出参数;物理实现是一个下游的、可替换的 fabric,消费清单、自定尺寸、只做能力校验。而整台引擎对协议全称量化,使 NoC 的内部、交叉开关的内部、乃至时钟树与供电网,都只是同一套机制在下一个协议层级的又一次应用。
