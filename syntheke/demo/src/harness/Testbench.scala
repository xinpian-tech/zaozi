package me.jiuyang.syntheke.demo.harness

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.{*, given}
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi
import me.jiuyang.stdlib.power.PowerControlParameter

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
      val refHz      = ClockDomain.hz(dut.domainOf(ports.ref, ClockDomain))
      val refLow     = ResetDomain.activeLow(dut.domainOf(ports.ref, ResetDomain))
      val boardPower = PowerDomain.root(PowerTreeDomain(PowerControlParameter(hasSwitch = false, 0, 0, 0)))
      val reference  = ClockDomain.root(ClockInput(refHz))
      val reset      = ResetDomain.root(ResetRoot.Source(refLow))
      boardPower.scope {
        val oscillator = generator[OscillatorP] {
          val taps = Vector("dutRef", "board", "console").map { name =>
            given sourcecode.Name = sourcecode.Name(name)
            outward(ClockReset)(reference, reset).fixed(())
          }
          parameters((_, _) => Right(OscillatorP(refHz, Vector("dutRef", "board", "console"))))
          taps
        }
        ports.ref <-- oscillator(0)

        val board = Design("SimulationBoard") {
          val power     = PowerDomain.root(PowerTreeDomain(PowerControlParameter(hasSwitch = false, 0, 0, 0)))
          val reference = ClockDomain.root(ClockInput(refHz))
          val reset     = ResetDomain.root(ResetRoot.Source(refLow))
          val tck       = ClockDomain.root(ClockInput(ClockDomain.hz(dut.domainOf(ports.dtmClock, ClockDomain))))
          val model = power.scope {
            generator[BoardP] {
              val clk = inward(ClockReset)().fixed(())
              val pins = ports.pins.indices.map { i =>
                given sourcecode.Name = sourcecode.Name(s"pin$i")
                inward(IO)().fixed(())
              }.toVector
              val clocks = Vector("dtm", "dmiCross").map { name =>
                given sourcecode.Name = sourcecode.Name(name)
                outward(ClockReset)(tck, reset).fixed(())
              }
              val serial = outward(Serial)(clk.domain(ClockDomain), clk.domain(ResetDomain)).fixed(config.baud)
              parameters((_, _) => Right(BoardP(
                refHz, Vector("dtm", "dmiCross"),
                config.baud, ports.pins.size, config.uartPins, config.jtagPins, config.jtagPort, config.tckDiv)))
              (clk, pins, clocks, serial)
            }
          }
          val clk = model._1.boundary(())(reference, reset, power)
          val pins = model._2.zipWithIndex.map { (pin, index) =>
            given sourcecode.Name = sourcecode.Name(s"pin$index")
            pin.boundary(())(power)
          }
          val clocks = model._3.zipWithIndex.map { (clock, index) =>
            given sourcecode.Name = sourcecode.Name(s"clock$index")
            clock.boundary(())(power)
          }
          val serial = model._4.boundary(())(power)
          (clk, pins, clocks, serial)
        }.instantiate
        board.ports._1 <-- oscillator(1)
        board.ports._2.zip(ports.pins).foreach((model, pin) => model <-- pin)
        ports.dtmClock <-- board.ports._3(0)
        ports.crossingClock <-- board.ports._3(1)

        val console = generator[ConsoleP] {
          val clk = inward(ClockReset)().fixed(())
          val serial = inward(Serial)().fixed(())
          parameters((view, domains) => Right(ConsoleP(ClockDomain.hz(domains(clk.domain(ClockDomain))) / view.edgeOf(serial))))
          (clk, serial)
        }
        console._1 <-- oscillator(2)
        console._2 <-- board.ports._4

        val monitors = wrapper("Monitors") {
          val retirement = wrapper("RetirementMonitors") {
            TraceObservation.select(dut.probes).zipWithIndex.foreach { (trace, index) =>
              given sourcecode.Name = sourcecode.Name(s"hart$index")
              val monitor = generator[TraceMonitorP] {
                parameters((_, _) => Right(TraceMonitorP(trace)))
                EmptyTuple
              }
            }
            EmptyTuple
          }
          EmptyTuple
        }
      }
      EmptyTuple
    }
