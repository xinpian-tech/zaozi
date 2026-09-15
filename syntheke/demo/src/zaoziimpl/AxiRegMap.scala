package me.jiuyang.syntheke.demo.zaoziimpl

import java.lang.foreign.Arena
import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.mmio.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

object AxiRegMap:
  def apply(
    in:    Referable[AxiPortBundle] & Writable[AxiPortBundle],
    shape: AxiShape
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext,
    ClockScope,
    ResetScope
  ): (Wire[DecoupledIO[RegMapRequest]], Wire[DecoupledIO[RegMapResponse]]) =
    require(shape.addrBits >= 3 && shape.dataBits == 32)

    val req = Wire(Decoupled(new RegMapRequest(shape.addrBits - 2, 32)))
    val rsp = Wire(Decoupled(new RegMapResponse(32, true)))

    val pendingAw = RegInit(false.B)
    val pendingW  = RegInit(false.B)
    val pendingAr = RegInit(false.B)
    val responding = RegInit(false.B)
    val responseRead = RegInit(false.B)
    val responseInvalid = RegInit(false.B)
    val responseId = RegInit(0.B(shape.idBits))

    val awAddr = Reg(Bits(shape.addrBits))
    val awId = Reg(Bits(shape.idBits))
    val awInvalid = Reg(Bool())
    val wData = Reg(Bits(32))
    val wMask = Reg(Bits(4))
    val wInvalid = RegInit(false.B)
    val arAddr = Reg(Bits(shape.addrBits))
    val arId = Reg(Bits(shape.idBits))
    val arSize = Reg(Bits(3))
    val arInvalid = Reg(Bool())

    def validAddress(addr: Referable[Bits], len: Referable[Bits], size: Referable[Bits], burst: Referable[Bits])(
      using Arena, Context, Block
    ): Referable[Bool] =
      val aligned = (size === 0.B(3)) |
        ((size === 1.B(3)) & !addr.bit(0)) |
        ((size === 2.B(3)) & (addr.bits(1, 0) === 0.B(2)))
      (len === 0.B(8)) & aligned & ((burst === 0.B(2)) | (burst === 1.B(2)))

    val collecting = !responding
    in.aw.ready := collecting & !pendingAw & !pendingAr
    in.w.ready := collecting & !pendingW & !pendingAr
    in.ar.ready := collecting & !pendingAr & !pendingAw & !pendingW & !in.aw.valid & !in.w.valid

    when(in.aw.valid & in.aw.ready) {
      pendingAw := true.B
      awAddr := in.aw.bits.addr
      awId := in.aw.bits.id
      awInvalid := !validAddress(in.aw.bits.addr, in.aw.bits.len, in.aw.bits.size, in.aw.bits.burst)
    }
    when(in.w.valid & in.w.ready) {
      when(in.w.bits.last) {
        pendingW := true.B
        wData := in.w.bits.data
        wMask := in.w.bits.strb
      }.otherwise {
        wInvalid := true.B
      }
    }
    when(in.ar.valid & in.ar.ready) {
      pendingAr := true.B
      arAddr := in.ar.bits.addr
      arId := in.ar.bits.id
      arSize := in.ar.bits.size
      arInvalid := !validAddress(in.ar.bits.addr, in.ar.bits.len, in.ar.bits.size, in.ar.bits.burst)
    }

    val issueRead = pendingAr
    val issueWrite = pendingAw & pendingW
    val issue = collecting & (issueRead | issueWrite)
    val invalid = issueRead ? (arInvalid, awInvalid | wInvalid)
    val address = issueRead ? (arAddr, awAddr)

    val readMask = Wire(Bits(4))
    readMask := 0.B(4)
    for lane <- 0 until 4 do
      when((arSize === 0.B(3)) & (arAddr.bits(1, 0) === lane.B(2))) {
        readMask := (1 << lane).B(4)
      }
    for lane <- Seq(0, 2) do
      when((arSize === 1.B(3)) & (arAddr.bits(1, 0) === lane.B(2))) {
        readMask := (3 << lane).B(4)
      }
    when(arSize === 2.B(3)) { readMask := 15.B(4) }

    req.valid := issue & !invalid
    req.bits.read := issueRead
    req.bits.index := address.bits(shape.addrBits - 1, 2).asUInt
    req.bits.data := issueRead ? (0.B(32), wData)
    req.bits.mask := issueRead ? (readMask, wMask)

    when(issue & (invalid | req.ready)) {
      pendingAw := false.B
      pendingW := false.B
      pendingAr := false.B
      wInvalid := false.B
      responding := true.B
      responseRead := issueRead
      responseInvalid := invalid
      responseId := issueRead ? (arId, awId)
    }

    val responseValid = responding & (responseInvalid | rsp.valid)
    val responseError = responseInvalid | rsp.bits.error.get
    in.b.valid := responseValid & !responseRead
    in.b.bits.id := responseId
    in.b.bits.resp := responseError ? (2.B(2), 0.B(2))
    in.r.valid := responseValid & responseRead
    in.r.bits.id := responseId
    in.r.bits.resp := responseError ? (2.B(2), 0.B(2))
    in.r.bits.data := responseInvalid ? (0.B(32), rsp.bits.data)
    in.r.bits.last := true.B
    rsp.ready := responding & !responseInvalid & (responseRead ? (in.r.ready, in.b.ready))
    when((in.r.valid & in.r.ready) | (in.b.valid & in.b.ready)) {
      responding := false.B
    }

    (req, rsp)
