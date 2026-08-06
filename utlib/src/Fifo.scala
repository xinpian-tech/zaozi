// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.magic.macros.generator
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

/** ============================================================================
  * The framework's reference DUT: a two-entry Decoupled FIFO.
  *
  * Why not `me.jiuyang.stdlib.Queue`? Its only `QueueImpl` instance wraps the
  * Synopsys `DW_fifo_s1_sf` DesignWare macro, which is a Verilog *blackbox*
  * with no RTL body — Verilator cannot elaborate it, so it cannot serve as the
  * DUT for a simulation-based framework. This FIFO is a self-contained,
  * synthesizable stand-in with the same Decoupled shape and the same
  * `empty`/`full` status signals the coverpoints reference.
  *
  * The depth is fixed at two and the implementation is deliberately
  * arithmetic-free (two data registers and two valid bits rather than
  * pointers and a counter). Two entries is the smallest depth that still
  * exercises ordering, back-pressure, simultaneous enqueue/dequeue, and both
  * the empty and full boundaries — which is everything the coverpoints need —
  * and avoiding `+`/`-` avoids FIRRTL's width-growth rules entirely.
  * ============================================================================
  */
final case class FifoParameter(width: Int) extends Parameter

object FifoParameter:
  given upickle.default.ReadWriter[FifoParameter] = upickle.default.macroRW

class FifoLayers(parameter: FifoParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer(Names.verificationLayer))

class FifoIO(parameter: FifoParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val enq   = Flipped(Decoupled(UInt(parameter.width)))
  val deq   = Aligned(Decoupled(UInt(parameter.width)))
  val empty = Aligned(Bool())
  val full  = Aligned(Bool())

/** White-box observation points. These are what coverpoints and traces bind to: real, typed signal references rather
  * than signals named by string. They live under a `Verification` layer, so they are DV-only and cost nothing in
  * synthesis.
  */
class FifoProbe(parameter: FifoParameter) extends DVBundle[FifoParameter, FifoLayers](parameter):
  /** Both slots occupied. */
  val isFull = ProbeRead(Bool(), layers(Names.verificationLayer))

  /** Head slot occupied. */
  val valid0 = ProbeRead(Bool(), layers(Names.verificationLayer))

  /** Tail slot occupied. */
  val valid1 = ProbeRead(Bool(), layers(Names.verificationLayer))

  /** An enqueue was accepted this cycle. */
  val enqFire = ProbeRead(Bool(), layers(Names.verificationLayer))

  /** A dequeue was accepted this cycle. */
  val deqFire = ProbeRead(Bool(), layers(Names.verificationLayer))

@generator
object Fifo extends Generator[FifoParameter, FifoLayers, FifoIO, FifoProbe] with HasSvEmit:
  def architecture(parameter: FifoParameter) =
    val io = summon[Interface[FifoIO]]

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)

    // Slot 0 is the head (oldest entry); slot 1 is the tail.
    val data0  = Reg(UInt(parameter.width))
    val valid0 = RegInit(false.B)
    val data1  = Reg(UInt(parameter.width))
    val valid1 = RegInit(false.B)

    val isFull = valid0 & valid1

    io.empty     := !valid0
    io.full      := isFull
    io.enq.ready := !isFull
    io.deq.valid := valid0
    io.deq.bits  := data0

    val enqFire = io.enq.valid & !isFull
    val deqFire = valid0 & io.deq.ready

    val probe = summon[ProbeInterface[FifoProbe]]
    layer(Names.verificationLayer):
      val isFullP  = Wire(Bool()); isFullP  := isFull; probe.isFull <== isFullP
      val valid0P  = Wire(Bool()); valid0P  := valid0; probe.valid0 <== valid0P
      val valid1P  = Wire(Bool()); valid1P  := valid1; probe.valid1 <== valid1P
      val enqFireP = Wire(Bool()); enqFireP := enqFire; probe.enqFire <== enqFireP
      val deqFireP = Wire(Bool()); deqFireP := deqFire; probe.deqFire <== deqFireP

    when(deqFire) {
      // Head leaves; the tail shifts down into it.
      data0  := data1
      valid0 := valid1
      valid1 := false.B
      when(enqFire) {
        // Land the new entry in whichever slot the shift left free. Later
        // connects win, so these override the shift assignments above.
        when(valid1) {
          data1  := io.enq.bits
          valid1 := true.B
        }.otherwise {
          data0  := io.enq.bits
          valid0 := true.B
        }
      }
    }.otherwise {
      when(enqFire) {
        when(valid0) {
          data1  := io.enq.bits
          valid1 := true.B
        }.otherwise {
          data0  := io.enq.bits
          valid0 := true.B
        }
      }
    }
