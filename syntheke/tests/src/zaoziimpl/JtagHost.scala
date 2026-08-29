// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import com.vowstar.ditdah32.DmiOp
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

/** The debug host: real RTL driving real JTAG pins. It divides the system clock into `tck`, walks the TAP state machine
  * — reset, one IR scan selecting DMI, then one DR scan per script entry — and shifts each entry out LSB first. The
  * script is a list of DMI register writes, so this is a debugger baked into the SoC: with it, a design needs no boot
  * ROM at all, because the program arrives through the debug chain like any other JTAG payload.
  *
  * It never reads status back: `dwell` idle cycles between scans is what covers the debug module's slowest abstract
  * command, and a scan is itself an order of magnitude longer than one. A debugger driven by software polls
  * `abstractcs.busy` instead — this one is a fixed script, and its correctness shows up as the program it loaded
  * running or not.
  */

case class JtagHostP(
  script:         Vector[DmiWrite],
  irLength:       Int,
  abits:          Int,
  dataBits:       Int,
  dmiInstruction: Int,
  tckDiv:         Int,
  dwell:          Int)
    extends Parameter derives ReadWriter:
  require(script.nonEmpty, "the debug host needs a script")
  require(irLength >= 2, s"IR length $irLength must be at least 2")
  require(abits >= 1, s"DMI address width $abits must be positive")
  require(dataBits == 32, s"the DMI data field is 32 bits, got $dataBits")
  require(tckDiv >= 2, s"tck divisor $tckDiv must be at least 2")
  require(dwell >= 1, s"dwell $dwell must be positive")
  script.foreach(w => require(w.addr < (1 << abits), s"DMI address ${w.addr} does not fit in $abits bits"))
  val drWidth: Int = abits + dataBits + 2

  /** One script entry as it rides the scan register: `{addr, data, op}`. */
  def dr(i: Int): BigInt =
    (BigInt(script(i).addr) << (dataBits + 2)) | (BigInt(script(i).data) << 2) | BigInt(DmiOp.WRITE)

class JtagHostPLayers(p: JtagHostP) extends LayerInterface(p):
  def layers = Seq.empty
class JtagHostPProbe(p: JtagHostP)  extends DVBundle[JtagHostP, JtagHostPLayers](p)
class JtagHostPIO(p: JtagHostP)     extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val tap = Flipped(new JtagBundle)

@generator
object JtagHostGen extends Generator[JtagHostP, JtagHostPLayers, JtagHostPIO, JtagHostPProbe]:
  def architecture(p: JtagHostP) =
    val io           = summon[Interface[JtagHostPIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    def widthOf(n: Int): Int = math.max(1, 32 - Integer.numberOfLeadingZeros(math.max(1, n)))

    val steps   = p.script.length
    val divW    = widthOf(p.tckDiv)
    val bitW    = widthOf(p.drWidth)
    val idxW    = widthOf(steps)
    val dwellW  = widthOf(p.dwell)
    val resetW  = 4
    val resetHi = 8 // TAP reset: eight cycles of tms high reach Test-Logic-Reset from anywhere

    // ---- tck: the system clock divided; outputs change on its falling edge, the TAP samples them on the rising one
    val divCnt  = RegInit(0.U(divW))
    val tckReg  = RegInit(false.B)
    val tickNow = divCnt === (p.tckDiv - 1).U(divW)
    when(tickNow) {
      divCnt := 0.U(divW)
      tckReg := !tckReg
    }.otherwise {
      divCnt := (divCnt + 1.U(divW)).asBits.bits(divW - 1, 0).asUInt
    }
    val fall    = tickNow & tckReg
    io.tap.tck   := tckReg.asClock
    io.tap.trstN := true.B

    // ---- the TAP state the transport is in, mirrored: 0 test-logic-reset, 1 run-test-idle, 2 select-DR,
    // 3 select-IR, 4 capture-IR, 5 shift-IR, 6 exit1-IR, 7 update-IR, 8 capture-DR, 9 shift-DR, 10 exit1-DR,
    // 11 update-DR, 12 the idle dwell, 13 done.
    val state     = RegInit(0.U(4))
    val resetCnt  = RegInit(0.U(resetW))
    val bitCnt    = RegInit(0.U(bitW))
    val scriptIdx = RegInit(0.U(idxW))
    val dwellCnt  = RegInit(0.U(dwellW))
    val irOut     = RegInit(0.U(p.irLength))
    val drOut     = RegInit(0.U(p.drWidth))
    val irLoaded  = RegInit(false.B)

    val moreWork = scriptIdx =/= steps.U(idxW)

    val drValue = Wire(UInt(p.drWidth))
    drValue := p.dr(0).U(p.drWidth)
    for i <- 1 until steps do when(scriptIdx === i.U(idxW)) { drValue := p.dr(i).U(p.drWidth) }

    val tms = Wire(Bool())
    val tdi = Wire(Bool())
    tms        := false.B
    tdi        := false.B
    when(state === 0.U(4)) { tms := resetCnt =/= resetHi.U(resetW) }
    when(state === 1.U(4)) { tms := true.B }
    when(state === 2.U(4)) { tms := !irLoaded }
    when(state === 5.U(4)) {
      tms := bitCnt === (p.irLength - 1).U(bitW)
      tdi := irOut.asBits.bit(0)
    }
    when(state === 6.U(4)) { tms := true.B }
    when(state === 9.U(4)) {
      tms := bitCnt === (p.drWidth - 1).U(bitW)
      tdi := drOut.asBits.bit(0)
    }
    when(state === 10.U(4)) { tms := true.B }
    when(state === 12.U(4)) { tms := (dwellCnt === (p.dwell - 1).U(dwellW)) & moreWork }
    io.tap.tms := tms
    io.tap.tdi := tdi

    when(fall) {
      when(state === 0.U(4)) {
        when(resetCnt === resetHi.U(resetW)) {
          state := 1.U(4)
        }.otherwise {
          resetCnt := (resetCnt + 1.U(resetW)).asBits.bits(resetW - 1, 0).asUInt
        }
      }
      when(state === 1.U(4)) { state := 2.U(4) }
      when(state === 2.U(4)) {
        when(irLoaded) { state := 8.U(4) }.otherwise { state := 3.U(4) }
      }
      when(state === 3.U(4)) { state := 4.U(4) }
      when(state === 4.U(4)) {
        state  := 5.U(4)
        irOut  := p.dmiInstruction.U(p.irLength)
        bitCnt := 0.U(bitW)
      }
      when(state === 5.U(4)) {
        irOut := (0.B(1) ## irOut.asBits.bits(p.irLength - 1, 1)).asUInt
        when(bitCnt === (p.irLength - 1).U(bitW)) {
          state  := 6.U(4)
          bitCnt := 0.U(bitW)
        }.otherwise {
          bitCnt := (bitCnt + 1.U(bitW)).asBits.bits(bitW - 1, 0).asUInt
        }
      }
      when(state === 6.U(4)) { state := 7.U(4) }
      when(state === 7.U(4)) {
        state    := 1.U(4)
        irLoaded := true.B
      }
      when(state === 8.U(4)) {
        state  := 9.U(4)
        drOut  := drValue
        bitCnt := 0.U(bitW)
      }
      when(state === 9.U(4)) {
        drOut := (0.B(1) ## drOut.asBits.bits(p.drWidth - 1, 1)).asUInt
        when(bitCnt === (p.drWidth - 1).U(bitW)) {
          state  := 10.U(4)
          bitCnt := 0.U(bitW)
        }.otherwise {
          bitCnt := (bitCnt + 1.U(bitW)).asBits.bits(bitW - 1, 0).asUInt
        }
      }
      when(state === 10.U(4)) { state := 11.U(4) }
      when(state === 11.U(4)) {
        state     := 12.U(4)
        scriptIdx := (scriptIdx + 1.U(idxW)).asBits.bits(idxW - 1, 0).asUInt
        dwellCnt  := 0.U(dwellW)
      }
      when(state === 12.U(4)) {
        when(dwellCnt === (p.dwell - 1).U(dwellW)) {
          when(moreWork) { state := 2.U(4) }.otherwise { state := 13.U(4) }
        }.otherwise {
          dwellCnt := (dwellCnt + 1.U(dwellW)).asBits.bits(dwellW - 1, 0).asUInt
        }
      }
    }
