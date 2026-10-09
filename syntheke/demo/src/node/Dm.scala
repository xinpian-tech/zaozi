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
  ): DmNodes =
    val clkDraft =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)()
    val dmiDraft =
      given sourcecode.Name = sourcecode.Name("dmi")
      inward(Dmi)(clkDraft.domain(ClockDomain), clkDraft.domain(ResetDomain))

    val hartDrafts = (0 until harts).map { i =>
      given sourcecode.Name = sourcecode.Name(s"hart$i")
      outward(DebugInterrupt)(clkDraft.domain(ClockDomain), clkDraft.domain(ResetDomain))
    }.toVector

    val sbDraft =
      given sourcecode.Name = sourcecode.Name("sb")
      outward(Axi4)(clkDraft.domain(ClockDomain), clkDraft.domain(ResetDomain))

    val clk       = clkDraft.fixed(())
    val dmi       = dmiDraft.fixed(DmiSlave(DmNodes.addrBits, 32))
    val hartPorts = hartDrafts.zipWithIndex.map { (port, i) =>
      port.fixed(DebugRequest(i))
    }
    val sb        = sbDraft.fixed(
      AxiMasterPort(Vector(AxiMasterParams(name, IdRange(0, 1 << sbIdBits), maxFlight = Some(1))))
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
    DmNodes(clk, dmi, sb, hartPorts)

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
