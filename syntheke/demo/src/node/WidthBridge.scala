package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[WidthBridgeP] = zaozi(WidthBridgeGen)

final case class WidthBridgeNodes(
  clk: ClockReset.Inward,
  in:  Axi4.Inward,
  out: Axi4.Outward)

object WidthBridgeNodes:
  private[demo] def build(
    wideBeatBytes:       Int,
    maxUpstreamTransfer: Int
  )(
    using GeneratorScope[WidthBridgeP]
  ): (WidthBridgeNodes, Vector[Constraint]) =
    val clkDraft =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)(
        ClockDomain,
        ResetDomain,
        PowerDomain
      )
    val inDraft  =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Axi4)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )
    val outDraft =
      given sourcecode.Name = sourcecode.Name("out")
      outward(Axi4)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )
    val (d, u)   = depend(inDraft, outDraft)


    val clk = clkDraft.seal(ReadPlan())(_ => Right(((), Vector.empty)))
    val out = outDraft.seal(ReadPlan(d))(ctx => Right((ctx(d), Vector.empty)))
    val in  = inDraft.seal(ReadPlan(u)) { ctx =>
      val narrow = ctx(u)
      Right((
        narrow.copy(
          beatBytes = wideBeatBytes,
          slaves = narrow.slaves.map(s =>
            s.copy(
              supportsRead = TransferSizes(s.supportsRead.min, maxUpstreamTransfer),
              supportsWrite = TransferSizes(s.supportsWrite.min, maxUpstreamTransfer)
            )
          )
        ),
        Vector.empty
      ))
    }

    parameters { (view, _) =>
      Right(WidthBridgeP(shapeOf(view.edgeOf(in)), shapeOf(view.edgeOf(out))))
    }
    (WidthBridgeNodes(clk, in, out), Vector.empty)

def widthBridge(
  wideBeatBytes:       Int,
  maxUpstreamTransfer: Int
)(
  using
  ws:                  WrapperScope,
  name:                sourcecode.Name,
  file:                sourcecode.File,
  line:                sourcecode.Line
): WidthBridgeNodes =
  generator[WidthBridgeP](WidthBridgeNodes.build(wideBeatBytes, maxUpstreamTransfer))
