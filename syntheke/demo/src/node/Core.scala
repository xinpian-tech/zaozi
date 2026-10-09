package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[CoreP] = zaozi(CoreGen)

final case class CoreNodes(
  clk:                   ClockReset.Inward,
  mem:                   Axi4.Outward,
  retirement:            ProbeNode[InstructionRetirement],
  private val debugNode: Option[DebugInterrupt.Inward]):
  def debug: DebugInterrupt.Inward =
    require(debugNode.isDefined, s"core '${clk.id.module.show}' was built without a debug node")
    debugNode.get

object CoreNodes:
  private[demo] def build(
    name:        String,
    idBits:      Int,
    maxFlight:   Int,
    resetPc:     Int,
    enableDebug: Boolean,
    enableTrace: Boolean
  )(
    using GeneratorScope[CoreP]
  ): CoreNodes =
    val clkDraft   =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)()
    val debugDraft = Option.when(enableDebug) {
      given sourcecode.Name = sourcecode.Name("debug")
      inward(DebugInterrupt)(clkDraft.domain(ClockDomain), clkDraft.domain(ResetDomain))
    }

    val memDraft =
      given sourcecode.Name = sourcecode.Name("mem")
      outward(Axi4)(clkDraft.domain(ClockDomain), clkDraft.domain(ResetDomain))

    val clk       = clkDraft.fixed(())
    val debugNode = debugDraft.map(_.fixed(DebugHartCap(CoreP.xlen)))
    val mem       = memDraft.fixed(
      AxiMasterPort(
        Vector(AxiMasterParams(name, IdRange(0, 1 << idBits), maxFlight = Some(maxFlight)))
      )
    )

    parameters { (view, domains) =>
      val s = shapeOf(view.edgeOf(mem))
      val reset = domains(clk.domain(ResetDomain))
      Right(CoreP(resetPc, s.addrBits, s.dataBits, s.idBits, enableDebug, enableTrace, ResetDomain.activeLow(reset)))
    }
    val retirement = probe(retirementBinding.from(CoreGen) { (fp, public) =>
      public.instructionTrace.map(field => (InstructionRetirement(fp.xlen, fp.regIndexBits), field))
    })
    CoreNodes(clk, mem, retirement, debugNode)

def core(
  idBits:      Int,
  maxFlight:   Int,
  resetPc:     Int,
  enableDebug: Boolean,
  enableTrace: Boolean
)(
  using
  ws:          WrapperScope,
  name:        sourcecode.Name,
  file:        sourcecode.File,
  line:        sourcecode.Line
): CoreNodes =
  generator[CoreP](CoreNodes.build(name.value, idBits, maxFlight, resetPc, enableDebug, enableTrace))
