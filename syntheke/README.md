# Syntheke

> Draft the graph. Negotiate the terms. Enact the hardware.

Syntheke (from Greek *συνθήκη* — "treaty, agreement"; Chinese name **合契**) is a
treaty-driven topology and parameter negotiation framework for SoC generators,
implemented in pure Scala 3.

- **`syntheke`** (this module) — the **Build** and **Negotiate** phases of the
  Triptych pipeline plus the tooling exports. Pure computation over plain data;
  no MLIR/CIRCT native dependency.
- **`syntheke.circt`** — the **Elaborate** phase: wrapper modules are emitted
  directly through the CIRCT C-API from the negotiated plans, generator modules
  are reached only through the `GeneratorBackend` contract, the per-module
  `.mlirbc` circuits are linked, and the in-process firtool pipeline lowers the
  design to Verilog. FIRRTL is bytecode throughout — the artifacts are the
  linked circuit's `.mlirbc` and the Verilog; no textual FIRRTL is read or
  written. It depends on CIRCT, and on no eDSL.
- **`syntheke.zaoziBackend`** — one implementation of that contract: a
  generator entry enacted by a zaozi `Generator`, whose zaozi `Parameter` *is*
  the entry's full parameter. The only module that depends on zaozi.
- **`syntheke.demo`** — the design document's motivation SoC as a real design,
  and the only thing here that runs end to end. Mill's job ends at its jar;
  `demo/meson.build` takes over from there — elaborate, verilate, bring up.

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
| 生成器契约、端口结构校验 (@dec-binding-check)、例化流程 | `circt/src/Backend.scala`（契约）, `zaoziBackend/src/ZaoziBackend.scala`（zaozi 实现）, `circt/src/Elaborator.scala` |

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
sysXbar.input("core0") <-- core0.mem

// Elaborate (syntheke.circt): bind entries to backends — syntheke.zaoziBackend has one for zaozi — and get Verilog.
// Elaborator.elaborate(resolved, backends) // ElaboratedDesign; throws ElaborationException at the first error
```

The demo (`demo/`) negotiates over an AXI4 protocol modeled on rocket-chip's
`amba.axi4`: id-space prefixing in the crossbar, upward address aggregation,
a 128→32 width bridge, per-edge conflict reporting, and end-to-end Verilog.
It is the demo's AXI and not a model to build on — enough of the parameter
algebra to put negotiation through its paces, and no further; a protocol
object worth depending on is separate work.

The user story splits by file: `library/` is what an IP author ships —
one file per IP, the same three declarations in each (FullParam, endpoint
class, a def binding the registry entry) — and `Soc.scala` is what an SoC
integrator writes (instantiate and wire). The testbench is wrapped exactly
like an IP but is not one, so it is `Harness.scala`'s and not the
library's, and `Backends.scala` binds every entry from both to its zaozi
generator — the one table the elaboration receives. What someone then does
to the result is split by what knows it:
`Bringup.scala` reads the design back (the address map, and the debugger's
target description out of the settled edges), and `program/hello.S` is the
software, assembled against those same addresses — so the program cannot
disagree with the chip about where its UART is, and where each hart starts
comes from the symbol table rather than an offset someone counted.

What that integrator chooses rather than writes is `SocConfig.scala`: the
rates the chip runs at, the map it decodes, the few numbers a board would
pick. It is an ordinary serializable parameter with defaults, so
`meson setup build -Dconfig=my.json` builds a different chip out of the
same topology — and the elaboration writes the effective configuration
back out as `config.json` beside the design. The line is what changes the
design's shape: how many crossbar ports there are, which IPs exist, how
wide their id spaces are, are `Soc.scala`'s; these are its settings.

The zaozi modules themselves sit under `demo/src/zaoziimpl/`, one real
implementation per file, zaozi API only — the cores are the vendored
DitDah32 RV32EC
(`zaoziimpl/ditdah32/`, MIT) behind a Lite→AXI4 widening shim, and Xbar /
WidthBridge / Uart / Gpio / Dma follow their rocket-chip counterparts
(AXI4Xbar with address decode and arbitration, a width widget, real
peripheral register files; no L2 — an AXI fabric without coherence gives
one nothing testable to do). `demo/src/library/` is the wrap that puts them
on the negotiation graph, one file per IP against one file per
implementation — and not all of them speak AXI: the PLL negotiates clock
domains, the debug transport a TAP and a DMI bus. What is not on the die
keeps to `zaoziimpl/harness/` — the clock generator, the console, the pads,
the JTAG adapter, the DRAM and the trace log — so the directory says which
modules the chip ships and which only surround it.

One convention runs through all of them, and through the protocols they
settle on: a port, a register or a wire is `Bits` unless an arithmetic
operator actually reaches it, and only then is it `UInt`. So the AXI
address, id, data and strobes are bits, and what is left in `UInt` names
itself — the crossbar's round-robin pointer, the UART's baud counters, the
DMA's beat counter, the debug module's `hartsel`, `data1` and `sbAddress`.
Both lower to the same FIRRTL type; the difference is that the declaration
says which signals are numbers, and slicing and concatenating no longer
round-trip through `asBits` and `asUInt` at every step. The exception is
the trace, whose types are the vendored core's — see `TraceProtocol.scala`.

There is no DRAM among them. Memory is not on the die and is not an IP of
this design, so what the chip has is a memory port: an `Axi4` node the
testbench terminates, publishing upward the range it answers for. Behind
it is Ramulator (`nix build .#syntheke-ramulator`) through `DramDpi.scala` — the
timing is a real DRAM simulator's, the contents a byte store beside it,
and the ratio between the DRAM's clock and the port's comes from
Ramulator's own tCK against the settled edge frequency. A register file
pretending to be a DRAM would tell the design nothing about the latency it
will actually see.

The RISC-V debug chain is three more protocols
(`demo/src/DebugProtocols.scala`), one per boundary, because that
is where the parameters actually meet: `Jtag` carries the TAP the
transport implements down to whoever drives the pins (idcode, IR length,
the scan-register widths, the instruction selecting DMI), `Dmi` is the
transport's request/response bus and rejects a debug module whose register
file is wider than the transport can address, and `DebugInterrupt` is one
debug-module port per hart — the halt request with the abstract command
channel and the hart's status back. So the debug module and the transport
are ordinary IPs, lifted out of the core into `Dm.scala` / `Dtm.scala`: the
transport holds the tck-to-system crossing, and the module holds a hart
array selected by `dmcontrol.hartsello`, each hart with its own halt
request and flags. The core keeps only its hart-side debug port. The two
sit inside a `wrapper("DebugIsland")` of their own, so only what leaves the
island — the pins, the hart ports, the bus master — crosses its boundary,
as dangle ports the framework punches through.

The debug module is a fourth edge as well: `dm.sb` is an ordinary `Axi4`
master, the system bus access the debug spec gives a debugger, and the
crossbar sizes it like every other master. That is the path a download
takes, so memory is reachable without borrowing a hart — the alternative
the spec allows, a program buffer, needs the hart to execute the
debugger's instructions, which this core does not do.

Everything that is not the chip is in one module, the design's
`testbench`: `zaoziimpl/harness/TestHarness.scala` publishes the board's 25 MHz reference
clock, holds the debug adapter on the JTAG pins, the DRAM on the memory
port, and terminates the serial and GPIO pins in a console and a board
model. On the die the reference
meets `Pll.scala`, which multiplies it to the 100 MHz system clock and
feeds every consumer — the loop ratio comes out of the settled reference
frequency, and one the dividers cannot reach fails negotiation. So the
chip crosses its boundary with one clock, and the UART and the console
end up in different clock domains on the same wire, each computing its own
divisor (868 and 217) from the frequency at its own edge. It is a container, not a
monolith — each of those is its own zaozi module instantiated inside it —
and every rate it needs comes from the settled edges, so the harness
cannot disagree with the chip about the baud rate or the pin count.
Nothing in the design is test equipment, and no module of the harness is
an IP — which is why neither the wrap nor the modules sit among them.

There is no boot ROM, and no debugger inside the design either. The
adapter on the JTAG pins is `JtagDpi.scala`, an external module whose
behavioral definition is a socket: it clocks one bit per `tck` period out
of whatever a debugger sends and reports `tdo` back, and knows nothing
else. The debugger is [probe-rs](https://probe.rs) — `demo/simprobe/`, a
small crate implementing probe-rs's `RawJtagIo` over that socket, so
everything above the pins (the TAP walk, DTM, debug module, the RISC-V
debug sequences) is probe-rs's own. It attaches, halts the harts, downloads the
program into DRAM over the debug module's system bus, gives each hart its
start PC through `dpc` and resumes them — hart 0 into the done-spin, hart 1
into the program. The target description it reads the chip from is written
out of the settled design: the TAP's IR length comes from the `Jtag` edge,
the RAM range from the memory port's, the harts from the debug module's.

The harts' instruction trace reaches the harness the way the framework
intends verification to work, and it is the only thing in the demo that
does: `RvTrace` (`demo/src/TraceProtocol.scala`) is a `DVProtocol`
carrying the core's whole trace surface, and each `CoreNodes` declares one
`dvSource` of it. That declaration is not a connection — nothing in the
topology mentions the trace — and the framework forwards every leaf up to
the testbench's matching data input on its own. The harness shapes those
ports out of `view.probes`, takes the chip's clock as an ordinary edge to
sample them in, and gives each hart a `TraceLog`. The trace lives in a
FIRRTL layer throughout: `Top.sv` and firtool's `filelist.f` are the
release build, and the `layers-*.sv` collateral binds the trace in on top
of it, which is what the simulation compiles.

What RTL cannot express becomes an external Verilog module declared
through zaozi's `VerilogWrapper` and linked as an extmodule, with its
behavioral definition in `demo/sim/`: `ClockGen`, the board's oscillator,
`SimConsole`, stdout at the far end of the serial line, `JtagDpi`, the
debugger's cable, and `DramDpi`, the memory device — all four in the
harness — and `PllAnalog`, the loop inside the chip's PLL, where a real
flow would put a hard macro. Two of them are wires to real tools rather
than models of our own: probe-rs on one, Ramulator on the other. The SoC
therefore simulates itself: verilator runs the linked Verilog, probe-rs
brings it up over the bridge, and hart 1 prints "hello world" over the
UART at 115200 baud, out of a DDR4 the whole time.

Those `.sv` files drive pins and count beats; everything with state behind
them — the socket the debugger connects to, the memory's byte store and
its requests to Ramulator — is Rust, in `demo/sim/` as a crate of its own,
built into the shared object the simulation resolves its DPI imports
against. Ramulator itself is C++, and a C++ interface (classes, a
`std::function` callback) is not something Rust can call, so it is reached
through a C ABI — `nix/syntheke/ramulator-capi`, bound with bindgen. That
shim is the only C++ written here, and it sits with the packaging of the
C++ library it wraps, not with the simulation.

Everything the demo needs beyond the JVM is a nix package of its own
(`nix/syntheke/` — nothing else in the monorepo depends on them):
`syntheke-ramulator`, `syntheke-ramulator-capi`, `syntheke-dpi`,
`syntheke-simprobe`. `nix develop .#syntheke` is the default shell plus
meson, verilator and all four.

Where the boundary falls: mill compiles Scala into `syntheke.demo.assembly`
and stops. Everything downstream is `demo/meson.build` — run the jar to
elaborate the design, compile the testbench's DPI against Ramulator,
verilate the file set, and bring the result up with a debugger. So the
whole thing is one command:

```
nix develop .#syntheke
cd syntheke/demo && meson setup build && meson test -C build
```

```
1/1 syntheke-demo:soc bring-up   OK   0.68s
```

Each step is a target of its own if you want the pieces: `ninja -C build
artifacts` for the elaborated design, `ninja -C build VTop` for the
simulator. `demo/scripts/` holds the four scripts those targets run,
and what each needs to know about the design — where the debugger attaches,
what it loads where, what the harts should then be seen doing — comes from
`design.env`, which the elaboration writes out of the design itself.
