#set page(margin: 2cm, numbering: "1")
#set text(lang: "zh")
#show link: underline

= Diplomacy 2.0 设计文档

#quote(block: true)[Scala 3 重构版本。本文档是后续实现的设计基线，记录所有已定的架构决策与未决项。]

#line(length: 100%)

== 1. 概述

Diplomacy 2.0 是面向 SoC 设计的参数协商与拓扑连接框架。在保留旧 Diplomacy 核心思想（图协商、协议抽象、参数双向传播）的前提下，彻底重构了它与硬件构造的耦合方式：

- *后端从 Chisel 切换到 #link("https://github.com/xinpian-tech/zaozi")[zaozi]*；通过 zaozi 间接复用 CIRCT/MLIR 工具链
- *`InModuleBody` / `LazyModuleImp` 等阶段裂痕产物全部消除*
- *DSL 与硬件构造解耦*：Diplomacy 描述"要例化什么 zaozi 模块、用什么参数、怎么连"，自身不构造任何电路
- *CDE 移除*：参数显式流动，不再有全局隐式 `Parameters` PartialFunction
- *三阶段流水线*：Build → Negotiate → Elaborate，阶段间边界清晰

#line(length: 100%)

== 2. 设计哲学与不变量

=== 2.1 三阶段

```
   Phase 1: Build                Phase 2: Negotiate            Phase 3: Elaborate
┌──────────────────────┐     ┌──────────────────────────┐  ┌──────────────────────┐
│ 用户 DSL 构造 spec   │ ──▶ │ 跑协商算法               │──▶ 调 zaozi + circtlib  │
│ DiplomaticModule     │     │ 解析 cardinality         │  │ 例化模块、连线       │
│ 节点声明、连接、bind │     │ 传播 D/U 参数            │  │ 生成 CIRCT FIRRTL    │
│                      │     │ DV 路由 + Layer merge    │  │                      │
│ JVM 内、含闭包       │     │ 调用 computeProtocolParam│  │ 同 zaozi 上下文      │
└──────────────────────┘     └──────────────────────────┘  └──────────────────────┘
```

每个阶段输入输出明确，无 lazy val 链式触发。

=== 2.2 核心不变量

+ *每个 DiplomaticModule wrap 0 或 1 个 zaozi Generator*：sealed 类型层面强制
+ *DiplomaticModule 二选一*：`WrapperModule`（无 Generator）或 `TerminatorModule`（含 Generator），不能混合
+ *只有传给 zaozi 的 PARAM 必须可序列化*；spec 中 binding / lambda 是 JVM-local
+ *PARAM 分两层*：`UserParam` 由用户在 Phase 1 给，`ProtocolParam` 由 Negotiator 在 Phase 2 算
+ *DV 只能向上传播*：从 Source 一路打洞到 Sink 所在 ancestor，路径上每个 wrapper 自动增加 DV IO
+ *硬件逻辑只能在 zaozi Generator 里写*：Diplomatic 模块不接受 inline 硬件代码（彻底消除 InModuleBody）

#line(length: 100%)

== 3. 架构与边界

=== 3.1 模块依赖图

```
┌────────────────────────────────────────────────────┐
│ diplomacy-core                                     │
│  - Protocol / DVProtocol trait                     │
│  - ProtocolInterface ADT                           │
│  - NodeSpec / ConnectionSpec (pure data)           │
│  - WrapperModule                                   │
│  - DiplomaticModule (sealed trait)                 │
│  - Negotiator (算法层)                              │
└──────────────────┬─────────────────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────────────────┐
│ diplomacy-zaozi   （depends on zaozi + circtlib）   │
│  - TerminatorModule[UP, PP, FP, I]                 │
│  - HWBinding 类型与 lambda 工具                      │
│  - Elaborator                                      │
│      ├─ Wrapper path (circtlib 直接 emit FIRRTL)    │
│      └─ Terminator path (zaozi gen.instantiate)    │
└────────────────────────────────────────────────────┘
```

模块切分按"职责"组织，`core` 放图模型和协商算法，`zaozi` 放硬件实例化。`core` 是否额外引入 zaozi / circtlib 依赖不强制——按实现便利性决定。如果 core 内部用 zaozi 的某些 pure type 帮忙写更顺手，引入即可。

=== 3.2 边界与序列化

#table(
  columns: 4,
  table.header([边界], [跨越者], [必须 serializable], [形态]),
  [Phase 1 → Phase 2], [同 JVM 函数调用], [✗], [spec 持有 TerminatorModule 实例（含闭包）],
  [Phase 2 → Phase 3], [同 JVM 函数调用], [✗], [ResolvedDesign + lambdas in memory],
  [*Diplomacy ↔ zaozi*], [*`gen.instantiate(param)`*], [✓], [*PARAM 必须 derive upickle ReadWriter*],
  [可选 debug dump], [落盘], [✓（部分）], [ModuleSpec + topology + PARAM JSON],
)

*唯一强制序列化点*是 PARAM（既包含 UserParam 也包含 ProtocolParam）。其它 spec 字段可选择序列化用于调试/可视化，但不是契约。

#line(length: 100%)

== 4. 核心抽象

=== 4.1 Protocol（设计协议）

```scala
trait Protocol:
  type Down       // 向下流动的参数
  type Up         // 向上流动的参数
  type Edge       // 协商后的边参数

  def negotiate(d: Down, u: Up): Edge

  // wrapper module 生成 IO 时用；terminator path 不需要
  def protocolInterface(e: Edge): ProtocolInterface

  // metadata
  def name:    String
  def version: String
  def render(e: Edge): RenderedEdge = RenderedEdge.default
```

注意 *没有 `bundleType: Edge => zaozi.Bundle`*——zaozi 的 Bundle 形状由 Generator 的 `HWInterface` 决定，不由 Protocol 决定。Protocol 只提供 `ProtocolInterface`（Diplomacy 内部 ADT），仅 wrapper IO 生成路径用。

=== 4.2 DVProtocol（验证协议）

```scala
trait DVProtocol:
  type Up                // DV Source 声明的参数
  type Edge              // 顶层 Sink resolve 出的 edge
  type Bundle <: Data    // probe bundle 形状

  // Sink 端收到多个 Sources 的 Up 后做合并/解析
  def resolve(ups: Seq[Up]): Edge

  // wrapper IO 生成用
  def protocolInterface(e: Edge): ProtocolInterface
```

DVProtocol 不像 Protocol 那样双向协商；只有 `Up`（向上传），由 Sink 端 resolve。

=== 4.3 ProtocolInterface

纯数据 ADT，描述 edge 在协议层面的 bundle 形状。*只用于 wrapper IO 生成*（terminator path 不需要）：

```scala
sealed trait ProtocolInterface derives ReadWriter
case class PIBundle(fields: Vector[PIField]) extends ProtocolInterface
case class PIVec(size: Int, element: ProtocolInterface) extends ProtocolInterface

case class PIField(
  name:  String,
  flip:  Boolean,
  shape: ProtocolInterface | PIPrimitive
) derives ReadWriter

sealed trait PIPrimitive derives ReadWriter
case class PIUInt(width: Int)  extends PIPrimitive
case class PISInt(width: Int)  extends PIPrimitive
case class PIBool()            extends PIPrimitive
case class PIClock()           extends PIPrimitive
case class PIReset()           extends PIPrimitive
```

elaborate 时 `piToFirrtlType(pi)` 单向翻译到 circtlib 的 `BundleType` / `UIntType` 等。

=== 4.4 PARAM 双层结构

每个 Generator 的 PARAM 由 UserParam（用户填）+ ProtocolParam（Diplomacy 协商出）组合：

```scala
trait DiplomaticParameter[UP <: Parameter, PP <: Parameter] extends Parameter:
  def user:     UP
  def protocol: PP

// 示例
case class TLRAMParam(
  user:     TLRAMUserParam,
  protocol: TLRAMProtocolParam
) extends DiplomaticParameter[TLRAMUserParam, TLRAMProtocolParam]

case class TLRAMUserParam(
  base:      BigInt,
  size:      BigInt,
  beatBytes: Int
) extends Parameter

case class TLRAMProtocolParam(
  clientPorts: Vector[TLClientPortParameters]   // 谁在跟我说话
) extends Parameter
```

zaozi 看到的是完整 `TLRAMParam`。Diplomacy 在 Phase 3 用 `combine: (UP, PP) => FP` 合成。

=== 4.5 DiplomaticModule 层次（sealed）

```scala
sealed trait DiplomaticModule

trait WrapperModule extends DiplomaticModule:
  // 无 Generator
  // sinkNode/sourceNode/adapterNode/nexusNode 不带 hwBinding
  // 自由用 := / :*= / :=* / :*=*

abstract class TerminatorModule[
  UP <: Parameter,
  PP <: Parameter,
  FP <: DiplomaticParameter[UP, PP],
  I  <: HWInterface[FP]
](
  val generator: Generator[FP, ?, I, ?],
  val userParam: UP,
  val combine:   (UP, PP) => FP
)(using DesignBuilder) extends DiplomaticModule:
  
  // 用户必须实现：从协商完的 edges 算 PP
  def computeProtocolParam(view: EdgeView): PP
  
  // 该模块的节点声明必须带 hwBinding，bind 到 generator.interface(_) 的字段
```

Phase 2 末尾对每个 TerminatorModule 调用 `computeProtocolParam(view)` 拿 PP。

#line(length: 100%)

== 5. Phase 1：Builder DSL

=== 5.1 入口

```scala
def design(body: DesignBuilder ?=> Unit): DesignSpec = ...

// 用户写：
val spec = design:
  given Parameters = DefaultParams()   // 任何 SoC 级配置
  val top = new MySoC                  // 构造 DiplomaticModule 树
```

`DesignBuilder` 是 sealed trait，只能由框架入口注入。`:=` 等连接操作只在有 `given DesignBuilder` 时编译通过。

=== 5.2 节点构造

*Design 节点*：

```scala
// WrapperModule 里（无 hwBinding）
protected def sinkNode  [P <: Protocol](protocol: P, params: Vector[P#Up]):   SinkHandle[P]
protected def sourceNode[P <: Protocol](protocol: P, params: Vector[P#Down]): SourceHandle[P]
protected def adapterNode[P <: Protocol](
  protocol: P,
  dFn: P#Down => P#Down,
  uFn: P#Up   => P#Up
): NodeHandle[P, P]
protected def nexusNode[P <: Protocol](
  protocol: P,
  dFn: Seq[P#Down] => P#Down,
  uFn: Seq[P#Up]   => P#Up
): NodeHandle[P, P]
protected def identityNode[P <: Protocol](protocol: P): NodeHandle[P, P]
protected def ephemeralNode[P <: Protocol](protocol: P): NodeHandle[P, P]
protected def junctionNode[P <: Protocol](...): NodeHandle[P, P]

// TerminatorModule 里（必带 hwBinding）
protected def sinkNode[P <: Protocol, T <: Data](
  protocol:  P,
  params:    Vector[P#Up],
  hwBinding: Wire[I] => (Arena, Context, Block, TypeImpl) ?=> Ref[T]
): SinkHandle[P]
// sourceNode / adapter / nexus 同理，均要求 hwBinding
```

*DV 节点*：

```scala
// TerminatorModule 里声明 DVSource，bind 到 Generator 的 probe interface
protected def dvSourceNode[DP <: DVProtocol, T <: Data](
  protocol:  DP,
  upParam:   DP#Up,
  layer:     LayerSpec,
  hwBinding: (Wire[I], Wire[ProbeI]) => ContextFn[Ref[T]]   // 二元 lambda
): DVSourceHandle[DP]

// 任意 ancestor 模块（通常顶层）声明 DVSink
protected def dvSinkNode[DP <: DVProtocol](
  protocol: DP,
  layer:    LayerSpec
): DVSinkHandle[DP]
```

=== 5.3 连接 DSL

Design 连接：4 种 binding 保留，sealed 操作符在 NodeHandle 上：

```scala
trait SourceHandle[P <: Protocol]:
  def :=  (sink: SinkHandle[P])(using DesignBuilder, SourceLocation): Unit
  def :*= (sink: SinkHandle[P])(using DesignBuilder, SourceLocation): Unit
  def :=* (sink: SinkHandle[P])(using DesignBuilder, SourceLocation): Unit
  def :*=*(sink: SinkHandle[P])(using DesignBuilder, SourceLocation): Unit

// NodeHandle = 同时 in + out 的 handle，支持 chain
// 16 种 operator 重载组合按老 Diplomacy 语义保留
```

DV 连接：

```scala
extension [DP <: DVProtocol](sink: DVSinkHandle[DP])
  def :=(src: DVSourceHandle[DP])(using DesignBuilder, SourceLocation): Unit
```

=== 5.4 完整示例

```scala
class TLRAMDip(up: TLRAMUserParam)(using DesignBuilder)
    extends TerminatorModule[
      TLRAMUserParam, TLRAMProtocolParam, TLRAMParam, TLRAMInterface
    ](
      generator = TLRAMGen,
      userParam = up,
      combine   = TLRAMParam.apply
    ):
  
  val node = sinkNode[TLProtocol, TLBundle](
    protocol  = TL,
    params    = Vector(TLSlavePortParameters(base = up.base, size = up.size)),
    hwBinding = _.io.tl
  )

  def computeProtocolParam(view: EdgeView): TLRAMProtocolParam =
    TLRAMProtocolParam(clientPorts = view.inEdges(node).map(_.client))


class MySoC(using DesignBuilder) extends WrapperModule:
  val core = submodule(RocketTileDip(coreUserParam))
  val ram  = submodule(TLRAMDip(TLRAMUserParam(0x80000000L, 0x10000000L, 8)))
  val xbar = submodule(TLXbarDip())
  
  xbar.node := core.masterNode
  ram.node  := xbar.node
  
  // DV
  val cosimProbe = dvSinkNode[FSMProbeProtocol](
    protocol = FSMProbe,
    layer    = LayerSpec("verification.cosim")
  )
  cosimProbe := core.fsmStateProbe
```

#line(length: 100%)

== 6. Phase 2：Negotiator

=== 6.1 算法流水线

```scala
def negotiate(spec: DesignSpec): Either[NegotiationError, ResolvedDesign] =
  for
    _      <- validateSpec(spec)
    topo   <- topologicalSort(spec)
    arity  <- resolveCardinality(spec, topo)       // 老算法保留
    dPar   <- propagateDown(spec, topo, arity)
    uPar   <- propagateUp  (spec, topo.reverse, arity)
    edges  <- computeEdgeParams(spec, dPar, uPar)
    pp     <- invokeComputeProtocolParam(spec, edges)  // 调用每个 Terminator 的 callback
    dvPlan <- routeDVConnections(spec, edges)      // DV 打洞 + 算每个 wrapper 的 DV IO
    layers <- mergeLayerTrees(spec, dvPlan)        // 每个 wrapper 的 merged Layer
    wires  <- buildWirePlan(spec, edges, dvPlan)
  yield ResolvedDesign(spec, edges, pp, dvPlan, layers, wires)
```

每一步纯函数，错误是值（`Either`）。

=== 6.2 Cardinality 解析

*完全保留旧 Diplomacy 的算法*：

- 4 种 binding：`BIND_ONCE` / `BIND_STAR` / `BIND_QUERY` / `BIND_FLEX`
- `resolveStar(iKnown, oKnown, iStars, oStars)` 按 NodeRole 分派
- `flexOffset` 通过 DFS 在 flex 连通分量上求解方向
- `edgeArityDirection` / `edgeAritySelect`

实现重写为纯函数，去掉 `lazy val starCycleGuard` 等 mutable 哨兵；环检测通过显式 visited set 在拓扑排序前完成。

=== 6.3 参数传播

按拓扑序：

```scala
def propagateDown(...): Either[Err, Map[NodeId, Vector[Down]]] =
  topo.foldLeft(...) { (acc, nid) =>
    node.role match
      case Source  => acc + (nid -> node.staticDown)
      case Adapter => acc + (nid -> inputs(nid).map(node.dFn))
      case Nexus   => acc + (nid -> Vector.fill(outCount)(node.nexusDFn(inputs(nid))))
      case Sink    => acc                                  // 不产 down
      case Identity => acc + (nid -> inputs(nid))
  }
```

`uFn` / `dFn` 是 NodeSpec 上的 Scala 闭包（`α` 决定 spec JVM-local，允许闭包）。

=== 6.4 computeProtocolParam 调用

```scala
def invokeComputeProtocolParam(
    spec: DesignSpec, edges: EdgeMap
): Map[ModuleId, ParameterValue] =
  spec.modules.collect { case t: TerminatorModule[?, ?, ?, ?] =>
    val view = makeEdgeView(t, edges)
    t.id -> t.computeProtocolParam(view)
  }.toMap
```

PP 算完后跟用户提供的 UP 组合：`FP = t.combine(t.userParam, pp)`，存入 ResolvedDesign。

=== 6.5 DV 路由（"打洞"）

```scala
def routeDVConnections(spec, edges):
  spec.dvConnections.foreach { case DVConnection(srcId, sinkId) =>
    val srcMod  = nodeOwner(srcId)
    val sinkMod = nodeOwner(sinkId)
    
    // Validator: Sink 必须在 Source 的 ancestor 链上
    require(isAncestor(sinkMod, srcMod), "DV must propagate upward")
    
    // 一路向上打 IO，直到 sinkMod
    val path = ancestorChain(srcMod, sinkMod)   // [srcMod, ..., sinkMod]
    path.sliding(2).foreach { case Seq(lower, upper) =>
      // upper 模块要增加一个 DV IO 端口
      addDVIO(upper, connection.protocol, source.layer)
      addWire(lower.dvOut → upper.dvIn)
    }
  }
```

每经过一个 wrapper 边界产生一段 wire；wrapper module 的 IO 自动追加对应字段。

=== 6.6 Layer 合并

每个 wrapper 模块的 `mergedLayers: LayerTree` 是子树所有 DV 节点 LayerSpec 的 prefix tree union：

```
Source A: "verification.cosim"
Source B: "verification.assert.fatal"
Source C: "verification.cosim"
==> merged:
    verification
      ├─ cosim
      └─ assert
           └─ fatal
```

merge 完展平成 `Seq[Seq[String]]` 喂给 circtlib `Module.op(..., layers = ...)`。

#line(length: 100%)

== 7. Phase 3：Elaborator

=== 7.1 入口

```scala
def elaborate(
    resolved:  ResolvedDesign,
    circuit:   Circuit
)(using Arena, Context): Unit =
  resolved.modules.foreach(m => elaborateModule(m, resolved, circuit))
```

要求外部提供 `Arena` / `Context`（zaozi 上下文）。模块按拓扑序（bottom-up）elaborate。

=== 7.2 Wrapper Path（circtlib）

对 `WrapperModule`：

```scala
def elaborateWrapper(m: WrapperResolved, circuit: Circuit)(using Arena, Context): Unit =
  // 1. 算出 IO：design IO（forward 链）+ DV IO（打洞）
  val designIO = m.designIOPlan.map(piToFirrtlBundleField)
  val dvIO     = m.dvIOPlan.map(piToFirrtlBundleField)
  val allIO    = designIO ++ dvIO
  
  // 2. 创建 wrapper module
  val mod = Module.op(
    name             = s"${m.valName}_${topologyHash(m)}",
    location         = m.sourceLoc.toMLIR,
    firrtlConvention = Scalarized,
    interface        = allIO,
    layers           = m.mergedLayers.flatten
  )
  mod.appendToCircuit(circuit)
  
  // 3. 例化子模块、连线
  given Block = mod.block
  m.children.foreach(c => Instance.op(...).appendTo(mod))
  m.wires.foreach { w => connect(w.sink, w.source) }
```

Wrapper 模块名：`<diplomatic_module_valName>_<topologyHash>`。

=== 7.3 Terminator Path（zaozi）

对 `TerminatorModule`：

```scala
def elaborateTerminator(m: TerminatorResolved)(using Arena, Context): Unit =
  // 1. 合成 FullParam
  val fp = m.combine(m.userParam, m.protocolParam)
  
  // 2. 调 zaozi 例化
  val instance: Instance[I, P] = m.generator.instantiate(fp)
  
  // 3. 应用 binding lambda 算出 ref，连线
  given Block = parentBlock
  m.designNodes.foreach { node =>
    val ref = node.hwBinding(instance.io)
    wireMap += (node.id -> ref)
  }
  m.dvNodes.foreach { node =>
    val ref = node.hwBinding(instance.io, instance.probe)
    dvWireMap += (node.id -> ref)
  }
```

zaozi 的 `:=` 操作符负责 bundle-level 连接，Diplomacy 不需要枚举字段。

=== 7.4 WirePlan 执行

```scala
resolved.wires.foreach { case WireSpec(srcId, sinkId) =>
  val srcRef  = resolveRef(srcId)
  val sinkRef = resolveRef(sinkId)
  sinkRef := srcRef
}

def resolveRef(id: NodeId): Ref[?] =
  if isTerminatorOwned(id) then bindingRef(id)
  else                          wrapperIORef(id)
```

设计 wires 和 DV wires 走同一套机制，区别只在 `resolveRef` 走 design IO 还是 DV IO。

#line(length: 100%)

== 8. Spec 数据结构

```scala
// === 标识 ===
opaque type NodeId    = Long
opaque type ModuleId  = Long
opaque type EdgeId    = Long

// === 模块 ===
sealed trait ModuleSpec:
  def id:       ModuleId
  def name:     String
  def parent:   Option[ModuleId]
  def children: Vector[ModuleId]
  def designNodes: Vector[NodeId]
  def dvNodes:     Vector[NodeId]

case class WrapperSpec(...)    extends ModuleSpec
case class TerminatorSpec(
  ...,
  module:    TerminatorModule[?, ?, ?, ?]   // JVM 引用，含 computeProtocolParam
) extends ModuleSpec

// === 节点 ===
sealed trait NodeSpec:
  def id:    NodeId
  def owner: ModuleId

case class DesignNodeSpec[P <: Protocol](
  id:           NodeId,
  owner:        ModuleId,
  protocol:     P,
  role:         DesignNodeRole,
  staticDown:   Vector[P#Down] = Vector.empty,
  staticUp:     Vector[P#Up]   = Vector.empty,
  dFn:          Option[Any => Any] = None,
  uFn:          Option[Any => Any] = None,
  nexusDFn:     Option[Seq[Any] => Any] = None,
  nexusUFn:     Option[Seq[Any] => Any] = None,
  hwBinding:    Option[Any => Any] = None    // TerminatorModule 必填
) extends NodeSpec

case class DVNodeSpec[DP <: DVProtocol](
  id:        NodeId,
  owner:     ModuleId,
  protocol:  DP,
  role:      DVRole,                          // Source | Sink
  layer:     LayerSpec,
  upParam:   Option[DP#Up],                   // Source 必填
  hwBinding: Option[(Any, Any) => Any] = None // Source 必填
) extends NodeSpec

enum DesignNodeRole:
  case Source, Sink, Adapter, Nexus, Identity, Ephemeral, Junction

enum DVRole:
  case Source, Sink

// === 连接 ===
sealed trait ConnectionSpec
case class DesignConnection(
  source:      NodeId,
  sink:        NodeId,
  cardinality: Cardinality
) extends ConnectionSpec

case class DVConnection(
  source: NodeId,
  sink:   NodeId
) extends ConnectionSpec

enum Cardinality:
  case Once                     // :=
  case StarFromSink             // :*=
  case StarFromSource           // :=*
  case Flex                     // :*=*

// === DesignSpec ===
case class DesignSpec(
  modules:     Vector[ModuleSpec],
  rootModule:  ModuleId,
  nodes:       Vector[NodeSpec],
  connections: Vector[ConnectionSpec]
)

// === ResolvedDesign ===
case class ResolvedDesign(
  spec:           DesignSpec,
  edges:          Map[(NodeId, NodeId), Any],   // 每条 design 连接的 edge param
  protocolParams: Map[ModuleId, Any],            // 每个 Terminator 的 PP
  dvIOPlan:       Map[ModuleId, Vector[DVIOEntry]], // 每个 wrapper 的 DV IO
  mergedLayers:   Map[ModuleId, LayerTree],     // 每个 wrapper 的 layer 声明
  wires:          Vector[WireSpec]
)

case class WireSpec(source: NodeId, sink: NodeId)

case class DVIOEntry(
  protocol:     DVProtocolRef,
  layer:        LayerSpec,
  bundleSchema: ProtocolInterface
)

case class LayerSpec(name: String, children: Vector[LayerSpec] = Vector.empty)
  derives ReadWriter
```

注意 `DesignNodeSpec` 和 `DVNodeSpec` 里的 lambda 字段是 `Any => Any` 等粗类型——spec 是 JVM-local 容器，类型安全在 Builder DSL 入口由 generics 保证；进 spec 后类型擦除。

#line(length: 100%)

== 9. 序列化模型

*唯一必须 serializable*：每个 TerminatorModule 的 FullParam（user + protocol 合成后）。这是 `gen.instantiate(param)` 的入参，zaozi 要求 `upickle.default.ReadWriter[PARAM]`。

*可选 serializable*（用于 debug / 可视化 / 跨进程分析）：
- `ModuleSpec.id / name / parent / children`（拓扑）
- `DesignConnection / DVConnection`（连接图）
- 每条 edge 的 edge param
- 每个 LayerTree

*永远不 serializable*：
- `TerminatorModule` 实例（含 `computeProtocolParam` 等方法）
- 所有 `hwBinding` lambda
- 所有 `dFn` / `uFn`
- WirePlan（间接通过 NodeId 引用，本身字段可序列化但语义解释依赖 spec）

实践上：dump 一份 `design.json` 包含可序列化部分用于 review / 工具消费；运行时 elaborate 用 in-memory ResolvedDesign。

#line(length: 100%)

== 10. 与旧 Diplomacy 的差异

#table(
  columns: 2,
  table.header([旧概念], [新设计中的处理]),
  [`LazyModule` 基类], [拆成 sealed `DiplomaticModule` → `WrapperModule` / `TerminatorModule`],
  [`LazyModuleImp`], [删除。硬件逻辑在 zaozi Generator 里],
  [`LazyModuleImpLike extends RawModule`], [删除。Diplomatic 模块不是 zaozi 模块],
  [`InModuleBody { ... }`], [删除。所有依赖协商参数的逻辑在 Generator 内（PARAM 已含 PP）],
  [`AutoBundle` + `Dangle` 自动打孔], [wrapper IO 由 Negotiator 显式算出，circtlib 直接 emit],
  [`CloneLazyModule`], [删除（zaozi 自己有 Instance 复用机制）],
  [CDE `Parameters` / `Field`], [删除。配置走显式 PARAM；Diplomacy 自身配置走 `NegotiationConfig`],
  [`bundleOut/bundleIn` lazy Wire], [删除。Wire 只在 zaozi 调用里出现],
  [`iPush/oPush` mutate `ListBuffer`], [改为 Builder DSL 显式累积（spec 不可变）#footnote[严格地说 `:=` 仍然是副作用语法，无法做成纯函数。新设计是把 `iPush/oPush` 这两个函数从 NodeSpec 上*彻底消掉*，把状态搬到一个有边界的 `DesignBuilder` 累加器里：

- *NodeSpec 完全 immutable*（case class，无 var / ListBuffer）。
- 每次 `:=` 在 `DesignBuilder` 上做*单次* `addConnection`，不再双端 mutate。
- *Index 由 Negotiator 派生*：connection list 的插入顺序决定每个节点 `iBindings`/`oBindings` 的位置，跟老的 `iPush(index = y.oPushed, ...)` 语义等价。
- *Freeze 时机由生命周期保证*：`design { body }` 块结束后 builder 不再被引用，Negotiator 拿到的是 immutable spec，"在 negotiate 后再 push"在类型/作用域层面不可能发生（替代了老的 `require(!iRealized)` 运行时检查）。
- 副作用通过 `using DesignBuilder` 编译期收敛——脱离 design block 写 `:=` 编译不过。
- 全部正确性靠这一个 contextual scope token 把死。]],
  [8 个类型参数 `MixedNode[DI,UI,EI,BI,DO,UO,EO,BO]`], [类型成员收敛到 `Protocol` trait],
  [`JunctionNode` / `EphemeralNode`], [保留（SoC 偶有用）],
  [`IdentityNode`], [保留],
  [4 种 binding（ONCE/STAR/QUERY/FLEX）], [*全部保留*，原算法],
  [16 种 `:= / :*= / :=* / :*=*` 重载], [*全部保留*，迁移到 sealed Handle trait],
  [`BundleBridge*`], [改造成基于 `Protocol` 的标准协议],
  [GraphML 可视化], [保留，从 spec 重新生成],
)

#line(length: 100%)

== 11. 未决项

=== Tier 2（实现时随手定）

- *ProtocolInterface 必选 vs 可选*：倾向必选；wrapper forwarding 强制要求 PI
- *hwBinding 必填 vs 默认*：倾向必填；多字段下默认会出怪事
- *LazyScope 替代*：动态生成子模块树的 API（普通 `for { submodule(...) }` 是否足够，还是需要专门 scope）
- *SourceLocation 实现*：用 `sourcecode` 库（跟 zaozi 一致），少一个差异点
- *错误报告格式*：`Either[NegotiationError, ResolvedDesign]` + 累积式错误，让 SoC 协商一次看完所有问题

=== Tier 3（推迟）

- *AddressMap / ClockDomain 抽象层归属*：倾向放协议库或 SoC framework，不进 diplomacy-core
- *mill 模块切分*：纯工程，按 `core / zaozi / protocols` 切
- *ResolvedDesign JSON schema*：等有外部工具消费需求再约定
- *测试策略*：core 纯函数单测；zaozi 层 integration test（lit 风格）
- *老 Diplomacy 迁移路径*：用户已表态彻底重构，可能不需要 scalafix；rocket-chip 类项目直接 fork

#line(length: 100%)

== 12. 实施路线图

按"自下而上、可独立合并"原则分 8 步：

#table(
  columns: 4,
  table.header([步骤], [内容], [依赖], [工程量估计]),
  [1], [`diplomacy-core/protocol/` Protocol / DVProtocol / ProtocolInterface], [无], [小],
  [2], [`diplomacy-core/spec/` NodeSpec / ModuleSpec / DesignSpec], [1], [小],
  [3], [`diplomacy-core/negotiator/` Cardinality + 参数传播 + DV 路由 + Layer merge], [2], [*大*（原算法搬迁 + 重写为纯函数）],
  [4], [`diplomacy-core/builder/` Builder DSL（WrapperModule、节点构造、operator）], [2], [中],
  [5], [core 层 unit test：纯函数协商可独立验证], [1-4], [中],
  [6], [`diplomacy-zaozi/` TerminatorModule + hwBinding 工具], [4], [中],
  [7], [`diplomacy-zaozi/elaborator/` Wrapper path (circtlib) + Terminator path (zaozi)], [3,6], [*大*],
  [8], [至少一个完整协议实现（BundleBridge / TileLink 子集），验证端到端], [7], [中],
)

整体估计 4-6 周（一人全职）。

#line(length: 100%)

== Appendix A：术语对照

#table(
  columns: 3,
  table.header([中文], [英文], [旧 Diplomacy 中的对应]),
  [设计协议], [Protocol], [NodeImp],
  [验证协议], [DVProtocol], [（新增）],
  [协议接口], [ProtocolInterface], [（新增，部分覆盖 Bundle）],
  [拓扑模块], [DiplomaticModule], [LazyModule],
  [包装模块], [WrapperModule], [SimpleLazyModule],
  [终结模块], [TerminatorModule], [LazyModule + LazyModuleImp],
  [用户参数], [UserParam], [（旧的 case class 构造参数）],
  [协议参数], [ProtocolParam], [（旧的 `node.in/.out._2`）],
  [完整参数], [FullParam], [（隐式合成）],
  [协商器], [Negotiator], [散布在 `MixedNode` 的 lazy val],
  [例化器], [Elaborator], [`LazyModuleImp.instantiate`],
  [边描述], [EdgeView], [`node.in / node.out`],
  [硬件绑定], [HWBinding], [旧 `LazyModuleImp` body 里手写 connect],
)

#line(length: 100%)

== Appendix B：示例 SoC

```scala
@main def buildMySoC(): Unit =
  // ====== Phase 1 ======
  val spec = design:
    new WrapperModule:
      val core = submodule(RocketTileDip(
        TileUserParam(hartId = 0, useFPU = true)
      ))
      val l2  = submodule(L2CacheDip(L2UserParam(banks = 4, sizeKB = 512)))
      val dram = submodule(DRAMCtrlDip(DRAMUserParam(0x80000000L, 0x40000000L)))
      val uart = submodule(UARTDip(UARTUserParam(0x10000000L)))
      
      val mbus = submodule(TLXbarDip())
      val pbus = submodule(TLXbarDip())
      
      mbus.node := core.iCacheNode
      mbus.node := core.dCacheNode
      l2.cpuNode := mbus.node
      dram.node  := l2.memNode
      pbus.node  := l2.ioNode
      uart.node  := pbus.node
      
      // DV
      val cosimSink = dvSinkNode[CoreStateProbeProtocol](
        protocol = CoreStateProbe,
        layer    = LayerSpec("verification.cosim")
      )
      cosimSink := core.fsmStateProbe
  
  // ====== Phase 2 ======
  val resolved = Negotiator.negotiate(spec) match
    case Right(r) => r
    case Left(e)  => sys.error(e.report)
  
  // 可选 dump
  PARAMDump.write(resolved, "params.json")
  GraphML.write(resolved, "soc.graphml")
  
  // ====== Phase 3 ======
  withArenaContext:
    val circuit = Circuit.op(circuitName = "MySoC")
    Elaborator.elaborate(resolved, circuit)
    ExportVerilog(circuit, "build/MySoC.sv")
```

#line(length: 100%)
