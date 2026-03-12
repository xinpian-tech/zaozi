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
    Some(!cNzuimm10Equal(0))

// C.LUI — nzimm.
// Spec §16.5: The encoding with nzimm=0 is reserved (would be C.LI with rd=x2,
// which is the ADDI16SP encoding).  Both hi and lo parts must not be
// simultaneously zero.
given SpecFor[IsCLui] with
  def spec(using Arena, Context, Block, Index): Option[ArgConstraint] =
    Some(!(cNzimm18hiEqual(0) & cNzimm18loEqual(0)))

// C.ADDI — nzimm.
// Spec §16.3: The encoding with nzimm=0 and rd≠x0 is a HINT (C.NOP when rd=x0).
// Exclude nzimm=0 to avoid generating pointless no-op hints.
given SpecFor[IsCAddi] with
  def spec(using Arena, Context, Block, Index): Option[ArgConstraint] =
    Some(!(cNzimm6hiEqual(0) & cNzimm6loEqual(0)))

// C.ADDIW (RV64C) — rd.
// Spec §16.4: The encoding with rd=x0 is reserved.
// C.ADDIW uses CI format where rd=rs1; rvdecoderdb encodes this as rdRs1N0.
given SpecFor[IsCAddiw] with
  def spec(using Arena, Context, Block, Index): Option[ArgConstraint] =
    Some(rdRs1N0Range(1, 32))

// C.ADDI16SP — nzimm.
// Spec §16.4: The encoding with nzimm=0 is reserved.
given SpecFor[IsCAddi16sp] with
  def spec(using Arena, Context, Block, Index): Option[ArgConstraint] =
    Some(!(cNzimm10hiEqual(0) & cNzimm10loEqual(0)))

// C.SLLI (RV64C) — nzuimm.
// Spec §16.5: The encoding with nzuimm=0 (shamt=0) is reserved (RV32C);
// also excluded for RV64C as a zero shift is a no-op and generates no useful tests.
given SpecFor[IsCSlli] with
  def spec(using Arena, Context, Block, Index): Option[ArgConstraint] =
    Some(!(cNzuimm6hiEqual(0) & cNzuimm6loEqual(0)))

// C.LDSP (RV64C) — rd.
// Spec §16.5: The encoding with rd=x0 is reserved.
given SpecFor[IsCLdsp] with
  def spec(using Arena, Context, Block, Index): Option[ArgConstraint] =
    Some(rdN0Range(1, 32))

// C.LWSP — rd.
// Spec §16.5: The encoding with rd=x0 is reserved.
// (rvdecoderdb models this field as rdN0, whose range already excludes 0 by
// convention, but we add the explicit SMT constraint to be certain.)
given SpecFor[IsCLwsp] with
  def spec(using Arena, Context, Block, Index): Option[ArgConstraint] =
    Some(rdN0Range(1, 32))
