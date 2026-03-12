// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.constraints

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, given}

import java.lang.foreign.Arena

// =============================================================================
//  Spec-mandated constraints for RV32I (and shared RVC) instructions.
//
//  Each given instance associates an instruction opaque type (e.g. IsFence)
//  with the RISC-V spec "must/shall" restrictions that go beyond what the
//  raw bit-field widths already enforce.
//
//  References are to "The RISC-V Instruction Set Manual, Volume I:
//  Unprivileged ISA" (Document Version 20191213).
// =============================================================================

// -----------------------------------------------------------------------------
// RV32I / RV64I Base Integer Instructions
// -----------------------------------------------------------------------------

// FENCE — fm field.
// Spec §3.3: fm=0000 (normal) and fm=1000 (TSO) are defined.
// All other fm values are reserved.
given SpecFor[IsFence] with
  def spec(using Arena, Context, Block, Index): Option[ArgConstraint] =
    Some(ArgConstraint(smtOr(fmEqual(0).toRef, fmEqual(8).toRef)))

// -----------------------------------------------------------------------------
// "C" Standard Extension (RVC) — Chapter 16
// -----------------------------------------------------------------------------

// C.ADDI4SPN — nzuimm.
// Spec §16.3: The encoding with nzuimm=0 is reserved.
given SpecFor[IsCAddi4spn] with
  def spec(using Arena, Context, Block, Index): Option[ArgConstraint] =
    Some(ArgConstraint((!cNzuimm10Equal(0)).toRef))

// C.LUI — nzimm.
// Spec §16.5: The encoding with nzimm=0 is reserved (would be C.LI with rd=x2,
// which is the ADDI16SP encoding).  Both hi and lo parts must not be
// simultaneously zero.
given SpecFor[IsCLui] with
  def spec(using Arena, Context, Block, Index): Option[ArgConstraint] =
    Some(ArgConstraint((!ArgConstraint(smtAnd(cNzimm18hiEqual(0).toRef, cNzimm18loEqual(0).toRef))).toRef))

// C.LWSP — rd.
// Spec §16.5: The encoding with rd=x0 is reserved.
// (rvdecoderdb models this field as rdN0, whose range already excludes 0 by
// convention, but we add the explicit SMT constraint to be certain.)
given SpecFor[IsCLwsp] with
  def spec(using Arena, Context, Block, Index): Option[ArgConstraint] =
    Some(rdN0Range(1, 32))
