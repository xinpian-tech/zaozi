// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.chaining

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.chaining.ChainingLib.*

// Chaining hazard matrix: 5 dependency types × 8 execution unit crossings = 40 cells.
// This file implements 15 cells covering all 5 dependency types and 7 unit crossings,
// corresponding to the 8 confirmed T1 chaining bugs.
//
// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.chaining.ChainingMatrix
@main def ChainingMatrix(
  outputPath: String = "rvprobe/src/cases/output/asm/chaining/ChainingMatrix.S"
): Unit =
  // ========== D1: Explicit RAW ==========

  // D1×C1: ALU × ALU — baseline same-unit RAW
  object D1C1_ExplicitRAW_ALUxALU extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = explicitRAW_ALUxALU()

  // D1×C2: ALU × LSU — ALU result consumed by load (address or data)
  object D1C2_ExplicitRAW_ALUxLSU extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = explicitRAW_ALUxLSU()

  // D1×C3: Mask unit × ALU — mask result consumed by ALU
  // Bug: mask unit execution result not reported to chaining module (2023-06)
  object D1C3_ExplicitRAW_MaskxALU extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = explicitRAW_MaskxALU()

  // D1×C4: Slow(divider) × Fast(ALU) — long-latency div result consumed by ALU
  // Bug: slow instruction chaining window underestimated (2023-08)
  object D1C4_ExplicitRAW_SlowxFast extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = explicitRAW_SlowxFast()

  // D1×C5: Widen × Normal — widened vd occupies 2×LMUL registers
  // Bug: hazard check only covers vd, not vd+1 (2023-06)
  object D1C5_ExplicitRAW_WidenxNormal extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = explicitRAW_WidenxNormal()

  // D1×C7: Slide × Store — slide cross-lane data movement with store
  // Bug: slide RAW window underestimated (2023-06)
  object D1C7_ExplicitRAW_SlidexStore extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = explicitRAW_SlidexStore()

  // ========== D2: Implicit v0 Mask RAW ==========

  // D2×C1: ALU × ALU — A writes v0, B uses v0 as implicit mask
  // Bug: instructionRAWReady only checks unordered types, misses v0 mask dependency (commit 0dd6e504)
  object D2C1_ImplicitV0RAW_ALUxALU extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = implicitV0RAW_ALUxALU()

  // ========== D3: WAR ==========

  // D3×C1: WAR, ALU × ALU — B writes what A is still reading
  object D3C1_WAR_ALUxALU extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = war_ALUxALU()

  // D3×C2: WAR, Store × ALU — store doesn't register chaining record
  // Bug: store instruction not registering chaining record (2023-06)
  object D3C2_WAR_StorexALU extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = war_StorexALU()

  // D3×C6: WAR, Gather × ALU — gather non-sequential read defeats elementMask
  // Bug: elementMask assumes sequential processing, gather reads out-of-order (commit 50986c9d)
  // This bug was DIRECTLY DISCOVERED by the hazard matrix test.
  object D3C6_WAR_GatherxALU extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = war_GatherxALU()

  // ========== D4: WAW ==========

  // D4×C1: WAW, ALU × ALU — both write same vd
  object D4C1_WAW_ALUxALU extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = waw_ALUxALU()

  // D4×C2: WAW, Slow × Load — slow div followed by load to same vd
  // Bug: WAW check not covering slow→LSU path (2023-08)
  object D4C2_WAW_SlowxLoad extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = waw_SlowxLoad()

  // D4×C4: WAW, Slow × Fast — div and add to same vd
  object D4C4_WAW_SlowxFast extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = waw_SlowxFast()

  // ========== D5: Implicit v0 WAR ==========

  // D5×C1: A uses v0 as mask, B writes v0
  object D5C1_ImplicitV0WAR_ALUxALU extends RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = implicitV0WAR_ALUxALU()

  writeChainingAsm(
    outputPath,
    D1C1_ExplicitRAW_ALUxALU,
    D1C2_ExplicitRAW_ALUxLSU,
    D1C3_ExplicitRAW_MaskxALU,
    D1C4_ExplicitRAW_SlowxFast,
    D1C5_ExplicitRAW_WidenxNormal,
    D1C7_ExplicitRAW_SlidexStore,
    D2C1_ImplicitV0RAW_ALUxALU,
    D3C1_WAR_ALUxALU,
    D3C2_WAR_StorexALU,
    D3C6_WAR_GatherxALU,
    D4C1_WAW_ALUxALU,
    D4C2_WAW_SlowxLoad,
    D4C4_WAW_SlowxFast,
    D5C1_ImplicitV0WAR_ALUxALU
  )
