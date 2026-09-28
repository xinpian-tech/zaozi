package me.jiuyang.syntheke.demo

import me.jiuyang.stdlib.iomux.IOMuxRoute
import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[IOMuxP] = zaozi(IOMuxGen)

final case class IOMuxNodes(
  clk: ClockReset.Inward,
  in: Axi4.Inward,
  pads: Vector[IO.Outward],
  private val inputs: Vector[IO.Inward]):
  def input(name: String): IO.Inward =
    inputs.find(_.id.name == name).getOrElse(throw new IllegalArgumentException(s"unknown IOMux input $name"))

def ioMux(
  base: Long,
  size: Long,
  idCapacityBits: Int,
  pinCount: Int,
  routes: Vector[(String, IOMuxRoute)]
)(using
  WrapperScope,
  sourcecode.Name,
  sourcecode.File,
  sourcecode.Line
): IOMuxNodes =
  generator[IOMuxP] {
    val clkDraft =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)(ClockDomain, ResetDomain, PowerDomain)
    val inDraft =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Axi4)(clkDraft.domain(ClockDomain), clkDraft.domain(ResetDomain), PowerDomain)
    val pads = Vector.tabulate(pinCount) { i =>
      given sourcecode.Name = sourcecode.Name(s"pad$i")
      outward(IO)(PowerDomain).fixed(())
    }
    val ports = routes.map { (name, _) =>
      given sourcecode.Name = sourcecode.Name(name)
      inward(IO)(PowerDomain).fixed(())
    }
    val clk = clkDraft.fixed(())
    val in = inDraft.fixed(AxiSlavePort(
      slaves = Vector(AxiSlaveParams(
        "iomux",
        AddressSet.misaligned(base, size),
        RegionType.PutEffects,
        executable = false,
        supportsWrite = TransferSizes(1, 4),
        supportsRead = TransferSizes(1, 4)
      )),
      beatBytes = 4,
      idCapacityBits = idCapacityBits,
      minLatency = 1
    ))
    parameters { (view, _) =>
      Right(IOMuxP(base, size, shapeOf(view.edgeOf(in)), pinCount, routes))
    }
    (IOMuxNodes(clk, in, pads, ports), Vector.empty)
  }
