// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl.harness

import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

/** The demo SoC's testbench device: terminates the UART's serial pins as a loopback jumper, reassembles the 8N1 frames
  * on the line at `divisor` clocks per bit — the UART's own receive engine, watching from outside — and hands every
  * completed byte to [[SimConsole]], an external Verilog module whose behavioral definition `sim/SimConsole.sv` prints
  * it and ends the simulation at a newline.
  */

case class ConsoleP(divisor: Int) extends Parameter derives ReadWriter:
  require(divisor >= 8, s"console divisor $divisor: needs at least 8 clocks per bit")

class ConsolePLayers(p: ConsoleP) extends LayerInterface(p):
  def layers = Seq.empty
class ConsolePProbe(p: ConsoleP)  extends DVBundle[ConsoleP, ConsolePLayers](p)
class ConsolePIO(p: ConsoleP)     extends HWBundle(p):
  val clk    = Flipped(new ClockBundle)
  val serial = Flipped(new SerialBundle)

/** What [[SimConsole]] takes: one completed byte per `valid` cycle. */
class SimConsoleIO(p: ConsoleP) extends HWBundle(p):
  val clock = Flipped(Clock())
  val valid = Flipped(Bool())
  val data  = Flipped(UInt(8))

case class SimConsoleVerilogP() extends VerilogParameter

@generator
object SimConsole extends VerilogWrapper[ConsoleP, ConsolePLayers, SimConsoleIO, ConsolePProbe, SimConsoleVerilogP]:
  def verilogModuleName(p: ConsoleP) = "SimConsole"
  def verilogParameter(p:  ConsoleP) = SimConsoleVerilogP()

@generator
object ConsoleGen extends Generator[ConsoleP, ConsolePLayers, ConsolePIO, ConsolePProbe]:
  override def moduleName(p: ConsoleP): String = s"Console_${p.hashCode.toHexString}"

  def architecture(p: ConsoleP) =
    val io           = summon[Interface[ConsolePIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    // The jumper: the line echoes back, so the UART's receiver sees its own bytes.
    io.serial.rx := io.serial.tx

    val divW   = math.max(1, 32 - Integer.numberOfLeadingZeros(p.divisor - 1))
    val reload = (p.divisor - 1).U(divW)

    // The UART's receive engine (see [[UartDeviceGen]]), watching the tx line from outside.
    val rxSync = RegInit(true.B)
    rxSync := io.serial.tx
    val rxShift = RegInit(0.U(8))
    val rxCnt   = RegInit(0.U(4))
    val rxBaud  = RegInit(0.U(divW))
    val rxBusy  = RegInit(false.B)
    val rxData  = RegInit(0.U(8))
    val strobe  = RegInit(false.B)
    strobe := false.B
    when(!rxBusy) {
      when(!rxSync) {
        rxBusy := true.B
        rxCnt  := 0.U(4)
        rxBaud := (p.divisor / 2).U(divW)
      }
    }.otherwise {
      when(rxBaud === 0.U(divW)) {
        rxBaud := reload
        rxCnt  := (rxCnt + 1.U(4)).asBits.bits(3, 0).asUInt
        when((rxCnt === 0.U(4)) & rxSync) { rxBusy := false.B } // start bit was a glitch
        when((rxCnt >= 1.U(4)) & (rxCnt <= 8.U(4))) {
          rxShift := (rxSync.asBits ## rxShift.asBits.bits(7, 1)).asUInt
        }
        when(rxCnt === 9.U(4)) { // stop bit: byte complete
          rxBusy := false.B
          rxData := rxShift
          strobe := true.B
        }
      }.otherwise {
        rxBaud := (rxBaud - 1.U(divW)).asBits.bits(divW - 1, 0).asUInt
      }
    }

    val console = SimConsole.instantiate(p)
    console.io.clock := io.clk.clock
    console.io.valid := strobe
    console.io.data  := rxData
