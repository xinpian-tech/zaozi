package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[DmiCrossingP] = zaozi(DmiCrossingGen)

final case class DmiCrossingNodes(
  enqClk: ClockReset.Inward,
  deqClk: ClockReset.Inward,
  in:     Dmi.Inward,
  out:    Dmi.Outward)

object DmiCrossingNodes:
  private[demo] def build(
    depth: Int
  )(
    using GeneratorScope[DmiCrossingP]
  ): DmiCrossingNodes =
    val enqClkDraft =
      given sourcecode.Name = sourcecode.Name("enqClk")
      inward(ClockReset)()

    val deqClkDraft =
      given sourcecode.Name = sourcecode.Name("deqClk")
      inward(ClockReset)()
    // The dequeue reset, released on the enqueue clock.
    val enqReset    = ResetDomain.target(activeLow = false, deqClkDraft.domain(ResetDomain) -> None)(
      Some(ResetProcessing.Async(enqClkDraft.domain(ClockDomain), 2))
    )
    val inDraft     =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Dmi)(enqClkDraft.domain(ClockDomain), enqReset)
    val outDraft    =
      given sourcecode.Name = sourcecode.Name("out")
      outward(Dmi)(deqClkDraft.domain(ClockDomain), deqClkDraft.domain(ResetDomain))

    val enqClock = enqClkDraft.domain(ClockDomain)
    val deqClock = deqClkDraft.domain(ClockDomain)

    val enqClk = enqClkDraft.fixed(())
    val deqClk = deqClkDraft.fixed(())
    val out    = outDraft.derive(inDraft)(master => Right(master))
    val in     = inDraft.derive(out)(slave => Right(slave))

    parameters { (view, domains) =>
      val e = view.edgeOf(out)
      if ClockDomain.relate(domains(enqClock), domains(deqClock)) != ClockRelation.Asynchronous then
        Left(Violation("the DMI crossing synchronizes between two asynchronous clocks"))
      else Right(DmiCrossingP(e.abits, e.dataBits, depth))
    }
    DmiCrossingNodes(enqClk, deqClk, in, out)

def dmiCrossing(
  depth: Int
)(
  using
  ws:    WrapperScope,
  name:  sourcecode.Name,
  file:  sourcecode.File,
  line:  sourcecode.Line
): DmiCrossingNodes =
  generator[DmiCrossingP](DmiCrossingNodes.build(depth))
