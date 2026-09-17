package me.jiuyang.syntheke.demo

import com.vowstar.ditdah32.JtagInstruction
import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[DtmP] = zaozi(DtmGen)

final case class DtmNodes(
  tck:  ClockReset.Inward,
  jtag: Jtag.Outward,
  dmi:  Dmi.Outward)

object DtmNodes:
  val irLength: Int = 5

  private[demo] def build(
    idcode: Long,
    abits:  Int
  )(
    using GeneratorScope[DtmP]
  ): (DtmNodes, Vector[Constraint]) =
    val tckDraft =
      given sourcecode.Name = sourcecode.Name("tck")
      inward(ClockReset)(
        ClockDomain,
        ResetDomain,
        PowerDomain
      )

    val jtagDraft =
      given sourcecode.Name = sourcecode.Name("jtag")
      outward(Jtag)(
        tckDraft.domain(ClockDomain),
        tckDraft.domain(ResetDomain),
        PowerDomain
      )
    val dmiDraft  =
      given sourcecode.Name = sourcecode.Name("dmi")
      outward(Dmi)(
        tckDraft.domain(ClockDomain),
        tckDraft.domain(ResetDomain),
        PowerDomain
      )


    val tck  = tckDraft.seal(ReadPlan())(_ => Right(((), Vector.empty)))
    val jtag =
      jtagDraft.seal(ReadPlan())(_ => Right((JtagTap(idcode, DtmNodes.irLength, abits, 32, JtagInstruction.DMI), Vector.empty)))
    val dmi  = dmiDraft.seal(ReadPlan())(_ => Right((DmiMaster(abits, 32), Vector.empty)))

    parameters { (view, _) =>
      val e = view.edgeOf(dmi)
      Right(DtmP(idcode, DtmNodes.irLength, e.abits, e.dataBits))
    }
    (DtmNodes(tck, jtag, dmi), Vector.empty)

def debugTransport(
  idcode: Long,
  abits:  Int
)(
  using
  ws:     WrapperScope,
  name:   sourcecode.Name,
  file:   sourcecode.File,
  line:   sourcecode.Line
): DtmNodes =
  generator[DtmP](DtmNodes.build(idcode, abits))
