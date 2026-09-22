package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.model.{MemorySubsystemGen, RamulatorMemoryP}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[RamulatorMemoryP] = zaozi(MemorySubsystemGen)

final case class MemoryNodes(clk: ClockReset.Inward, in: Axi4.Inward)

def memoryModel(edge: AxiEdgeParams, power: DomainReadable[PowerDomain.type], configFile: String)(using
  WrapperScope, sourcecode.Name, sourcecode.File, sourcecode.Line
): MemoryNodes =
  generator[RamulatorMemoryP] {
    val clk =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)(ClockDomain, ResetDomain, power).fixed(())
    val in =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Axi4)(clk.domain(ClockDomain), clk.domain(ResetDomain), power).fixed(edge.slave)
    parameters { (view, domains) =>
      val addresses = edge.slave.slaves.flatMap(_.address)
      Right(RamulatorMemoryP(configFile, addresses.map(_.base).min,
        1000000000000L / domains.value(clk.domain(ClockDomain)).hz, shapeOf(view.edgeOf(in))))
    }
    (MemoryNodes(clk, in), Vector.empty)
  }
