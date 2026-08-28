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
| 协议对象 (`Protocol`, `DVProtocol`, upickle 序列化) | `Protocol.scala` |
| 构建阶段（用户 API 门面 / 作用域机制 / 冻结的 `DesignSpec`） | `Dsl.scala`, `Builder.scala`, `Spec.scala` |
| 协商算法（结构校验、稳定拓扑序、双向传播、逐边求解、`EdgeView`、遇错即抛） | `Negotiator.scala` |
| 跨层端口规划、端口命名、FIRRTL 层 | `Planner.scala` |
| 已求解记录 (`ResolvedDesign`, `ResolvedEdge`, `EdgeView`, …) | `Resolved.scala` |
| 工具产物（topology/edges/plan/params JSON, @ch-tooling） | `Export.scala` |
| 生成器契约、端口结构校验 (@dec-binding-check)、例化流程 | `circt/src/Backend.scala`, `circt/src/Elaborator.scala` |

Deviations from the document's surface syntax:

- The bind operator is written `target <-- source`: `<-` itself is a reserved
  token in Scala.
- Dangle-port name segments encode to FIRRTL-legal identifiers by joining with
  `_` and escaping `_` → `$u`, `-` → `$m`, `$` → `$$` (the document leaves the
  concrete reversible encoding to the implementation).
- Verification is publish-only: a `dvSource` declares the probes a module
  publishes (`DVProtocol.interfaceOf(down, layer)`, checked at the
  declaration), and the framework forwards every probe leaf automatically —
  one pure-probe dangle per signal leaf, `ref.define` per wrapper boundary —
  up to the root. There is no sink and no verification bind in the design
  graph.
- The testbench is a special generator module (`testbench(entry){...}`, top
  level only, at most one): its nodes bind to the design's nodes with the
  ordinary `<--` and negotiate like any edge, terminating the design's
  outward-facing interfaces. Its one specialty: the framework wires every
  probe leaf of the design into a matching data input — its `parameters`
  reads the manifest from `view.probes` (computed after the spec froze, so
  declaration order never matters) to shape those ports and the FullParam,
  checked by the same binding checkpoint as any generator; `resolved.probes`
  serves external tools. Without a testbench the probes surface as
  layer-gated top-level ports.

```scala
import me.jiuyang.syntheke.*

val spec = Design {
  // Declarations are named by the val they are bound to (sourceinfo, like zaozi);
  // provide `given sourcecode.Name = sourcecode.Name(s"in$i")` for computed names.
  val prod = generator(prodEntry) {
    parameters(_ => Right(0))
    val out = outward(Wid).dFn(_ => Right(32))
    out
  }
  val cons = generator(consEntry) {
    parameters(_ => Right(0))
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
val Core = new GeneratorEntry[CoreFull]   // registry name "Core", from the val

final class CoreNodes(name: String, idBits: Int)(using GeneratorScope[CoreFull]) extends Nodes:
  parameters(_ => Right(CoreFull(name, idBits)))
  val mem = outward(Axi4).dFn(...)

def core(idBits: Int)(using ws: WrapperScope, name: sourcecode.Name, file: sourcecode.File, line: sourcecode.Line): CoreNodes =
  generator(Core)(new CoreNodes(name.value, idBits))

// in any design:
val core0 = core(idBits = 2)   // instance "core0"
sysXbar.input("in0") <-- core0.mem

// Elaborate (syntheke.circt): bind entries to zaozi generators, get Verilog.
// Elaborator.elaborate(resolved, backends) // ElaboratedDesign; throws ElaborationException at the first error
```

The AXI4 demo (`tests/src/axi/`, `circt/tests/src/`) mirrors rocket-chip's
`amba.axi4` parameter model over the design document's motivation SoC:
id-space prefixing in the crossbar, upward address aggregation, a 128→32
width bridge, per-edge conflict reporting, and end-to-end Verilog. The user
story splits by file: `AxiLibrary.scala` is what an IP author ships (per IP:
FullParam, endpoint class, a def binding the registry entry), the spec file
is what an SoC integrator writes (instantiate and wire). On the circt side
the zaozi modules themselves sit under `tests/src/zaoziimpl/`, one real
implementation per file, zaozi API only — the cores are the vendored
DitDah32 RV32EC (`zaoziimpl/ditdah32/`, MIT) behind a Lite→AXI4 widening
shim, and Xbar / BootRom / Dram / WidthBridge / Uart / Gpio / Dma follow
their rocket-chip counterparts (AXI4Xbar with address decode and
arbitration, an AXI4ROM, a burst-capable AXI4RAM, a width widget, real
peripheral register files; no L2 — an AXI fabric without coherence gives
one nothing testable to do). `circt/tests/src/AxiLibrary.scala` is the wrap
that puts them on the negotiation graph; a clock tree, serial pins and GPIO
pads reach every IP. The two places RTL cannot go are external Verilog
modules declared through zaozi's `VerilogWrapper` and linked as extmodules,
each shipping its behavioral definition as a string next to the wrapper:
`ClockGen`, the clock/reset origin inside ClockSource, and `SimConsole`,
stdout inside the Console device — the framework's `testbench` feature
terminating the serial pins. The SoC therefore simulates itself: the
AxiVerilogSpec boots both cores from the BootRom image (a hand-assembled
RV32E program), and verilator prints the "hello world" it received over
the UART at 115200 baud.

`tests/src/zaoziimpl/UartDevice.scala` is a real device: an 8N1 UART with
a single-beat AXI slave register file, written as a plain zaozi module
(zaozi implementations live under `tests/src/zaoziimpl`).
`circt/tests/src/UartSpec.scala` wraps it with a minimal clock-domain protocol (the
source's frequency flows down; the UART computes its baud divisor from
the settled clock and capability-checks it) and a serial pin protocol,
then negotiates and lowers it to Verilog.
