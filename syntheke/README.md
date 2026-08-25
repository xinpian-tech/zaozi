# Syntheke

> Draft the graph. Negotiate the terms. Enact the hardware.

Syntheke (from Greek *συνθήκη* — "treaty, agreement"; Chinese name **合契**) is a
treaty-driven topology and parameter negotiation framework for SoC generators,
implemented in pure Scala 3. This module contains the **Build** and
**Negotiate** phases of the Triptych pipeline; both are pure computation over
plain data and depend on no MLIR/CIRCT native code. The **Elaborate** phase
(zaozi generator invocation, wrapper emission, wiring) binds to the rest of
this monorepo in a separate module.

The design contract is the Syntheke design document (`syntheke` repository,
`doc/design/`, Chinese). Correspondence:

| design document | here |
|---|---|
| 稳定标识 (`ModuleId`, `ModuleNodeId`, `BindId`, …) | `Ids.scala` |
| 协议接口 (`ProtocolBundle`, `InterfacePath`, `LayerPath`) | `Interface.scala` |
| 协议对象 (`Protocol`, `DVProtocol`, `Codec`, `render`) | `Protocol.scala` |
| 构建阶段 (`DesignSpec`, 模块与节点规格) | `Spec.scala`, `Builder.scala`, `Dsl.scala` |
| 协商算法（结构校验、稳定拓扑序、双向传播、逐边求解、`EdgeView`、N1–N10） | `Negotiator.scala` |
| 跨层端口规划、端口命名、FIRRTL 层 | `Planner.scala` |
| 已求解记录 (`ResolvedDesign`, `ResolvedEdge`, `EdgeView`, …) | `Resolved.scala` |

Deviations from the document's surface syntax:

- The bind operator is written `target <-- source`: `<-` itself is a reserved
  token in Scala.
- Dangle-port name segments encode to FIRRTL-legal identifiers by joining with
  `_` and escaping `_` → `$u`, `-` → `$m`, `$` → `$$` (the document leaves the
  concrete reversible encoding to the implementation).

```scala
import me.jiuyang.syntheke.*

val spec = Design {
  var out: OutwardNodeBuilder[Wid.type] = null
  var in:  InwardNodeBuilder[Wid.type]  = null
  generator("prod", prodEntry) {
    out = outward(Wid)("out").dFn(_ => Right(32))
    parametersConst(0)
  }
  generator("cons", consEntry) {
    in = inward(Wid)("in").uFn(_ => Right(64))
    parametersConst(0)
  }
  in <-- out
}
Negotiator.negotiate(spec) // Either[Vector[NegotiationError], ResolvedDesign]
```
