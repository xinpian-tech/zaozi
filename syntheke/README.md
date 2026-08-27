# Syntheke

> Draft the graph. Negotiate the terms. Enact the hardware.

Syntheke (from Greek *συνθήκη* — "treaty, agreement"; Chinese name **合契**) is a
treaty-driven topology and parameter negotiation framework for SoC generators,
implemented in pure Scala 3.

- **`syntheke`** (this module) — the **Build** and **Negotiate** phases of the
  Triptych pipeline plus the tooling exports. Pure computation over plain data;
  no MLIR/CIRCT native dependency.
- **`syntheke.circt`** — the **Elaborate** phase: generator modules are enacted
  by zaozi through a `GeneratorBackend`, wrapper modules are emitted directly
  through the CIRCT C-API from the negotiated plans, the per-module `.mlirbc`
  circuits are linked, and the in-process firtool pipeline lowers the design to
  Verilog. No textual FIRRTL is ever constructed by hand.

The design contract is the Syntheke design document (`syntheke` repository,
branch `init`, `doc/design/`, Chinese). Correspondence:

| design document | here |
|---|---|
| 稳定标识 (`ModuleId`, `ModuleNodeId`, `BindId`, …) | `Ids.scala` |
| 协议接口 (`ProtocolBundle`, `InterfacePath`, `LayerPath`) | `Interface.scala` |
| 协议对象 (`Protocol`, `DVProtocol`, `Codec`, `render`) | `Protocol.scala` |
| 构建阶段 (`DesignSpec`, 模块与节点规格) | `Spec.scala`, `Builder.scala`, `Dsl.scala` |
| 协商算法（结构校验、稳定拓扑序、双向传播、逐边求解、`EdgeView`、遇错即抛） | `Negotiator.scala` |
| 跨层端口规划、端口命名、FIRRTL 层 | `Planner.scala` |
| 已求解记录 (`ResolvedDesign`, `ResolvedEdge`, `EdgeView`, …) | `Resolved.scala` |
| 模块身份与去重 (@sec-dedup 结构键、模块命名) | `Dedup.scala` |
| 工具产物（topology/edges/plan/params JSON, @ch-tooling） | `Export.scala` |
| 可视化（DOT 与 GraphML, @sec-visualization） | `Viz.scala` |
| 生成器契约、端口结构校验 (@dec-binding-check)、例化流程 | `circt/src/Backend.scala`, `circt/src/Elaborator.scala` |

Deviations from the document's surface syntax:

- The bind operator is written `target <-- source`: `<-` itself is a reserved
  token in Scala.
- Dangle-port name segments encode to FIRRTL-legal identifiers by joining with
  `_` and escaping `_` → `$u`, `-` → `$m`, `$` → `$$` (the document leaves the
  concrete reversible encoding to the implementation).
- FIRRTL forbids input probe ports, so a probe *sink* generator's ports carry
  the probe-*stripped* (resolved) interface: the enclosing wrapper
  `ref.resolve`s every probe inside a layerblock and feeds plain data into the
  sink instance, which is itself instantiated under that layerblock (the bind
  pattern). The probe-typed `DVInterfaces.sink` stays the protocol-level
  contract; `ProtocolBundle.stripProbes` is the boundary rule. This also means
  a sink is expressible in zaozi as ordinary data inputs — the demo uses a
  raw-CAPI `StubBackend` only to keep its body trivial.

```scala
import me.jiuyang.syntheke.*

val spec = Design {
  // Declarations are named by the val they are bound to (sourceinfo, like zaozi);
  // provide `given sourcecode.Name = sourcecode.Name(s"in$i")` for computed names.
  val prod = generator(prodEntry) {
    parametersConst(0)
    val out = outward(Wid).dFn(_ => Right(32))
    out
  }
  val cons = generator(consEntry) {
    parametersConst(0)
    val in = inward(Wid).uFn(_ => Right(64))
    in
  }
  cons <-- prod
}
val resolved = Negotiator.negotiate(spec) // ResolvedDesign; throws NegotiationException at the first error
```

A reusable module definition is an endpoint class plus a def binding the
entry: the class declares nodes as fields (each named by its val), the def
forwards the build context so the instance name comes from the call-site
val. A def taking `sourcecode.Name` must contain only the single
`generator`/`wrapper` call — named declarations belong in the endpoint
class, where the context name cannot capture them.

```scala
final class CorePorts(name: String, idBits: Int)(using GeneratorScope[CoreFull]) extends Endpoints:
  parametersConst(CoreFull(name, idBits))
  val mem = outward(Axi4).dFn(...)

def core(idBits: Int)(using ws: WrapperScope, name: sourcecode.Name, loc: SourceLocation): CorePorts =
  generator(coreEntry)(new CorePorts(name.value, idBits))

// in any design:
val core0 = core(idBits = 2)   // instance "core0"
sysXbar.inputs(0) <-- core0.mem

// Elaborate (syntheke.circt): bind entries to zaozi generators, get Verilog.
// Elaborator.elaborate(resolved, backends) // ElaboratedDesign; throws ElaborationException at the first error
```

The AXI4 demo (`tests/src/axi/`, `circt/tests/src/`) mirrors rocket-chip's
`amba.axi4` parameter model over the design document's motivation SoC:
id-space prefixing in the crossbar, upward address aggregation, a 128→32
width bridge, per-edge conflict reporting, and end-to-end Verilog.
