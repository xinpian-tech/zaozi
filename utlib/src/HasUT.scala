// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.zaozi.{HWInterface, Parameter}

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Input constraints owned by a hardware generator.
  *
  * Implementations append SMT operations to the current block. [[ConstraintInterface]] provides typed access to the DUT
  * inputs and memoizes one symbolic value for each input and cycle.
  */
trait HasUT[PARAM <: Parameter, I <: HWInterface[PARAM]]:
  def constraints(
    parameter: PARAM
  )(
    using Arena,
    Context,
    Block,
    ConstraintInterface[I]
  ): Unit
