// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*
import me.jiuyang.syntheke.tests.zaoziimpl.{*, given}
import me.jiuyang.syntheke.tests.axi.{AddressSet, Axi4, AxiSlaveParams, AxiSlavePort, RegionType, TransferSizes}
import utest.*

/** The real UART device ([[UartDeviceGen]], `zaoziimpl/UartDevice.scala`) on the negotiation graph, end to end: an AXI
  * host and the UART on the chip, the clock and the serial line's other end in the test harness, negotiated over
  * [[ClockDomain]], [[Axi4]] and [[Serial]]. The UART computes its baud divisor from the settled clock frequency in
  * `parameters` and rejects a clock too slow for the requested baud rate.
  */
object UartSpec extends TestSuite:

  val UartDevice = new GeneratorEntry[UartDeviceP]

  val backends: Seq[GeneratorBackend] = Seq(
    ZaoziBackend(UartDevice, UartDeviceGen),
    ZaoziBackend(UartHarness, UartHarnessGen),
    ZaoziBackend(Core, CoreDeviceGen)
  )

  def buildDesign(freqHz: Int = 1843200, baud: Int = 115200): DesignSpec =
    Design {
      val harness = uartHarness(freqHz, Vector("uart", "host"))

      val host = core(idBits = 2, maxFlight = 4, resetPc = 0, enableDebug = false, enableTrace = false)

      val uart                          = generator(UartDevice) {
        val clk    = inward(ClockDomain).uFn(_ => Right(()))
        val in     = inward(Axi4).uFn(_ =>
          Right(
            AxiSlavePort(
              slaves = Vector(
                AxiSlaveParams(
                  "uart",
                  AddressSet.misaligned(0x10000000L, 0x1000L),
                  RegionType.PutEffects,
                  executable = false,
                  supportsWrite = TransferSizes(1, 4),
                  supportsRead = TransferSizes(1, 4)
                )
              ),
              beatBytes = 4,
              idCapacityBits = 8,
              minLatency = 1
            )
          )
        )
        val serial = outward(Serial).dFn(_ => Right(baud))
        parameters { view =>
          val freq = view.edgeOf(clk)
          if freq < baud * 8 then Left(Violation(s"clock $freq Hz too slow for $baud baud: needs 8 clocks per bit"))
          else
            val s = shapeOf(view, in)
            Right(UartDeviceP(freq / baud, s.addrBits, s.dataBits, s.idBits))
        }
        (clk, in, serial)
      }
      val (uartClk, uartIn, uartSerial) = uart

      uartClk <-- harness.tap("uart")
      host.clk <-- harness.tap("host")
      uartIn <-- host.mem
      harness.serialPins <-- uartSerial
    }

  val tests = Tests {

    test("the uart negotiates its divisor from the clock and publishes its baud to the pads") {
      val resolved = Negotiator.negotiate(buildDesign())
      val uart     = resolved.generatorModule(ModuleId.root / "uart").get.fullParam
      assert(uart == UartDeviceP(divisor = 16, addrBits = 29, dataBits = 32, idBits = 2)) // 1843200 / 115200
      // The harness learns the baud rate from the same edge, and its pads carry it.
      val harness = resolved.generatorModule(ModuleId.root / "harness").get.fullParam.asInstanceOf[UartHarnessP]
      assert(harness.baud == 115200)
      assert(harness.padsP == SerialPadsP(115200))
      assert(resolved.edgeAt(ModuleNodeId(ModuleId.root / "uart", "clk")).edgeAs(ClockDomain) == 1843200)
    }

    test("a clock too slow for the baud rate fails the uart's capability check") {
      val e = intercept[NegotiationException](Negotiator.negotiate(buildDesign(baud = 460800)))
      assert(e.getMessage.contains("too slow for 460800 baud"))
    }

    test("the uart elaborates through zaozi to Verilog with real sequential logic") {
      val resolved   = Negotiator.negotiate(buildDesign())
      val design     = Elaborator.elaborate(resolved, backends)
      val uartModule = design.moduleNames(ModuleId.root / "uart")
      assert(design.verilog.contains(s"module $uartModule"))
      // The device is sequential and its pins survive: clocked process, serial ports, register state.
      val uartBody   = design.verilog.substring(design.verilog.indexOf(s"module $uartModule"))
      assert(uartBody.contains("always @(posedge"))
      assert(uartBody.contains("serial_tx"))
      assert(uartBody.contains("serial_rx"))
      assert(uartBody.contains("in_aw_valid"))
      // The negotiated divisor reaches the hardware: DIV reads back 16, the tx baud counter reloads at 15.
      assert(uartBody.contains("32'h10"))
    }
  }
