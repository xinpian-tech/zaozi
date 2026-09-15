package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.model.{MemorySubsystemGen, RamulatorMemoryP}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[RamulatorMemoryP] = zaozi(MemorySubsystemGen)

final case class MemoryNodes(clk: ClockReset.Inward, in: Axi4.Inward)

object MemoryNodes:
  private[demo] def build(
    name:           String,
    base:           Long,
    size:           Long,
    idCapacityBits: Int,
    configFile:     String
  )(
    using GeneratorScope[RamulatorMemoryP]
  ): (MemoryNodes, Vector[Constraint]) =
    val clkDraft =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)(
        ClockDomain,
        ResetDomain,
        PowerDomain
      )
    val inDraft  =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Axi4)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )

    val clock = clkDraft.domain(ClockDomain)

    val clk = clkDraft.seal(ReadPlan())(_ => Right(((), Vector.empty)))
    val in  = inDraft.seal(ReadPlan())(_ =>
      Right((
        AxiSlavePort(
          slaves = Vector(
            AxiSlaveParams(
              name,
              AddressSet.misaligned(base, size),
              RegionType.Uncached,
              executable = true,
              supportsWrite = TransferSizes(1, 64),
              supportsRead = TransferSizes(1, 64)
            )
          ),
          beatBytes = 16,
          idCapacityBits = idCapacityBits,
          minLatency = 8
        ),
        Vector.empty
      ))
    )

    parameters { (view, domains) =>
      Right(RamulatorMemoryP(configFile, base, 1000000000000L / domains.value(clock).hz, shapeOf(view.edgeOf(in))))
    }
    (MemoryNodes(clk, in), Vector.empty)

def memorySubsystem(
  base:           Long,
  size:           Long,
  idCapacityBits: Int,
  configFile:     String
)(
  using
  ws:             WrapperScope,
  name:           sourcecode.Name,
  file:           sourcecode.File,
  line:           sourcecode.Line
): MemoryNodes =
  generator[RamulatorMemoryP](MemoryNodes.build(name.value, base, size, idCapacityBits, configFile))
