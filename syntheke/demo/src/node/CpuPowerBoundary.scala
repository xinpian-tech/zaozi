package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[CpuPowerBoundaryP] = zaozi(CpuPowerBoundaryGen)

final case class CpuPowerBoundaryNodes(
  clk:      ClockReset.Inward,
  cpuClk:   ClockReset.Outward,
  cpuMem:   Axi4.Inward,
  bus:      Axi4.Outward,
  debug:    DebugInterrupt.Inward,
  cpuDebug: DebugInterrupt.Outward,
  retention: Retention.Outward,
  control:  PowerControl.Inward)

object CpuPowerBoundaryNodes:
  private[demo] def build(
    cpuPower:      DomainHandle[PowerDomain.type],
    startupCycles: Int
  )(
    using GeneratorScope[CpuPowerBoundaryP]
  ): (CpuPowerBoundaryNodes, Vector[Constraint]) =
    val clkDraft =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)(ClockDomain, ResetDomain, PowerDomain)
    val clock = clkDraft.domain(ClockDomain)
    val reset = clkDraft.domain(ResetDomain)
    val aonPower = clkDraft.domain(PowerDomain)
    val cpuClock = clock.derive(ClockDomain) { view =>
      Right((view.value(clock), None))
    }
    val cpuReset = reset.derive(ResetDomain) { view =>
      Right((view.value(reset), None))
    }
    val cpuClkDraft =
      given sourcecode.Name = sourcecode.Name("cpuClk")
      outward(ClockReset)(cpuClock, cpuReset, cpuPower)
    val cpuMemDraft =
      given sourcecode.Name = sourcecode.Name("cpuMem")
      inward(Axi4)(cpuClock, cpuReset, cpuPower)
    val busDraft =
      given sourcecode.Name = sourcecode.Name("bus")
      outward(Axi4)(clock, reset, aonPower)
    val debugDraft =
      given sourcecode.Name = sourcecode.Name("debug")
      inward(DebugInterrupt)(clock, reset, aonPower)
    val cpuDebugDraft =
      given sourcecode.Name = sourcecode.Name("cpuDebug")
      outward(DebugInterrupt)(cpuClock, cpuReset, cpuPower)
    val controlDraft =
      given sourcecode.Name = sourcecode.Name("control")
      inward(PowerControl)(clock, reset, aonPower)
    val retentionReset = reset.derive(ResetDomain)(view => Right((view.value(reset), None)))
    val retentionDraft =
      given sourcecode.Name = sourcecode.Name("retention")
      outward(Retention)(cpuClock, retentionReset, aonPower)

    val (masters, slaves) = depend(cpuMemDraft, busDraft)
    val (request, hart) = depend(debugDraft, cpuDebugDraft)
    val clk = clkDraft.fixed(())
    val cpuClk = cpuClkDraft.fixed(())
    val control = controlDraft.fixed(())
    val retention = retentionDraft.fixed(())
    val cpuMem = cpuMemDraft.seal(ReadPlan(slaves))(values => Right((values(slaves), Vector.empty)))
    val bus = busDraft.seal(ReadPlan(masters)) { values =>
      val port = values(masters)
      if port.masters.exists(_.maxFlight.forall(_ <= 0)) then
        Left(Violation("CPU power shutdown requires a finite positive AXI maxFlight for every master"))
      else if port.masters.map(m => BigInt(m.maxFlight.get) * (m.id.end - m.id.start)).sum > Int.MaxValue then
        Left(Violation("CPU power shutdown AXI outstanding bound exceeds the counter capacity"))
      else Right((port, Vector.empty))
    }
    val debug = debugDraft.seal(ReadPlan(hart))(values => Right((values(hart), Vector.empty)))
    val cpuDebug = cpuDebugDraft.seal(ReadPlan(request))(values => Right((values(request), Vector.empty)))

    val isolation = Seq(aonPower, cpuPower).check { view =>
      (view.value(aonPower), view.value(cpuPower)) match
        case (_: PowerValue.Supply, PowerValue.Supply(_, false)) =>
          if view.sameIdentity(aonPower, cpuPower) then
            Left(Violation("CPU isolation requires independent AON and CPU supplies"))
          else Right(())
        case _ => Left(Violation("CPU isolation requires an AON supply and a switchable CPU supply"))
    }

    parameters { (view, domains) =>
      val edge = view.edgeOf(bus)
      val maxOutstanding = edge.master.masters.map(m => m.maxFlight.get.toLong * (m.id.end - m.id.start)).sum.toInt
      (domains.value(aonPower), domains.value(cpuPower)) match
        case (PowerValue.Supply(aonMv, _), PowerValue.Supply(cpuMv, _)) =>
          Right(CpuPowerBoundaryP(shapeOf(edge), view.edgeOf(cpuDebug).xlen, startupCycles, maxOutstanding, aonMv, cpuMv))
        case _ => Left(Violation("CPU power boundary requires physical supply declarations"))
    }
    (
      CpuPowerBoundaryNodes(clk, cpuClk, cpuMem, bus, debug, cpuDebug, retention, control),
      Vector(
        aonPower.requirement(PowerRequirement(requiresAlwaysOn = true)),
        reset.requirement(ResetRequirement(
          requireAsynchronousAssertion = true,
          requireSynchronousRelease = true,
          requiredActiveHigh = Some(true)
        )),
        isolation
      )
    )

def cpuPowerBoundary(
  cpuPower:      DomainHandle[PowerDomain.type],
  startupCycles: Int
)(
  using
  ws:   WrapperScope,
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): CpuPowerBoundaryNodes =
  generator[CpuPowerBoundaryP](CpuPowerBoundaryNodes.build(cpuPower, startupCycles))
