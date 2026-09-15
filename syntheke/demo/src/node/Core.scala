package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[CoreP] = zaozi(CoreGen)

final case class CoreNodes(
  clk:                   ClockReset.Inward,
  mem:                   Axi4.Outward,
  retention:             Retention.Inward,
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
  ): (CoreNodes, Vector[Constraint]) =
    val clkDraft   =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)(
        ClockDomain,
        ResetDomain,
        PowerDomain
      )
    val debugDraft = Option.when(enableDebug) {
      given sourcecode.Name = sourcecode.Name("debug")
      inward(DebugInterrupt)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )
    }

    val retentionDraft =
      given sourcecode.Name = sourcecode.Name("retention")
      inward(Retention)(clkDraft.domain(ClockDomain), ResetDomain, PowerDomain)

    val memDraft =
      given sourcecode.Name = sourcecode.Name("mem")
      outward(Axi4)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )

    val clk       = clkDraft.seal(ReadPlan())(_ => Right(((), Vector.empty)))
    val retention = retentionDraft.fixed(())
    val debugNode = debugDraft.map(_.seal(ReadPlan())(_ => Right((DebugHartCap(CoreP.xlen), Vector.empty))))
    val mem       = memDraft.seal(ReadPlan())(_ =>
      Right((
        AxiMasterPort(
          Vector(AxiMasterParams(name, IdRange(0, 1 << idBits), maxFlight = Some(maxFlight)))
        ),
        Vector.empty
      ))
    )

    parameters { (view, domains) =>
      val s = shapeOf(view.edgeOf(mem))
      (domains.value(clk.domain(PowerDomain)), domains.value(retention.domain(PowerDomain))) match
        case (PowerValue.Supply(cpuMv, _), PowerValue.Supply(retentionMv, _)) =>
          Right(CoreP(resetPc, s.addrBits, s.dataBits, s.idBits, enableDebug, enableTrace, cpuMv, retentionMv))
        case _ => Left(Violation("CPU retention requires physical CPU and retention supplies"))
    }
    val retirement = probe(retirementBinding.from(CoreGen) { (fp, public) =>
      public.instructionTrace.map(field => (InstructionRetirement(fp.xlen, fp.regIndexBits), field))
    })
    (
      CoreNodes(clk, mem, retention, retirement, debugNode),
      Vector(
        clk.domain(PowerDomain).requirement(
          PowerRequirement(minMillivolts = Some(850), maxMillivolts = Some(950))
        ),
        retention.domain(PowerDomain).requirement(PowerRequirement(requiresAlwaysOn = true))
      )
    )

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
