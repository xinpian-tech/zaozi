// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import java.lang.foreign.Arena

/** A raw, sign-less bit vector of [[width]] bits (FIRRTL's `!firrtl.uint` used as an untyped payload). Unlike [[UInt]]
  * it carries no arithmetic operators (no `+`, `-`, `*`, ...) -- only bitwise/structural ones (slicing, concatenation,
  * casts). Values naturally produced by structural operations (e.g. `asBits`, `##`, `bits`) land here; cast to
  * [[UInt]]/[[SInt]] via `asUInt`/`asSInt` to get arithmetic back.
  */
trait Bits extends Element with CanProbe:
  private[zaozi] val _width: Int

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
