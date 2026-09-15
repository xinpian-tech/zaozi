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
  ): (DmiCrossingNodes, Vector[Constraint]) =
    val enqClkDraft =
      given sourcecode.Name = sourcecode.Name("enqClk")
      inward(ClockReset)(
        ClockDomain,
        ResetDomain,
        PowerDomain
      )

    val deqClkDraft =
      given sourcecode.Name = sourcecode.Name("deqClk")
      inward(ClockReset)(
        ClockDomain,
        ResetDomain,
        PowerDomain
      )
    val inDraft     =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Dmi)(
        enqClkDraft.domain(ClockDomain),
        deqClkDraft.domain(ResetDomain),
        PowerDomain
      )
    val outDraft    =
      given sourcecode.Name = sourcecode.Name("out")
      outward(Dmi)(
        deqClkDraft.domain(ClockDomain),
        deqClkDraft.domain(ResetDomain),
        PowerDomain
      )
    val (d, u)      = depend(inDraft, outDraft)

    val enqClock = enqClkDraft.domain(ClockDomain)
    val deqClock = deqClkDraft.domain(ClockDomain)

    val distinctClocks = Seq(enqClock, deqClock).check { domains =>
      if domains.sameIdentity(enqClock, deqClock) then
        Left(Violation("DMI crossing endpoints must use distinct clock-domain identities"))
      else Right(())
    }

    val enqClk = enqClkDraft.seal(ReadPlan())(_ => Right(((), Vector.empty)))
    val deqClk = deqClkDraft.seal(ReadPlan())(_ => Right(((), Vector.empty)))
    val out    = outDraft.seal(ReadPlan(d))(ctx => Right((ctx(d), Vector.empty)))
    val in     = inDraft.seal(ReadPlan(u))(ctx => Right((ctx(u), Vector.empty)))

    parameters { (view, _) =>
      val e = view.edgeOf(out)
      Right(DmiCrossingP(e.abits, e.dataBits, depth))
    }
    (DmiCrossingNodes(enqClk, deqClk, in, out), Vector(distinctClocks))

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
