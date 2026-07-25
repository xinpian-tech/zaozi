#set page(margin: (x: 2.2cm, y: 2.4cm), numbering: "1")
#set text(font: ("Noto Serif CJK SC",), lang: "zh", region: "cn", size: 10.5pt)
#set par(justify: true, leading: 0.82em, spacing: 1.1em)
#set heading(numbering: "1.1")
#show raw: set text(font: ("JetBrains Mono", "Noto Sans Mono CJK SC"), size: 8.5pt)
#show raw.where(block: true): it => block(width: 100%, fill: luma(247), inset: 8pt, radius: 3pt, it)
#set table(stroke: 0.5pt + luma(170), inset: 5.5pt)
#show table: set text(size: 9pt)
#show ref: it => {
  let el = it.element
  if el != none and el.func() == heading {
    let nums = counter(heading).at(el.location())
    if el.level == 1 { link(el.location(), [第 #nums.first() 章]) }
    else { link(el.location(), [§#nums.map(str).join(".")]) }
  } else { it }
}

#align(center)[
  #text(size: 20pt, weight: "bold")[边数机制实证调查]
  #v(2mm)
  #text(size: 12pt)[diplomacy 连接基数系统在 rocket-chip、federation、chipyard 中的真实使用]
  #v(2mm)
  #text(size: 9.5pt, fill: luma(90))[调查执行：2026-07-25 · 三个只读调查代理 · 证据文档，非设计结论]
]
#v(4mm)

*本文档的性质与边界。*本文是为 Syntheke 设计讨论（"连接的边数是否可由供给-消费参数推导"）准备的证据库：三个只读调查分别覆盖 rocket-chip、federation、chipyard 三个仓库，只收集事实与出处，不评价任何设计假设。设计结论不在本文档；本文档与 `doc/design/` 设计文档系列相互独立——设计系列不提及既有框架的命名纪律不适用于本文。文中路径均相对各仓库根目录；行号对应调查当日的仓库快照。

= 调查对象与方法 <ch-method>

#table(
  columns: (auto, 1fr, auto),
  table.header([仓库], [形态], [Scala 文件数]),
  [rocket-chip], [框架发源地。调查范围 `src/main/scala`（317 文件，54191 行）；算子语义以其 `dependencies/diplomacy` 为准，不计入统计。], [916（全仓库）],
  [federation], [SiFive 风格工业 SoC/IP monorepo（Freedom 产品家族，2020 年前后快照）。diplomacy 框架以整份拷贝内联于 `rocketchip/`；`src/` 为芯片集成层（约 30 个外设集成目录、多个产品 SKU 目录）；`ipdelivery/` 为例化之后的交付打包，不含 Scala。], [2091],
  [chipyard], [伯克利 SoC 集成框架。本地副本不含子模块；实存源码为 `generators/chipyard`（73）、`generators/firechip`（41）、`fpga/src`（21）等共 177 文件，即 chipyard 自己书写的集成/胶合层。], [177],
)

方法要点与局限：星号算子（`:*=`、`:=*`、`:*=*`）为唯一词法，计数精确（已处理子串互斥）；`:=` 与 chisel 硬件赋值同符号，diplomatic 用量只能启发式估计（作用域分类器 + 节点命名约定 + 人工抽样），各仓库报告均注明估计区间与漏判来源。四类信息在各仓库内做了穷尽而非抽样：`:*=*` 全部出现点、JunctionNode 全部使用点、CustomNode/`resolveStar` 覆写全部实现、对框架的本地封装。

= rocket-chip 调查结果 <ch-rocket>

== 算子清点 <sec-rc-counts>

#table(
  columns: (auto, auto, auto, auto, 1fr),
  table.header([算子], [代码行], [出现次数], [文件数], [备注]),
  [`:*=*`], [33], [103], [6], [无注释/字符串假阳性],
  [`:*=`], [30], [38], [16], [另有 2 行为 `require` 文案中的算子字样],
  [`:=*`], [29], [35], [14], [另有 2 行文案 + 1 行注释],
)

目录分布（行数）：subsystem 40、tilelink 21、tile 12、interrupts 10、devices 7、amba 6（全部为 flex 跨域 helper）、groundtest 1；`prci/` 本身为零——时钟硬件原语的定义处不写算子，跨域的图连接发生在 `subsystem/`。diplomatic `:=` 估计约 350–450 次（占全部干净 `:=` 的 6%–8%），集中于拓扑装配代码；`rocket/` 核心流水线的一千余处 `:=` 全为 chisel 赋值。

== 边数的四种来源 <sec-rc-origins>

*(a) 源/汇参数表长度。*`subsystem/Ports.scala:61-77`：`WithNMemoryChannels(n)` 写入 `ExtMem.nMemoryChannels` → `Seq.tabulate(nMemoryChannels)` 构造参数 → `AXI4SlaveNode` 边数即该 Seq 长度。同型：`subsystem/InterruptBus.scala:44-45`（`NExtTopInterrupts` → `IntSourcePortSimple(num=...)`）。

*(b) 宿主语言循环。*`subsystem/HasTiles.scala:108`：`(0 until nTotalTiles).map { i => tileHartIdNodes(i) :*= tileHartIdNexusNode }`；`tile/BaseTile.scala:317-323`：`foldRight(xbarNode)(_ :*= _)`，链长由一个布尔比较决定；`tilelink/Buffer.scala:79-88`：`Seq.fill(depth){...}.reduceLeftOption(_ :*=* _)`，`depth` 是普通 `Int`。

*(c) 配置键同时驱动参数与连接数。*`nBanks` 全链路：`WithNBanks(n)`（`subsystem/Configs.scala:130-132`）→ `SubsystemBankedCoherenceKey` → `BankedCoherenceParams.scala:74` → `BankBinder.scala:75-78`（换算为地址掩码）→ `BankBinder.scala:17-23`（`resolveStar` 内 `ports = ids.size` 决定星号展开数）。同型：`p(BuildRoCC)`（`tile/LazyRoCC.scala:22,85,89-91`，同一 Seq 决定参数与三条 `foreach` 连接的循环次数）、`TilesLocated(location)`。

*(d) 纯图结构，数字事后读回。*`devices/tilelink/Plic.scala:106-116`：`nDevices = intnode.edges.in.map(_.source.num).sum`、`nHarts = intnode.edges.out...sum`——无任何配置字段持有它们，且 `Plic.scala:187` 用 `if (nDevices > 0)` 直接决定是否生成扇入仲裁硬件。同型：`devices/debug/Debug.scala:711`（`nComponents = dmOuter.intnode.edges.out.size`）；`tilelink/BusWrapper.scala:106,109`（`coupleTo/From` 定义处不知道将来有多少外设调用它）。

== `:*=*` 穷尽清单 <sec-rc-flex>

33 行 / 103 次，6 个文件，四类：

+ *三协议 CrossingHelper*（`tilelink/`、`amba/axi4/`、`interrupts/` 各一份，结构同构，共 30 行 / 92 次）。一端是调用方传入的接口类型形参 `node`（真实身份未知），另一端是协议内固定的 1:1 跨域适配链。同一段代码既被"总线 Xbar（基数取决于挂载数）"调用，也被"单 tile 固定一条中断线"调用；"哪侧定数"只有调用方知道，定义处不掌握。
+ *`subsystem/MemoryBus.scala:49-50`*：`xbar.node :*=* TLFIFOFixer(...) :*=* replicator.node`——xbar 侧基数未知（来自 BankBinder），replicator 是 `Option`，两种不确定性同时在场。
+ *`tilelink/Buffer.scala:87`*：buffer 链自身归约，链两端将来接在哪一侧未知。
+ *`tilelink/BusWrapper.scala:106,109,208,272`*：`coupleTo/From` 的挂接占位（须同时适配 Xbar/Jbar/AddressAdjuster 三种总线实现）；`bindTLNodes` 把 `BIND_FLEX` 作为 `nodeBinding` 参数的四选一分支显式暴露。

== JunctionNode 与 CustomNode 穷尽清单 <sec-rc-junction>

Junction 直接子类仅 `TLJunctionNode`（`tilelink/Nodes.scala:49-53`），两处使用：`TLJbar`（`tilelink/Jbar.scala:12-46`，比例来自图中已定一侧的边数；用于 `BankedCoherenceParams.scala:67-69` 与 `TLJBarWrapper`）与 `AddressAdjuster`（`tilelink/AddressAdjuster.scala:127-131`，硬编码 `uRatio==1; dRatio==2`，本地/远程双路）。

CustomNode 三个：`BankBinderNode`（`tilelink/BankBinder.scala:11-24`，唯一做算术的——按 `nBanks` 换算的地址掩码均分星号侧）；`MasterMuxNode`（`devices/tilelink/MasterMux.scala:20-24`）与 `TLBypassNode`（`devices/tilelink/BusBypass.scala:51-56`）——后两者 `resolveStar` 第一行即 `require(iStars==0 && oStars==0, "... does not support :=* or :*=")`，并强制恰好 2 入 1 出 / 1 入 2 出。

== 弱指针实证 <sec-rc-weak>

框架注释原文（`NexusNode`）："a nexus treats `:=*` as a weak pointer"。仓库内有完整配置→连接→图证据链的场景：

- *PLIC 存在与否*：`Plic.scala:106-110` 显式 `outputRequiresInput=false, inputRequiresOutput=false`；存在时 `Plic.scala:369` `plic.intnode :=* ibus.toPLIC`；groundtest 配置显式无 PLIC，`groundtest/GroundTestSubsystem.scala:35` 用 `IntSinkNode(...) :=* ibus.toPLIC` 接一个哑 Sink（注释："just sink the interrupts to nowhere"）。两种配置下 `ibus.toPLIC` 的定义零改动。
- *RoCC 数量*：`BuildRoCC` 默认 `Nil`，`WithRoccExample` 为 4；`LazyRoCC.scala:89-91` 同一段 `foreach` 星号语句贡献 0 或 4 条边。
- *机制存在但仓库内未见触发*：`HasHierarchicalElements.scala:54-64` 的 `blockerCtrlAddr: Option[BigInt]`，仓库内全部赋值为 `None`。

== 绕开星号的证据 <sec-rc-bypass>

- `subsystem/Ports.scala:79-117`：多边 `AXI4SlaveNode`（边数 = `nMemoryChannels`）用*显式 for 循环包住纯 `:=` 链*连接，而非星号；全文件 237 行零星号。
- 同文件内两种风格并存：`HasTiles.scala:208` 纯 `:=` 链（两侧基数恰为 1）与 `:164` 星号相邻；`:302` 同一条语句前半 `:*=` 后半 `:=`。
- 顺序敏感警示：`HasTiles.scala:203-205` "NOTE: The order of calls to `:=` matters! They must match how interrupts are decoded..."。
- 隐性风格耦合：`tilelink/ProbePicker.scala:19` 注释声明其参数合并正确性依赖 "clusters of xbar `:=*` BankBinder connections" 这种连接写法。
- `TLBusWrapperConnection` 文档（`BusWrapper.scala:178`）以 "fine-grained control of multi-edge cardinality resolution" 描述 `nodeBinding` 参数——算子选择被物化为数据。

== 证据支持的需求点 <sec-rc-needs>

+ 同一段装配代码同时覆盖"边数已定"与"边数未定"两种情形（`bindTLNodes` 四算子切换；三份 CrossingHelper）。
+ "有几个"交给配置驱动的宿主循环，连接语句不随数量改写（`BuildRoCC`；`nTotalTiles` 循环）。
+ 中心汇聚点在服务对象完全不存在时无需改图（PLIC 双分支；`nDevices` 读回驱动硬件生成）。
+ 固定比例分流/合流（BankBinder 的 `nBanks`；TLJbar；AddressAdjuster 1:2）。
+ 跨域适配链封装为与拓扑形状无关的公共函数，被总线级与 tile 级两种量级复用。
+ 可选适配级用同一句连接表达有/无两态（`cork.map{...}.getOrElse{TLTempNode()}`；`chainNode` 对 `depth=0` 的退化）。
+ 边数被设计定死为常数时，作者以自定义 `resolveStar` *禁止*星号而非利用星号（MasterMux/BusBypass）。

= federation 调查结果 <ch-federation>

== 仓库形态要点 <sec-fed-form>

工业 monorepo；diplomacy 框架整份内联于 `rocketchip/`（`diplomacy/Nodes.scala`，721 行，2020 年内联版本）；`src/` 为集成层（约 30 个外设集成目录、everywhere/unleashed 两个产品家族的 SKU 目录、JSON 驱动的配置读取器 `api/json/JSONReader.scala`）；两个真实签出的交付型 IP 仓（`block-hca-sifive/`、`block-caboosevectors-sifive/`）；`ipdelivery/` 是例化之后的打包与 QA 比对，无 Scala。

== 算子清点 <sec-fed-counts>

#table(
  columns: (auto, auto, 1fr),
  table.header([算子], [总数], [分布]),
  [`:*=*`], [76], [*100% 位于 `rocketchip/`（框架层，7 文件）；产品代码零直接使用*],
  [`:*=`], [136], [rocketchip 86、src 34、shells 8、composablecache 6、mallard 1、blocks 1],
  [`:=*`], [97], [rocketchip 59、src 30、shells 4、block-caboosevectors 2、mallard 1、blocks 1],
)

diplomatic `:=` 估计 800–900 行（约占全部 `:=` 的 2.5%–3%，偏保守下界）。

== 三层封装：工业代码如何隐藏算子 <sec-fed-wrap>

+ *第一层*：算子定义本身（`rocketchip/.../diplomacy/Nodes.scala:140-190`，映射到 `BIND_ONCE/STAR/QUERY/FLEX`）。
+ *第二层*：总线挂接原语（`tilelink/BusWrapper.scala`）——`coupleTo/coupleFrom`（内部各一次 `:*=*`，对外暴露"名字 + 接收节点的函数"）；`toSlave/toVariableWidthSlave/toFixedWidthSlave/.../fromMaster/fromPort` 方法族（内部固定星号链，对外暴露电气语义，调用方只传参数）；`crossIn/crossOut/crossToBus/crossFromBus`（内部 `:*=*`）；`TLBusWrapperConnection` 把四算子整体做成运行时可选枚举 `nodeBinding: NodeBinding = BIND_ONCE`。
+ *第三层*：产品级 attach API——穷尽检索到 *37 个* `XxxAttachParams` case class，统一于 `blocks/src/main/scala/util/Devices.scala:29-36` 的 `trait DeviceAttachParams { def attachTo(where: Attachable): LazyModule }`，并被 `DevicesLocated: Field[Seq[DeviceAttachParams]]` 泛型消费——配置作者的可见界面是"设备知道怎么把自己挂上去"，看不到任何算子符号。

用量：`.coupleTo(` 96 处 + `.coupleFrom(` 19 处（55 个文件）+ `.attachTo(/.attach(` ≥28 处。逐一核查产品目录全部星号调用点：*没有任何一处"设备接入共享总线"的连接是在封装之外裸写的*；产品目录内的星号或在 `coupleTo/From` 闭包体内，或在 `attachTo` 实现体内，或是已挂接子系统内部兄弟模块间的微架构连线。

== CustomNode 与 JunctionNode 穷尽清单 <sec-fed-custom>

具体 CustomNode 五个：

#table(
  columns: (auto, auto, 1fr, auto),
  table.header([节点], [位置], [`resolveStar` 语义], [禁星号？]),
  [`BankBinderNode`], [`rocketchip/.../BankBinder.scala:9-22`], [按地址掩码分区数均分，强约束整除], [否],
  [`MasterMuxNode`], [`rocketchip/.../MasterMux.scala:11-21`], [固定 2 入 1 出], [是],
  [`TLBypassNode`], [`rocketchip/.../BusBypass.scala:51-61`], [固定 1 入 2 出], [是],
  [`StuckSnooperNode`], [`blocks/.../StuckSnooper.scala:11-21`], [固定 2 入 1 出], [是],
  [`OrderOglerNode`], [`src/.../OrderOgler.scala:45-94`], [固定 1 入 2 出，按 `requestFifo` 拆分 client 并注入合成 hint-source], [是——文案"Use `:=` only."],
)

`rg 'override def resolveStar'` 全仓库零命中：所有自定义基数策略都经由 CustomNode 的抽象契约实现，无一覆写内置角色；其中 4/5 显式禁用星号。Junction 仅框架内两处（TLJbar、AddressAdjuster），产品代码零直接使用。

== 弱指针与 SKU 裁剪 <sec-fed-weak>

"weak pointer" 字样全仓库仅 1 处（框架注释）。产品配置裁剪落到连接图的实际机制，绝大多数*不经过* 0/1 语义，而是宿主语言包装：`Option[Node].foreach` 跳过整段挂接（`composablecache/.../Configs.scala:241,245,249`）；`Option.map(...).getOrElse(桩节点)` 保持图形状、值被钳位（`HasTiles.scala:268-290` 的 `NullIntSource`）；`device.aes.isDefined` 决定是否构建整条通路（`block-hca-sifive/.../HCA.scala:865-869`，其上游是 JSON 产品描述 `JSONReader.scala:785-789` 的 `if(i.hasAES)`）；同一 `blockerAddr: Option[BigInt]` 模式在多外设重复。

== 边数来源与绕开星号 <sec-fed-origins>

四种来源均在场，其中独有事实：数字可上溯到*仓库之外的 JSON 产品描述文件*（`JSONReader.scala:763-786`，七种外设各一次 `.map` 构造 AttachParams 列表）。绕开星号：`HasTiles.scala:351-353` 的 `tileAttachParams.zip(tiles).foreach`；`config/Devices.scala` 内 8 处同构 `Seq.tabulate/zipWithIndex.map`。备忘/踩坑注释：`HasCabooseTiles.scala:91` 的 TODO 把"`:=` 改 `:*=`"列为候选方案；`devices/chiplink/Periphery.scala:25-26` 注释掉的备选写法旁标注 "!!! unsound in multi-socket systems"；四个禁星号 `require` 文案本身即写死在代码里的算子语义备忘。

== 证据支持的需求点 <sec-fed-needs>

+ 可复用胶水在书写时不知道自己接在哪侧、对方基数几何（三协议 CrossingHelper、`coupleTo/From`、`flexOffset` 断言把"连通子图恰一侧供数"形式化）。
+ 汇聚点接受数量随产品配置变化、彼此互不知晓的连接方（多 tile 通知汇入、PRCI 单例节点）。
+ 固定非 1:1 比例的真实拓扑存在但稀有（Jbar、AddressAdjuster、BankBinder）。
+ 基数固定的一次性业务节点需要在类型层面禁止星号误用（4/5 CustomNode）。
+ SKU 差异传导到连接图，主要由宿主语言 `Option/Seq` 承担而非弱指针。
+ "设备挂总线"动作重复度极高（96+19+28 处），被收敛为固定 API，算子退化为内部实现细节。

= chipyard 调查结果 <ch-chipyard>

== 覆盖面与清点 <sec-cy-counts>

实存源码即 chipyard 自己的集成层（177 文件）。星号算子*总计 12 处*，穷尽列表覆盖 6 个文件（SpikeTile、System、Subsystem、HasChipyardPRCI、ClockBinders、NodeTypes 教学示例）；`:*=*`、JunctionNode、CustomNode 出现次数*均为零*（明确的零结果）。diplomatic `:=` 估计 65–90 处（占约 6%–9%），集中在少数搭图文件；`generators/firechip` 的 434 处 `:=` 全为 chisel 赋值。

== 配置键到连接数的完整链路 <sec-cy-chains>

五条穷尽追踪的代表性结论：

- *TL 内存通道*：`WithNMemoryChannels(n)` → `ExtMem` →（`WithTLBackingMemory` 转发进 `ExtTLMem`）→ `System.scala:65-66` `Seq.tabulate(nMemoryChannels)` 构造 `TLManagerNode` 参数【来源 (a)】→ `System.scala:86-89` 一条 `:*=` 链由左端已定边数自动吸收【来源 (c) 星号】→ `System.scala:92` `makeIOs()` 固化。
- *AXI4 内存通道（对照）*：同一根因，rocket-chip 侧 `Ports.scala:79,107-116` 用*显式 for 循环 + 纯 `:=` 链*完成同类连接【来源 (b)】——同一"N 通道"问题，两个代码库两种风格。
- *tile 数量*：`WithNBigCores(n)` → `TilesLocated` 列表 → 逐元素 `connect()`（循环 + 星号复合）；chipyard 侧 `Subsystem.scala:98-101` 在零 tile 分支为 Xbar 补桩驱动源（`IntXbar` 的最小驱动约束）。
- *时钟聚合*：总线位置列表 `foreach` 裸 `:=` 绑定 + `aggregator`（nexus）`:*=` 汇聚数量不定的时钟组。
- *中断兜底*：零 tile 分支专设 `IntNexusNode` 以 `:=*` 吸收 PLIC 侧数量不定的输出。

== 设计-测试边界 <sec-cy-boundary>

边数的"数字化边界"是 `makeIOs()`：此前边数可由星号/参数决定，此后固化为具体长度的 Scala 序列，harness/IOBinder 全部用 `.zipWithIndex.map`/`foreach` 加纯 chisel 赋值处理。12 处星号中仅 2 处贴着 ChipTop↔TestHarness 交界（时钟组二次汇聚），跨界后立即转为集合遍历。FPGA 板级 harness 文件共 22 处 diplomatic 连接，*全部裸 `:=`，零星号*——该处每次只处理一路，数量恒为 1。

== 官方文档口径 <sec-cy-docs>

`docs/TileLink-Diplomacy-Reference/Diplomacy-Connectors.rst` 是全仓库对四算子唯一的系统性说明，采用 TileLink 的 client/manager 措辞："`:=*` … the number of edges determined by *the client node (right side)*"；"`:*=` … determined by *the manager (left side)*"；"`:*=*` … based on whichever side has a known number of edges … where the type of node on either side isn't known until runtime"。即官方教学口径为"谁具体谁定数、写胶水时不知道就用 flex"。

== 证据支持的需求点 <sec-cy-needs>

+ 同一段生成器代码覆盖"恰一条"与"N 由配置定"两种情形（`Seq.tabulate` 同段代码被 N=0 与 N=4 的配置分别驱动）。
+ 边数由结构上真正掌握它的一端决定，另一端书写时无需知道（`:*=` 链右侧的 Buffer/Widget/mbus 代码不引用通道数）。
+ 汇聚抽象（nexus）使生产者数量到例化期才定（时钟聚合、中断兜底）。
+ 设计-测试交界处需要把"星号未定"转换为可下标的普通集合（`makeIOs()`）。
+ 边数恒为 1 或书写期已知的小常数时，直接写显式 `:=`（SpikeTile 对 icache/dcache/mmio 三条手写语句；FPGA harness）。
+ "改数字的 fragment"与"写连接的文件"跨模块解耦（六处配置位点独立设置同一键，消费代码一无所知）。

= 跨库归纳 <ch-synthesis>

== 分层梯度 <sec-syn-gradient>

星号算子沿"框架 → 工业产品 → 集成"急剧衰减：rocket-chip 97 行、federation 产品目录数十处（flex 为零）、chipyard 12 处（flex/junction/custom 全零）。flex 在三个仓库中*无一例外只出现在可复用库代码*（跨域 helper、挂接占位、buffer 链、总线连接器）。工业层把算子完全藏进封装，算子选择本身可被物化为配置数据。

== 边数来源的两层结构 <sec-syn-origins>

四种来源中，(a) 参数表长度、(b) 宿主循环、(c) 配置键三类的共性是：*数字先于图存在，且本来就是某处的参数*——机制在这些场合的作用是搬运一个已存在的数。唯 (d) 枢纽侧的数字由连接行为本身产生、事后从图读回（PLIC 的 `nDevices` 无任何参数持有，却驱动硬件生成）。但存在嵌套事实：配置层的"接入者名单"（`Seq[AttachParams]`、`TilesLocated`、JSON 条目）已把 (d) 的数量在装配层参数化了一层——真正对数量无知的只有枢纽与胶水的*定义处*。

== 九条跨库一致事实 <sec-syn-facts>

+ 定义处无知是 flex 的全部存在理由；方向由装配现场"具体的那一端"锚定。
+ 官方教学口径即"谁具体谁定数"。
+ "一句连接覆盖 0..N"是普遍写法（foreach 包星号；`getOrElse(占位节点)`）。
+ CustomNode 的实际用途以"拒绝星号、断言固定形状"为主（7 例中 5 例），做算术的只有 BankBinder。
+ Junction 极稀有（全三库共两个类型），产品代码零直接使用。
+ 弱指针 0/1 很少承重；空缺处理主要由宿主语言 Option/getOrElse(桩) 承担，另有补桩假源满足最小驱动约束。
+ `makeIOs()` 之后星号不再出现；测试环境全部使用普通集合。
+ 同一问题星号链与显式循环两种解法长期并存，甚至同一语句内混用；存在"`:=` 顺序决定语义"的警示。
+ 存在参数语义依赖连接书写风格的隐性约定（ProbePicker 对 "`:=*` BankBinder" 拓扑形状的正确性假设）。

== 证据摆出的张力点 <sec-syn-tensions>

+ *T1*：数字在 (a)(b)(c) 情形本就是参数，机制只是搬运；(d) 的数字由图产生——但配置层名单又把它参数化了一层。"边数可否由参数推导"在两层上的答案形态不同。
+ *T2*：方向在实践与文档中都由"哪端具体"决定，而四算子把方向作为每次书写的人工选择，flex 再推迟给全分量求解。
+ *T3*：星号的主要受益者是库作者（定义处无知），工业使用者看不到算子；同一问题星号与显式循环并存。
+ *T4*："空"的处理分裂于三处：弱指针 0/1、宿主语言 Option、补桩假源。
+ *T5*：固定形状节点宁可自定义 `resolveStar` 禁用星号，也不用现成机制表达"恰好 2 入 1 出"。

本文档到此为止只陈述证据；对张力点的取舍属于设计讨论，记录于设计文档而非本文。
