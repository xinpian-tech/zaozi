// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.capi.dialect.firrtl.given_TypeApi
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

/** One generation entry point: a hardware predicate, sequence or property describes the target trace.
  * No value/state/relation categories are required. Generation seeks a witness, not a universal proof.
  * Use finite-witness goals; accepting Property does not promise support for arbitrary infinite-time LTL.
  */
object Gen:
  type Expr = Referable[Bool] | Sequence | Property

  /** Native zaozi past supplies temporal predicates; Gen adds no history state or guards. */
  def apply(goal: Expr, label: String)(using ClockEvent)(using
    Arena, Context, Block, sourcecode.File, sourcecode.Line, sourcecode.Name.Machine, InstanceContext
  ): Unit =
    goal match
      case sequence: Sequence => Assert(!sequence, label)
      case property: Property => Assert(!property, label)
      case predicate: Referable[?] =>
        Assert((!predicate.asInstanceOf[Referable[Bool]]).I, label)
