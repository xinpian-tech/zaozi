package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[DmaP] = zaozi(DmaGen)

final case class DmaNodes(
  clk: ClockReset.Inward,
  mem: Axi4.Outward)

object DmaNodes:
  private[demo] def build(
    name:       String,
    idBits:     Int,
    maxFlight:  Int,
    targetBase: Long,
    windowLog2: Int
  )(
    using GeneratorScope[DmaP]
  ): (DmaNodes, Vector[Constraint]) =
    val clkDraft =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)(
        ClockDomain,
        ResetDomain,
        PowerDomain
      )
    val memDraft =
      given sourcecode.Name = sourcecode.Name("mem")
      outward(Axi4)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )


    val clk = clkDraft.seal(ReadPlan())(_ => Right(((), Vector.empty)))
    val mem = memDraft.seal(ReadPlan())(_ =>
      Right((
        AxiMasterPort(
          Vector(AxiMasterParams(name, IdRange(0, 1 << idBits), maxFlight = Some(maxFlight)))
        ),
        Vector.empty
      ))
    )

    parameters { (view, _) =>
      val s = shapeOf(view.edgeOf(mem))
      Right(DmaP(targetBase, windowLog2, s.addrBits, s.dataBits, s.idBits))
    }
    (DmaNodes(clk, mem), Vector.empty)

def dmaCtrl(
  idBits:     Int,
  maxFlight:  Int,
  targetBase: Long,
  windowLog2: Int
)(
  using
  ws:         WrapperScope,
  name:       sourcecode.Name,
  file:       sourcecode.File,
  line:       sourcecode.Line
): DmaNodes =
  generator[DmaP](DmaNodes.build(name.value, idBits, maxFlight, targetBase, windowLog2))
