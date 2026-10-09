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
    wideBeatBytes: Int
  )(
    using GeneratorScope[WidthBridgeP]
  ): WidthBridgeNodes =
    val clkDraft =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)()
    val inDraft  =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Axi4)(clkDraft.domain(ClockDomain), clkDraft.domain(ResetDomain))
    val outDraft =
      given sourcecode.Name = sourcecode.Name("out")
      outward(Axi4)(clkDraft.domain(ClockDomain), clkDraft.domain(ResetDomain))

    val clk = clkDraft.fixed(())
    val out = outDraft.derive(inDraft)(master => Right(master))
    val in  = inDraft.derive(out) { narrow =>
      def singleBeat(sizes: TransferSizes): TransferSizes =
        if sizes.min > narrow.beatBytes then TransferSizes(0, 0)
        else TransferSizes(sizes.min, math.min(sizes.max, narrow.beatBytes))
      Right(
        narrow.copy(
          beatBytes = wideBeatBytes,
          slaves = narrow.slaves.map(s =>
            s.copy(
              supportsRead = singleBeat(s.supportsRead),
              supportsWrite = singleBeat(s.supportsWrite)
            )
          )
        )
      )
    }

    parameters { (view, _) =>
      Right(WidthBridgeP(shapeOf(view.edgeOf(in)), shapeOf(view.edgeOf(out))))
    }
    WidthBridgeNodes(clk, in, out)

def widthBridge(
  wideBeatBytes: Int
)(
  using
  ws:            WrapperScope,
  name:          sourcecode.Name,
  file:          sourcecode.File,
  line:          sourcecode.Line
): WidthBridgeNodes =
  generator[WidthBridgeP](WidthBridgeNodes.build(wideBeatBytes))
