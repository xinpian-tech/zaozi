package me.jiuyang.syntheke.demo.harness

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.*

object FPGAHarness:
  def build(socDesign: Design[Soc.Ports]): Design[Soc.Ports] =
    Design("FPGAHarness") {
      val soc = socDesign.instantiate
      val ports = soc.ports
      val ref = ports.ref.boundary
      val dtmClock = ports.dtmClock.boundary
      val crossingClock = ports.crossingClock.boundary
      val memory = ports.memory.boundary
      val memoryClock = ports.memoryClock.boundary
      val pins = ports.pins.zipWithIndex.map { (pin, index) =>
        given sourcecode.Name = sourcecode.Name(s"pin$index")
        pin.boundary
      }
      Soc.Ports(ref, dtmClock, crossingClock, memory, memoryClock, pins)
    }
