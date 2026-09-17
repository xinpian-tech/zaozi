package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[DmP] = zaozi(DmGen)

final case class DmNodes(
  clk:                   ClockReset.Inward,
  dmi:                   Dmi.Inward,
  sb:                    Axi4.Outward,
  private val hartPorts: Vector[DebugInterrupt.Outward]):
  def hart(i: Int): DebugInterrupt.Outward =
    require(
      i >= 0 && i < hartPorts.size,
      s"debug module '${clk.id.module.show}' has no hart $i (holds ${hartPorts.size})"
    )
    hartPorts(i)

object DmNodes:
  val addrBits: Int = 7

  private[demo] def build(
    name:        String,
    harts:       Int,
    haltOnReset: Boolean,
    sbIdBits:    Int
  )(
    using GeneratorScope[DmP]
  ): (DmNodes, Vector[Constraint]) =
    val clkDraft =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)(
        ClockDomain,
        ResetDomain,
        PowerDomain
      )
    val dmiDraft =
      given sourcecode.Name = sourcecode.Name("dmi")
      inward(Dmi)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )

    val hartDrafts = (0 until harts).map { i =>
      given sourcecode.Name = sourcecode.Name(s"hart$i")
      outward(DebugInterrupt)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )
    }.toVector

    val sbDraft =
      given sourcecode.Name = sourcecode.Name("sb")
      outward(Axi4)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )

    val clk       = clkDraft.seal(ReadPlan())(_ => Right(((), Vector.empty)))
    val dmi       = dmiDraft.seal(ReadPlan())(_ => Right((DmiSlave(DmNodes.addrBits, 32), Vector.empty)))
    val hartPorts = hartDrafts.zipWithIndex.map { (port, i) =>
      port.seal(ReadPlan())(_ => Right((DebugRequest(i), Vector.empty)))
    }
    val sb        = sbDraft.seal(ReadPlan())(_ =>
      Right((
        AxiMasterPort(Vector(AxiMasterParams(name, IdRange(0, 1 << sbIdBits), maxFlight = Some(1)))),
        Vector.empty
      ))
    )

    parameters { (view, _) =>
      for
        e             = view.edgeOf(dmi)
        sbE           = view.edgeOf(sb)
        xlens         = hartPorts.map(port => view.edgeOf(port).xlen)
        distinctXlens = xlens.distinct
        _            <- if distinctXlens.sizeIs == 1 then Right(())
                        else Left(Violation(s"harts disagree on register width: ${distinctXlens.mkString(", ")}"))
        _            <- if distinctXlens.head == e.dataBits then Right(())
                        else
                          Left(
                            Violation(
                              s"hart register width ${distinctXlens.head} does not match the ${e.dataBits}-bit abstract data path"
                            )
                          )
      yield
        val s = shapeOf(sbE)
        DmP(harts, e.abits, e.dataBits, distinctXlens.head, haltOnReset, s.addrBits, s.dataBits, s.idBits)
    }
    (DmNodes(clk, dmi, sb, hartPorts), Vector.empty)

def debugModule(
  harts:       Int,
  haltOnReset: Boolean,
  sbIdBits:    Int
)(
  using
  ws:          WrapperScope,
  name:        sourcecode.Name,
  file:        sourcecode.File,
  line:        sourcecode.Line
): DmNodes =
  generator[DmP](DmNodes.build(name.value, harts, haltOnReset, sbIdBits))
