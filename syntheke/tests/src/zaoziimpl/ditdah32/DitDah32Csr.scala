// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// SPDX-License-Identifier: MIT
package com.vowstar.ditdah32

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

trait DitDah32Csr:
  protected def trapMstatus[R <: Referable[Bits]](current: R)(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[Bits] =
    0.B(19) ##
    3.B(2) ##
    0.B(3) ##
    current.bits(CsrBits.MSTATUS_MIE, CsrBits.MSTATUS_MIE) ##
    0.B(3) ##
    0.B(1) ##
    0.B(3)

  protected def mretMstatus[R <: Referable[Bits]](current: R)(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[Bits] =
    0.B(19) ##
    3.B(2) ##
    0.B(3) ##
    1.B(1) ##
    0.B(3) ##
    current.bits(CsrBits.MSTATUS_MPIE, CsrBits.MSTATUS_MPIE) ##
    0.B(3)

  protected def writableMstatus[R <: Referable[Bits]](writeData: R)(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[Bits] =
    // WARL legalization for DitDah32 (M-only): MPP is hard-wired to 2'b11
    // because U and S modes are not supported. Any value the software writes
    // to mstatus.MPP reads back as 11, matching the Priv Spec recommendation
    // for cores that implement a single privilege level. All non-MIE/MPIE
    // bits stay reserved-zero.
    0.B(19) ##
    3.B(2) ##
    0.B(3) ##
    writeData.bits(CsrBits.MSTATUS_MPIE, CsrBits.MSTATUS_MPIE) ##
    0.B(3) ##
    writeData.bits(CsrBits.MSTATUS_MIE, CsrBits.MSTATUS_MIE) ##
    0.B(3)

  protected def csrReadSignals(
      addr: Referable[Bits],
      mstatus: Referable[Bits],
      mie: Referable[Bits],
      mtvec: Referable[Bits],
      mscratch: Referable[Bits],
      mepc: Referable[Bits],
      mcause: Referable[Bits],
      mtval: Referable[Bits],
      mip: Referable[Bits],
      parameter: DitDah32Parameter
  )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): (Wire[Bits], Wire[Bool], Wire[Bool]) =
    val data = Wire(Bits(parameter.xlen))
    val valid = Wire(Bool())
    val readOnly = Wire(Bool())

    data := 0.B(parameter.xlen)
    valid := false.B
    readOnly := false.B
    when(addr === CsrAddr.MSTATUS.B(12)) { valid := true.B; data := mstatus }
    when(addr === CsrAddr.MISA.B(12)) { valid := true.B; readOnly := true.B; data := 0x40000014.B(parameter.xlen) }
    when(addr === CsrAddr.MIE.B(12)) { valid := true.B; data := mie }
    when(addr === CsrAddr.MTVEC.B(12)) { valid := true.B; data := mtvec }
    when(addr === CsrAddr.MSCRATCH.B(12)) { valid := true.B; data := mscratch }
    when(addr === CsrAddr.MEPC.B(12)) { valid := true.B; data := mepc }
    when(addr === CsrAddr.MCAUSE.B(12)) { valid := true.B; data := mcause }
    when(addr === CsrAddr.MTVAL.B(12)) { valid := true.B; data := mtval }
    when(addr === CsrAddr.MIP.B(12)) { valid := true.B; readOnly := true.B; data := mip }
    when(addr === CsrAddr.MVENDORID.B(12)) { valid := true.B; readOnly := true.B; data := 0.B(parameter.xlen) }
    when(addr === CsrAddr.MARCHID.B(12)) { valid := true.B; readOnly := true.B; data := 0.B(parameter.xlen) }
    when(addr === CsrAddr.MIMPID.B(12)) { valid := true.B; readOnly := true.B; data := 0.B(parameter.xlen) }
    when(addr === CsrAddr.MHARTID.B(12)) { valid := true.B; readOnly := true.B; data := 0.B(parameter.xlen) }

    (data, valid, readOnly)
