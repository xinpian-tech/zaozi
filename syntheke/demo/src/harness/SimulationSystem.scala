package me.jiuyang.syntheke.demo.harness

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.{*, given}

object SimulationSystem:
  final case class Ports(
    ref: InwardBoundary[ClockReset.type],
    dtmClock: InwardBoundary[ClockReset.type],
    crossingClock: InwardBoundary[ClockReset.type],
    pins: Vector[OutwardBoundary[IO.type]])

  def build(socDesign: Design[Soc.Ports], config: SocConfig): Design[Ports] =
    Design("SimulationSystem") {
      val soc = socDesign.instantiate
      val ports = soc.ports
      val memory = memoryModel(soc.edgeOf(ports.memory), ports.memory.domain(PowerDomain), config.dramConfigFile)
      memory.in <-- ports.memory
      memory.clk <-- ports.memoryClock
      val ref = ports.ref.boundary
      val dtmClock = ports.dtmClock.boundary
      val crossingClock = ports.crossingClock.boundary
      val pins = ports.pins.zipWithIndex.map { (pin, index) =>
        given sourcecode.Name = sourcecode.Name(s"pin$index")
        pin.boundary
      }
      soc.probes.query(retirementBinding).foreach(_.boundary)
      Ports(ref, dtmClock, crossingClock, pins)
    }
