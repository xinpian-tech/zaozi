package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[CpuPowerBoundaryP] = zaozi(CpuPowerBoundaryGen)

final case class CpuPowerBoundaryNodes(
  clk:      ClockReset.Inward,
  port:     PRCMPort.Inward,
  cpuClk:   ClockReset.Outward,
  cpuMem:   Axi4.Inward,
  bus:      Axi4.Outward,
  debug:    DebugInterrupt.Inward,
  cpuDebug: DebugInterrupt.Outward)

object CpuPowerBoundaryNodes:
  private[demo] def build(
    cpuPower: Domain[PowerDomain.type]
  )(
    using GeneratorScope[CpuPowerBoundaryP]
  ): CpuPowerBoundaryNodes =
    val clkDraft =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)()
    val clock = clkDraft.domain(ClockDomain)
    val reset = clkDraft.domain(ResetDomain)
    val aonPower = clkDraft.domain(PowerDomain)
    // The PRCM port brings the managed domain and the clock and reset it drives for the CPU.
    val portDraft =
      given sourcecode.Name = sourcecode.Name("port")
      inward(PRCMPort)(aonPower)
    val prcm = portDraft.domain(PRCMDomain)
    val cpuClock = portDraft.domain(ClockDomain)
    val cpuReset = portDraft.domain(ResetDomain)
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

    val clk = clkDraft.fixed(())
    val port = portDraft.fixed(())
    val cpuClk = cpuClkDraft.fixed(())
    val cpuMem = cpuMemDraft.derive(busDraft)(slave => Right(slave))
    val bus = busDraft.derive(cpuMem) { port =>
      if port.masters.exists(_.maxFlight.forall(_ <= 0)) then
        Left(Violation("CPU power shutdown requires a finite positive AXI maxFlight for every master"))
      else if port.masters.map(m => BigInt(m.maxFlight.get) * (m.id.end - m.id.start)).sum > Int.MaxValue then
        Left(Violation("CPU power shutdown AXI outstanding bound exceeds the counter capacity"))
      else Right(port)
    }
    val debug = debugDraft.derive(cpuDebugDraft)(hart => Right(hart))
    val cpuDebug = cpuDebugDraft.derive(debug)(request => Right(request))

    parameters { (view, domains) =>
      val edge           = view.edgeOf(bus)
      val maxOutstanding = edge.master.masters.map(m => m.maxFlight.get.toLong * (m.id.end - m.id.start)).sum.toInt
      val aon            = PowerDomain.tree(domains(aonPower))
      val cpu            = PowerDomain.tree(domains(cpuPower))
      val systemReset    = domains(reset)
      if !aon.alwaysOn || cpu.alwaysOn then
        Left(Violation("CPU isolation requires an always-on control supply and a switched CPU supply"))
      else if !(PRCMDomain.power(domains(prcm)) eq domains(cpuPower)) then
        Left(Violation(s"the boundary switches ${domains(cpuPower)} but its control sequences ${domains(prcm)}"))
      else if ClockDomain.relate(domains(clock), domains(cpuClock)) != ClockRelation.Synchronous(1, 1) then
        Left(Violation(s"the boundary answers the PRCM on ${domains(clock)}, not on the clock the PRCM gates for the CPU"))
      else if ResetDomain.activeLow(systemReset) || ResetDomain.releaseClock(systemReset).isEmpty then
        Left(Violation("the CPU power boundary needs an active-high reset released on a clock"))
      else
        Right(
          CpuPowerBoundaryP(
            shapeOf(edge),
            view.edgeOf(cpuDebug).xlen,
            cpu.control.settleOnCycles.toInt,
            cpu.control.settleOffCycles.toInt,
            maxOutstanding
          )
        )
    }
    CpuPowerBoundaryNodes(clk, port, cpuClk, cpuMem, bus, debug, cpuDebug)

def cpuPowerBoundary(
  cpuPower: Domain[PowerDomain.type]
)(
  using
  ws:   WrapperScope,
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): CpuPowerBoundaryNodes =
  generator[CpuPowerBoundaryP](CpuPowerBoundaryNodes.build(cpuPower))
