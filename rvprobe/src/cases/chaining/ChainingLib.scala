// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.chaining

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.*
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.HTIFLib
import me.jiuyang.rvprobe.cases.HTIFLib.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Reusable chaining hazard matrix patterns for T1 vector processor verification.
  *
  * The matrix has two orthogonal dimensions:
  *   - Dimension 1 (D1-D5): Data dependency types from architecture specification
  *   - Dimension 2 (C1-C8): Execution unit crossings from T1 Object Model
  *
  * Each cell generates a two-instruction vector sequence that targets a specific chaining hazard scenario. The SMT
  * solver fills in concrete register indices and instruction variants satisfying both the architectural dependency and
  * the microarchitectural execution-unit constraint.
  *
  * Every test cell is wrapped with: vsetvli setup (SEW=32, LMUL=1, vl=max) + valid memory buffer so the generated
  * assembly can run directly on T1 RTL simulation.
  */
object ChainingLib:
  /** Vector configuration preamble: set up vtype and a valid memory base address.
    *
    * Uses t2 for vl result, t3 for memory base address. SEW=32, LMUL=1 (zimm11 = 0b00000010000 = 0x010 = 16 decimal:
    * vsew=010 → SEW=32, vlmul=000 → LMUL=1).
    */
  private val VectorPreamble =
    """    # Vector configuration: SEW=32, LMUL=1, vl=max
      |    li   t2, -1
      |    vsetvli t2, t2, e32, m1, ta, ma
      |    # Set up valid memory base in t3
      |    la   t3, vbuf""".stripMargin

  /** Data section with a buffer for vector load/store. */
  private val DataSection =
    """    .section ".data","aw",@progbits
      |    .align 8
      |vbuf:
      |    .zero 256""".stripMargin

  private val ChainingPreamble = HTIFLib.asmTextStart()
  private val ChainingEpilogue =
    s"""${HTIFLib.asmExit()}
       |
       |${HTIFLib.asmTohostSection()}
       |
       |$DataSection""".stripMargin

  def writeChainingAsm(
    outputPath: String,
    generators: RVGenerator*
  ): Unit =
    val body    = generators.map(_.toRecipeAsm().trim).mkString("\n\n")
    val content =
      s"""$ChainingPreamble
         |
         |$VectorPreamble
         |
         |$body
         |
         |$ChainingEpilogue
         |""".stripMargin
    os.write.over(os.Path(outputPath, os.pwd), content, createFolders = true)

  // ================== Dimension 1: Dependency Types ==================

  /** D1: Explicit RAW — A.vd → B.vs2 (B reads what A wrote).
    *
    * Register selection: vd/vs2 share `reg` for the dependency. Other vector registers avoid v0 (mask) and the shared
    * register by using explicit ranges.
    */
  def explicitRAW(
    reg:     Int,
    opcodeA: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint,
    opcodeB: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    instruction(0, opcodeA) { vdEqual(reg.S) & hasVd() & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }
    instruction(1, opcodeB) { vs2Equal(reg.S) & hasVs2() & vdRange(1, 32) & vmEqual(1) }

  /** D2: Implicit v0 mask RAW — A writes v0, B uses v0 as implicit mask (vm=0). */
  def implicitV0RAW(
    opcodeA: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint,
    opcodeB: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    instruction(0, opcodeA) { vdEqual(0.S) & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }
    instruction(1, opcodeB) { vdRange(1, 32) & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(0) }

  /** D3: WAR — A reads vs2, B writes the same register as vd. */
  def war(
    reg:     Int,
    opcodeA: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint,
    opcodeB: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    instruction(0, opcodeA) { vs2Equal(reg.S) & hasVs2() & vdRange(1, 32) & vs1Range(1, 32) & vmEqual(1) }
    instruction(1, opcodeB) { vdEqual(reg.S) & hasVd() & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }

  /** D4: WAW — A.vd == B.vd (both write the same register). */
  def waw(
    reg:     Int,
    opcodeA: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint,
    opcodeB: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    instruction(0, opcodeA) { vdEqual(reg.S) & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }
    instruction(1, opcodeB) { vdEqual(reg.S) & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }

  /** D5: Implicit WAR — A uses v0 as mask (vm=0), B writes v0 as vd. */
  def implicitV0WAR(
    opcodeA: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint,
    opcodeB: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    instruction(0, opcodeA) { vdRange(1, 32) & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(0) }
    instruction(1, opcodeB) { vdEqual(0.S) & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }

  // ================== Combined Matrix Cells ==================
  // Naming: {dependency}_{unitA}x{unitB}

  // --- D1 × C1: Explicit RAW, ALU × ALU ---
  def explicitRAW_ALUxALU(
    reg: Int = 4
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    explicitRAW(reg, isVaddVv(), isVsubVv())

  // --- D1 × C2: Explicit RAW, ALU × LSU ---
  // Uses t3 (memory base) set up in VectorPreamble
  def explicitRAW_ALUxLSU(
    reg: Int = 4
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    instruction(0, isVaddVv()) { vdEqual(reg.S) & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }
    instruction(1, isVle32V()) { vdEqual(reg.S) & rs1Range(28, 29) & vmEqual(1) }

  // --- D1 × C3: Explicit RAW, ALU × Mask unit ---
  def explicitRAW_MaskxALU(
    reg: Int = 4
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    explicitRAW(reg, isVmseqVv(), isVaddVv())

  // --- D1 × C4: Explicit RAW, Slow × Fast ---
  def explicitRAW_SlowxFast(
    reg: Int = 4
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    explicitRAW(reg, isVdivVv(), isVaddVv())

  // --- D1 × C5: Explicit RAW, Widen × Normal ---
  def explicitRAW_WidenxNormal(
    reg: Int = 4
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    explicitRAW(reg, isVwaddVv(), isVaddVv())

  // --- D1 × C7: Explicit RAW, Slide × Store ---
  def explicitRAW_SlidexStore(
    reg: Int = 4
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    instruction(0, isVslidedownVi()) { vdEqual(reg.S) & vs2Range(1, 32) & vmEqual(1) }
    instruction(1, isVse32V()) { vs2Range(1, 32) & rs1Range(28, 29) & vmEqual(1) }

  // --- D2 × C1: Implicit v0 mask RAW, ALU × ALU ---
  def implicitV0RAW_ALUxALU(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    implicitV0RAW(isVaddVv(), isVsubVv())

  // --- D3 × C2: WAR, Store × ALU ---
  def war_StorexALU(
    reg: Int = 4
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    instruction(0, isVse32V()) { vs2Range(1, 32) & rs1Range(28, 29) & vmEqual(1) }
    instruction(1, isVaddVv()) { vdEqual(reg.S) & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }

  // --- D3 × C6: WAR, Gather × ALU ---
  def war_GatherxALU(
    reg: Int = 4
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    inst(isVrgatherVv()) { vs2Equal(reg.S) & vdRange(1, 32) & vmEqual(1) }
    inst(isVaddVv()) { vdEqual(reg.S) & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }

  // --- D4 × C2: WAW, Slow × Load ---
  def waw_SlowxLoad(
    reg: Int = 4
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    instruction(0, isVdivVv()) { vdEqual(reg.S) & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }
    instruction(1, isVle32V()) { vdEqual(reg.S) & rs1Range(28, 29) & vmEqual(1) }

  // --- D5 × C1: Implicit v0 WAR, ALU × ALU ---
  def implicitV0WAR_ALUxALU(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    implicitV0WAR(isVaddVv(), isVsubVv())

  // --- D3 × C1: WAR, ALU × ALU ---
  def war_ALUxALU(
    reg: Int = 4
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    war(reg, isVaddVv(), isVsubVv())

  // --- D4 × C1: WAW, ALU × ALU ---
  def waw_ALUxALU(
    reg: Int = 4
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    waw(reg, isVaddVv(), isVsubVv())

  // --- D3 × C4: WAR, Slow × Fast (divider reads, fast ALU overwrites) ---
  def war_SlowxFast(
    reg: Int = 4
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    war(reg, isVdivVv(), isVaddVv())

  // --- D4 × C4: WAW, Slow × Fast ---
  def waw_SlowxFast(
    reg: Int = 4
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    waw(reg, isVdivVv(), isVaddVv())

  // ================== Complete Test Generators ==================
  // These methods generate full, self-contained test sequences including:
  //   - Vector configuration (vsetvli)
  //   - Data initialization
  //   - The hazardous instruction pair (solver-constrained)
  //   - Result verification
  // All scalar registers use freshReg() for solver-driven allocation.

  /** D3×C6 complete: Gather WAR with reversed index pattern.
    *
    * Generates a complete test that triggers the gather non-sequential read WAR bug:
    *   1. vsetvli: configure SEW=32, LMUL=1
    *   2. vid.v: source data v_src = {0, 1, 2, ..., vl-1}
    *   3. vid.v + vxor.vx: index vector v_idx = {vl-1, vl-2, ..., 0} (reversed)
    *   4. vrgather.vv: reference result v_ref = gather(v_src, v_idx) (safe, no concurrent write)
    *   5. vmv.v.x: poison value v_poison = {999, 999, ...}
    *   6. vrgather.vv + vadd.vv: WAR pair (gather reads v_src, vadd overwrites v_src with poison)
    *   7. vmsne.vv + vcpop.m: compare result against reference, count mismatches
    *
    * The solver picks all scalar temporaries (freshReg) and vector register assignments (v_src, v_idx, etc.) to avoid
    * conflicts. The `isGather()` OM predicate constrains instruction A to a gather variant.
    *
    * On pre-fix T1 (before commit 50986c9d): gather reads corrupted data from v_src because WriteCheck allows vadd to
    * write while gather is still reading non-sequentially. On post-fix T1: vadd is stalled until gather completes
    * (record.bits.gather flag).
    */
  def war_GatherxALU_full(
    vSrc:    Int = 8,
    vIdx:    Int = 2,
    vRef:    Int = 6,
    vPoison: Int = 7,
    vResult: Int = 1,
    vCmp:    Int = 0
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    // Fixed scalar registers for vector config (avoid solver interference)
    useFixed(x5, x6)

    // 1. Vector configuration: SEW=32, LMUL=1, vl=max
    //    zimm11 = 0b11_0_010_000 = 0xC8 = 200 → vsew=010(SEW=32), vlmul=000(LMUL=1), vta=1, vma=1
    raw("li   x5, -1")                     // pseudo — no SMT index
    raw("vsetvli x5, x5, e32, m1, ta, ma") // vector config — raw to avoid SMT constraint

    // 2. Source data: v_src = {0, 1, 2, ..., vl-1} (unique per element)
    raw(s"vid.v v$vSrc")

    // 3. Reversed index: v_idx = vid XOR (vl-1)
    //    For vl=128: v_idx = {127, 126, ..., 1, 0}
    //    This forces gather to read element 0 at the LAST processing step,
    //    while sequential elementMask marks element 0 as "done" at step 0.
    raw("addi x6, x5, -1")             // x6 = vl - 1
    raw(s"vid.v v$vIdx")
    raw(s"vxor.vx v$vIdx, v$vIdx, x6") // v_idx = vid XOR (vl-1) = reversed

    // 4. Reference gather (safe, before any WAR hazard)
    raw(s"vrgather.vv v$vRef, v$vSrc, v$vIdx") // v_ref = gather(v_src, v_idx)

    // 5. Poison value: v_poison = {999, 999, ...}
    raw("li x6, 999")
    raw(s"vmv.v.x v$vPoison, x6")

    // 6. === Critical WAR pair (solver-constrained) ===
    //    A: vrgather.vv — gather reads v_src in reversed order (isGather() OM predicate)
    //       Solver selects this opcode via isVrgatherVv() constraint
    //    B: vadd.vv — overwrites v_src with poison (WAR hazard!)
    //       Solver selects this opcode via isVaddVv() constraint
    //    The solver guarantees the register dependency: B.vd == A.vs2 (WAR on v_src)
    instruction(0, isVrgatherVv()) {
      vdEqual(vResult.S) & vs2Equal(vSrc.S) & vs1Equal(vIdx.S) & vmEqual(1)
    }
    instruction(1, isVaddVv()) {
      vdEqual(vSrc.S) & vs1Equal(vPoison.S) & vs2Equal(vPoison.S) & vmEqual(1)
    }

    // 7. Verification: compare result against reference
    raw(s"vmsne.vv v$vCmp, v$vResult, v$vRef") // mismatch mask
    raw(s"vcpop.m x6, v$vCmp")                 // count mismatches → x6
