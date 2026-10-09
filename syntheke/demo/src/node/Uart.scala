package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[UartP] = zaozi(UartGen)

final case class UartNodes(
  clk:    ClockReset.Inward,
  serial: Serial.Outward,
  in:     Axi4.Inward)

object UartNodes:
  private[demo] def build(
    name:           String,
    base:           Long,
    size:           Long,
    idCapacityBits: Int,
    baud:           Int
  )(
    using GeneratorScope[UartP]
  ): UartNodes =
    val clkDraft    =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)()
    val serialDraft =
      given sourcecode.Name = sourcecode.Name("serial")
      outward(Serial)(clkDraft.domain(ClockDomain), clkDraft.domain(ResetDomain))
    val inDraft     =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Axi4)(clkDraft.domain(ClockDomain), clkDraft.domain(ResetDomain))

    val clkClock = clkDraft.domain(ClockDomain)

    val clk    = clkDraft.fixed(())
    val serial = serialDraft.fixed(baud)
    val in     = inDraft.fixed(
      AxiSlavePort(
        slaves = Vector(
          AxiSlaveParams(
            name,
            AddressSet.misaligned(base, size),
            RegionType.PutEffects,
            executable = false,
            supportsWrite = TransferSizes(1, 4),
            supportsRead = TransferSizes(1, 4)
          )
        ),
        beatBytes = 4,
        idCapacityBits = idCapacityBits,
        minLatency = 1
      )
    )

    parameters { (view, domains) =>
      val freq  = ClockDomain.hz(domains(clkClock))
      val reset = domains(clk.domain(ResetDomain))
      val power = PowerDomain.tree(domains(clk.domain(PowerDomain)))
      val s     = shapeOf(view.edgeOf(in))
      if freq < baud * 8 then Left(Violation(s"the UART needs at least ${baud * 8} Hz for $baud baud, not $freq Hz"))
      else if ResetDomain.activeLow(reset) || ResetDomain.releaseClock(reset).isEmpty then
        Left(Violation("the UART needs an active-high reset released on a clock"))
      else if !power.alwaysOn then Left(Violation("the UART needs an always-on supply"))
      else Right(UartP(freq / baud, base, s.addrBits, s.dataBits, s.idBits))
    }
    UartNodes(clk, serial, in)

def uartCtrl(
  base:           Long,
  size:           Long,
  idCapacityBits: Int,
  baud:           Int
)(
  using
  ws:             WrapperScope,
  name:           sourcecode.Name,
  file:           sourcecode.File,
  line:           sourcecode.Line
): UartNodes =
  generator[UartP](UartNodes.build(name.value, base, size, idCapacityBits, baud))
