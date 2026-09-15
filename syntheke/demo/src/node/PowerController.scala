package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[PowerControllerP] = zaozi(PowerControllerGen)

final case class PowerControllerNodes(
  clk: ClockReset.Inward,
  in: Axi4.Inward,
  cpu0: PowerControl.Outward,
  cpu1: PowerControl.Outward)

object PowerControllerNodes:
  private[demo] def build(
    name: String,
    base: Long,
    size: Long,
    idCapacityBits: Int
  )(using GeneratorScope[PowerControllerP]): (PowerControllerNodes, Vector[Constraint]) =
    require(base >= 0 && base % 4 == 0 && size >= 12, "power controller requires an aligned base and at least 12 bytes")
    val clkDraft =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)(ClockDomain, ResetDomain, PowerDomain)
    val clock = clkDraft.domain(ClockDomain)
    val reset = clkDraft.domain(ResetDomain)
    val inDraft =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Axi4)(clock, reset, PowerDomain)
    val cpu0Draft =
      given sourcecode.Name = sourcecode.Name("cpu0")
      outward(PowerControl)(clock, reset, PowerDomain)
    val cpu1Draft =
      given sourcecode.Name = sourcecode.Name("cpu1")
      outward(PowerControl)(clock, reset, PowerDomain)

    val clk = clkDraft.fixed(())
    val cpu0 = cpu0Draft.fixed(())
    val cpu1 = cpu1Draft.fixed(())
    val in = inDraft.fixed(AxiSlavePort(
      slaves = Vector(AxiSlaveParams(
        name,
        AddressSet.misaligned(base, size),
        RegionType.PutEffects,
        executable = false,
        supportsWrite = TransferSizes(4, 4),
        supportsRead = TransferSizes(4, 4)
      )),
      beatBytes = 4,
      idCapacityBits = idCapacityBits,
      minLatency = 1
    ))

    parameters { (view, _) =>
      val shape = shapeOf(view.edgeOf(in))
      Right(PowerControllerP(base, shape.addrBits, shape.dataBits, shape.idBits))
    }
    (
      PowerControllerNodes(clk, in, cpu0, cpu1),
      Vector(clk.domain(PowerDomain).requirement(PowerRequirement(requiresAlwaysOn = true)))
    )

def powerController(
  base: Long,
  size: Long,
  idCapacityBits: Int
)(using
  ws: WrapperScope,
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): PowerControllerNodes =
  generator[PowerControllerP](PowerControllerNodes.build(name.value, base, size, idCapacityBits))
