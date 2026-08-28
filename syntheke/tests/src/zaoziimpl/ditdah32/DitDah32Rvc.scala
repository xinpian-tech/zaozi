// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// SPDX-License-Identifier: MIT
package com.vowstar.ditdah32

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

trait DitDah32Rvc:
  protected def decodeRvc(
      parameter: DitDah32Parameter,
      commitInstr: Referable[Bits],
      commitCompressed: Referable[Bool],
      cInsn: Wire[Bits],
      cQuadrant: Wire[Bits],
      cFunct3: Wire[Bits],
      cRdRs1: Wire[Bits],
      cRs2: Wire[Bits],
      cRdPrime: Wire[Bits],
      cRs1Prime: Wire[Bits],
      cRs2Prime: Wire[Bits],
      cShamt: Wire[Bits],
      cImm6: Wire[Bits],
      cAddi4spnImm: Wire[Bits],
      cLwImm: Wire[Bits],
      cLwspImm: Wire[Bits],
      cSwspImm: Wire[Bits],
      cAddi16spImm: Wire[Bits],
      cBranchImm: Wire[Bits],
      cJumpImm: Wire[Bits],
      cDecodedInstr: Wire[Bits],
      cNoWriteHint: Wire[Bool],
      decodedInstr: Wire[Bits]
  )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Unit =
    cInsn := commitInstr.bits(15, 0)
    cQuadrant := cInsn.bits(1, 0)
    cFunct3 := cInsn.bits(15, 13)
    cRdRs1 := cInsn.bits(11, 7)
    cRs2 := cInsn.bits(6, 2)
    cRdPrime := 1.B(2) ## cInsn.bits(4, 2)
    cRs1Prime := 1.B(2) ## cInsn.bits(9, 7)
    cRs2Prime := 1.B(2) ## cInsn.bits(4, 2)
    cShamt := cInsn.bits(6, 2)
    cImm6 :=
      cInsn.bit(12).?(0x3ffffff.B(26), 0.B(26)) ##
      cInsn.bits(12, 12) ##
      cInsn.bits(6, 2)
    cAddi4spnImm :=
      0.B(22) ##
      cInsn.bits(10, 7) ##
      cInsn.bits(12, 11) ##
      cInsn.bits(5, 5) ##
      cInsn.bits(6, 6) ##
      0.B(2)
    cLwImm :=
      0.B(25) ##
      cInsn.bits(5, 5) ##
      cInsn.bits(12, 10) ##
      cInsn.bits(6, 6) ##
      0.B(2)
    cLwspImm :=
      0.B(24) ##
      cInsn.bits(3, 2) ##
      cInsn.bits(12, 12) ##
      cInsn.bits(6, 4) ##
      0.B(2)
    cSwspImm :=
      0.B(24) ##
      cInsn.bits(8, 7) ##
      cInsn.bits(12, 9) ##
      0.B(2)
    cAddi16spImm :=
      cInsn.bit(12).?(0x3fffff.B(22), 0.B(22)) ##
      cInsn.bits(12, 12) ##
      cInsn.bits(4, 3) ##
      cInsn.bits(5, 5) ##
      cInsn.bits(2, 2) ##
      cInsn.bits(6, 6) ##
      0.B(4)
    cBranchImm :=
      cInsn.bit(12).?(0x7fffff.B(23), 0.B(23)) ##
      cInsn.bits(12, 12) ##
      cInsn.bits(6, 5) ##
      cInsn.bits(2, 2) ##
      cInsn.bits(11, 10) ##
      cInsn.bits(4, 3) ##
      0.B(1)
    cJumpImm :=
      cInsn.bit(12).?(0xfffff.B(20), 0.B(20)) ##
      cInsn.bits(12, 12) ##
      cInsn.bits(8, 8) ##
      cInsn.bits(10, 9) ##
      cInsn.bits(6, 6) ##
      cInsn.bits(7, 7) ##
      cInsn.bits(2, 2) ##
      cInsn.bits(11, 11) ##
      cInsn.bits(5, 3) ##
      0.B(1)

    cDecodedInstr := 0.B(parameter.xlen)
    cNoWriteHint := false.B
    when(cQuadrant === 0.B(2)) {
      when((cFunct3 === 0.B(3)) & (cAddi4spnImm =/= 0.B(parameter.xlen))) {
        cDecodedInstr := (
          cAddi4spnImm.bits(11, 0) ##
          2.B(5) ##
          0.B(3) ##
          cRdPrime ##
          0x13.B(7)
        )
      }
      when(cFunct3 === 2.B(3)) {
        cDecodedInstr := (
          cLwImm.bits(11, 0) ##
          cRs1Prime ##
          2.B(3) ##
          cRdPrime ##
          0x03.B(7)
        )
      }
      when(cFunct3 === 6.B(3)) {
        cDecodedInstr := (
          cLwImm.bits(11, 5) ##
          cRs2Prime ##
          cRs1Prime ##
          2.B(3) ##
          cLwImm.bits(4, 0) ##
          0x23.B(7)
        )
      }
    }
    when(cQuadrant === 1.B(2)) {
      when(cFunct3 === 0.B(3)) {
        cDecodedInstr := (
          cImm6.bits(11, 0) ##
          cRdRs1 ##
          0.B(3) ##
          cRdRs1 ##
          0x13.B(7)
        )
      }
      when(cFunct3 === 1.B(3)) {
        cDecodedInstr := (
          cJumpImm.bits(20, 20) ##
          cJumpImm.bits(10, 1) ##
          cJumpImm.bits(11, 11) ##
          cJumpImm.bits(19, 12) ##
          1.B(5) ##
          0x6f.B(7)
        )
      }
      when(cFunct3 === 2.B(3)) {
        cDecodedInstr := (
          cImm6.bits(11, 0) ##
          0.B(5) ##
          0.B(3) ##
          cRdRs1 ##
          0x13.B(7)
        )
      }
      when(cFunct3 === 3.B(3)) {
        when((cRdRs1 === 0.B(5)) & (cImm6 =/= 0.B(parameter.xlen))) {
          cDecodedInstr := (
            cImm6.bits(19, 0) ##
            cRdRs1 ##
            0x37.B(7)
          )
        }
        when((cRdRs1 === 2.B(5)) & (cAddi16spImm =/= 0.B(parameter.xlen))) {
          cDecodedInstr := (
            cAddi16spImm.bits(11, 0) ##
            2.B(5) ##
            0.B(3) ##
            2.B(5) ##
            0x13.B(7)
          )
        }
        when((cRdRs1 =/= 0.B(5)) & (cRdRs1 =/= 2.B(5)) & ((cImm6 =/= 0.B(parameter.xlen)) | cRdRs1.bit(4))) {
          cDecodedInstr := (
            cImm6.bits(19, 0) ##
            cRdRs1 ##
            0x37.B(7)
          )
        }
      }
      when(cFunct3 === 4.B(3)) {
        when(cInsn.bits(11, 10) === 0.B(2)) {
          when(!cInsn.bit(12)) {
            cDecodedInstr := (
              0.B(7) ##
              cShamt ##
              cRs1Prime ##
              5.B(3) ##
              cRs1Prime ##
              0x13.B(7)
            )
          }
        }
        when(cInsn.bits(11, 10) === 1.B(2)) {
          when(!cInsn.bit(12)) {
            cDecodedInstr := (
              0x20.B(7) ##
              cShamt ##
              cRs1Prime ##
              5.B(3) ##
              cRs1Prime ##
              0x13.B(7)
            )
          }
        }
        when(cInsn.bits(11, 10) === 2.B(2)) {
          cDecodedInstr := (
            cImm6.bits(11, 0) ##
            cRs1Prime ##
            7.B(3) ##
            cRs1Prime ##
            0x13.B(7)
          )
        }
        when(cInsn.bits(11, 10) === 3.B(2)) {
          when(!cInsn.bit(12)) {
            when(cInsn.bits(6, 5) === 0.B(2)) {
              cDecodedInstr := (
                0x20.B(7) ##
                cRs2Prime ##
                cRs1Prime ##
                0.B(3) ##
                cRs1Prime ##
                0x33.B(7)
              )
            }
            when(cInsn.bits(6, 5) === 1.B(2)) {
              cDecodedInstr := (
                0.B(7) ##
                cRs2Prime ##
                cRs1Prime ##
                4.B(3) ##
                cRs1Prime ##
                0x33.B(7)
              )
            }
            when(cInsn.bits(6, 5) === 2.B(2)) {
              cDecodedInstr := (
                0.B(7) ##
                cRs2Prime ##
                cRs1Prime ##
                6.B(3) ##
                cRs1Prime ##
                0x33.B(7)
              )
            }
            when(cInsn.bits(6, 5) === 3.B(2)) {
              cDecodedInstr := (
                0.B(7) ##
                cRs2Prime ##
                cRs1Prime ##
                7.B(3) ##
                cRs1Prime ##
                0x33.B(7)
              )
            }
          }
        }
      }
      when(cFunct3 === 5.B(3)) {
        cDecodedInstr := (
          cJumpImm.bits(20, 20) ##
          cJumpImm.bits(10, 1) ##
          cJumpImm.bits(11, 11) ##
          cJumpImm.bits(19, 12) ##
          0.B(5) ##
          0x6f.B(7)
        )
      }
      when((cFunct3 === 6.B(3)) | (cFunct3 === 7.B(3))) {
        cDecodedInstr := (
          cBranchImm.bits(12, 12) ##
          cBranchImm.bits(10, 5) ##
          0.B(5) ##
          cRs1Prime ##
          (cFunct3 === 6.B(3)).?(0.B(3), 1.B(3)) ##
          cBranchImm.bits(4, 1) ##
          cBranchImm.bits(11, 11) ##
          0x63.B(7)
        )
      }
    }
    when(cQuadrant === 2.B(2)) {
      when(cFunct3 === 0.B(3)) {
        when(!cInsn.bit(12)) {
          cDecodedInstr := (
            0.B(7) ##
            cShamt ##
            cRdRs1 ##
            1.B(3) ##
            cRdRs1 ##
            0x13.B(7)
          )
        }
      }
      when((cFunct3 === 2.B(3)) & (cRdRs1 =/= 0.B(5))) {
        cDecodedInstr := (
          cLwspImm.bits(11, 0) ##
          2.B(5) ##
          2.B(3) ##
          cRdRs1 ##
          0x03.B(7)
        )
      }
      when(cFunct3 === 4.B(3)) {
        when(!cInsn.bit(12) & (cRs2 === 0.B(5)) & (cRdRs1 =/= 0.B(5))) {
          cDecodedInstr := (
            0.B(12) ##
            cRdRs1 ##
            0.B(3) ##
            0.B(5) ##
            0x67.B(7)
          )
        }
        when(!cInsn.bit(12) & (cRs2 =/= 0.B(5))) {
          cDecodedInstr := (
            0.B(7) ##
            cRs2 ##
            0.B(5) ##
            0.B(3) ##
            cRdRs1 ##
            0x33.B(7)
          )
        }
        when(cInsn.bit(12) & (cRs2 === 0.B(5)) & (cRdRs1 === 0.B(5))) {
          cDecodedInstr := 0x00100073.B(parameter.xlen)
        }
        when(cInsn.bit(12) & (cRs2 === 0.B(5)) & (cRdRs1 =/= 0.B(5))) {
          cDecodedInstr := (
            0.B(12) ##
            cRdRs1 ##
            0.B(3) ##
            1.B(5) ##
            0x67.B(7)
          )
        }
        when(cInsn.bit(12) & (cRs2 =/= 0.B(5))) {
          cDecodedInstr := (
            0.B(7) ##
            cRs2 ##
            cRdRs1 ##
            0.B(3) ##
            cRdRs1 ##
            0x33.B(7)
          )
        }
      }
      when(cFunct3 === 6.B(3)) {
        cDecodedInstr := (
          cSwspImm.bits(11, 5) ##
          cRs2 ##
          2.B(5) ##
          2.B(3) ##
          cSwspImm.bits(4, 0) ##
          0x23.B(7)
        )
      }
    }
    decodedInstr := commitCompressed.?(cDecodedInstr, commitInstr)
