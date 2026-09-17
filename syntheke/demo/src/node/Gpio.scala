package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[GpioP] = zaozi(GpioGen)

final case class GpioNodes(
  clk:  ClockReset.Inward,
  pins: GpioPins.Outward,
  in:   Axi4.Inward)

object GpioNodes:
  private[demo] def build(
    name:           String,
    base:           Long,
    size:           Long,
    idCapacityBits: Int,
    width:          Int
  )(
    using GeneratorScope[GpioP]
  ): (GpioNodes, Vector[Constraint]) =
    val clkDraft  =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)(
        ClockDomain,
        ResetDomain,
        PowerDomain
      )
    val pinsDraft =
      given sourcecode.Name = sourcecode.Name("pins")
      outward(GpioPins)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )
    val inDraft   =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Axi4)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )


    val clk  = clkDraft.seal(ReadPlan())(_ => Right(((), Vector.empty)))
    val pins = pinsDraft.seal(ReadPlan())(_ => Right((width, Vector.empty)))
    val in   = inDraft.seal(ReadPlan())(_ =>
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

    parameters { (view, _) =>
      val s = shapeOf(view.edgeOf(in))
      Right(GpioP(width, base, s.addrBits, s.dataBits, s.idBits))
    }
    (GpioNodes(clk, pins, in), Vector.empty)

def gpioCtrl(
  base:           Long,
  size:           Long,
  idCapacityBits: Int,
  width:          Int
)(
  using
  ws:             WrapperScope,
  name:           sourcecode.Name,
  file:           sourcecode.File,
  line:           sourcecode.Line
): GpioNodes =
  generator[GpioP](GpioNodes.build(name.value, base, size, idCapacityBits, width))
