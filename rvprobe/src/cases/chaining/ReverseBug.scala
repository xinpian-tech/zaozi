// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.chaining

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.chaining.ChainingLib.*

// White-box case study: Reverse signal × scalar-vector RAW hazard.
//
// Target: T1 pipeline bug where scalar data forwarding path doesn't account for
// the Reverse signal's effect on operand position. The forwarding logic sends x10
// to the vs1 port (standard subtrahend position), but Reverse swaps operands,
// so the ALU expects the value from vs2.
//
// Constraint composition:
//   instruction(0) { isLw() & rd === 10 }           // scalar load → x10
//   instruction(1) { isReverse() & rs1 === 10 }     // vrsub reads x10, ALU swaps operands
//
// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.chaining.ReverseBug
@main def ReverseBug(
  outputPath: String = "rvprobe/src/cases/output/asm/chaining/ReverseBug.S"
): Unit =
  // Scalar load followed by vector reverse-subtract: scalar-vector RAW + Reverse signal
  object ScalarVectorReverse extends RVGenerator:
    val sets: Seq[Recipe ?=> SetConstraint] = Seq(isRVI(), isRVV())
    def constraints() =
      // Step 1: Scalar load into x10
      instruction(0, isLw()) { rdRange(10, 11) & rs1Range(1, 32) }
      // Step 2: Vector reverse-subtract consuming x10 under Reverse signal
      instruction(1, isReverse()) { rs1Range(10, 11) & vdRange(1, 32) & vs2Range(1, 32) & vmEqual(1) }

  writeChainingAsm(outputPath, ScalarVectorReverse)
