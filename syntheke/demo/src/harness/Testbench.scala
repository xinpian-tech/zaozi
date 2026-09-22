package me.jiuyang.syntheke.demo.harness

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.{*, given}
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[BoardP] = zaozi(BoardGen)
given GeneratorDefinition[OscillatorP] = zaozi(OscillatorGen)
given GeneratorDefinition[ConsoleP] = zaozi(ConsoleGen)
given GeneratorDefinition[TraceMonitorP] = zaozi(TraceMonitorGen)

object Testbench:
  def build(soc: Design[Soc.Ports], config: SocConfig): Design[EmptyTuple] =
    val system = SimulationSystem.build(soc, config)
    Design("Testbench") {
      val dut = system.instantiate
      val ports = dut.ports
      val boardPower = PowerDomain.declare(PowerValue.ExternalDigitalModel, None)
      val reference = ClockDomain.declare(dut.value(ports.ref, ClockDomain), None)
      val reset = ResetDomain.declare(dut.value(ports.ref, ResetDomain), None)
      boardPower.provide {
        val oscillator = generator[OscillatorP] {
          val taps = Vector("dutRef", "board", "console").map { name =>
            given sourcecode.Name = sourcecode.Name(name)
            outward(ClockReset)(reference, reset, PowerDomain).fixed(())
          }
          parameters((_, _) => Right(OscillatorP(dut.value(ports.ref, ClockDomain).hz, Vector("dutRef", "board", "console"))))
          (taps, Vector.empty)
        }
        ports.ref <-- oscillator(0)

        val board = Design("SimulationBoard") {
          val power = PowerDomain.declare(PowerValue.ExternalDigitalModel, None)
          val reference = ClockDomain.declare(dut.value(ports.ref, ClockDomain), None)
          val reset = ResetDomain.declare(dut.value(ports.ref, ResetDomain), None)
          val tck = ClockDomain.declare(dut.value(ports.dtmClock, ClockDomain), None)
          val model = power.provide {
            generator[BoardP] {
              val clk = inward(ClockReset)(ClockDomain, ResetDomain, PowerDomain).fixed(())
              val pins = ports.pins.indices.map { i =>
                given sourcecode.Name = sourcecode.Name(s"pin$i")
                inward(IO)(PowerDomain).fixed(())
              }.toVector
              val clocks = Vector("dtm", "dmiCross").map { name =>
                given sourcecode.Name = sourcecode.Name(name)
                outward(ClockReset)(tck, reset, PowerDomain).fixed(())
              }
              val serial = outward(Serial)(clk.domain(ClockDomain), clk.domain(ResetDomain), PowerDomain).fixed(config.baud)
              parameters((_, _) => Right(BoardP(
                dut.value(ports.ref, ClockDomain).hz, Vector("dtm", "dmiCross"),
                config.baud, ports.pins.size, config.uartPins, config.jtagPins, config.jtagPort, config.tckDiv)))
              ((clk, pins, clocks, serial), Vector.empty)
            }
          }
          val clk = model._1.boundary(())(reference, reset, power)
          val pins = model._2.zipWithIndex.map { (pin, index) =>
            given sourcecode.Name = sourcecode.Name(s"pin$index")
            pin.boundary(())(power)
          }
          val clocks = model._3.zipWithIndex.map { (clock, index) =>
            given sourcecode.Name = sourcecode.Name(s"clock$index")
            clock.boundary(())(ClockDomain, ResetDomain, power)
          }
          val serial = model._4.boundary(())(reference, reset, power)
          ((clk, pins, clocks, serial), Vector.empty)
        }.instantiate
        board.ports._1 <-- oscillator(1)
        board.ports._2.zip(ports.pins).foreach((model, pin) => model <-- pin)
        ports.dtmClock <-- board.ports._3(0)
        ports.crossingClock <-- board.ports._3(1)

        val console = generator[ConsoleP] {
          val clk = inward(ClockReset)(ClockDomain, ResetDomain, PowerDomain).fixed(())
          val serial = inward(Serial)(clk.domain(ClockDomain), clk.domain(ResetDomain), PowerDomain).fixed(())
          parameters((view, domains) => Right(ConsoleP(domains.value(clk.domain(ClockDomain)).hz / view.edgeOf(serial))))
          ((clk, serial), Vector.empty)
        }
        console._1 <-- oscillator(2)
        console._2 <-- board.ports._4

        val monitors = wrapper("Monitors") {
          val retirement = wrapper("RetirementMonitors") {
            TraceObservation.select(dut.probes).zipWithIndex.foreach { (trace, index) =>
              given sourcecode.Name = sourcecode.Name(s"hart$index")
              val monitor = generator[TraceMonitorP] {
                parameters((_, _) => Right(TraceMonitorP(trace)))
                (EmptyTuple, Vector.empty)
              }
            }
            (EmptyTuple, Vector.empty)
          }
          (EmptyTuple, Vector.empty)
        }
      }
      (EmptyTuple, Vector.empty)
    }
