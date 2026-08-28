// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import upickle.default.ReadWriter

import java.lang.foreign.Arena

/** A real n×m AXI crossbar in the spirit of rocket-chip's AXI4Xbar: per-transaction address decode against each
  * output's route table, round-robin or fixed-priority arbitration among the inputs, and the static id remap the
  * negotiation promised — input `i`'s ids ride behind prefix `i`, responses un-map by prefix. One outstanding write and
  * one outstanding read across the switch; an address no route claims is answered DECERR by the crossbar itself.
  */

given arbitrationTokens: mainargs.TokensReader.Simple[Arbitration]                  = jsonTokens("arbitration")
given routesTokens:      mainargs.TokensReader.Simple[Vector[Vector[(Long, Long)]]] = jsonTokens("routes")

enum Arbitration derives CanEqual, ReadWriter:
  case RoundRobin, FixedPriority

case class XbarP(
  name:        String,
  arbitration: Arbitration,
  inputs:      Vector[(String, AxiShape)],
  outputs:     Vector[(String, AxiShape)],
  routes:      Vector[Vector[(Long, Long)]]) // per output: (base, mask) address sets
    extends Parameter derives ReadWriter:
  require(inputs.nonEmpty && outputs.nonEmpty, "xbar needs inputs and outputs")
  require(routes.size == outputs.size, "one route table per output")
  val dataBits:   Int = inputs.head._2.dataBits
  val addrBits:   Int = inputs.head._2.addrBits
  require(
    (inputs ++ outputs).forall(_._2.dataBits == dataBits),
    "xbar ports share the data width"
  )
  require(inputs.forall(_._2.addrBits == addrBits), "xbar inputs share the address width")
  require(outputs.forall(_._2.addrBits <= addrBits), "each output sees at most the input address space")
  val localBits:  Int = inputs.map(_._2.idBits).max
  val prefixBits: Int = math.max(1, 32 - Integer.numberOfLeadingZeros(inputs.size - 1))
  require(
    outputs.forall(_._2.idBits == (if inputs.size == 1 then inputs.head._2.idBits else prefixBits + localBits)),
    "output ids carry the input prefix over the padded local id space"
  )

class XbarPLayers(p: XbarP) extends LayerInterface(p):
  def layers = Seq.empty
class XbarPProbe(p: XbarP)  extends DVRecord[XbarP, XbarPLayers](p)
class XbarPIO(p: XbarP)     extends HWRecord(p):
  val clk  = Flipped("clk", new ClockRecord)
  val ins  = p.inputs.map((n, s) => Flipped(n, new AxiPortRecord(s)))
  val outs = p.outputs.map((n, s) => Aligned(n, new AxiPortRecord(s)))

@generator
object XbarGen extends Generator[XbarP, XbarPLayers, XbarPIO, XbarPProbe]:
  def architecture(p: XbarP) =
    val io           = summon[Interface[XbarPIO]]
    given ClockScope = ClockScope.posedge(io.field[Record]("clk").field[Clock]("clock"))
    given ResetScope = ResetScope.asyncActiveHigh(io.field[Record]("clk").field[Reset]("reset"))

    val n    = p.inputs.size
    val m    = p.outputs.size
    val inW  = math.max(1, 32 - Integer.numberOfLeadingZeros(math.max(1, n - 1)))
    val outW = math.max(1, 32 - Integer.numberOfLeadingZeros(math.max(1, m - 1)))

    // The context parameters matter: field-access ops must build in the caller's block.
    def in(
      i: Int
    )(
      using Arena,
      Context,
      Block
    ) = io.field[Record](p.inputs(i)._1)
    def out(
      o: Int
    )(
      using Arena,
      Context,
      Block
    ) = io.field[Record](p.outputs(o)._1)
    def ch(
      r:    Referable[Record],
      name: String
    )(
      using Arena,
      Context,
      Block
    ) = r.field[Record](name)

    // Everything below is generated per (input, output) pair with static indices; the state registers pick the pair.

    def decode(
      addr: Referable[UInt],
      o:    Int
    )(
      using Arena,
      Context,
      Block
    ): Referable[Bool] =
      p.routes(o)
        .map((base, mask) =>
          ((addr.asBits ^ BigInt(base).U(p.addrBits).asBits) & (~BigInt(mask).U(p.addrBits).asBits)) === 0
            .U(p.addrBits)
            .asBits
        )
        .reduce(_ | _)

    def grantChain(
      valids: Vector[Referable[Bool]],
      rrPtr:  Option[Referable[UInt]]
    )(sel:    Wire[UInt],
      any:    Wire[Bool]
    )(
      using Arena,
      Context,
      Block
    ): Unit =
      any := false.B
      sel := 0.U(inW)
      // Fixed priority: lowest index wins; later whens override earlier ones, so scan from high to low.
      for i <- (0 until n).reverse do
        when(valids(i)) {
          any := true.B
          sel := i.U(inW)
        }
      // Round robin: an input at or after the pointer overrides the fixed-priority pick.
      rrPtr.foreach { ptr =>
        for i <- (0 until n).reverse do
          when(valids(i) & (i.U(inW) >= ptr)) {
            sel := i.U(inW)
          }
      }

    // ================= write path =================
    // 0 idle, 1 AW forward, 2 W stream, 3 B return, 4 DECERR absorb W, 5 DECERR answer B.
    val wState = RegInit(0.U(3))
    val wIn    = RegInit(0.U(inW))
    val wOut   = RegInit(0.U(outW))
    val wRr    = Option.when(p.arbitration == Arbitration.RoundRobin)(RegInit(0.U(inW)))

    val wSel = Wire(UInt(inW))
    val wAny = Wire(Bool())
    grantChain(Vector.tabulate(n)(i => ch(in(i), "aw").field[Bool]("valid")), wRr)(wSel, wAny)

    when((wState === 0.U(3)) & wAny) {
      wIn    := wSel
      wState := 4.U(3) // no route: DECERR unless a decode hits below
      for i <- 0 until n do
        when(wSel === i.U(inW)) {
          val addr = ch(in(i), "aw").field[Record]("bits").field[UInt]("addr")
          for o <- 0 until m do
            when(decode(addr, o)) {
              wOut   := o.U(outW)
              wState := 1.U(3)
            }
        }
    }

    // Defaults for every port, overridden while a transaction is in flight.
    for i <- 0 until n do
      ch(in(i), "aw").field[Bool]("ready")                     := false.B
      ch(in(i), "w").field[Bool]("ready")                      := false.B
      ch(in(i), "b").field[Bool]("valid")                      := false.B
      ch(in(i), "b").field[Record]("bits").field[UInt]("id")   := 0.U(p.inputs(i)._2.idBits)
      ch(in(i), "b").field[Record]("bits").field[UInt]("resp") := 0.U(2)
    for o <- 0 until m do
      ch(out(o), "aw").field[Bool]("valid")                       := false.B
      ch(out(o), "aw").field[Record]("bits").field[UInt]("id")    := 0.U(p.outputs(o)._2.idBits)
      ch(out(o), "aw").field[Record]("bits").field[UInt]("addr")  := 0.U(p.outputs(o)._2.addrBits)
      ch(out(o), "aw").field[Record]("bits").field[UInt]("len")   := 0.U(8)
      ch(out(o), "aw").field[Record]("bits").field[UInt]("size")  := 0.U(3)
      ch(out(o), "aw").field[Record]("bits").field[UInt]("burst") := 1.U(2)
      ch(out(o), "w").field[Bool]("valid")                        := false.B
      ch(out(o), "w").field[Record]("bits").field[UInt]("data")   := 0.U(p.dataBits)
      ch(out(o), "w").field[Record]("bits").field[UInt]("strb")   := 0.U(p.dataBits / 8)
      ch(out(o), "w").field[Record]("bits").field[Bool]("last")   := false.B
      ch(out(o), "b").field[Bool]("ready")                        := false.B

    for i <- 0 until n do
      for o <- 0 until m do
        val active = (wIn === i.U(inW)) & (wOut === o.U(outW))
        // AW forward with the static id remap.
        when((wState === 1.U(3)) & active) {
          val aw  = ch(in(i), "aw")
          val oaw = ch(out(o), "aw")
          oaw.field[Bool]("valid") := aw.field[Bool]("valid")
          aw.field[Bool]("ready")  := oaw.field[Bool]("ready")
          val lid = aw.field[Record]("bits").field[UInt]("id")
          val gid =
            if n == 1 then lid.asBits.asUInt
            else if p.localBits == p.inputs(i)._2.idBits then (i.U(p.prefixBits).asBits ## lid.asBits).asUInt
            else (i.U(p.prefixBits).asBits ## 0.U(p.localBits - p.inputs(i)._2.idBits).asBits ## lid.asBits).asUInt
          oaw.field[Record]("bits").field[UInt]("id")    := gid
          oaw.field[Record]("bits").field[UInt]("addr")  :=
            aw.field[Record]("bits").field[UInt]("addr").asBits.bits(p.outputs(o)._2.addrBits - 1, 0).asUInt
          oaw.field[Record]("bits").field[UInt]("len")   := aw.field[Record]("bits").field[UInt]("len")
          oaw.field[Record]("bits").field[UInt]("size")  := aw.field[Record]("bits").field[UInt]("size")
          oaw.field[Record]("bits").field[UInt]("burst") := aw.field[Record]("bits").field[UInt]("burst")
          when(aw.field[Bool]("valid") & oaw.field[Bool]("ready")) { wState := 2.U(3) }
        }
        // W stream until last.
        when((wState === 2.U(3)) & active) {
          val w  = ch(in(i), "w")
          val ow = ch(out(o), "w")
          ow.field[Bool]("valid")                      := w.field[Bool]("valid")
          w.field[Bool]("ready")                       := ow.field[Bool]("ready")
          ow.field[Record]("bits").field[UInt]("data") := w.field[Record]("bits").field[UInt]("data")
          ow.field[Record]("bits").field[UInt]("strb") := w.field[Record]("bits").field[UInt]("strb")
          ow.field[Record]("bits").field[Bool]("last") := w.field[Record]("bits").field[Bool]("last")
          when(
            w.field[Bool]("valid") & ow.field[Bool]("ready") & w.field[Record]("bits").field[Bool]("last")
          ) { wState := 3.U(3) }
        }
        // B return with the id un-mapped.
        when((wState === 3.U(3)) & active) {
          val b  = ch(in(i), "b")
          val ob = ch(out(o), "b")
          b.field[Bool]("valid")                      := ob.field[Bool]("valid")
          ob.field[Bool]("ready")                     := b.field[Bool]("ready")
          b.field[Record]("bits").field[UInt]("id")   :=
            ob.field[Record]("bits").field[UInt]("id").asBits.bits(p.inputs(i)._2.idBits - 1, 0).asUInt
          b.field[Record]("bits").field[UInt]("resp") := ob.field[Record]("bits").field[UInt]("resp")
          when(ob.field[Bool]("valid") & b.field[Bool]("ready")) {
            wState := 0.U(3)
            wRr.foreach(
              _ := (if n == 1 then 0.U(inW) else ((wIn + 1.U(inW)) % n.U(inW)).asBits.bits(inW - 1, 0).asUInt)
            )
          }
        }

    // DECERR: absorb the write burst from the granted input, then answer.
    for i <- 0 until n do
      val active = wIn === i.U(inW)
      when((wState === 4.U(3)) & active) {
        val w = ch(in(i), "w")
        w.field[Bool]("ready") := true.B
        when(w.field[Bool]("valid") & w.field[Record]("bits").field[Bool]("last")) { wState := 5.U(3) }
      }
      when((wState === 5.U(3)) & active) {
        val b = ch(in(i), "b")
        b.field[Bool]("valid")                      := true.B
        b.field[Record]("bits").field[UInt]("resp") := 3.U(2) // DECERR
        when(b.field[Bool]("ready")) {
          wState := 0.U(3)
          wRr.foreach(_ := (if n == 1 then 0.U(inW) else ((wIn + 1.U(inW)) % n.U(inW)).asBits.bits(inW - 1, 0).asUInt))
        }
      }

    // ================= read path =================
    // 0 idle, 1 AR forward, 2 R stream, 3 DECERR answer.
    val rState = RegInit(0.U(2))
    val rIn    = RegInit(0.U(inW))
    val rOut   = RegInit(0.U(outW))
    val rRr    = Option.when(p.arbitration == Arbitration.RoundRobin)(RegInit(0.U(inW)))
    val rErrId = RegInit(0.U(if n == 1 then p.inputs.head._2.idBits else p.prefixBits + p.localBits))

    val rSel = Wire(UInt(inW))
    val rAny = Wire(Bool())
    grantChain(Vector.tabulate(n)(i => ch(in(i), "ar").field[Bool]("valid")), rRr)(rSel, rAny)

    when((rState === 0.U(2)) & rAny) {
      rIn    := rSel
      rState := 3.U(2)
      for i <- 0 until n do
        when(rSel === i.U(inW)) {
          val ar   = ch(in(i), "ar")
          val lid  = ar.field[Record]("bits").field[UInt]("id")
          val errW = if n == 1 then p.inputs.head._2.idBits else p.prefixBits + p.localBits
          rErrId := (
            if errW == p.inputs(i)._2.idBits then lid.asBits.asUInt
            else (0.U(errW - p.inputs(i)._2.idBits).asBits ## lid.asBits).asUInt
          ) // captured only for the DECERR answer
          val addr = ar.field[Record]("bits").field[UInt]("addr")
          for o <- 0 until m do
            when(decode(addr, o)) {
              rOut   := o.U(outW)
              rState := 1.U(2)
            }
        }
    }

    for i <- 0 until n do
      ch(in(i), "ar").field[Bool]("ready")                     := false.B
      ch(in(i), "r").field[Bool]("valid")                      := false.B
      ch(in(i), "r").field[Record]("bits").field[UInt]("id")   := 0.U(p.inputs(i)._2.idBits)
      ch(in(i), "r").field[Record]("bits").field[UInt]("data") := 0.U(p.dataBits)
      ch(in(i), "r").field[Record]("bits").field[UInt]("resp") := 0.U(2)
      ch(in(i), "r").field[Record]("bits").field[Bool]("last") := true.B
    for o <- 0 until m do
      ch(out(o), "ar").field[Bool]("valid")                       := false.B
      ch(out(o), "ar").field[Record]("bits").field[UInt]("id")    := 0.U(p.outputs(o)._2.idBits)
      ch(out(o), "ar").field[Record]("bits").field[UInt]("addr")  := 0.U(p.outputs(o)._2.addrBits)
      ch(out(o), "ar").field[Record]("bits").field[UInt]("len")   := 0.U(8)
      ch(out(o), "ar").field[Record]("bits").field[UInt]("size")  := 0.U(3)
      ch(out(o), "ar").field[Record]("bits").field[UInt]("burst") := 1.U(2)
      ch(out(o), "r").field[Bool]("ready")                        := false.B

    for i <- 0 until n do
      for o <- 0 until m do
        val active = (rIn === i.U(inW)) & (rOut === o.U(outW))
        when((rState === 1.U(2)) & active) {
          val ar  = ch(in(i), "ar")
          val oar = ch(out(o), "ar")
          oar.field[Bool]("valid") := ar.field[Bool]("valid")
          ar.field[Bool]("ready")  := oar.field[Bool]("ready")
          val lid = ar.field[Record]("bits").field[UInt]("id")
          val gid =
            if n == 1 then lid.asBits.asUInt
            else if p.localBits == p.inputs(i)._2.idBits then (i.U(p.prefixBits).asBits ## lid.asBits).asUInt
            else (i.U(p.prefixBits).asBits ## 0.U(p.localBits - p.inputs(i)._2.idBits).asBits ## lid.asBits).asUInt
          oar.field[Record]("bits").field[UInt]("id")    := gid
          oar.field[Record]("bits").field[UInt]("addr")  :=
            ar.field[Record]("bits").field[UInt]("addr").asBits.bits(p.outputs(o)._2.addrBits - 1, 0).asUInt
          oar.field[Record]("bits").field[UInt]("len")   := ar.field[Record]("bits").field[UInt]("len")
          oar.field[Record]("bits").field[UInt]("size")  := ar.field[Record]("bits").field[UInt]("size")
          oar.field[Record]("bits").field[UInt]("burst") := ar.field[Record]("bits").field[UInt]("burst")
          when(ar.field[Bool]("valid") & oar.field[Bool]("ready")) { rState := 2.U(2) }
        }
        when((rState === 2.U(2)) & active) {
          val r  = ch(in(i), "r")
          val or = ch(out(o), "r")
          r.field[Bool]("valid")                      := or.field[Bool]("valid")
          or.field[Bool]("ready")                     := r.field[Bool]("ready")
          r.field[Record]("bits").field[UInt]("id")   :=
            or.field[Record]("bits").field[UInt]("id").asBits.bits(p.inputs(i)._2.idBits - 1, 0).asUInt
          r.field[Record]("bits").field[UInt]("data") := or.field[Record]("bits").field[UInt]("data")
          r.field[Record]("bits").field[UInt]("resp") := or.field[Record]("bits").field[UInt]("resp")
          r.field[Record]("bits").field[Bool]("last") := or.field[Record]("bits").field[Bool]("last")
          when(
            or.field[Bool]("valid") & r.field[Bool]("ready") & or.field[Record]("bits").field[Bool]("last")
          ) {
            rState := 0.U(2)
            rRr.foreach(
              _ := (if n == 1 then 0.U(inW) else ((rIn + 1.U(inW)) % n.U(inW)).asBits.bits(inW - 1, 0).asUInt)
            )
          }
        }

    // DECERR: one R beat with the captured id truncated back to the input's width.
    for i <- 0 until n do
      when((rState === 3.U(2)) & (rIn === i.U(inW))) {
        val r = ch(in(i), "r")
        r.field[Bool]("valid")                      := true.B
        r.field[Record]("bits").field[UInt]("id")   := rErrId.asBits.bits(p.inputs(i)._2.idBits - 1, 0).asUInt
        r.field[Record]("bits").field[UInt]("resp") := 3.U(2)
        r.field[Record]("bits").field[Bool]("last") := true.B
        when(r.field[Bool]("ready")) {
          rState := 0.U(2)
          rRr.foreach(_ := (if n == 1 then 0.U(inW) else ((rIn + 1.U(inW)) % n.U(inW)).asBits.bits(inW - 1, 0).asUInt))
        }
      }
