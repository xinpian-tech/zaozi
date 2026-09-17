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
  ): (UartNodes, Vector[Constraint]) =
    val clkDraft    =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)(
        ClockDomain,
        ResetDomain,
        PowerDomain
      )
    val serialDraft =
      given sourcecode.Name = sourcecode.Name("serial")
      outward(Serial)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )
    val inDraft     =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Axi4)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )

    val clkClock = clkDraft.domain(ClockDomain)

    val clk    = clkDraft.seal(ReadPlan())(_ =>
      Right((
        (),
        Vector(
          clkDraft.domain(ClockDomain).requirement(ClockRequirement(minHz = Some(baud * 8))),
          clkDraft.domain(ResetDomain).requirement(
            ResetRequirement(
              requireAsynchronousAssertion = true,
              requireSynchronousRelease = true,
              requiredActiveHigh = Some(true)
            )
          ),
          clkDraft.domain(PowerDomain).requirement(
            PowerRequirement(minMillivolts = Some(850), maxMillivolts = Some(950), requiresAlwaysOn = true)
          )
        )
      ))
    )
    val serial = serialDraft.seal(ReadPlan())(_ => Right((baud, Vector.empty)))
    val in     = inDraft.seal(ReadPlan())(_ =>
      Right((
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
        ),
        Vector.empty
      ))
    )

    parameters { (view, domains) =>
      val freq = domains.value(clkClock).hz
      val s    = shapeOf(view.edgeOf(in))
      Right(UartP(freq / baud, base, s.addrBits, s.dataBits, s.idBits))
    }
    (UartNodes(clk, serial, in), Vector.empty)

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
