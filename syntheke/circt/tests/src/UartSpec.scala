// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*
import me.jiuyang.syntheke.tests.axi.{
  AddressSet,
  Axi4,
  AxiMasterParams,
  AxiMasterPort,
  AxiSlaveParams,
  AxiSlavePort,
  IdRange,
  RegionType,
  TransferSizes
}
import me.jiuyang.zaozi.{DVBundle, Generator, HWBundle, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter
import utest.*

/** The real UART device ([[UartDeviceGen]], `UartDevice.scala`) on the negotiation graph, end to end.
  *
  * Two small protocols carry what a sequential device needs beyond its bus:
  *   - [[ClockDomain]]: the source publishes its frequency downward; the interface is one clock plus one reset. The
  *     UART computes its baud divisor from the settled frequency in `parameters` — a capability check rejects a clock
  *     too slow for the requested baud rate.
  *   - [[Serial]]: the device publishes its baud rate downward to whoever terminates the pins; the interface is `tx`
  *     out, `rx` in.
  */

/** One clock and reset; `Down` is the source's frequency in Hz. */
object ClockDomain extends Protocol:
  type Down = Int
  type Up = Unit
  type Edge = Int
  def negotiate(down: Int, up: Unit): Either[Violation, Int]           = Right(down)
  def interfaceOf(edge: Int):         ProtocolBundle                   =
    ProtocolBundle(
      ProtocolInterface.Field("clock", ProtocolInterface.Clock),
      ProtocolInterface.Field("reset", ProtocolInterface.Reset)
    )
  val downRW:                         upickle.default.ReadWriter[Int]  = summon
  val upRW:                           upickle.default.ReadWriter[Unit] = summon
  val edgeRW:                         upickle.default.ReadWriter[Int]  = summon

/** A serial pin pair; `Down` is the transmitter's baud rate. */
object Serial extends Protocol:
  type Down = Int
  type Up = Unit
  type Edge = Int
  def negotiate(down: Int, up: Unit): Either[Violation, Int]           = Right(down)
  def interfaceOf(edge: Int):         ProtocolBundle                   =
    ProtocolBundle(
      ProtocolInterface.Field("tx", ProtocolInterface.Bool),
      ProtocolInterface.Field("rx", ProtocolInterface.Flipped(ProtocolInterface.Bool))
    )
  val downRW:                         upickle.default.ReadWriter[Int]  = summon
  val upRW:                           upickle.default.ReadWriter[Unit] = summon
  val edgeRW:                         upickle.default.ReadWriter[Int]  = summon

// ============ test-side stubs: a clock source and the pad ring (placeholder bodies) ============

case class ClockSourceP(freqHz: Int)      extends Parameter derives ReadWriter
class ClockSourcePLayers(p: ClockSourceP) extends LayerInterface(p):
  def layers = Seq.empty
class ClockSourcePProbe(p: ClockSourceP)  extends DVBundle[ClockSourceP, ClockSourcePLayers](p)
class ClockSourcePIO(p: ClockSourceP)     extends HWBundle(p):
  val clk = Aligned(new UartClockBundle)
@zaoziGenerator
object ClockSourceGen                     extends Generator[ClockSourceP, ClockSourcePLayers, ClockSourcePIO, ClockSourcePProbe]:
  def architecture(p: ClockSourceP) = summon[Interface[ClockSourcePIO]].dontCare()

case class SerialPadsP(baud: Int)       extends Parameter derives ReadWriter
class SerialPadsPLayers(p: SerialPadsP) extends LayerInterface(p):
  def layers = Seq.empty
class SerialPadsPProbe(p: SerialPadsP)  extends DVBundle[SerialPadsP, SerialPadsPLayers](p)
class SerialPadsPIO(p: SerialPadsP)     extends HWBundle(p):
  val in = Flipped(new UartSerialBundle)
@zaoziGenerator
object SerialPadsGen                    extends Generator[SerialPadsP, SerialPadsPLayers, SerialPadsPIO, SerialPadsPProbe]:
  def architecture(p: SerialPadsP) = summon[Interface[SerialPadsPIO]].dontCare()

object UartSpec extends TestSuite:

  val UartDevice  = new GeneratorEntry[UartDeviceP]
  val ClockSource = new GeneratorEntry[ClockSourceP]
  val SerialPads  = new GeneratorEntry[SerialPadsP]

  val backends: Seq[GeneratorBackend] = Seq(
    ZaoziBackend(UartDevice, UartDeviceGen),
    ZaoziBackend(ClockSource, ClockSourceGen),
    ZaoziBackend(SerialPads, SerialPadsGen),
    ZaoziBackend(Core, CoreGen)
  )

  def buildDesign(freqHz: Int = 1843200, baud: Int = 115200): DesignSpec =
    Design {
      val clkSrc = generator(ClockSource) {
        parameters(_ => Right(ClockSourceP(freqHz)))
        val clk = outward(ClockDomain).dFn(_ => Right(freqHz))
        clk
      }

      val host = generator(Core) {
        val mem = outward(Axi4).dFn(_ => Right(AxiMasterPort(Vector(AxiMasterParams("host", IdRange(0, 4))))))
        parameters(view => Right(CoreP("host", 2, 4, shapeOf(view, mem))))
        mem
      }

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
          else Right(UartDeviceP("uart", freq / baud, shapeOf(view, in)))
        }
        (clk, in, serial)
      }
      val (uartClk, uartIn, uartSerial) = uart

      val pads = generator(SerialPads) {
        val in = inward(Serial).uFn(_ => Right(()))
        parameters(view => Right(SerialPadsP(view.edgeOf(in))))
        in
      }

      uartClk <-- clkSrc
      uartIn <-- host
      pads <-- uartSerial
    }

  val tests = Tests {

    test("the uart negotiates its divisor from the clock and publishes its baud to the pads") {
      val resolved = Negotiator.negotiate(buildDesign())
      val uart     = resolved.generatorModule(ModuleId.root / "uart").get.fullParam.asInstanceOf[UartDeviceP]
      assert(uart.divisor == 16) // 1843200 / 115200
      assert(uart.port == AxiShape(addrBits = 29, dataBits = 32, idBits = 2))
      assert(resolved.generatorModule(ModuleId.root / "pads").get.fullParam == SerialPadsP(115200))
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
